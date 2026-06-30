package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log"
	neturl "net/url"
	"os"
	"strings"

	fhttp "github.com/bogdanfinn/fhttp"
	tlsclient "github.com/bogdanfinn/tls-client"
	"github.com/bogdanfinn/tls-client/profiles"
	"github.com/google/uuid"
)

const (
	vkConnectClientID     = "8093730"
	vkCallsAPIHost        = "api.vk.me"
	vkCallsAnonAPIVersion = "5.276"
)

var vkCallsProfile = Profile{
	UserAgent:       "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36",
	SecChUa:         `"Chromium";v="146", "Not-A.Brand";v="24", "Google Chrome";v="146"`,
	SecChUaMobile:   "?0",
	SecChUaPlatform: `"Windows"`,
}

func getVKCredsViaVKCallsPath(ctx context.Context, link string, streamID int) (string, string, []string, error) {
	if os.Getenv("VK_SKIP_VKCALLS") == "1" {
		return "", "", nil, fmt.Errorf("vkcalls skipped")
	}

	deviceID := uuid.New().String()
	name := generateName()
	profile := vkCallsProfile
	linkURL := neturl.QueryEscape("https://vk.com/call/join/" + link)
	nameEnc := neturl.QueryEscape(name)

	client, err := tlsclient.NewHttpClient(tlsclient.NewNoopLogger(),
		tlsclient.WithTimeoutSeconds(20),
		tlsclient.WithClientProfile(profiles.Chrome_146),
		tlsclient.WithCookieJar(tlsclient.NewCookieJar()),
	)
	if err != nil {
		return "", "", nil, fmt.Errorf("vkcalls client: %w", err)
	}

	log.Printf("[STREAM %d] [VKCalls] Identity - Name: %s | device_id=%s | TLS=Chrome_146 | UA: %s", streamID, name, deviceID, profile.UserAgent)

	doRequest := func(url string) (map[string]interface{}, error) {
		req, err := fhttp.NewRequestWithContext(ctx, "POST", url, bytes.NewReader(nil))
		if err != nil {
			return nil, err
		}
		req.Header.Set("User-Agent", profile.UserAgent)
		req.Header.Set("Accept", "*/*")
		req.Header.Set("Accept-Encoding", "gzip, deflate, br, zstd")
		req.Header.Set("Accept-Language", "en-GB,en;q=0.9")

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

		var resp map[string]interface{}
		if err := json.Unmarshal(body, &resp); err != nil {
			return nil, fmt.Errorf("unmarshal: %w, body: %s", err, truncateVKCallsLog(string(body), 200))
		}
		return resp, nil
	}

	step1URL := fmt.Sprintf(
		"https://%s/method/auth.getAnonymToken?v=%s&client_id=%s&link=%s&device_id=%s&anonymName=%s&lang=en",
		vkCallsAPIHost, vkCallsAnonAPIVersion, vkConnectClientID,
		linkURL, deviceID, nameEnc,
	)
	resp1, err := doRequest(step1URL)
	if err != nil {
		return "", "", nil, fmt.Errorf("vkcalls step1 auth.getAnonymToken: %w", err)
	}
	anonymToken, err := extractVKCallsStr(resp1, "response", "token")
	if err != nil {
		return "", "", nil, fmt.Errorf("vkcalls step1 parse: %w (resp: %s)", err, truncateVKCallsResp(resp1))
	}
	anonymTokenEnc := neturl.QueryEscape(anonymToken)
	log.Printf("[STREAM %d] [VKCalls] step1 OK, anonymous_token (%d chars)", streamID, len(anonymToken))

	step2URL := fmt.Sprintf(
		"https://%s/method/messages.getCallPreview?v=%s&anonymous_token=%s&device_id=%s&extended=1&fields=first_name,last_name,photo_200&lang=en&link=%s",
		vkCallsAPIHost, vkCallsAnonAPIVersion, anonymTokenEnc, deviceID, linkURL,
	)
	resp2, err := doRequest(step2URL)
	if err != nil {
		return "", "", nil, fmt.Errorf("vkcalls step2 messages.getCallPreview: %w", err)
	}
	if apiErr := vkCallsAPIError(resp2); apiErr != nil {
		if captchaErr, ok := apiErr.(*VkCaptchaError); ok {
			log.Printf("[STREAM %d] [VKCalls] step2 captcha gate appeared (sid=%q, redirect_uri=%t)", streamID, captchaErr.CaptchaSid, captchaErr.RedirectURI != "")
		}
		return "", "", nil, fmt.Errorf("vkcalls step2 API error: %w", apiErr)
	}
	userIDFloat, err := extractVKCallsFloat(resp2, "response", "user_id")
	if err != nil {
		return "", "", nil, fmt.Errorf("vkcalls step2 parse user_id: %w (resp: %s)", err, truncateVKCallsResp(resp2))
	}
	userIDStr := fmt.Sprintf("%.0f", userIDFloat)
	secret, err := extractVKCallsStr(resp2, "response", "secret")
	if err != nil {
		return "", "", nil, fmt.Errorf("vkcalls step2 parse secret: %w", err)
	}
	log.Printf("[STREAM %d] [VKCalls] step2 OK, user_id=%s, secret (%d chars)", streamID, userIDStr, len(secret))

	step3URL := fmt.Sprintf(
		"https://%s/method/messages.getAnonymCallToken?v=%s&anonymous_token=%s&device_id=%s&link=%s&name=%s&user_id=%s&secret=%s&lang=en",
		vkCallsAPIHost, vkCallsAnonAPIVersion, anonymTokenEnc, deviceID, linkURL,
		nameEnc, userIDStr, neturl.QueryEscape(secret),
	)
	resp3, err := doRequest(step3URL)
	if err != nil {
		return "", "", nil, fmt.Errorf("vkcalls step3 messages.getAnonymCallToken: %w", err)
	}
	if apiErr := vkCallsAPIError(resp3); apiErr != nil {
		if captchaErr, ok := apiErr.(*VkCaptchaError); ok {
			log.Printf("[STREAM %d] [VKCalls] step3 captcha gate appeared (sid=%q, redirect_uri=%t)", streamID, captchaErr.CaptchaSid, captchaErr.RedirectURI != "")
		}
		return "", "", nil, fmt.Errorf("vkcalls step3 API error: %w", apiErr)
	}
	okAnonymToken, err := extractVKCallsStr(resp3, "response", "token")
	if err != nil {
		return "", "", nil, fmt.Errorf("vkcalls step3 parse: %w (resp: %s)", err, truncateVKCallsResp(resp3))
	}
	log.Printf("[STREAM %d] [VKCalls] step3 OK, OK anonymToken (%d chars)", streamID, len(okAnonymToken))

	okDeviceID := uuid.New().String()
	step4URL := "https://calls.okcdn.ru/fb.do?session_data=" +
		neturl.QueryEscape(fmt.Sprintf(
			`{"version":2,"device_id":"%s","client_version":"1.0.1"}`, okDeviceID,
		)) +
		"&method=auth.anonymLogin&format=JSON&application_key=CGMMEJLGDIHBABABA"
	resp4, err := doRequest(step4URL)
	if err != nil {
		return "", "", nil, fmt.Errorf("vkcalls step4 auth.anonymLogin: %w", err)
	}
	sessionKey, err := extractVKCallsStr(resp4, "session_key")
	if err != nil {
		return "", "", nil, fmt.Errorf("vkcalls step4 parse: %w (resp: %s)", err, truncateVKCallsResp(resp4))
	}
	log.Printf("[STREAM %d] [VKCalls] step4 OK, OK session_key (%d chars)", streamID, len(sessionKey))

	step5URL := fmt.Sprintf(
		"https://calls.okcdn.ru/fb.do?joinLink=%s&isVideo=false&protocolVersion=5&anonymToken=%s&method=vchat.joinConversationByLink&format=JSON&application_key=CGMMEJLGDIHBABABA&session_key=%s",
		link, okAnonymToken, sessionKey,
	)
	resp5, err := doRequest(step5URL)
	if err != nil {
		return "", "", nil, fmt.Errorf("vkcalls step5 vchat.joinConversationByLink: %w", err)
	}
	if errText := vkCallsOKError(resp5); errText != "" {
		return "", "", nil, fmt.Errorf("vkcalls step5 OK error: %s (resp: %s)", errText, truncateVKCallsResp(resp5))
	}

	user, err := extractVKCallsStr(resp5, "turn_server", "username")
	if err != nil {
		return "", "", nil, fmt.Errorf("vkcalls step5 parse username: %w (resp: %s)", err, truncateVKCallsResp(resp5))
	}
	pass, err := extractVKCallsStr(resp5, "turn_server", "credential")
	if err != nil {
		return "", "", nil, fmt.Errorf("vkcalls step5 parse credential: %w", err)
	}
	addrs := parseVKCallsTURNAddresses(resp5)
	if len(addrs) == 0 {
		return "", "", nil, fmt.Errorf("vkcalls step5: turn_server.urls empty")
	}

	log.Printf("[STREAM %d] [VKCalls] SUCCESS, TURN urls=%d", streamID, len(addrs))
	return user, pass, addrs, nil
}

func extractVKCallsStr(resp map[string]interface{}, keys ...string) (string, error) {
	var cur interface{} = resp
	for _, k := range keys {
		m, ok := cur.(map[string]interface{})
		if !ok {
			return "", fmt.Errorf("expected map at key %q, got %T", k, cur)
		}
		cur = m[k]
	}
	s, ok := cur.(string)
	if !ok {
		return "", fmt.Errorf("expected string at end of path, got %T", cur)
	}
	return s, nil
}

func extractVKCallsFloat(resp map[string]interface{}, keys ...string) (float64, error) {
	var cur interface{} = resp
	for _, k := range keys {
		m, ok := cur.(map[string]interface{})
		if !ok {
			return 0, fmt.Errorf("expected map at key %q, got %T", k, cur)
		}
		cur = m[k]
	}
	f, ok := cur.(float64)
	if !ok {
		return 0, fmt.Errorf("expected float64 at end of path, got %T", cur)
	}
	return f, nil
}

func parseVKCallsTURNAddresses(resp map[string]interface{}) []string {
	turnServer, ok := resp["turn_server"].(map[string]interface{})
	if !ok {
		return nil
	}
	urls, ok := turnServer["urls"].([]interface{})
	if !ok {
		return nil
	}
	var addrs []string
	for i, u := range urls {
		s, ok := u.(string)
		if !ok {
			log.Printf("[VKCalls] turn_server.urls[%d]=<non-string %T>, skipping", i, u)
			continue
		}
		clean := strings.Split(s, "?")[0]
		addr := strings.TrimPrefix(strings.TrimPrefix(clean, "turn:"), "turns:")
		log.Printf("[VKCalls] turn_server.urls[%d]=%s", i, addr)
		addrs = append(addrs, addr)
	}
	return addrs
}

func vkCallsAPIError(resp map[string]interface{}) error {
	errObj, ok := resp["error"].(map[string]interface{})
	if !ok {
		return nil
	}
	code, _ := errObj["error_code"].(float64)
	msg, _ := errObj["error_msg"].(string)
	if code == 0 && msg == "" {
		return nil
	}
	if int(code) == 14 {
		if errJSON, err := json.Marshal(errObj); err == nil {
			log.Printf("[VKCalls] captcha error response: %s", truncateVKCallsLog(string(errJSON), 300))
		}
		return parseVkCaptchaError(errObj)
	}
	return fmt.Errorf("error_code=%.0f %s", code, msg)
}

func vkCallsOKError(resp map[string]interface{}) string {
	code, ok := resp["error_code"].(float64)
	if !ok || code == 0 {
		return ""
	}
	msg, _ := resp["error_msg"].(string)
	return fmt.Sprintf("error_code=%.0f %s", code, msg)
}

func truncateVKCallsLog(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n] + "..."
}

func truncateVKCallsResp(resp map[string]interface{}) string {
	b, err := json.Marshal(resp)
	if err != nil {
		return fmt.Sprintf("(unmarshallable: %v)", err)
	}
	return truncateVKCallsLog(string(b), 300)
}
