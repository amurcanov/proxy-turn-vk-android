package main

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"math/rand"
	"net"
	"net/http"
	neturl "net/url"
	"os"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/google/uuid"
)

var vkSemaphore = make(chan struct{}, 2)

var (
	sharedTransportOnce sync.Once
	sharedTransport     *http.Transport
)

var noDnsFlag atomic.Bool

var userAgent atomic.Value

func init() {
	userAgent.Store("Mozilla/5.0")
}

func SetUserAgent(ua string) {
	if ua != "" {
		userAgent.Store(ua)
	}
}

func getUserAgent() string {
	return userAgent.Load().(string)
}

func getSharedTransport() *http.Transport {
	sharedTransportOnce.Do(func() {
		dialer := &net.Dialer{
			Timeout: 10 * time.Second,
		}
		sharedTransport = &http.Transport{
			Proxy:                 http.ProxyFromEnvironment,
			DialContext:           dialer.DialContext,
			ForceAttemptHTTP2:     true,
			MaxIdleConns:          100,
			MaxIdleConnsPerHost:   10,
			IdleConnTimeout:       90 * time.Second,
			TLSHandshakeTimeout:   10 * time.Second,
			ExpectContinueTimeout: 1 * time.Second,
		}
	})
	return sharedTransport
}

type Credentials struct {
	User     string
	Pass     string
	TurnURLs []string
}

func GetCredsWithFallback(ctx context.Context, tp *TurnParams, hash string, stats *Stats) (*Credentials, error) {
	creds, err := getUniqueVKCreds(ctx, hash, 5, stats)
	if err == nil {
		return creds, nil
	}
	if tp.SecondaryHash != "" && hash != tp.SecondaryHash {
		log.Println("Основной хеш не сработал, пробую запасной")
		return getUniqueVKCreds(ctx, tp.SecondaryHash, 3, stats)
	}
	return nil, err
}

type vkCaptchaError struct {
	ErrorCode      int
	ErrorMsg       string
	CaptchaSid     string
	RedirectUri    string
	SessionToken   string
	CaptchaTs      string
	CaptchaAttempt string
}

func parseVkCaptchaError(errData map[string]interface{}) *vkCaptchaError {
	codeFloat, _ := errData["error_code"].(float64)
	redirectUri, _ := errData["redirect_uri"].(string)
	errorMsg, _ := errData["error_msg"].(string)

	captchaSid, _ := errData["captcha_sid"].(string)
	if captchaSid == "" {
		if sidNum, ok := errData["captcha_sid"].(float64); ok {
			captchaSid = fmt.Sprintf("%.0f", sidNum)
		}
	}

	var sessionToken string
	if redirectUri != "" {
		if parsed, err := neturl.Parse(redirectUri); err == nil {
			sessionToken = parsed.Query().Get("session_token")
		}
	}

	var captchaTs string
	if tsFloat, ok := errData["captcha_ts"].(float64); ok {
		captchaTs = strconv.FormatFloat(tsFloat, 'f', -1, 64)
	} else if tsStr, ok := errData["captcha_ts"].(string); ok {
		captchaTs = tsStr
	}

	var captchaAttempt string
	if attFloat, ok := errData["captcha_attempt"].(float64); ok {
		captchaAttempt = fmt.Sprintf("%.0f", attFloat)
	} else if attStr, ok := errData["captcha_attempt"].(string); ok {
		captchaAttempt = attStr
	}

	return &vkCaptchaError{
		ErrorCode:      int(codeFloat),
		ErrorMsg:       errorMsg,
		CaptchaSid:     captchaSid,
		RedirectUri:    redirectUri,
		SessionToken:   sessionToken,
		CaptchaTs:      captchaTs,
		CaptchaAttempt: captchaAttempt,
	}
}

func solveVkCaptcha(ctx context.Context, captchaErr *vkCaptchaError, profile BotProfile) (string, error) {
	if captchaErr.SessionToken == "" {
		return "", fmt.Errorf("нет session_token в redirect_uri")
	}

	mode := getCaptchaMode()
	if mode == "wv" {
		log.Printf("[КАПЧА] Режим: WebView")
		return solveVkCaptchaViaWV(ctx, captchaErr)
	}

	log.Printf("[КАПЧА] Режим: Автоматический")
	return solveVkCaptchaViaRJS(ctx, captchaErr, profile)
}

func getCaptchaMode() string {
	return os.Getenv("WDTT_CAPTCHA_MODE")
}

func SetCaptchaModeEnv(mode string) {
	os.Setenv("WDTT_CAPTCHA_MODE", mode)
}

var captchaWVSem = make(chan struct{}, 1)

func solveVkCaptchaViaWV(ctx context.Context, captchaErr *vkCaptchaError) (string, error) {
	select {
	case captchaWVSem <- struct{}{}:
	case <-ctx.Done():
		return "", fmt.Errorf("отмена во время ожидания очереди капчи: %w", ctx.Err())
	}
	defer func() {
		// Даем Kotlin гарантированную секунду на очистку Mutex и WebView
		time.Sleep(1 * time.Second)
		<-captchaWVSem
	}()

	log.Printf("[КАПЧА] WV: Запрос отправлен")

	drainCaptchaResult()

	fmt.Printf("CAPTCHA_SOLVE|%s|%s\n", captchaErr.RedirectUri, captchaErr.SessionToken)
	os.Stdout.Sync()

	select {
	case result := <-CaptchaResultCh:
		if strings.HasPrefix(result, "error:") {
			errMsg := strings.TrimPrefix(result, "error:")
			if !strings.Contains(errMsg, "tunnel stopped") {
				log.Printf("[КАПЧА] WV: Ошибка — %s", errMsg)
			}
			return "", fmt.Errorf("WV captcha error: %s", errMsg)
		}
		log.Printf("[КАПЧА] WV: Токен получен ✓")
		return result, nil
	case <-ctx.Done():
		return "", fmt.Errorf("отмена контекста во время ожидания капчи: %w", ctx.Err())
	case <-time.After(45 * time.Second):
		return "", fmt.Errorf("таймаут решения капчи через WV (45с)")
	}
}

func truncateStr(s string, maxLen int) string {
	if len(s) <= maxLen {
		return s
	}
	return s[:maxLen] + "..."
}

func solveVkCaptchaViaRJS(ctx context.Context, captchaErr *vkCaptchaError, profile BotProfile) (string, error) {
	log.Printf("[КАПЧА] RJS: Загрузка страницы капчи...")
	powInput, difficulty, err := fetchPowInput(ctx, captchaErr.RedirectUri, profile.UserAgent)
	if err != nil {
		return "", fmt.Errorf("не удалось получить PoW input: %w", err)
	}

	captchaRng := rand.New(rand.NewSource(time.Now().UnixNano()))
	timing := GenerateCaptchaTiming(captchaRng)

	log.Printf("[КАПЧА] RJS: Человек осматривает страницу капчи...")
	time.Sleep(time.Duration(timing.ReadCaptchaMs) * time.Millisecond)

	log.Printf("[КАПЧА] RJS: Решение PoW...")
	hash := solvePoW(powInput, difficulty)
	time.Sleep(time.Duration(timing.FetchPowMs) * time.Millisecond)

	log.Printf("[КАПЧА] RJS: Отправка данных...")
	successToken, err := callCaptchaNotRobot(ctx, captchaErr.SessionToken, hash, profile, captchaRng)
	if err != nil {
		return "", fmt.Errorf("ошибка captchaNotRobot API: %w", err)
	}

	log.Printf("[КАПЧА] RJS: Завершение сессии...")
	time.Sleep(time.Duration(timing.EndSessionMs) * time.Millisecond)

	vkReq := func(method string, postData string) (map[string]interface{}, error) {
		reqURL := "https://api.vk.ru/method/" + method + "?v=5.131"
		req, _ := http.NewRequestWithContext(ctx, "POST", reqURL, strings.NewReader(postData))
		req.Header.Set("User-Agent", profile.UserAgent)
		req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
		client := &http.Client{Timeout: 20 * time.Second}
		resp, err := client.Do(req)
		if err != nil {
			return nil, err
		}
		defer resp.Body.Close()
		body, _ := io.ReadAll(resp.Body)
		var r map[string]interface{}
		json.Unmarshal(body, &r)
		return r, nil
	}
	baseParams := fmt.Sprintf("session_token=%s&domain=vk.com&adFp=&access_token=", neturl.QueryEscape(captchaErr.SessionToken))
	vkReq("captchaNotRobot.endSession", baseParams)

	log.Printf("[КАПЧА] RJS: Капча решена успешно ✓")
	return successToken, nil
}

func fetchPowInput(ctx context.Context, redirectUri, userAgent string) (string, int, error) {
	req, err := http.NewRequestWithContext(ctx, "GET", redirectUri, nil)
	if err != nil {
		return "", 0, err
	}
	req.Header.Set("User-Agent", userAgent)
	req.Header.Set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")

	client := &http.Client{Timeout: 20 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return "", 0, err
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return "", 0, err
	}
	html := string(body)

	powInputRe := regexp.MustCompile(`const\s+powInput\s*=\s*"([^"]+)"`)
	powInputMatch := powInputRe.FindStringSubmatch(html)
	if len(powInputMatch) < 2 {
		return "", 0, fmt.Errorf("powInput не найден в HTML капчи")
	}
	powInput := powInputMatch[1]

	diffRe := regexp.MustCompile(`startsWith\('0'\.repeat\((\d+)\)\)`)
	diffMatch := diffRe.FindStringSubmatch(html)
	difficulty := 2
	if len(diffMatch) >= 2 {
		if d, err := strconv.Atoi(diffMatch[1]); err == nil {
			difficulty = d
		}
	}
	return powInput, difficulty, nil
}

func solvePoW(powInput string, difficulty int) string {
	target := strings.Repeat("0", difficulty)
	for nonce := 1; nonce <= 10000000; nonce++ {
		data := powInput + strconv.Itoa(nonce)
		hash := sha256.Sum256([]byte(data))
		hexHash := hex.EncodeToString(hash[:])
		if strings.HasPrefix(hexHash, target) {
			return hexHash
		}
	}
	return ""
}

func callCaptchaNotRobot(ctx context.Context, sessionToken, hash string, profile BotProfile, captchaRng *rand.Rand) (string, error) {
	captchaUA := "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36"
	captchaBrowserFP := fmt.Sprintf("%016x%016x", rand.Uint64(), rand.Uint64())
	captchaDeviceJSON := `{"screenWidth":1920,"screenHeight":1080,"screenAvailWidth":1920,"screenAvailHeight":1032,"innerWidth":1920,"innerHeight":945,"devicePixelRatio":1,"language":"en-US","languages":["en-US"],"webdriver":false,"hardwareConcurrency":16,"deviceMemory":8,"connectionEffectiveType":"4g","notificationsPermission":"denied"}`

	captchaCursor := GenerateCaptchaCursor(captchaRng)

	captchaDownlink := GenerateCaptchaDownlink(captchaRng)

	captchaRTT := GenerateCaptchaConnectionRtt(captchaRng)

	captchaAccel := "[]"
	captchaGyro := "[]"
	captchaMotion := "[]"
	captchaTaps := "[]"

	vkReq := func(method string, postData string) (map[string]interface{}, error) {
		reqURL := "https://api.vk.ru/method/" + method + "?v=5.131"
		req, err := http.NewRequestWithContext(ctx, "POST", reqURL, strings.NewReader(postData))
		if err != nil {
			return nil, err
		}
		req.Header.Set("User-Agent", captchaUA)
		req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
		req.Header.Set("Origin", "https://id.vk.ru")
		req.Header.Set("Referer", "https://id.vk.ru/")
		req.Header.Set("sec-ch-ua-platform", `"Windows"`)
		req.Header.Set("sec-ch-ua", `"Chromium";v="146", "Not-A.Brand";v="24", "Google Chrome";v="146"`)
		req.Header.Set("sec-ch-ua-mobile", "?0")
		req.Header.Set("Sec-Fetch-Site", "same-site")
		req.Header.Set("Sec-Fetch-Mode", "cors")
		req.Header.Set("Sec-Fetch-Dest", "empty")
		req.Header.Set("DNT", "1")
		req.Header.Set("Priority", "u=1, i")
		req.Header.Set("Accept", "*/*")
		req.Header.Set("Accept-Language", "en-US,en;q=0.9")

		client := &http.Client{
			Timeout:   20 * time.Second,
			Transport: getSharedTransport(),
		}
		httpResp, err := client.Do(req)
		if err != nil {
			return nil, err
		}
		defer httpResp.Body.Close()

		body, err := io.ReadAll(httpResp.Body)
		if err != nil {
			return nil, err
		}
		var resp map[string]interface{}
		if err := json.Unmarshal(body, &resp); err != nil {
			return nil, err
		}
		return resp, nil
	}

	baseParams := fmt.Sprintf("session_token=%s&domain=vk.com&adFp=&access_token=", neturl.QueryEscape(sessionToken))

	timing := GenerateCaptchaTiming(captchaRng)

	log.Printf("[КАПЧА]   Шаг 1/4: settings...")
	_, err := vkReq("captchaNotRobot.settings", baseParams)
	if err != nil {
		return "", fmt.Errorf("settings failed: %w", err)
	}

	log.Printf("[КАПЧА]   ...пауза: изучение виджета...")
	time.Sleep(time.Duration(timing.SettingsToComponentMs) * time.Millisecond)

	log.Printf("[КАПЧА]   Шаг 2/4: componentDone...")
	componentDoneData := baseParams + fmt.Sprintf("&browser_fp=%s&device=%s", captchaBrowserFP, neturl.QueryEscape(captchaDeviceJSON))
	_, err = vkReq("captchaNotRobot.componentDone", componentDoneData)
	if err != nil {
		return "", fmt.Errorf("componentDone failed: %w", err)
	}

	log.Printf("[КАПЧА]   ...пауза: движение мыши к чекбоксу + клик...")
	time.Sleep(time.Duration(timing.ComponentToCheckMs) * time.Millisecond)

	if timing.ExtraPauseMs > 0 {
		log.Printf("[КАПЧА]   ...дополнительная пауза: человек 'завис'...")
		time.Sleep(time.Duration(timing.ExtraPauseMs) * time.Millisecond)
	}

	log.Printf("[КАПЧА]   Шаг 3/4: check...")
	answer := base64.StdEncoding.EncodeToString([]byte("{}"))

	debugInfo := profile.DebugInfo

	checkData := baseParams + fmt.Sprintf(
		"&accelerometer=%s&gyroscope=%s&motion=%s&cursor=%s&taps=%s&connectionRtt=%s&connectionDownlink=%s&browser_fp=%s&hash=%s&answer=%s&debug_info=%s",
		neturl.QueryEscape(captchaAccel),
		neturl.QueryEscape(captchaGyro),
		neturl.QueryEscape(captchaMotion),
		neturl.QueryEscape(captchaCursor),
		neturl.QueryEscape(captchaTaps),
		neturl.QueryEscape(captchaRTT),
		neturl.QueryEscape(captchaDownlink),
		captchaBrowserFP,
		hash,
		answer,
		debugInfo,
	)

	checkResp, err := vkReq("captchaNotRobot.check", checkData)
	if err != nil {
		return "", fmt.Errorf("check failed: %w", err)
	}

	respObj, ok := checkResp["response"].(map[string]interface{})
	if !ok {
		return "", fmt.Errorf("invalid check response: %v", checkResp)
	}
	status, _ := respObj["status"].(string)
	if status != "OK" {
		return "", fmt.Errorf("check status: %s", status)
	}
	successToken, ok := respObj["success_token"].(string)
	if !ok || successToken == "" {
		return "", fmt.Errorf("success_token not found")
	}

	time.Sleep(time.Duration(timing.CheckToEndMs) * time.Millisecond)

	return successToken, nil
}

func getUniqueVKCreds(ctx context.Context, hash string, maxRetries int, stats *Stats) (*Credentials, error) {
	var lastErr error

	realUA := getUserAgent()
	actionSeed := uint64(time.Now().UnixNano()) ^ uint64(len(hash))
	profile := GenerateBotProfile(realUA, hash, actionSeed)

	for attempt := 0; attempt < maxRetries; attempt++ {
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case vkSemaphore <- struct{}{}:
		}

		creds, err := getVKCredsOnce(ctx, hash, profile)
		<-vkSemaphore

		if err == nil {
			return creds, nil
		}

		atomic.AddInt64(&stats.CredsErrors, 1)
		lastErr = err
		errStr := err.Error()

		if strings.Contains(errStr, "9000") || strings.Contains(errStr, "call not found") {
			return nil, fmt.Errorf("хеш мёртв: %w", err)
		}

		var backoff time.Duration
		if strings.Contains(errStr, "flood") || strings.Contains(errStr, "Flood") {
			secs := 5 * (attempt + 1)
			if secs > 60 {
				secs = 60
			}
			backoff = time.Duration(secs) * time.Second
		} else {
			base := 1 << uint(min(attempt, 5))
			if base > 30 {
				base = 30
			}
			backoff = time.Duration(base)*time.Second + time.Duration(rand.Intn(1000))*time.Millisecond
		}

		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-time.After(backoff):
		}
	}

	return nil, fmt.Errorf("исчерпаны %d попыток: %w", maxRetries, lastErr)
}

func min(a, b int) int {
	if a < b {
		return a
	}
	return b
}

func getVKCredsOnce(ctx context.Context, hash string, profile BotProfile) (*Credentials, error) {
	client := &http.Client{
		Timeout:   15 * time.Second,
		Transport: getSharedTransport(),
	}

	okAppKey := "CGMMEJLGDIHBABABA"
	appID := vkAppID.Load().(string)
	appSecret := vkAppSecret.Load().(string)

	doReq := func(data, url string) (map[string]interface{}, error) {
		req, err := http.NewRequestWithContext(ctx, "POST", url, bytes.NewBufferString(data))
		if err != nil {
			return nil, fmt.Errorf("создание запроса: %w", err)
		}
		req.Header.Set("User-Agent", profile.UserAgent)
		req.Header.Set("Content-Type", "application/x-www-form-urlencoded")

		resp, err := client.Do(req)
		if err != nil {
			return nil, err
		}
		defer resp.Body.Close()

		body, err := io.ReadAll(resp.Body)
		if err != nil {
			return nil, fmt.Errorf("чтение ответа: %w", err)
		}

		var m map[string]interface{}
		if err := json.Unmarshal(body, &m); err != nil {
			return nil, fmt.Errorf("парсинг JSON: %w | Body: %s", err, string(body))
		}
		return m, nil
	}

	checkAPIError := func(m map[string]interface{}, step string) error {
		if errObj, ok := m["error"]; ok {
			return fmt.Errorf("%s API error: %v", step, errObj)
		}
		return nil
	}

	get := func(m map[string]interface{}, keys ...string) (string, error) {
		var cur interface{} = m
		for _, k := range keys {
			mm, ok := cur.(map[string]interface{})
			if !ok {
				return "", fmt.Errorf("path %q not found", k)
			}
			cur = mm[k]
		}
		s, ok := cur.(string)
		if !ok {
			return "", fmt.Errorf("value at path is not string")
		}
		return s, nil
	}

	r, err := doReq(fmt.Sprintf(
		"client_secret=%s&client_id=%s&scopes=audio_anonymous%%2Cvideo_anonymous%%2Cphotos_anonymous%%2Cprofile_anonymous&isApiOauthAnonymEnabled=false&version=1&app_id=%s",
		appSecret, appID, appID,
	), "https://login.vk.ru/?act=get_anonym_token")
	if err != nil {
		return nil, fmt.Errorf("шаг 1: %w", err)
	}
	if err := checkAPIError(r, "шаг 1"); err != nil {
		return nil, err
	}
	t1, err := get(r, "data", "access_token")
	if err != nil {
		return nil, fmt.Errorf("шаг 1 парсинг: %w", err)
	}

	r, err = doReq(fmt.Sprintf(
		"client_id=%s&token_type=messages&payload=%s&client_secret=%s&version=1&app_id=%s",
		appID, t1, appSecret, appID,
	), "https://login.vk.ru/?act=get_anonym_token")
	if err != nil {
		return nil, fmt.Errorf("шаг 2: %w", err)
	}
	if err := checkAPIError(r, "шаг 2"); err != nil {
		return nil, err
	}
	t3, err := get(r, "data", "access_token")
	if err != nil {
		return nil, fmt.Errorf("шаг 2 парсинг: %w", err)
	}

	var t4 string

	postData := fmt.Sprintf(
		"vk_join_link=https://vk.com/call/join/%s&name=%s&access_token=%s",
		hash, neturl.QueryEscape(profile.Name), t3,
	)
	r, err = doReq(postData, "https://api.vk.ru/method/calls.getAnonymousToken?v=5.264")
	if err != nil {
		return nil, fmt.Errorf("шаг 3: %w", err)
	}

	if errObj, hasErr := r["error"].(map[string]interface{}); hasErr {
		errCode, _ := errObj["error_code"].(float64)
		if errCode == 14 {
			captchaErr := parseVkCaptchaError(errObj)
			log.Printf("[КАПЧА] Обнаружена: sid=%s, ts=%s, attempt=%s", captchaErr.CaptchaSid, captchaErr.CaptchaTs, captchaErr.CaptchaAttempt)
			if captchaErr.SessionToken == "" {
				return nil, fmt.Errorf("капча без session_token (старый тип)")
			}

			successToken, solveErr := solveVkCaptcha(ctx, captchaErr, profile)
			if solveErr != nil {
				if !strings.Contains(solveErr.Error(), "tunnel stopped") {
					log.Printf("[КАПЧА] ОШИБКА решения: %v", solveErr)
				}
				return nil, fmt.Errorf("ошибка решения капчи: %w", solveErr)
			}

			captchaAttemptStr := captchaErr.CaptchaAttempt
			if captchaAttemptStr == "0" || captchaAttemptStr == "" {
				captchaAttemptStr = "1"
			}

			postData = fmt.Sprintf(
				"vk_join_link=https://vk.com/call/join/%s&name=%s&access_token=%s&captcha_key=&captcha_sid=%s&is_sound_captcha=0&success_token=%s&captcha_ts=%s&captcha_attempt=%s",
				hash, neturl.QueryEscape(profile.Name), t3, captchaErr.CaptchaSid,
				neturl.QueryEscape(successToken), captchaErr.CaptchaTs, captchaAttemptStr,
			)
			r, err = doReq(postData, "https://api.vk.ru/method/calls.getAnonymousToken?v=5.264")
			if err != nil {
				return nil, fmt.Errorf("шаг 3 (после капчи): %w", err)
			}

			if errObj2, hasErr2 := r["error"].(map[string]interface{}); hasErr2 {
				errCode2, _ := errObj2["error_code"].(float64)
				if errCode2 == 14 {
					time.Sleep(30 * time.Second)
					log.Printf("[КАПЧА] ОТКАЗ: VK всё ещё требует капчу после решения")
					return nil, fmt.Errorf("капча не пройдена после решения (пауза 30с)")
				}
				log.Printf("[КАПЧА] ОШИБКА после решения: %v", errObj2)
				return nil, fmt.Errorf("VK API error после капчи: %v", errObj2)
			}
			log.Printf("[КАПЧА] УСПЕХ: токен получен после решения капчи")
		} else {
			return nil, fmt.Errorf("VK API error: %v", errObj)
		}
	}

	t4, err = get(r, "response", "token")
	if err != nil {
		return nil, fmt.Errorf("шаг 3 парсинг: %w", err)
	}

	r, err = doReq(fmt.Sprintf(
		"session_data=%%7B%%22version%%22%%3A2%%2C%%22device_id%%22%%3A%%22%s%%22%%2C%%22client_version%%22%%3A1.1%%2C%%22client_type%%22%%3A%%22SDK_JS%%22%%7D&method=auth.anonymLogin&format=JSON&application_key=%s",
		uuid.New().String(), okAppKey,
	), "https://calls.okcdn.ru/fb.do")
	if err != nil {
		return nil, fmt.Errorf("шаг 4: %w", err)
	}
	if err := checkAPIError(r, "шаг 4"); err != nil {
		return nil, err
	}
	t5, err := get(r, "session_key")
	if err != nil {
		return nil, fmt.Errorf("шаг 4 парсинг: %w", err)
	}

	r, err = doReq(fmt.Sprintf(
		"joinLink=%s&isVideo=false&protocolVersion=5&anonymToken=%s&method=vchat.joinConversationByLink&format=JSON&application_key=%s&session_key=%s",
		hash, t4, okAppKey, t5,
	), "https://calls.okcdn.ru/fb.do")
	if err != nil {
		return nil, fmt.Errorf("шаг 5: %w", err)
	}
	if err := checkAPIError(r, "шаг 5"); err != nil {
		return nil, err
	}

	ts, ok := r["turn_server"].(map[string]interface{})
	if !ok {
		return nil, fmt.Errorf("turn_server не найден в ответе")
	}

	user, _ := ts["username"].(string)
	pass, _ := ts["credential"].(string)
	if user == "" || pass == "" {
		return nil, fmt.Errorf("пустые credentials в ответе")
	}

	lifetime, _ := ts["lifetime"].(float64)
	if lifetime > 0 {
		log.Printf("[ВК] Креды получены ✓ (LIFE: %.0f сек)", lifetime)
	} else {
		log.Printf("[ВК] Креды получены ✓")
	}

	urls, _ := ts["urls"].([]interface{})
	var turnAddrs []string
	for _, u := range urls {
		s, ok := u.(string)
		if !ok {
			continue
		}
		clean := strings.Split(s, "?")[0]
		addr := strings.TrimPrefix(strings.TrimPrefix(clean, "turn:"), "turns:")
		if addr != "" {
			turnAddrs = append(turnAddrs, addr)
		}
	}
	if len(turnAddrs) == 0 {
		return nil, fmt.Errorf("нет TURN urls в ответе")
	}

	return &Credentials{User: user, Pass: pass, TurnURLs: turnAddrs}, nil
}
