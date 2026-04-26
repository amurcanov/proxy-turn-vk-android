package main

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"image"
	"image/color"
	_ "image/jpeg"
	"io"
	"log"
	"math/rand"
	neturl "net/url"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"

	fhttp "github.com/bogdanfinn/fhttp"
	tlsclient "github.com/bogdanfinn/tls-client"
)

const (
	captchaDebugInfo      = "1d3e9babfd3a74f4588bf90cf5c30d3e8e89a0e2a4544da8de8bbf4d78a32f5c"
	sliderCaptchaType     = "slider"
	defaultSliderAttempts = 4
)

type captchaNotRobotSession struct {
	ctx          context.Context
	sessionToken string
	hash         string
	streamID     int
	client       tlsclient.HttpClient
	profile      BotProfile
	browserFp    string
	captchaRng   *rand.Rand
	timing       CaptchaSessionTiming
}

type captchaSettingsResponse struct {
	ShowCaptchaType string
	SettingsByType  map[string]string
}

type captchaCheckResult struct {
	Status          string
	SuccessToken    string
	ShowCaptchaType string
}

type sliderCaptchaContent struct {
	Image    image.Image
	Size     int
	Steps    []int
	Attempts int
}

type sliderCandidate struct {
	Index       int
	ActiveSteps []int
	Score       int64
}

type captchaBootstrap struct {
	PowInput   string
	Difficulty int
	Settings   *captchaSettingsResponse
}

func newCaptchaNotRobotSession(
	ctx context.Context,
	sessionToken string,
	hash string,
	streamID int,
	client tlsclient.HttpClient,
	profile BotProfile,
) *captchaNotRobotSession {
	captchaRng := rand.New(rand.NewSource(time.Now().UnixNano()))
	return &captchaNotRobotSession{
		ctx:          ctx,
		sessionToken: sessionToken,
		hash:         hash,
		streamID:     streamID,
		client:       client,
		profile:      profile,
		browserFp:    profile.BrowserFP,
		captchaRng:   captchaRng,
		timing:       GenerateCaptchaTiming(captchaRng),
	}
}

func (s *captchaNotRobotSession) baseValues() neturl.Values {
	values := neturl.Values{}
	values.Set("session_token", s.sessionToken)
	values.Set("domain", "vk.com")
	values.Set("adFp", "")
	values.Set("access_token", "")
	return values
}

func (s *captchaNotRobotSession) request(method string, values neturl.Values) (map[string]interface{}, error) {
	reqURL := "https://api.vk.ru/method/" + method + "?v=5.131"

	req, err := fhttp.NewRequestWithContext(s.ctx, "POST", reqURL, strings.NewReader(values.Encode()))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	applyCaptchaApiHeaders(req.Header, s.profile.UserAgent)

	httpResp, err := s.client.Do(req)
	if err != nil {
		return nil, err
	}
	defer func() {
		_ = httpResp.Body.Close()
	}()

	body, err := io.ReadAll(httpResp.Body)
	if err != nil {
		return nil, err
	}
	if httpResp.StatusCode >= 400 {
		log.Printf("[КАПЧА] RJS: HTTP %d на %s, body=%s", httpResp.StatusCode, method, truncateCaptchaLog(string(body), 800))
	}

	var resp map[string]interface{}
	if err := json.Unmarshal(body, &resp); err != nil {
		return nil, err
	}
	return resp, nil
}

func (s *captchaNotRobotSession) requestSettings() (*captchaSettingsResponse, error) {
	resp, err := s.request("captchaNotRobot.settings", s.baseValues())
	if err != nil {
		return nil, fmt.Errorf("settings failed: %w", err)
	}
	return parseCaptchaSettingsResponse(resp)
}

func (s *captchaNotRobotSession) requestComponentDone() error {
	values := s.baseValues()
	values.Set("browser_fp", s.browserFp)
	values.Set("device", buildCaptchaDeviceJSON(s.profile))

	resp, err := s.request("captchaNotRobot.componentDone", values)
	if err != nil {
		return fmt.Errorf("componentDone failed: %w", err)
	}

	respObj, ok := resp["response"].(map[string]interface{})
	if ok {
		if status, _ := respObj["status"].(string); status != "" && status != "OK" {
			return fmt.Errorf("componentDone status: %s", status)
		}
	}

	return nil
}

func (s *captchaNotRobotSession) requestCheckboxCheck() (*captchaCheckResult, error) {
	cursor := nonEmptyCaptchaValue(s.profile.CursorJSON, GenerateCaptchaCursor(s.captchaRng))
	return s.requestCheck(cursor, base64.StdEncoding.EncodeToString([]byte("{}")))
}

func (s *captchaNotRobotSession) requestSliderContent(sliderSettings string) (*sliderCaptchaContent, error) {
	values := s.baseValues()
	if sliderSettings != "" {
		values.Set("captcha_settings", sliderSettings)
	}

	resp, err := s.request("captchaNotRobot.getContent", values)
	if err != nil {
		return nil, fmt.Errorf("getContent failed: %w", err)
	}
	if status := extractCaptchaResponseStatus(resp); status != "" && status != "OK" {
		log.Printf("[КАПЧА] RJS: getContent raw response (status=%s): %s", status, summarizeCaptchaResponse(resp))
	}
	return parseSliderCaptchaContentResponse(resp)
}

func (s *captchaNotRobotSession) requestSliderCheck(activeSteps []int, candidateIndex int, candidateCount int) (*captchaCheckResult, error) {
	answer, err := encodeSliderAnswer(activeSteps)
	if err != nil {
		return nil, err
	}

	return s.requestCheck(generateSliderCursor(candidateIndex, candidateCount), answer)
}

func (s *captchaNotRobotSession) requestCheck(cursor string, answer string) (*captchaCheckResult, error) {
	values := s.baseValues()
	values.Set("accelerometer", nonEmptyCaptchaValue(s.profile.Accelerometer, GenerateCaptchaAccelerometer()))
	values.Set("gyroscope", nonEmptyCaptchaValue(s.profile.Gyroscope, GenerateCaptchaGyroscope()))
	values.Set("motion", nonEmptyCaptchaValue(s.profile.Motion, GenerateCaptchaMotion()))
	values.Set("cursor", cursor)
	values.Set("taps", nonEmptyCaptchaValue(s.profile.Taps, GenerateCaptchaTaps()))
	values.Set("connectionRtt", GenerateCaptchaConnectionRtt(s.captchaRng))
	values.Set("connectionDownlink", nonEmptyCaptchaValue(s.profile.Downlink, GenerateCaptchaDownlink(s.captchaRng)))
	values.Set("browser_fp", s.browserFp)
	values.Set("hash", s.hash)
	values.Set("answer", answer)
	debugInfo := s.profile.DebugInfo
	if debugInfo == "" {
		debugInfo = captchaDebugInfo
	}
	values.Set("debug_info", debugInfo)

	resp, err := s.request("captchaNotRobot.check", values)
	if err != nil {
		return nil, fmt.Errorf("check failed: %w", err)
	}
	result, err := parseCaptchaCheckResult(resp)
	if err != nil {
		return nil, err
	}
	if result.Status == "BOT" || result.Status == "ERROR" || result.Status == "ERROR_LIMIT" {
		log.Printf("[КАПЧА] RJS: check raw response (status=%s): %s", result.Status, summarizeCaptchaResponse(resp))
	}
	return result, nil
}

func (s *captchaNotRobotSession) requestEndSession() {
	log.Printf("[STREAM %d] [Captcha] Step 4/4: endSession", s.streamID)
	if _, err := s.request("captchaNotRobot.endSession", s.baseValues()); err != nil {
		log.Printf("[STREAM %d] [Captcha] Warning: endSession failed: %v", s.streamID, err)
	}
}

func callCaptchaNotRobotWithSliderPOC(
	ctx context.Context,
	sessionToken string,
	hash string,
	streamID int,
	client tlsclient.HttpClient,
	profile BotProfile,
	initialSettings *captchaSettingsResponse,
) (string, error) {
	session := newCaptchaNotRobotSession(ctx, sessionToken, hash, streamID, client, profile)

	log.Printf("[КАПЧА RJS] Инициализация сессии и получение параметров (Bootstrap)...")
	settingsResp, err := session.requestSettings()
	if err != nil {
		return "", err
	}
	settingsResp = mergeCaptchaSettings(settingsResp, initialSettings)

	time.Sleep(time.Duration(session.timing.SettingsToComponentMs) * time.Millisecond)

	if err := session.requestComponentDone(); err != nil {
		return "", err
	}

	time.Sleep(time.Duration(session.timing.ComponentToCheckMs) * time.Millisecond)
	if session.timing.ExtraPauseMs > 0 {
		time.Sleep(time.Duration(session.timing.ExtraPauseMs) * time.Millisecond)
	}

	initialCheck, err := session.requestCheckboxCheck()
	if err != nil {
		return "", err
	}
	if initialCheck.Status == "OK" {
		if initialCheck.SuccessToken == "" {
			return "", fmt.Errorf("success_token not found")
		}
		time.Sleep(time.Duration(session.timing.CheckToEndMs) * time.Millisecond)
		session.requestEndSession()
		time.Sleep(time.Duration(session.timing.EndSessionMs) * time.Millisecond)
		return initialCheck.SuccessToken, nil
	}

	sliderSettings, hasSlider := settingsResp.SettingsByType[sliderCaptchaType]
	if !hasSlider {
		availableTypes := describeCaptchaTypes(settingsResp.SettingsByType)
		if settingsResp.ShowCaptchaType != sliderCaptchaType {
			if availableTypes == "" {
				availableTypes = "unknown"
			}
			return "", fmt.Errorf("check status: %s (slider settings not found, captcha types: %s)", initialCheck.Status, availableTypes)
		}
		log.Printf("[КАПЧА RJS] Слайдер заявлен без settings, пробую getContent без captcha_settings...")
	}

	log.Printf("[КАПЧА RJS] Checkbox требует слайдер, запрашиваю фрагменты пазла...")
	sliderContent, err := session.requestSliderContent(sliderSettings)
	if err != nil {
		return "", fmt.Errorf("check status: %s (slider getContent failed: %w)", initialCheck.Status, err)
	}

	candidates, err := rankSliderCandidates(sliderContent.Image, sliderContent.Size, sliderContent.Steps)
	if err != nil {
		return "", err
	}

	log.Printf("[КАПЧА RJS] Интеллектуальный поиск совпадений через PixelDiff (кандидатов: %d)...", len(candidates))
	log.Printf("[КАПЧА RJS] Отправка решения и проверка результата...")
	successToken, err := trySliderCaptchaCandidates(candidates, sliderContent.Attempts, func(candidate sliderCandidate) (*captchaCheckResult, error) {
		// Hidden internal guess logs
		return session.requestSliderCheck(candidate.ActiveSteps, candidate.Index, len(candidates))
	})
	if err != nil {
		return "", err
	}

	time.Sleep(time.Duration(session.timing.CheckToEndMs) * time.Millisecond)
	session.requestEndSession()
	time.Sleep(time.Duration(session.timing.EndSessionMs) * time.Millisecond)
	return successToken, nil
}

func nonEmptyCaptchaValue(value string, fallback string) string {
	if strings.TrimSpace(value) == "" {
		return fallback
	}
	return value
}

func buildCaptchaDeviceJSON(profile BotProfile) string {
	if strings.TrimSpace(profile.DeviceJSON) != "" {
		return profile.DeviceJSON
	}
	return fmt.Sprintf(
		`{"screenWidth":1920,"screenHeight":1080,"screenAvailWidth":1920,"screenAvailHeight":1040,"innerWidth":1920,"innerHeight":969,"devicePixelRatio":1,"language":"en-US","languages":["en-US"],"webdriver":false,"hardwareConcurrency":8,"deviceMemory":8,"connectionEffectiveType":"4g","notificationsPermission":"default","userAgent":"%s","platform":"Win32"}`,
		profile.UserAgent,
	)
}

func normalizeCaptchaUserAgent(userAgent string) string {
	trimmed := strings.TrimSpace(userAgent)
	if trimmed == "" {
		return "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
	}

	chromeVersionRe := regexp.MustCompile(`Chrome/\d+(\.\d+)*`)
	if chromeVersionRe.MatchString(trimmed) {
		return chromeVersionRe.ReplaceAllString(trimmed, "Chrome/120.0.0.0")
	}
	return trimmed
}

func applyCaptchaApiHeaders(headers interface{ Set(string, string) }, userAgent string) {
	secChUa, secChPlatform, secChMobile := buildCaptchaClientHints(userAgent)
	headers.Set("User-Agent", userAgent)
	headers.Set("Accept", "*/*")
	headers.Set("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
	headers.Set("Origin", "https://id.vk.ru")
	headers.Set("Referer", "https://id.vk.ru/")
	headers.Set("sec-ch-ua-platform", fmt.Sprintf(`"%s"`, secChPlatform))
	headers.Set("sec-ch-ua", secChUa)
	headers.Set("sec-ch-ua-mobile", secChMobile)
	headers.Set("Sec-Fetch-Site", "same-site")
	headers.Set("Sec-Fetch-Mode", "cors")
	headers.Set("Sec-Fetch-Dest", "empty")
	headers.Set("DNT", "1")
	headers.Set("Priority", "u=1, i")
	headers.Set("Cache-Control", "no-cache")
	headers.Set("Pragma", "no-cache")
}

func applyCaptchaDocumentHeaders(headers interface{ Set(string, string) }, userAgent string) {
	secChUa, secChPlatform, secChMobile := buildCaptchaClientHints(userAgent)
	headers.Set("User-Agent", userAgent)
	headers.Set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
	headers.Set("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
	headers.Set("sec-ch-ua-platform", fmt.Sprintf(`"%s"`, secChPlatform))
	headers.Set("sec-ch-ua", secChUa)
	headers.Set("sec-ch-ua-mobile", secChMobile)
	headers.Set("Sec-Fetch-Site", "none")
	headers.Set("Sec-Fetch-Mode", "navigate")
	headers.Set("Sec-Fetch-Dest", "document")
	headers.Set("DNT", "1")
	headers.Set("Cache-Control", "no-cache")
	headers.Set("Pragma", "no-cache")
}

func buildCaptchaClientHints(userAgent string) (secChUa string, platform string, mobile string) {
	ua := normalizeCaptchaUserAgent(userAgent)
	major := "120"
	if match := regexp.MustCompile(`Chrome/(\d+)`).FindStringSubmatch(ua); len(match) >= 2 {
		major = match[1]
	}

	lowerUA := strings.ToLower(ua)
	platform = "Windows"
	mobile = "?0"
	brand := "Google Chrome"

	switch {
	case strings.Contains(lowerUA, "android"):
		platform = "Android"
		mobile = "?1"
	case strings.Contains(lowerUA, "iphone") || strings.Contains(lowerUA, "ipad"):
		platform = "iOS"
		mobile = "?1"
	case strings.Contains(lowerUA, "linux"):
		platform = "Linux"
	}

	if strings.Contains(lowerUA, "wv)") || strings.Contains(lowerUA, "android webview") {
		brand = "Android WebView"
	}

	secChUa = fmt.Sprintf(`"Chromium";v="%s", "Not-A.Brand";v="24", "%s";v="%s"`, major, brand, major)
	return secChUa, platform, mobile
}

func extractCaptchaResponseStatus(resp map[string]interface{}) string {
	respObj, ok := resp["response"].(map[string]interface{})
	if !ok {
		return ""
	}
	status, _ := respObj["status"].(string)
	return status
}

func summarizeCaptchaResponse(resp map[string]interface{}) string {
	data, err := json.Marshal(resp)
	if err != nil {
		return fmt.Sprintf("%v", resp)
	}
	return truncateCaptchaLog(string(data), 1200)
}

func truncateCaptchaLog(text string, maxLen int) string {
	if len(text) <= maxLen {
		return text
	}
	return text[:maxLen] + "...(truncated)"
}

func parseCaptchaSettingsResponse(resp map[string]interface{}) (*captchaSettingsResponse, error) {
	respObj, ok := resp["response"].(map[string]interface{})
	if !ok {
		return nil, fmt.Errorf("invalid settings response: %v", resp)
	}

	settings := &captchaSettingsResponse{
		SettingsByType: make(map[string]string),
	}
	settings.ShowCaptchaType, _ = respObj["show_captcha_type"].(string)

	rawSettings, ok := expandCaptchaSettings(respObj["captcha_settings"])
	if !ok {
		return settings, nil
	}

	for _, rawItem := range rawSettings {
		item, ok := rawItem.(map[string]interface{})
		if !ok {
			continue
		}

		captchaType, _ := item["type"].(string)
		if captchaType == "" {
			continue
		}

		normalized, err := normalizeCaptchaSettings(item["settings"])
		if err != nil {
			return nil, fmt.Errorf("invalid captcha_settings for %s: %w", captchaType, err)
		}

		settings.SettingsByType[captchaType] = normalized
	}

	return settings, nil
}

func parseCaptchaBootstrapHTML(html string) (*captchaBootstrap, error) {
	powInputRe := regexp.MustCompile(`const\s+powInput\s*=\s*"([^"]+)"`)
	powInputMatch := powInputRe.FindStringSubmatch(html)
	if len(powInputMatch) < 2 {
		return nil, fmt.Errorf("powInput not found in captcha HTML")
	}

	difficulty := 2
	for _, expr := range []*regexp.Regexp{
		regexp.MustCompile(`startsWith\('0'\.repeat\((\d+)\)\)`),
		regexp.MustCompile(`const\s+difficulty\s*=\s*(\d+)`),
	} {
		if match := expr.FindStringSubmatch(html); len(match) >= 2 {
			if parsed, err := strconv.Atoi(match[1]); err == nil {
				difficulty = parsed
				break
			}
		}
	}

	settings, err := parseCaptchaSettingsFromHTML(html)
	if err != nil {
		return nil, err
	}

	return &captchaBootstrap{
		PowInput:   powInputMatch[1],
		Difficulty: difficulty,
		Settings:   settings,
	}, nil
}

func parseCaptchaSettingsFromHTML(html string) (*captchaSettingsResponse, error) {
	initRe := regexp.MustCompile(`(?s)window\.init\s*=\s*(\{.*?})\s*;\s*window\.lang`)
	initMatch := initRe.FindStringSubmatch(html)
	if len(initMatch) < 2 {
		return &captchaSettingsResponse{SettingsByType: make(map[string]string)}, nil
	}

	var initPayload struct {
		Data struct {
			ShowCaptchaType string      `json:"show_captcha_type"`
			CaptchaSettings interface{} `json:"captcha_settings"`
		} `json:"data"`
	}
	if err := json.Unmarshal([]byte(initMatch[1]), &initPayload); err != nil {
		return nil, fmt.Errorf("parse window.init captcha data: %w", err)
	}

	return parseCaptchaSettingsResponse(map[string]interface{}{
		"response": map[string]interface{}{
			"show_captcha_type": initPayload.Data.ShowCaptchaType,
			"captcha_settings":  initPayload.Data.CaptchaSettings,
		},
	})
}

func mergeCaptchaSettings(primary *captchaSettingsResponse, fallback *captchaSettingsResponse) *captchaSettingsResponse {
	if primary == nil {
		return cloneCaptchaSettings(fallback)
	}
	if primary.SettingsByType == nil {
		primary.SettingsByType = make(map[string]string)
	}
	if fallback == nil {
		return primary
	}
	if primary.ShowCaptchaType == "" {
		primary.ShowCaptchaType = fallback.ShowCaptchaType
	}
	for captchaType, settings := range fallback.SettingsByType {
		if _, exists := primary.SettingsByType[captchaType]; !exists {
			primary.SettingsByType[captchaType] = settings
		}
	}
	return primary
}

func cloneCaptchaSettings(src *captchaSettingsResponse) *captchaSettingsResponse {
	if src == nil {
		return nil
	}

	cloned := &captchaSettingsResponse{
		ShowCaptchaType: src.ShowCaptchaType,
		SettingsByType:  make(map[string]string, len(src.SettingsByType)),
	}
	for captchaType, settings := range src.SettingsByType {
		cloned.SettingsByType[captchaType] = settings
	}
	return cloned
}

func expandCaptchaSettings(raw interface{}) ([]interface{}, bool) {
	switch value := raw.(type) {
	case nil:
		return nil, false
	case []interface{}:
		return value, true
	case map[string]interface{}:
		items := make([]interface{}, 0, len(value))
		for captchaType, settings := range value {
			items = append(items, map[string]interface{}{
				"type":     captchaType,
				"settings": settings,
			})
		}
		return items, true
	case string:
		trimmed := strings.TrimSpace(value)
		if trimmed == "" {
			return nil, false
		}

		var items []interface{}
		if err := json.Unmarshal([]byte(trimmed), &items); err == nil {
			return items, true
		}

		var mapping map[string]interface{}
		if err := json.Unmarshal([]byte(trimmed), &mapping); err == nil {
			return expandCaptchaSettings(mapping)
		}
	}

	return nil, false
}

func normalizeCaptchaSettings(raw interface{}) (string, error) {
	switch value := raw.(type) {
	case nil:
		return "", nil
	case string:
		return value, nil
	default:
		data, err := json.Marshal(value)
		if err != nil {
			return "", err
		}
		return string(data), nil
	}
}

func parseCaptchaCheckResult(resp map[string]interface{}) (*captchaCheckResult, error) {
	respObj, ok := resp["response"].(map[string]interface{})
	if !ok {
		return nil, fmt.Errorf("invalid check response: %v", resp)
	}

	result := &captchaCheckResult{}
	result.Status, _ = respObj["status"].(string)
	result.SuccessToken, _ = respObj["success_token"].(string)
	result.ShowCaptchaType, _ = respObj["show_captcha_type"].(string)
	if result.Status == "" {
		return nil, fmt.Errorf("check status missing: %v", resp)
	}

	return result, nil
}

func parseSliderCaptchaContentResponse(resp map[string]interface{}) (*sliderCaptchaContent, error) {
	respObj, ok := resp["response"].(map[string]interface{})
	if !ok {
		return nil, fmt.Errorf("invalid slider content response: %v", resp)
	}

	status, _ := respObj["status"].(string)
	if status != "OK" {
		return nil, fmt.Errorf("slider getContent status: %s", status)
	}

	extension, _ := respObj["extension"].(string)
	extension = strings.ToLower(extension)
	if extension != "jpeg" && extension != "jpg" {
		return nil, fmt.Errorf("unsupported slider image format: %s", extension)
	}

	rawImage, _ := respObj["image"].(string)
	if rawImage == "" {
		return nil, fmt.Errorf("slider image missing")
	}

	rawSteps, ok := respObj["steps"].([]interface{})
	if !ok {
		return nil, fmt.Errorf("slider steps missing")
	}

	steps, err := parseIntSlice(rawSteps)
	if err != nil {
		return nil, err
	}

	size, swaps, attempts, err := parseSliderSteps(steps)
	if err != nil {
		return nil, err
	}

	img, err := decodeSliderImage(rawImage)
	if err != nil {
		return nil, err
	}

	return &sliderCaptchaContent{
		Image:    img,
		Size:     size,
		Steps:    swaps,
		Attempts: attempts,
	}, nil
}

func parseIntSlice(raw []interface{}) ([]int, error) {
	values := make([]int, 0, len(raw))
	for _, item := range raw {
		number, err := parseIntValue(item)
		if err != nil {
			return nil, err
		}
		values = append(values, number)
	}
	return values, nil
}

func parseIntValue(raw interface{}) (int, error) {
	switch value := raw.(type) {
	case float64:
		return int(value), nil
	case int:
		return value, nil
	case string:
		parsed, err := strconv.Atoi(strings.TrimSpace(value))
		if err != nil {
			return 0, fmt.Errorf("invalid numeric value: %v", raw)
		}
		return parsed, nil
	default:
		return 0, fmt.Errorf("invalid numeric value: %v", raw)
	}
}

func parseSliderSteps(steps []int) (int, []int, int, error) {
	if len(steps) < 3 {
		return 0, nil, 0, fmt.Errorf("slider steps payload too short")
	}

	size := steps[0]
	if size <= 0 {
		return 0, nil, 0, fmt.Errorf("invalid slider size: %d", size)
	}

	remaining := append([]int(nil), steps[1:]...)
	attempts := defaultSliderAttempts
	if len(remaining)%2 != 0 {
		attempts = remaining[len(remaining)-1]
		remaining = remaining[:len(remaining)-1]
	}
	if attempts <= 0 {
		attempts = defaultSliderAttempts
	}
	if len(remaining) == 0 || len(remaining)%2 != 0 {
		return 0, nil, 0, fmt.Errorf("invalid slider swap payload")
	}

	return size, remaining, attempts, nil
}

func decodeSliderImage(rawImage string) (image.Image, error) {
	decoded, err := base64.StdEncoding.DecodeString(rawImage)
	if err != nil {
		return nil, fmt.Errorf("decode slider image: %w", err)
	}

	img, _, err := image.Decode(bytes.NewReader(decoded))
	if err != nil {
		return nil, fmt.Errorf("decode slider image: %w", err)
	}

	return img, nil
}

func encodeSliderAnswer(activeSteps []int) (string, error) {
	payload := struct {
		Value []int `json:"value"`
	}{
		Value: activeSteps,
	}

	data, err := json.Marshal(payload)
	if err != nil {
		return "", err
	}

	return base64.StdEncoding.EncodeToString(data), nil
}

func buildSliderActiveSteps(swaps []int, candidateIndex int) []int {
	if candidateIndex <= 0 {
		return []int{}
	}

	end := candidateIndex * 2
	if end > len(swaps) {
		end = len(swaps)
	}

	return append([]int(nil), swaps[:end]...)
}

func buildSliderTileMapping(gridSize int, activeSteps []int) ([]int, error) {
	tileCount := gridSize * gridSize
	if tileCount <= 0 {
		return nil, fmt.Errorf("invalid slider tile count: %d", tileCount)
	}
	if len(activeSteps)%2 != 0 {
		return nil, fmt.Errorf("invalid active steps length: %d", len(activeSteps))
	}

	mapping := make([]int, tileCount)
	for i := range mapping {
		mapping[i] = i
	}

	for idx := 0; idx < len(activeSteps); idx += 2 {
		left := activeSteps[idx]
		right := activeSteps[idx+1]
		if left < 0 || right < 0 || left >= tileCount || right >= tileCount {
			return nil, fmt.Errorf("slider step out of range: %d,%d", left, right)
		}
		mapping[left], mapping[right] = mapping[right], mapping[left]
	}

	return mapping, nil
}

func rankSliderCandidates(img image.Image, gridSize int, swaps []int) ([]sliderCandidate, error) {
	candidateCount := len(swaps) / 2
	if candidateCount == 0 {
		return nil, fmt.Errorf("slider has no candidates")
	}

	candidates := make([]sliderCandidate, 0, candidateCount)
	for idx := 1; idx <= candidateCount; idx++ {
		activeSteps := buildSliderActiveSteps(swaps, idx)
		mapping, err := buildSliderTileMapping(gridSize, activeSteps)
		if err != nil {
			return nil, err
		}

		score, err := scoreSliderCandidate(img, gridSize, mapping)
		if err != nil {
			return nil, err
		}

		candidates = append(candidates, sliderCandidate{
			Index:       idx,
			ActiveSteps: activeSteps,
			Score:       score,
		})
	}

	sort.SliceStable(candidates, func(i, j int) bool {
		if candidates[i].Score == candidates[j].Score {
			return candidates[i].Index < candidates[j].Index
		}
		return candidates[i].Score < candidates[j].Score
	})

	return candidates, nil
}

func scoreSliderCandidate(img image.Image, gridSize int, mapping []int) (int64, error) {
	rendered, err := renderSliderCandidate(img, gridSize, mapping)
	if err != nil {
		return 0, err
	}

	return scoreRenderedSliderImage(rendered, gridSize), nil
}

func renderSliderCandidate(img image.Image, gridSize int, mapping []int) (*image.RGBA, error) {
	if gridSize <= 0 {
		return nil, fmt.Errorf("invalid grid size: %d", gridSize)
	}

	tileCount := gridSize * gridSize
	if len(mapping) != tileCount {
		return nil, fmt.Errorf("unexpected tile mapping length: %d", len(mapping))
	}

	bounds := img.Bounds()
	rendered := image.NewRGBA(bounds)
	for dstIndex, srcIndex := range mapping {
		srcRect := sliderTileRect(bounds, gridSize, srcIndex)
		dstRect := sliderTileRect(bounds, gridSize, dstIndex)
		copyScaledTile(rendered, dstRect, img, srcRect)
	}

	return rendered, nil
}

func scoreRenderedSliderImage(img image.Image, gridSize int) int64 {
	bounds := img.Bounds()
	var score int64

	for row := 0; row < gridSize; row++ {
		for col := 0; col < gridSize-1; col++ {
			leftRect := sliderTileRect(bounds, gridSize, row*gridSize+col)
			rightRect := sliderTileRect(bounds, gridSize, row*gridSize+col+1)
			height := minInt(leftRect.Dy(), rightRect.Dy())
			for offset := 0; offset < height; offset++ {
				score += pixelDiff(
					img.At(leftRect.Max.X-1, leftRect.Min.Y+offset),
					img.At(rightRect.Min.X, rightRect.Min.Y+offset),
				)
			}
		}
	}

	for row := 0; row < gridSize-1; row++ {
		for col := 0; col < gridSize; col++ {
			topRect := sliderTileRect(bounds, gridSize, row*gridSize+col)
			bottomRect := sliderTileRect(bounds, gridSize, (row+1)*gridSize+col)
			width := minInt(topRect.Dx(), bottomRect.Dx())
			for offset := 0; offset < width; offset++ {
				score += pixelDiff(
					img.At(topRect.Min.X+offset, topRect.Max.Y-1),
					img.At(bottomRect.Min.X+offset, bottomRect.Min.Y),
				)
			}
		}
	}

	return score
}

func sliderTileRect(bounds image.Rectangle, gridSize int, index int) image.Rectangle {
	row := index / gridSize
	col := index % gridSize

	x0 := bounds.Min.X + col*bounds.Dx()/gridSize
	x1 := bounds.Min.X + (col+1)*bounds.Dx()/gridSize
	y0 := bounds.Min.Y + row*bounds.Dy()/gridSize
	y1 := bounds.Min.Y + (row+1)*bounds.Dy()/gridSize

	return image.Rect(x0, y0, x1, y1)
}

func copyScaledTile(dst *image.RGBA, dstRect image.Rectangle, src image.Image, srcRect image.Rectangle) {
	if dstRect.Empty() || srcRect.Empty() {
		return
	}

	dstWidth := dstRect.Dx()
	dstHeight := dstRect.Dy()
	srcWidth := srcRect.Dx()
	srcHeight := srcRect.Dy()

	for y := 0; y < dstHeight; y++ {
		sy := srcRect.Min.Y + y*srcHeight/dstHeight
		for x := 0; x < dstWidth; x++ {
			sx := srcRect.Min.X + x*srcWidth/dstWidth
			dst.Set(dstRect.Min.X+x, dstRect.Min.Y+y, src.At(sx, sy))
		}
	}
}

func pixelDiff(left color.Color, right color.Color) int64 {
	lr, lg, lb, _ := left.RGBA()
	rr, rg, rb, _ := right.RGBA()

	diff := absDiff(lr, rr) + absDiff(lg, rg) + absDiff(lb, rb)

	// Ограничиваем приоритет белого фона:
	luma := lr + lg + lb + rr + rg + rb

	return diff + int64(luma/200)
}

func absDiff(left uint32, right uint32) int64 {
	if left > right {
		return int64(left - right)
	}
	return int64(right - left)
}

func generateSliderCursor(candidateIndex int, candidateCount int) string {
	return buildSliderCursor(candidateIndex, candidateCount, time.Now().Add(-220*time.Millisecond).UnixMilli())
}

func buildSliderCursor(candidateIndex int, candidateCount int, startTime int64) string {
	if candidateCount <= 0 {
		return "[]"
	}

	type cursorPoint struct {
		X int   `json:"x"`
		Y int   `json:"y"`
		T int64 `json:"t"`
	}

	startX := 140
	endX := startX + 620*candidateIndex/candidateCount
	startY := 430

	points := make([]cursorPoint, 0, 12)
	for step := 0; step < 12; step++ {
		x := startX + (endX-startX)*step/11
		y := startY + ((step % 3) - 1)
		points = append(points, cursorPoint{
			X: x,
			Y: y,
			T: startTime + int64(step*18),
		})
	}

	data, err := json.Marshal(points)
	if err != nil {
		return "[]"
	}
	return string(data)
}

func trySliderCaptchaCandidates(
	candidates []sliderCandidate,
	maxAttempts int,
	check func(candidate sliderCandidate) (*captchaCheckResult, error),
) (string, error) {
	if len(candidates) == 0 {
		return "", fmt.Errorf("slider has no ranked candidates")
	}

	limit := minInt(maxAttempts, len(candidates))
	if limit <= 0 {
		return "", fmt.Errorf("slider has no attempts available")
	}

	for idx := 0; idx < limit; idx++ {
		result, err := check(candidates[idx])
		if err != nil {
			return "", err
		}

		switch result.Status {
		case "OK":
			if result.SuccessToken == "" {
				return "", fmt.Errorf("success_token not found")
			}
			return result.SuccessToken, nil
		case "ERROR_LIMIT":
			return "", fmt.Errorf("slider check status: %s", result.Status)
		default:
			continue
		}
	}

	return "", fmt.Errorf("slider guesses exhausted")
}

func minInt(left int, right int) int {
	if left < right {
		return left
	}
	return right
}

func describeCaptchaTypes(settingsByType map[string]string) string {
	if len(settingsByType) == 0 {
		return "none"
	}

	types := make([]string, 0, len(settingsByType))
	for captchaType := range settingsByType {
		types = append(types, captchaType)
	}
	sort.Strings(types)
	return strings.Join(types, ",")
}
