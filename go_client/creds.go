package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"math/rand"
	"net"
	neturl "net/url"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	fhttp "github.com/bogdanfinn/fhttp"
	tlsclient "github.com/bogdanfinn/tls-client"
	"github.com/bogdanfinn/tls-client/profiles"
	"github.com/google/uuid"
)

// ─── VK Credential Sets (2 stable app_id with rotating fallback) ───

type VKCredentials struct {
	ClientID     string
	ClientSecret string
}

var vkCredentialsList = []VKCredentials{
	{ClientID: "6287487", ClientSecret: "MuAxFaKDYDOICzGnEOhp"},
	{ClientID: "8202606", ClientSecret: "lMRsTiMCyPnp5vfoldmn"},
}

const vkCredentialAttemptLimit = 4

// ─── Credential Caching ───

type TurnCredentials struct {
	Username    string
	Password    string
	ServerAddrs []string
	ExpiresAt   time.Time
	Link        string
}

type StreamCredentialsCache struct {
	creds         TurnCredentials
	mutex         sync.RWMutex
	errorCount    atomic.Int32
	lastErrorTime atomic.Int64
}

const (
	credentialLifetime = 10 * time.Minute
	cacheSafetyMargin  = 60 * time.Second
	maxCacheErrors     = 3
	errorWindow        = 10 * time.Second
)

var streamsPerCache = 10

func getCacheID(streamID int) int {
	return streamID / streamsPerCache
}

var credentialsStore = struct {
	mu     sync.RWMutex
	caches map[int]*StreamCredentialsCache
}{
	caches: make(map[int]*StreamCredentialsCache),
}

func getStreamCache(streamID int) *StreamCredentialsCache {
	cacheID := getCacheID(streamID)

	credentialsStore.mu.RLock()
	cache, exists := credentialsStore.caches[cacheID]
	credentialsStore.mu.RUnlock()

	if exists {
		return cache
	}

	credentialsStore.mu.Lock()
	defer credentialsStore.mu.Unlock()

	if cache, exists = credentialsStore.caches[cacheID]; exists {
		return cache
	}

	cache = &StreamCredentialsCache{}
	credentialsStore.caches[cacheID] = cache
	return cache
}

func (c *StreamCredentialsCache) invalidate(streamID int) {
	c.mutex.Lock()
	c.creds = TurnCredentials{}
	c.mutex.Unlock()

	c.errorCount.Store(0)
	c.lastErrorTime.Store(0)

	log.Printf("[STREAM %d] [VK Auth] Credentials cache invalidated", streamID)
}

func cloneStringSlice(in []string) []string {
	out := make([]string, len(in))
	copy(out, in)
	return out
}

func isAuthError(err error) bool {
	if err == nil {
		return false
	}
	errStr := err.Error()
	return strings.Contains(errStr, "401") ||
		strings.Contains(errStr, "Unauthorized") ||
		strings.Contains(errStr, "authentication") ||
		strings.Contains(errStr, "invalid credential") ||
		strings.Contains(errStr, "stale nonce")
}

func handleAuthError(streamID int) bool {
	cache := getStreamCache(streamID)
	cacheID := getCacheID(streamID)

	now := time.Now().Unix()

	if now-cache.lastErrorTime.Load() > int64(errorWindow.Seconds()) {
		cache.errorCount.Store(0)
	}

	count := cache.errorCount.Add(1)
	cache.lastErrorTime.Store(now)

	log.Printf("[STREAM %d] Auth error (cache=%d, count=%d/%d)", streamID, cacheID, count, maxCacheErrors)

	if count >= maxCacheErrors {
		log.Printf("[VK Auth] Multiple auth errors detected (%d), invalidating cache %d", count, cacheID)
		cache.invalidate(streamID)
		return true
	}
	return false
}

// ─── Captcha lockout ───

var globalCaptchaLockout atomic.Int64

const (
	captchaAutoWebViewTimeout     = 10 * time.Second
	captchaManualWebViewTimeout   = 60 * time.Second
	captchaSelectedWebViewTimeout = 120 * time.Second
)

// ─── Random delay ───

func vkDelayRandom(minMs, maxMs int) {
	ms := minMs + rand.Intn(maxMs-minMs+1)
	time.Sleep(time.Duration(ms) * time.Millisecond)
}

// ─── Cached credential fetcher ───

func getVkCredsCached(ctx context.Context, link string, streamID int) (string, string, []string, error) {
	cache := getStreamCache(streamID)
	cacheID := getCacheID(streamID)

	cache.mutex.RLock()
	if cache.creds.Link == link && time.Now().Before(cache.creds.ExpiresAt) && len(cache.creds.ServerAddrs) > 0 {
		expires := time.Until(cache.creds.ExpiresAt)
		u, p := cache.creds.Username, cache.creds.Password
		addr := cache.creds.ServerAddrs[streamID%len(cache.creds.ServerAddrs)]
		addrs := cloneStringSlice(cache.creds.ServerAddrs)
		cache.mutex.RUnlock()
		log.Printf("[STREAM %d] [VK Auth] Using cached credentials (cache=%d, expires in %v, selected=%s, urls=%d)", streamID, cacheID, expires.Truncate(time.Second), addr, len(addrs))
		return u, p, addrs, nil
	}
	cache.mutex.RUnlock()

	cache.mutex.Lock()
	defer cache.mutex.Unlock()

	// Double-check inside lock
	if cache.creds.Link == link && time.Now().Before(cache.creds.ExpiresAt) && len(cache.creds.ServerAddrs) > 0 {
		return cache.creds.Username, cache.creds.Password, cloneStringSlice(cache.creds.ServerAddrs), nil
	}

	user, pass, addrs, err := fetchVkCredsSerialized(ctx, link, streamID)
	if err != nil {
		return "", "", nil, err
	}

	cache.creds = TurnCredentials{
		Username:    user,
		Password:    pass,
		ServerAddrs: addrs,
		ExpiresAt:   time.Now().Add(credentialLifetime - cacheSafetyMargin),
		Link:        link,
	}
	return user, pass, cloneStringSlice(addrs), nil
}

// ─── Serialized (throttled) fetcher ───

var (
	vkRequestMu           sync.Mutex
	globalLastVkFetchTime time.Time
)

func fetchVkCredsSerialized(ctx context.Context, link string, streamID int) (string, string, []string, error) {
	vkRequestMu.Lock()
	defer vkRequestMu.Unlock()

	// Throttle: 3-6 seconds between requests
	minInterval := 3*time.Second + time.Duration(rand.Intn(3000))*time.Millisecond
	elapsed := time.Since(globalLastVkFetchTime)

	if !globalLastVkFetchTime.IsZero() && elapsed < minInterval {
		wait := minInterval - elapsed
		log.Printf("[STREAM %d] [VK Auth] Throttling: waiting %v to prevent rate limit...", streamID, wait.Truncate(time.Millisecond))
		select {
		case <-ctx.Done():
			return "", "", nil, ctx.Err()
		case <-time.After(wait):
		}
	}

	defer func() {
		globalLastVkFetchTime = time.Now()
	}()

	return fetchVkCreds(ctx, link, streamID)
}

// ─── Main credential fetcher (rotates through stable credential sets) ───

func fetchVkCreds(ctx context.Context, link string, streamID int) (string, string, []string, error) {
	if time.Now().Unix() < globalCaptchaLockout.Load() {
		return "", "", nil, fmt.Errorf("CAPTCHA_WAIT_REQUIRED: global lockout active")
	}

	var lastErr error
	jar := tlsclient.NewCookieJar()

	for attempt := 0; attempt < vkCredentialAttemptLimit; attempt++ {
		creds := vkCredentialsList[attempt%len(vkCredentialsList)]
		log.Printf("[STREAM %d] [VK Auth] Trying credentials: client_id=%s (attempt %d/%d)", streamID, creds.ClientID, attempt+1, vkCredentialAttemptLimit)

		user, pass, addrs, err := getTokenChain(ctx, link, streamID, creds, jar)

		if err == nil {
			log.Printf("[STREAM %d] [VK Auth] Success with client_id=%s", streamID, creds.ClientID)
			return user, pass, addrs, nil
		}

		lastErr = err
		log.Printf("[STREAM %d] [VK Auth] Failed with client_id=%s: %v", streamID, creds.ClientID, err)

		if strings.Contains(err.Error(), "CAPTCHA_WAIT_REQUIRED") || strings.Contains(err.Error(), "FATAL_CAPTCHA") {
			return "", "", nil, err
		}

		if strings.Contains(err.Error(), "error_code:29") || strings.Contains(err.Error(), "error_code: 29") || strings.Contains(err.Error(), "Rate limit") {
			log.Printf("[STREAM %d] [VK Auth] Rate limit detected, trying next credentials...", streamID)
		}

		if attempt%len(vkCredentialsList) == len(vkCredentialsList)-1 && attempt+1 < vkCredentialAttemptLimit {
			wait := time.Duration(900+rand.Intn(900)) * time.Millisecond
			log.Printf("[STREAM %d] [VK Auth] Both VK credentials failed, retrying stable pair after %v...", streamID, wait)
			select {
			case <-ctx.Done():
				return "", "", nil, ctx.Err()
			case <-time.After(wait):
			}
		}
	}

	return "", "", nil, fmt.Errorf("all VK credentials failed: %w", lastErr)
}

// ─── Token chain: anon_token → getCallPreview → getAnonymousToken → OK session → joinConversation → TURN creds ───

func getTokenChain(ctx context.Context, link string, streamID int, creds VKCredentials, jar tlsclient.CookieJar) (string, string, []string, error) {
	profile := getRandomProfile()

	client, err := tlsclient.NewHttpClient(tlsclient.NewNoopLogger(),
		tlsclient.WithTimeoutSeconds(20),
		tlsclient.WithClientProfile(profiles.Chrome_146),
		tlsclient.WithCookieJar(jar),
	)
	if err != nil {
		return "", "", nil, fmt.Errorf("failed to initialize tls_client: %w", err)
	}

	name := generateName()
	escapedName := neturl.QueryEscape(name)

	log.Printf("[STREAM %d] [VK Auth] Identity - Name: %s | UA: %s", streamID, name, profile.UserAgent)

	doRequest := func(data string, url string) (resp map[string]interface{}, err error) {
		parsedURL, err := neturl.Parse(url)
		if err != nil {
			return nil, fmt.Errorf("parse request URL: %w", err)
		}
		domain := parsedURL.Hostname()

		req, err := fhttp.NewRequestWithContext(ctx, "POST", url, bytes.NewBuffer([]byte(data)))
		if err != nil {
			return nil, err
		}

		req.Host = domain
		applyBrowserProfileFhttp(req, profile)
		req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
		req.Header.Set("Accept", "*/*")
		req.Header.Set("Origin", "https://vk.ru")
		req.Header.Set("Referer", "https://vk.ru/")
		req.Header.Set("Sec-Fetch-Site", "same-site")
		req.Header.Set("Sec-Fetch-Mode", "cors")
		req.Header.Set("Sec-Fetch-Dest", "empty")
		req.Header.Set("Priority", "u=1, i")

		httpResp, err := client.Do(req)
		if err != nil {
			return nil, err
		}
		defer func() {
			if closeErr := httpResp.Body.Close(); closeErr != nil {
				log.Printf("close response body: %s", closeErr)
			}
		}()

		body, err := io.ReadAll(httpResp.Body)
		if err != nil {
			return nil, err
		}

		err = json.Unmarshal(body, &resp)
		if err != nil {
			return nil, err
		}
		return resp, nil
	}

	// Step 1: get_anonym_token
	data := fmt.Sprintf("client_id=%s&token_type=messages&client_secret=%s&version=1&app_id=%s", creds.ClientID, creds.ClientSecret, creds.ClientID)
	resp, err := doRequest(data, "https://login.vk.ru/?act=get_anonym_token")
	if err != nil {
		return "", "", nil, err
	}
	dataMap, ok := resp["data"].(map[string]interface{})
	if !ok {
		return "", "", nil, fmt.Errorf("unexpected anon token response: %v", resp)
	}
	token1, ok := dataMap["access_token"].(string)
	if !ok {
		return "", "", nil, fmt.Errorf("missing access_token in response: %v", resp)
	}

	vkDelayRandom(100, 150)

	// Step 2: getCallPreview (mimics real VK client behavior)
	data = fmt.Sprintf("vk_join_link=https://vk.com/call/join/%s&fields=photo_200&access_token=%s", link, token1)
	_, err = doRequest(data, "https://api.vk.ru/method/calls.getCallPreview?v=5.275&client_id="+creds.ClientID)
	if err != nil {
		log.Printf("[STREAM %d] [VK Auth] Warning: getCallPreview failed: %v", streamID, err)
	}

	vkDelayRandom(200, 400)

	// Step 3: getAnonymousToken (with captcha handling)
	data = fmt.Sprintf("vk_join_link=https://vk.com/call/join/%s&name=%s&access_token=%s", link, escapedName, token1)
	urlAddr := fmt.Sprintf("https://api.vk.ru/method/calls.getAnonymousToken?v=5.275&client_id=%s", creds.ClientID)

	var token2 string
	var savedProfile *SavedProfile
	savedProfile, _ = LoadProfileFromDisk()

	for attempt := 0; ; attempt++ {
		resp, err = doRequest(data, urlAddr)
		if err != nil {
			return "", "", nil, err
		}

		if errObj, hasErr := resp["error"].(map[string]interface{}); hasErr {
			captchaErr := parseVkCaptchaError(errObj)
			if captchaErr != nil && captchaErr.RedirectURI != "" && captchaErr.SessionToken != "" {
				if attempt >= 3 {
					log.Printf("[STREAM %d] [Captcha] Max attempts reached", streamID)
					globalCaptchaLockout.Store(time.Now().Add(60 * time.Second).Unix())
					return "", "", nil, fmt.Errorf("CAPTCHA_WAIT_REQUIRED")
				}

				successToken, solveErr := solveCaptchaBySelectedMode(ctx, streamID, attempt+1, captchaErr, client, profile, savedProfile)
				if solveErr != nil {
					log.Printf("[STREAM %d] [Captcha] Solve failed: %v", streamID, solveErr)
					globalCaptchaLockout.Store(time.Now().Add(60 * time.Second).Unix())
					return "", "", nil, fmt.Errorf("CAPTCHA_WAIT_REQUIRED")
				}

				captchaAttempt := captchaErr.CaptchaAttempt
				if captchaAttempt == "0" || captchaAttempt == "" {
					captchaAttempt = "1"
				}

				data = fmt.Sprintf("vk_join_link=https://vk.com/call/join/%s&name=%s&captcha_key=&captcha_sid=%s&is_sound_captcha=0&success_token=%s&captcha_ts=%s&captcha_attempt=%s&access_token=%s",
					link, escapedName, captchaErr.CaptchaSid, neturl.QueryEscape(successToken), captchaErr.CaptchaTs, captchaAttempt, token1)
				continue
			}
			return "", "", nil, fmt.Errorf("VK API error: %v", errObj)
		}

		respMap, okLoop := resp["response"].(map[string]interface{})
		if !okLoop {
			return "", "", nil, fmt.Errorf("unexpected getAnonymousToken response: %v", resp)
		}
		token2, okLoop = respMap["token"].(string)
		if !okLoop {
			return "", "", nil, fmt.Errorf("missing token in response: %v", resp)
		}
		break
	}

	vkDelayRandom(100, 150)

	// Step 4: OK.ru anonymLogin
	sessionData := fmt.Sprintf(`{"version":2,"device_id":"%s","client_version":1.1,"client_type":"SDK_JS"}`, uuid.New())
	data = fmt.Sprintf("session_data=%s&method=auth.anonymLogin&format=JSON&application_key=CGMMEJLGDIHBABABA", neturl.QueryEscape(sessionData))
	resp, err = doRequest(data, "https://calls.okcdn.ru/fb.do")
	if err != nil {
		return "", "", nil, err
	}
	token3, ok := resp["session_key"].(string)
	if !ok {
		return "", "", nil, fmt.Errorf("missing session_key in response: %v", resp)
	}

	vkDelayRandom(100, 150)

	// Step 5: joinConversationByLink → TURN creds
	data = fmt.Sprintf("joinLink=%s&isVideo=false&protocolVersion=5&capabilities=2F7F&anonymToken=%s&method=vchat.joinConversationByLink&format=JSON&application_key=CGMMEJLGDIHBABABA&session_key=%s", link, token2, token3)
	resp, err = doRequest(data, "https://calls.okcdn.ru/fb.do")
	if err != nil {
		return "", "", nil, err
	}

	tsRaw, ok := resp["turn_server"].(map[string]interface{})
	if !ok {
		return "", "", nil, fmt.Errorf("missing turn_server in response: %v", resp)
	}
	user, ok := tsRaw["username"].(string)
	if !ok {
		return "", "", nil, fmt.Errorf("missing username in turn_server")
	}
	pass, ok := tsRaw["credential"].(string)
	if !ok {
		return "", "", nil, fmt.Errorf("missing credential in turn_server")
	}
	urlsRaw, ok := tsRaw["urls"].([]interface{})
	if !ok || len(urlsRaw) == 0 {
		return "", "", nil, fmt.Errorf("missing or empty urls in turn_server")
	}

	log.Printf("[STREAM %d] [VK Auth] TURN urls (%d total):", streamID, len(urlsRaw))
	for i, u := range urlsRaw {
		log.Printf("[STREAM %d] [VK Auth]   [%d] %v", streamID, i, u)
	}

	var addresses []string
	for _, u := range urlsRaw {
		urlStr, ok := u.(string)
		if !ok {
			continue
		}
		clean := strings.Split(urlStr, "?")[0]
		address := strings.TrimPrefix(strings.TrimPrefix(clean, "turn:"), "turns:")
		addresses = append(addresses, address)
	}

	if len(addresses) == 0 {
		return "", "", nil, fmt.Errorf("no valid TURN addresses found")
	}

	return user, pass, addresses, nil
}

func solveCaptchaBySelectedMode(
	ctx context.Context,
	streamID int,
	attempt int,
	captchaErr *VkCaptchaError,
	client tlsclient.HttpClient,
	profile Profile,
	savedProfile *SavedProfile,
) (string, error) {
	switch getCaptchaMode() {
	case "wv":
		log.Printf("[STREAM %d] [КАПЧА] WBV: режим из настроек Android (attempt %d)", streamID, attempt)
		return requestWebViewCaptcha(streamID, captchaErr, "selected", captchaSelectedWebViewTimeout)
	case "rjs":
		log.Printf("[STREAM %d] [КАПЧА] RJS: Go v2 выбран в настройках (attempt %d)", streamID, attempt)
		token, solveErr := solveVkCaptchaV2Attempts(ctx, captchaErr, client, profile, savedProfile, 2)
		if solveErr == nil {
			return token, nil
		}
		if ctx.Err() != nil {
			return "", solveErr
		}
		log.Printf("[STREAM %d] [КАПЧА] RJS: ошибка, fallback на WBV Auto: %v", streamID, solveErr)
		return requestWebViewCaptcha(streamID, captchaErr, "auto", captchaAutoWebViewTimeout)
	}

	log.Printf("[STREAM %d] [КАПЧА] AUTO: старт цепочки (captcha attempt %d)", streamID, attempt)

	token, solveErr := solveVkCaptchaV2Attempts(ctx, captchaErr, client, profile, savedProfile, 2)
	if solveErr == nil {
		log.Printf("[STREAM %d] [КАПЧА] AUTO: Go v2 решил капчу", streamID)
		return token, nil
	}
	if ctx.Err() != nil {
		return "", solveErr
	}
	lastErr := solveErr
	log.Printf("[STREAM %d] [КАПЧА] AUTO: Go v2 не решил за 2 попытки: %v", streamID, solveErr)

	for wbvAttempt := 1; wbvAttempt <= 2; wbvAttempt++ {
		log.Printf("[STREAM %d] [КАПЧА] AUTO: WBV Auto попытка %d/2 (timeout %s)", streamID, wbvAttempt, captchaAutoWebViewTimeout)
		token, solveErr = requestWebViewCaptcha(streamID, captchaErr, "auto", captchaAutoWebViewTimeout)
		if solveErr == nil {
			log.Printf("[STREAM %d] [КАПЧА] AUTO: WBV Auto решил капчу", streamID)
			return token, nil
		}
		if ctx.Err() != nil {
			return "", solveErr
		}
		lastErr = solveErr
		if isWebViewCaptchaTimeout(solveErr) {
			log.Printf("[STREAM %d] [КАПЧА] AUTO: WBV Auto timeout %d/2", streamID, wbvAttempt)
		} else {
			log.Printf("[STREAM %d] [КАПЧА] AUTO: WBV Auto ошибка %d/2: %v", streamID, wbvAttempt, solveErr)
		}

		timer := time.NewTimer(time.Duration(250+rand.Intn(250)) * time.Millisecond)
		select {
		case <-ctx.Done():
			timer.Stop()
			return "", ctx.Err()
		case <-timer.C:
		}
	}

	log.Printf("[STREAM %d] [КАПЧА] AUTO: финальная Go v2 попытка после WBV", streamID)
	token, solveErr = solveVkCaptchaV2Attempts(ctx, captchaErr, client, profile, savedProfile, 1)
	if solveErr == nil {
		log.Printf("[STREAM %d] [КАПЧА] AUTO: финальная Go v2 решила капчу", streamID)
		return token, nil
	}
	if ctx.Err() != nil {
		return "", solveErr
	}
	lastErr = solveErr
	log.Printf("[STREAM %d] [КАПЧА] AUTO: финальная Go v2 ошибка: %v", streamID, solveErr)

	log.Printf("[STREAM %d] [КАПЧА] AUTO: автоцепочка не прошла, открыт ручной WebView", streamID)
	token, solveErr = requestWebViewCaptcha(streamID, captchaErr, "manual", captchaManualWebViewTimeout)
	if solveErr == nil {
		log.Printf("[STREAM %d] [КАПЧА] AUTO: ручной WebView решил капчу", streamID)
		return token, nil
	}
	if lastErr != nil {
		return "", fmt.Errorf("automatic captcha chain failed: %w; manual fallback failed: %v", lastErr, solveErr)
	}
	return "", solveErr
}

func requestWebViewCaptcha(streamID int, captchaErr *VkCaptchaError, mode string, timeout time.Duration) (string, error) {
	if CaptchaResultChan == nil || captchaErr == nil || captchaErr.RedirectURI == "" || captchaErr.SessionToken == "" {
		return "", fmt.Errorf("webview captcha data is incomplete")
	}
	mode = strings.ToLower(strings.TrimSpace(mode))
	if mode != "manual" && mode != "selected" {
		mode = "auto"
	}
	if timeout <= 0 {
		timeout = captchaAutoWebViewTimeout
	}

	drainCaptchaResult()
	fmt.Printf("CAPTCHA_SOLVE|%s|%s|%s\n", mode, captchaErr.RedirectURI, captchaErr.SessionToken)

	waitCtx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()

	select {
	case result := <-CaptchaResultChan:
		result = strings.TrimSpace(result)
		if result == "" {
			return "", fmt.Errorf("webview captcha returned empty result")
		}
		lowerResult := strings.ToLower(result)
		if lowerResult == "error:timeout" {
			return "", fmt.Errorf("webview captcha timed out")
		}
		if strings.HasPrefix(lowerResult, "error:") {
			return "", fmt.Errorf("webview captcha failed: %s", result)
		}
		log.Printf("[STREAM %d] [КАПЧА] WBV: %s solve succeeded", streamID, mode)
		return result, nil
	case <-waitCtx.Done():
		return "", fmt.Errorf("webview captcha timed out")
	}
}

func isWebViewCaptchaTimeout(err error) bool {
	return err != nil && strings.Contains(strings.ToLower(err.Error()), "timed out")
}

// ─── GetCreds returns TURN credentials for a given stream ───

func GetCreds(ctx context.Context, link string, streamID int) (string, string, []string, error) {
	return getVkCredsCached(ctx, link, streamID)
}

// ─── DNS dialer setup ───

func setupGlobalResolver() {
	dialer := &net.Dialer{
		Timeout:   3 * time.Second,
		KeepAlive: 30 * time.Second,
	}
	yandexDNSServers := []string{"77.88.8.8:53", "77.88.8.1:53"}

	net.DefaultResolver = &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, address string) (net.Conn, error) {
			var lastErr error
			for _, dns := range yandexDNSServers {
				conn, err := dialer.DialContext(ctx, "udp", dns)
				if err == nil {
					return conn, nil
				}
				lastErr = err
				conn, err = dialer.DialContext(ctx, "tcp", dns)
				if err == nil {
					return conn, nil
				}
				lastErr = err
			}

			address = strings.TrimSpace(address)
			if address != "" && !isYandexDNSAddress(address) {
				conn, err := dialer.DialContext(ctx, network, address)
				if err == nil {
					return conn, nil
				}
				lastErr = err
			}
			return nil, lastErr
		},
	}
}

func isYandexDNSAddress(address string) bool {
	host, _, err := net.SplitHostPort(address)
	if err != nil {
		host = address
	}
	host = strings.Trim(host, "[]")
	return host == "77.88.8.8" || host == "77.88.8.1"
}
