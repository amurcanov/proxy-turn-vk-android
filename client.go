package main

import (
	"bytes"
	"context"
	"crypto/tls"
	"encoding/binary"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"log"
	"math/rand/v2"
	"net"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/cbeuw/connutil"
	"github.com/google/uuid"
	"github.com/pion/dtls/v3"
	"github.com/pion/dtls/v3/pkg/crypto/selfsign"
	"github.com/pion/logging"
	"github.com/pion/turn/v5"
)

const (
	configFile         = "wg-turn.conf"
	workersPerGroup    = 4
	groupCycleMin      = 15
	overlapDuration    = 45 * time.Second
	workerSendBuf      = 128
	sessionReadTimeout = 60 * time.Second
	maxVKConcurrency   = 2
	returnChBuf        = 384
	readBufSize        = 1600
	socketBufSize      = 625 * 1024
)

var (
	vkAppID     atomic.Value // string
	vkAppSecret atomic.Value // string
	noDnsFlag   atomic.Bool
)

func init() {
	vkAppID.Store("8094476")
	vkAppSecret.Store("0sxydyHqvEaPJkMhnBEW")
}

type NullLoggerFactory struct{}

func (n *NullLoggerFactory) NewLogger(_ string) logging.LeveledLogger { return &NullLogger{} }

type NullLogger struct{}

func (n *NullLogger) Trace(_ string)                    {}
func (n *NullLogger) Tracef(_ string, _ ...interface{}) {}
func (n *NullLogger) Debug(_ string)                    {}
func (n *NullLogger) Debugf(_ string, _ ...interface{}) {}
func (n *NullLogger) Info(_ string)                     {}
func (n *NullLogger) Infof(_ string, _ ...interface{})  {}
func (n *NullLogger) Warn(_ string)                     {}
func (n *NullLogger) Warnf(_ string, _ ...interface{})  {}
func (n *NullLogger) Error(_ string)                    {}
func (n *NullLogger) Errorf(_ string, _ ...interface{}) {}

type stats struct {
	activeConnections atomic.Int32
	reconnects        atomic.Int64
	totalBytesUp      atomic.Int64
	totalBytesDown    atomic.Int64
	credsErrors       atomic.Int64
}

var globalStats stats

func statsLoop(ctx context.Context) {
	ticker := time.NewTicker(2 * time.Second)
	defer ticker.Stop()

	resetTicker := time.NewTicker(1 * time.Minute)
	defer resetTicker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-resetTicker.C:
			globalStats.reconnects.Store(0)
		case <-ticker.C:
			active := globalStats.activeConnections.Load()
			reconn := globalStats.reconnects.Load()
			up := globalStats.totalBytesUp.Load()
			down := globalStats.totalBytesDown.Load()
			credErrs := globalStats.credsErrors.Load()
			totalMB := float64(up+down) / (1024 * 1024)
			log.Printf("[СТАТИСТИКА] Активных: %d | Реконнектов/мин: %d | Ошибок ВК: %d | Трафик: %.2f МБ",
				active, reconn, credErrs, totalMB)
		}
	}
}

var vkSemaphore = make(chan struct{}, maxVKConcurrency)

var (
	sharedTransportOnce sync.Once
	sharedTransport     *http.Transport
)

func getSharedTransport() *http.Transport {
	sharedTransportOnce.Do(func() {
		var dialer *net.Dialer
		if noDnsFlag.Load() {
			dialer = &net.Dialer{Timeout: 10 * time.Second}
		} else {
			dialer = &net.Dialer{
				Timeout: 10 * time.Second,
				Resolver: &net.Resolver{
					PreferGo: true,
					Dial: func(ctx context.Context, network, address string) (net.Conn, error) {
						d := net.Dialer{Timeout: 5 * time.Second}
						conn, err := d.DialContext(ctx, "udp", "77.88.8.8:53")
						if err != nil {
							return d.DialContext(ctx, "udp", "77.88.8.1:53")
						}
						return conn, nil
					},
				},
			}
		}

		sharedTransport = &http.Transport{
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

func getUniqueVkCreds(ctx context.Context, hash string, maxRetries int) (user, pass string, turnAddrs []string, err error) {
	for attempt := range maxRetries {
		select {
		case <-ctx.Done():
			return "", "", nil, ctx.Err()
		case vkSemaphore <- struct{}{}:
		}

		user, pass, turnAddrs, err = getVkCredsOnce(ctx, hash)
		<-vkSemaphore

		if err == nil {
			return
		}

		globalStats.credsErrors.Add(1)

		errStr := err.Error()
		if strings.Contains(errStr, "9000") || strings.Contains(errStr, "call not found") {
			return "", "", nil, fmt.Errorf("хеш мёртв: %w", err)
		}

		var backoff time.Duration
		if strings.Contains(errStr, "flood") || strings.Contains(errStr, "Flood") {
			backoff = time.Duration(5*(attempt+1)) * time.Second
			if backoff > 60*time.Second {
				backoff = 60 * time.Second
			}
		} else {
			backoff = time.Duration(1<<min(attempt, 5)) * time.Second
			if backoff > 30*time.Second {
				backoff = 30 * time.Second
			}
			backoff += time.Duration(rand.IntN(1000)) * time.Millisecond
		}

		select {
		case <-ctx.Done():
			return "", "", nil, ctx.Err()
		case <-time.After(backoff):
		}
	}
	return "", "", nil, fmt.Errorf("исчерпаны %d попыток: %w", maxRetries, err)
}

func getVkCredsOnce(ctx context.Context, hash string) (user, pass string, turnAddrs []string, err error) {
	client := &http.Client{
		Timeout:   15 * time.Second,
		Transport: getSharedTransport(),
	}

	doReq := func(data, url string) (map[string]interface{}, error) {
		req, err := http.NewRequestWithContext(ctx, "POST", url, bytes.NewBufferString(data))
		if err != nil {
			return nil, fmt.Errorf("создание запроса: %w", err)
		}
		req.Header.Set("User-Agent", "Mozilla/5.0")
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
			return nil, fmt.Errorf("парсинг JSON: %w", err)
		}
		if errObj, ok := m["error"]; ok {
			return nil, fmt.Errorf("API error: %v", errObj)
		}
		return m, nil
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

	appID := vkAppID.Load().(string)
	appSecret := vkAppSecret.Load().(string)
	okAppKey := "CGMMEJLGDIHBABABA"

	r, err := doReq(fmt.Sprintf(
		"client_id=%s&token_type=messages&client_secret=%s&version=1&app_id=%s",
		appID, appSecret, appID,
	), "https://login.vk.ru/?act=get_anonym_token")
	if err != nil {
		return "", "", nil, fmt.Errorf("шаг 3: %w", err)
	}
	t3, err := get(r, "data", "access_token")
	if err != nil {
		return "", "", nil, fmt.Errorf("шаг 3 парсинг: %w", err)
	}

	r, err = doReq(fmt.Sprintf(
		"vk_join_link=https://vk.com/call/join/%s&name=123&access_token=%s",
		hash, t3,
	), "https://api.vk.ru/method/calls.getAnonymousToken?v=5.264")
	if err != nil {
		return "", "", nil, fmt.Errorf("шаг 4: %w", err)
	}
	t4, err := get(r, "response", "token")
	if err != nil {
		return "", "", nil, fmt.Errorf("шаг 4 парсинг: %w", err)
	}

	r, err = doReq(fmt.Sprintf(
		"session_data=%%7B%%22version%%22%%3A2%%2C%%22device_id%%22%%3A%%22%s%%22%%2C%%22client_version%%22%%3A1.1%%2C%%22client_type%%22%%3A%%22SDK_JS%%22%%7D&method=auth.anonymLogin&format=JSON&application_key=%s",
		uuid.New(), okAppKey,
	), "https://calls.okcdn.ru/fb.do")
	if err != nil {
		return "", "", nil, fmt.Errorf("шаг 5: %w", err)
	}
	t5, err := get(r, "session_key")
	if err != nil {
		return "", "", nil, fmt.Errorf("шаг 5 парсинг: %w", err)
	}

	r, err = doReq(fmt.Sprintf(
		"joinLink=%s&isVideo=false&protocolVersion=5&anonymToken=%s&method=vchat.joinConversationByLink&format=JSON&application_key=%s&session_key=%s",
		hash, t4, okAppKey, t5,
	), "https://calls.okcdn.ru/fb.do")
	if err != nil {
		return "", "", nil, fmt.Errorf("шаг 6: %w", err)
	}

	ts, ok := r["turn_server"].(map[string]interface{})
	if !ok {
		return "", "", nil, fmt.Errorf("turn_server не найден в ответе")
	}

	user, _ = ts["username"].(string)
	pass, _ = ts["credential"].(string)
	lifetime, _ := ts["lifetime"].(float64)
	if lifetime > 0 {
		log.Printf("[ВК] Креды получены, LIFE: %.0f сек", lifetime)
	} else {
		log.Printf("[ВК] Креды получены, LIFE: не указано")
	}
	if user == "" || pass == "" {
		return "", "", nil, fmt.Errorf("пустые credentials в ответе")
	}

	urls, _ := ts["urls"].([]interface{})
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
		return "", "", nil, fmt.Errorf("нет TURN urls в ответе")
	}
	return user, pass, turnAddrs, nil
}

type workerSlot struct {
	id     int
	sendCh chan []byte
}

type dispatcher struct {
	localConn  net.PacketConn
	clientAddr atomic.Pointer[net.Addr]
	mu         sync.Mutex
	workers    []*workerSlot
	rrIndex    int
	returnCh   chan []byte
	ctx        context.Context
	cancel     context.CancelFunc
	wg         sync.WaitGroup
}

func newDispatcher(ctx context.Context, localConn net.PacketConn) *dispatcher {
	dctx, dcancel := context.WithCancel(ctx)
	d := &dispatcher{
		localConn: localConn,
		returnCh:  make(chan []byte, returnChBuf),
		ctx:       dctx,
		cancel:    dcancel,
	}

	d.wg.Add(2)
	go d.readLoop()
	go d.writeLoop()

	return d
}

func (d *dispatcher) shutdown() {
	d.cancel()
	d.wg.Wait()
}

func (d *dispatcher) register(w *workerSlot) {
	d.mu.Lock()
	d.workers = append(d.workers, w)
	count := len(d.workers)
	d.mu.Unlock()
	log.Printf("[ДИСП] Воркер #%d зарегистрирован (всего: %d)", w.id, count)
}

func (d *dispatcher) unregister(slot *workerSlot) {
	d.mu.Lock()
	for i, w := range d.workers {
		if w == slot {
			d.workers = append(d.workers[:i], d.workers[i+1:]...)
			break
		}
	}
	remaining := len(d.workers)
	d.mu.Unlock()
	log.Printf("[ДИСП] Воркер #%d отключён (осталось: %d)", slot.id, remaining)
}

func (d *dispatcher) readLoop() {
	defer d.wg.Done()

	buf := make([]byte, readBufSize)
	for {
		if err := d.ctx.Err(); err != nil {
			return
		}

		n, addr, err := d.localConn.ReadFrom(buf)
		if err != nil {
			if d.ctx.Err() != nil {
				return
			}
			log.Printf("[ДИСП] Ошибка чтения: %v", err)
			time.Sleep(10 * time.Millisecond) // Защита от tight loop
			continue
		}

		d.clientAddr.Store(&addr)
		globalStats.totalBytesUp.Add(int64(n))

		pkt := make([]byte, n)
		copy(pkt, buf[:n])

		d.mu.Lock()
		nw := len(d.workers)
		if nw == 0 {
			d.mu.Unlock()
			continue
		}

		// Round-robin с fallback: пробуем всех, если никто не принял — дропаем
		sent := false
		startIdx := d.rrIndex % nw
		for i := range nw {
			idx := (startIdx + i) % nw
			w := d.workers[idx]
			select {
			case w.sendCh <- pkt:
				d.rrIndex = (idx + 1) % nw
				sent = true
			default:
			}
			if sent {
				break
			}
		}
		if !sent {
			// Все воркеры заняты — обновляем индекс и дропаем пакет
			d.rrIndex = (startIdx + 1) % nw
		}
		d.mu.Unlock()
	}
}

func (d *dispatcher) writeLoop() {
	defer d.wg.Done()

	for {
		select {
		case <-d.ctx.Done():
			return
		case pkt := <-d.returnCh:
			addrPtr := d.clientAddr.Load()
			if addrPtr == nil {
				continue
			}
			addr := *addrPtr
			if _, err := d.localConn.WriteTo(pkt, addr); err != nil {
				if d.ctx.Err() != nil {
					return
				}
			}
			globalStats.totalBytesDown.Add(int64(len(pkt)))
		}
	}
}

type connectedUDPConn struct{ *net.UDPConn }

func (c *connectedUDPConn) WriteTo(p []byte, _ net.Addr) (int, error) { return c.Write(p) }

type turnParams struct {
	host, port    string
	hashes        []string
	secondaryHash string
	sni           string
}

func requestConfig(conn net.Conn, localPort string, deviceID string, password string) (string, error) {
	payload := fmt.Sprintf("GETCONF:%s|%s|%s", localPort, deviceID, password)
	if _, err := conn.Write([]byte(payload)); err != nil {
		return "", fmt.Errorf("отправка GETCONF: %w", err)
	}

	b := make([]byte, 4096)
	if err := conn.SetReadDeadline(time.Now().Add(15 * time.Second)); err != nil {
		return "", fmt.Errorf("установка дедлайна: %w", err)
	}
	n, err := conn.Read(b)
	_ = conn.SetReadDeadline(time.Time{})
	if err != nil {
		return "", fmt.Errorf("чтение ответа конфига: %w", err)
	}

	resp := string(b[:n])
	if resp == "NOCONF" {
		return "", nil
	}

	// Обработка отказа сервера (неверный пароль, истёк, чужое устройство)
	if strings.HasPrefix(resp, "DENIED:") {
		reason := strings.TrimPrefix(resp, "DENIED:")
		switch reason {
		case "wrong_password":
			return "", fmt.Errorf("FATAL_AUTH: неверный пароль подключения")
		case "expired":
			return "", fmt.Errorf("FATAL_AUTH: срок действия пароля истёк")
		case "device_mismatch":
			return "", fmt.Errorf("FATAL_AUTH: пароль привязан к другому устройству")
		default:
			return "", fmt.Errorf("FATAL_AUTH: доступ запрещён (%s)", reason)
		}
	}

	if _, err := conn.Write([]byte("ACK")); err != nil {
		return "", fmt.Errorf("отправка ACK: %w", err)
	}
	return resp, nil
}

func sendReady(conn net.Conn) error {
	if _, err := conn.Write([]byte("READY")); err != nil {
		return fmt.Errorf("отправка READY: %w", err)
	}

	b := make([]byte, 64)
	if err := conn.SetReadDeadline(time.Now().Add(15 * time.Second)); err != nil {
		return fmt.Errorf("установка дедлайна: %w", err)
	}
	n, err := conn.Read(b)
	_ = conn.SetReadDeadline(time.Time{})
	if err != nil {
		return fmt.Errorf("чтение READY_OK: %w", err)
	}
	if string(b[:n]) != "READY_OK" {
		return fmt.Errorf("ожидался READY_OK, получено: %q", string(b[:n]))
	}
	return nil
}

type credentials struct {
	User     string
	Pass     string
	TurnURLs []string
}

func runSession(ctx context.Context, tp *turnParams, peer *net.UDPAddr,
	d *dispatcher, localPort string, useUDP bool, getConfig bool,
	configChan chan<- string, sessionID int, creds credentials,
	deviceID string, password string) error {

	if len(creds.TurnURLs) == 0 {
		return fmt.Errorf("нет TURN URL в учетных данных")
	}
	selectedURL := creds.TurnURLs[sessionID%len(creds.TurnURLs)]

	urlhost, urlport, err := net.SplitHostPort(selectedURL)
	if err != nil {
		return fmt.Errorf("разбор TURN URL %q: %w", selectedURL, err)
	}
	if tp.host != "" {
		urlhost = tp.host
	}
	if tp.port != "" {
		urlport = tp.port
	}
	turnAddr := net.JoinHostPort(urlhost, urlport)

	var turnConn net.PacketConn
	proto := "TCP"

	if useUDP {
		proto = "UDP"
		resolved, err := net.ResolveUDPAddr("udp", turnAddr)
		if err != nil {
			return fmt.Errorf("резолв TURN: %w", err)
		}
		c, err := net.DialUDP("udp", nil, resolved)
		if err != nil {
			return fmt.Errorf("подключение TURN UDP: %w", err)
		}
		defer c.Close()
		_ = c.SetReadBuffer(socketBufSize)
		_ = c.SetWriteBuffer(socketBufSize)
		turnConn = &connectedUDPConn{c}
	} else {
		c, err := net.DialTimeout("tcp", turnAddr, 10*time.Second)
		if err != nil {
			return fmt.Errorf("подключение TURN TCP: %w", err)
		}
		defer c.Close()
		if tc, ok := c.(*net.TCPConn); ok {
			_ = tc.SetNoDelay(true)
			_ = tc.SetReadBuffer(socketBufSize)
			_ = tc.SetWriteBuffer(socketBufSize)
		}
		turnConn = turn.NewSTUNConn(c)
	}
	log.Printf("[СЕССИЯ #%d] TURN %s (%s)", sessionID, turnAddr, proto)

	tc, err := turn.NewClient(&turn.ClientConfig{
		STUNServerAddr: turnAddr,
		TURNServerAddr: turnAddr,
		Conn:           turnConn,
		Username:       creds.User,
		Password:       creds.Pass,
		LoggerFactory:  &NullLoggerFactory{},
	})
	if err != nil {
		return fmt.Errorf("TURN клиент: %w", err)
	}
	defer tc.Close()

	if err = tc.Listen(); err != nil {
		return fmt.Errorf("TURN Listen: %w", err)
	}

	relay, err := tc.Allocate()
	if err != nil {
		errStr := err.Error()
		if strings.Contains(errStr, "Quota") || strings.Contains(errStr, "486") {
			return fmt.Errorf("TURN Allocate квота: %w", err)
		}
		return fmt.Errorf("TURN Allocate: %w", err)
	}
	defer relay.Close()

	log.Printf("[СЕССИЯ #%d] Relay: %s", sessionID, relay.LocalAddr())

	pipeA, pipeB := connutil.AsyncPacketPipe()

	sessCtx, sessCancel := context.WithCancel(ctx)
	defer sessCancel()

	// Keepalive goroutine
	var sessionWg sync.WaitGroup
	sessionWg.Add(1)
	go func() {
		defer sessionWg.Done()
		t := time.NewTicker(10 * time.Second)
		defer t.Stop()
		for {
			select {
			case <-sessCtx.Done():
				return
			case <-t.C:
				tc.SendBindingRequest()
			}
		}
	}()

	// Relay <-> Pipe proxy
	var relayWg sync.WaitGroup
	relayWg.Add(2)

	// При отмене контекста — снимаем блокировки
	stopRelay := context.AfterFunc(sessCtx, func() {
		_ = relay.SetDeadline(time.Now())
		_ = pipeA.SetDeadline(time.Now())
	})
	defer stopRelay()

	go func() {
		defer relayWg.Done()
		defer sessCancel()
		b := make([]byte, readBufSize)
		for {
			n, _, readErr := relay.ReadFrom(b)
			if readErr != nil {
				return
			}
			if _, writeErr := pipeA.WriteTo(b[:n], peer); writeErr != nil {
				return
			}
		}
	}()

	go func() {
		defer relayWg.Done()
		defer sessCancel()
		b := make([]byte, readBufSize)
		for {
			n, _, readErr := pipeA.ReadFrom(b)
			if readErr != nil {
				return
			}
			if _, writeErr := relay.WriteTo(b[:n], peer); writeErr != nil {
				return
			}
		}
	}()

	// DTLS
	cert, err := selfsign.GenerateSelfSigned()
	if err != nil {
		return fmt.Errorf("генерация сертификата: %w", err)
	}

	dtlsCfg := &dtls.Config{
		Certificates:          []tls.Certificate{cert},
		InsecureSkipVerify:    true,
		ExtendedMasterSecret:  dtls.RequireExtendedMasterSecret,
		CipherSuites:          []dtls.CipherSuiteID{dtls.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256},
		ConnectionIDGenerator: dtls.OnlySendCIDGenerator(),
		ServerName:            tp.sni,
	}

	dtlsConn, err := dtls.Client(pipeB, peer, dtlsCfg)
	if err != nil {
		return fmt.Errorf("DTLS клиент: %w", err)
	}
	defer dtlsConn.Close()

	hctx, hcancel := context.WithTimeout(sessCtx, 45*time.Second)
	err = dtlsConn.HandshakeContext(hctx)
	hcancel()
	if err != nil {
		return fmt.Errorf("DTLS хендшейк: %w", err)
	}
	log.Printf("[СЕССИЯ #%d] DTLS ОК ✓", sessionID)

	globalStats.activeConnections.Add(1)
	defer globalStats.activeConnections.Add(-1)

	// Получаем конфиг если нужно
	if getConfig && configChan != nil {
		conf, confErr := requestConfig(dtlsConn, localPort, deviceID, password)
		if confErr != nil {
			log.Printf("[СЕССИЯ #%d] Ошибка конфига: %v", sessionID, confErr)
		} else if conf != "" {
			select {
			case configChan <- conf:
				log.Printf("[СЕССИЯ #%d] Конфиг получен", sessionID)
			default:
			}
		}
	}

	if err := sendReady(dtlsConn); err != nil {
		return fmt.Errorf("READY: %w", err)
	}
	log.Printf("[СЕССИЯ #%d] Активна ✓", sessionID)

	// Регистрируем в диспетчере
	slot := &workerSlot{
		id:     sessionID,
		sendCh: make(chan []byte, workerSendBuf),
	}
	d.register(slot)
	defer d.unregister(slot)

	// Proxy DTLS <-> Dispatcher
	var proxyWg sync.WaitGroup
	proxyWg.Add(2)

	stopDTLS := context.AfterFunc(sessCtx, func() {
		_ = dtlsConn.SetDeadline(time.Now())
	})
	defer stopDTLS()

	// Writer: dispatcher -> DTLS
	go func() {
		defer proxyWg.Done()
		defer sessCancel()
		ticker := time.NewTicker(10 * time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-sessCtx.Done():
				return
			case <-ticker.C:
				_ = dtlsConn.SetWriteDeadline(time.Now().Add(5 * time.Second))
				if _, writeErr := dtlsConn.Write([]byte("WAKEUP")); writeErr != nil {
					return
				}
			case pkt, ok := <-slot.sendCh:
				if !ok {
					return
				}
				_ = dtlsConn.SetWriteDeadline(time.Now().Add(10 * time.Second))
				if _, writeErr := dtlsConn.Write(pkt); writeErr != nil {
					return
				}
			}
		}
	}()

	// Reader: DTLS -> dispatcher
	go func() {
		defer proxyWg.Done()
		defer sessCancel()
		b := make([]byte, readBufSize)
		consecutiveReadErrs := 0
		for {
			_ = dtlsConn.SetReadDeadline(time.Now().Add(sessionReadTimeout))
			n, readErr := dtlsConn.Read(b)
			if readErr != nil {
				if netErr, ok := readErr.(net.Error); ok && netErr.Timeout() {
					consecutiveReadErrs++
					if consecutiveReadErrs >= 3 {
						return
					}
					continue
				}
				return
			}
			consecutiveReadErrs = 0

			if n == 6 && string(b[:6]) == "WAKEUP" {
				continue
			}

			pkt := make([]byte, n)
			copy(pkt, b[:n])
			select {
			case d.returnCh <- pkt:
			case <-sessCtx.Done():
				return
			}
		}
	}()

	proxyWg.Wait()
	sessCancel()
	relayWg.Wait()
	sessionWg.Wait()
	// Закрываем pipe
	_ = pipeA.Close()
	_ = pipeB.Close()
	log.Printf("[СЕССИЯ #%d] Завершена", sessionID)
	return nil
}

func workerLoop(ctx context.Context, tp *turnParams, peer *net.UDPAddr,
	d *dispatcher, localPort string, useUDP bool,
	configChan chan<- string, workerID int,
	credsChan <-chan credentials,
	getConfigOnce *atomic.Bool, deviceID string, password string) {

	var currentCreds credentials
	var hasCreds bool

	for {
		// Ждём первые креды или берём новые
		if !hasCreds {
			select {
			case <-ctx.Done():
				return
			case c, ok := <-credsChan:
				if !ok {
					return
				}
				currentCreds = c
				hasCreds = true
			}
		}

		// Проверяем есть ли обновление кредов (неблокирующе)
		select {
		case c, ok := <-credsChan:
			if !ok {
				return
			}
			currentCreds = c
		default:
		}

		// Получаем конфиг только один раз на группу
		getConfig := getConfigOnce.CompareAndSwap(false, true)

		err := runSession(ctx, tp, peer, d, localPort, useUDP,
			getConfig, configChan, workerID, currentCreds, deviceID, password)

		select {
		case <-ctx.Done():
			return
		default:
		}

		if err != nil {
			errStr := err.Error()
			if strings.Contains(errStr, "FATAL_AUTH") {
				log.Printf("[ВОРКЕР #%d] ❌ %v — ОСТАНОВЛЕН", workerID, err)
				return // Не реконнектимся при ошибке авторизации
			}
			log.Printf("[ВОРКЕР #%d] Ошибка: %v, перезапуск через 2с", workerID, err)
		} else {
			log.Printf("[ВОРКЕР #%d] Сессия завершена штатно", workerID)
		}

		globalStats.reconnects.Add(1)

		// Пауза перед перезапуском с jitter
		jitter := time.Duration(rand.IntN(1000)) * time.Millisecond
		select {
		case <-ctx.Done():
			return
		case <-time.After(2*time.Second + jitter):
		}
	}
}

func workerGroup(ctx context.Context, groupID int, tp *turnParams,
	peer *net.UDPAddr, d *dispatcher, localPort string, useUDP bool,
	getConfig bool, configChan chan<- string, workerIDs []int,
	firstCycleTTL time.Duration, deviceID string, password string) {

	const maxConsecutiveErrors = 10

	cycleDuration := time.Duration(groupCycleMin) * time.Minute

	var getConfigOnce atomic.Bool
	if !getConfig {
		getConfigOnce.Store(true) // Уже "получен", не надо запрашивать
	}

	groupCtx, groupCancel := context.WithCancel(ctx)
	defer groupCancel()

	// Получаем первые креды
	hash := tp.hashes[rand.IntN(len(tp.hashes))]
	log.Printf("[ГРУППА #%d] Получаю первые креды (хеш: %s)...", groupID, hash[:min(8, len(hash))])

	user, pass, turnURLs, err := getCredsWithFallback(groupCtx, groupID, tp, hash)
	if err != nil {
		log.Printf("[ГРУППА #%d] Фатальная ошибка кредов: %v", groupID, err)
		return
	}

	log.Printf("[ГРУППА #%d] Первые креды OK, TURN: %v, первый TTL=%v",
		groupID, turnURLs, firstCycleTTL)

	creds := credentials{User: user, Pass: pass, TurnURLs: turnURLs}

	startBatch := func(c credentials) context.CancelFunc {
		bCtx, bCancel := context.WithCancel(groupCtx)
		for i, wid := range workerIDs {
			go func(idx, id int) {
				select {
				case <-bCtx.Done():
					return
				case <-time.After(time.Duration(idx) * 250 * time.Millisecond):
				}
				for {
					err := runSession(bCtx, tp, peer, d, localPort, useUDP, getConfigOnce.CompareAndSwap(false, true), configChan, id, c, deviceID, password)
					select {
					case <-bCtx.Done():
						return
					default:
					}
					if err != nil {
						errStr := err.Error()
						if strings.Contains(errStr, "FATAL_AUTH") {
							log.Printf("[ВОРКЕР #%d] ❌ %v — ВСЕ ВОРКЕРЫ ГРУППЫ ОСТАНОВЛЕНЫ", id, err)
							bCancel()     // Отменяем батч
							groupCancel() // Отменяем всю группу — цикл ротации тоже умрёт
							return
						}
						log.Printf("[ВОРКЕР #%d] Ошибка: %v, перезапуск через 2с", id, err)
					} else {
						log.Printf("[ВОРКЕР #%d] Сессия завершена штатно", id)
					}
					globalStats.reconnects.Add(1)
					jitter := time.Duration(rand.IntN(1000)) * time.Millisecond
					select {
					case <-bCtx.Done():
						return
					case <-time.After(2*time.Second + jitter):
					}
				}
			}(i, wid)
		}
		return bCancel
	}

	activeBatchCancel := startBatch(creds)

	// Цикл ротации кредов
	currentTTL := firstCycleTTL
	var consecutiveErrs int

	for {
		waitTime := currentTTL - overlapDuration
		if waitTime < 60*time.Second {
			waitTime = 60 * time.Second
		}

		log.Printf("[ГРУППА #%d] Ротация кредов через %v", groupID, waitTime)

		timer := time.NewTimer(waitTime)
		select {
		case <-timer.C:
		case <-groupCtx.Done():
			timer.Stop()
			log.Printf("[ГРУППА #%d] Завершаю", groupID)
			return
		}

		// Получаем новые креды
		hash = tp.hashes[rand.IntN(len(tp.hashes))]
		log.Printf("[ГРУППА #%d] Получаю новые креды (хеш: %s)...", groupID, hash[:min(8, len(hash))])

		newUser, newPass, newTurnURLs, credErr := getCredsWithFallback(groupCtx, groupID, tp, hash)
		if credErr != nil {
			if groupCtx.Err() != nil {
				return
			}
			consecutiveErrs++
			if consecutiveErrs >= maxConsecutiveErrors {
				log.Printf("[ГРУППА #%d] Слишком много ошибок (%d), останавливаюсь", groupID, consecutiveErrs)
				return
			}
			log.Printf("[ГРУППА #%d] Ошибка кредов (%d/%d): %v, повтор через минуту",
				groupID, consecutiveErrs, maxConsecutiveErrors, credErr)
			currentTTL = 1 * time.Minute
			continue
		}
		consecutiveErrs = 0

		log.Printf("[ГРУППА #%d] Создается новый батч из %d воркеров (замена старого через 45с)", groupID, len(workerIDs))
		newCreds := credentials{User: newUser, Pass: newPass, TurnURLs: newTurnURLs}
		newBatchCancel := startBatch(newCreds)

		oldBatchCancel := activeBatchCancel
		activeBatchCancel = newBatchCancel

		// Планируем отключение старого батча
		go func(cancel context.CancelFunc) {
			select {
			case <-time.After(overlapDuration):
				log.Printf("[ГРУППА #%d] Отключение старого батча воркеров", groupID)
				cancel()
			case <-groupCtx.Done():
			}
		}(oldBatchCancel)

		currentTTL = cycleDuration
	}
}

func getCredsWithFallback(ctx context.Context, groupID int, tp *turnParams, hash string) (user, pass string, turnURLs []string, err error) {
	user, pass, turnURLs, err = getUniqueVkCreds(ctx, hash, 5)
	if err != nil && tp.secondaryHash != "" && hash != tp.secondaryHash {
		log.Printf("[ГРУППА #%d] Основной хеш не работает, пробую запасной", groupID)
		user, pass, turnURLs, err = getUniqueVkCreds(ctx, tp.secondaryHash, 3)
	}
	return
}

func main() {
	log.SetFlags(log.Ldate | log.Ltime | log.Lmicroseconds)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	sig := make(chan os.Signal, 1)
	signal.Notify(sig, syscall.SIGTERM, syscall.SIGINT)
	go func() {
		select {
		case s := <-sig:
			log.Printf("[КЛИЕНТ] Сигнал %v, завершаю...", s)
			cancel()
		case <-ctx.Done():
			return
		}
		// Второй сигнал — принудительный выход
		select {
		case s := <-sig:
			log.Printf("[КЛИЕНТ] Повторный %v, выход", s)
			os.Exit(1)
		case <-ctx.Done():
		}
	}()

	host := flag.String("turn", "", "переопределить IP TURN")
	port := flag.String("port", "", "переопределить порт TURN")
	listen := flag.String("listen", "127.0.0.1:9000", "локальный адрес")
	vkHash := flag.String("vk", "", "хеши VK-звонков (через запятую)")
	secondaryHash := flag.String("vk2", "", "запасной VK хеш")
	peerAddr := flag.String("peer", "", "адрес:порт VPS сервера")
	numW := flag.Int("n", 16, "количество воркеров (кратно 8)")
	useTCP := flag.Bool("tcp", false, "TURN через TCP")
	useUDP := flag.Bool("udp", false, "TURN через UDP")
	useBoth := flag.Bool("tcpudp", false, "TURN через TCP и UDP")
	splitTunnel := flag.Bool("split", false, "split tunneling")
	sni := flag.String("sni", "", "SNI для DTLS")
	noDns := flag.Bool("nodns", false, "отключить DNS Яндекса")

	appID := flag.String("vk-app-id", "8094476", "VK App ID")
	appSecret := flag.String("vk-app-secret", "0sxydyHqvEaPJkMhnBEW", "VK App Secret")

	deviceID := flag.String("device-id", "unknown", "уникальный ID устройства")
	connPassword := flag.String("password", "", "пароль подключения")

	// Deprecated flags (ignored)
	_ = flag.Int("ramp", 100, "задержка (устарело, игнорируется)")

	flag.Parse()

	vkAppID.Store(*appID)
	vkAppSecret.Store(*appSecret)
	noDnsFlag.Store(*noDns)

	if *peerAddr == "" || *vkHash == "" {
		log.Fatal("[КЛИЕНТ] Нужны -peer и -vk")
	}

	peer, err := net.ResolveUDPAddr("udp", *peerAddr)
	if err != nil {
		log.Fatalf("[КЛИЕНТ] Ошибка разбора пира: %v", err)
	}

	// Парсим хеши
	rawHashes := strings.Split(*vkHash, ",")
	var hashes []string
	for _, h := range rawHashes {
		h = strings.TrimSpace(h)
		if idx := strings.IndexAny(h, "/?#"); idx != -1 {
			h = h[:idx]
		}
		if h != "" {
			hashes = append(hashes, h)
		}
	}
	if len(hashes) == 0 {
		log.Fatal("[КЛИЕНТ] Нет хешей VK")
	}

	// Протокол по умолчанию
	if !*useTCP && !*useUDP && !*useBoth {
		*useTCP = true
	}

	// Лимитируем воркеров
	maxWorkers := 48
	if *numW > maxWorkers {
		*numW = maxWorkers
	}
	if *numW < workersPerGroup {
		*numW = workersPerGroup
	}
	*numW = (*numW / workersPerGroup) * workersPerGroup

	tp := &turnParams{
		host:          *host,
		port:          *port,
		hashes:        hashes,
		secondaryHash: strings.TrimSpace(*secondaryHash),
		sni:           *sni,
	}

	// Слушаем локально
	localConn, err := net.ListenPacket("udp", *listen)
	if err != nil {
		log.Fatalf("[КЛИЕНТ] Ошибка слушателя %s: %v", *listen, err)
	}

	if uc, ok := localConn.(*net.UDPConn); ok {
		_ = uc.SetReadBuffer(socketBufSize)
		_ = uc.SetWriteBuffer(socketBufSize)
	}

	// Закрываем localConn при отмене контекста
	stopLocalConn := context.AfterFunc(ctx, func() { _ = localConn.Close() })
	defer stopLocalConn()

	_, localPort, _ := net.SplitHostPort(*listen)
	if localPort == "" {
		localPort = "9000"
	}

	// Считаем группы
	numGroups := *numW / workersPerGroup
	if *useBoth {
		numGroups = (*numW / workersPerGroup) * 2
	}

	staggerStep := time.Duration(groupCycleMin) * time.Minute / time.Duration(numGroups)
	if staggerStep < 10*time.Second {
		staggerStep = 10 * time.Second
	}

	// Логируем конфигурацию
	log.Printf("[КЛИЕНТ] ═══════════════════════════════════════")
	log.Printf("[КЛИЕНТ] VK App: %s", *appID)
	log.Printf("[КЛИЕНТ] Воркеров: %d (групп: %d, по %d)", *numW, numGroups, workersPerGroup)
	log.Printf("[КЛИЕНТ] Цикл: %d мин | Overlap: %v", groupCycleMin, overlapDuration)
	log.Printf("[КЛИЕНТ] Таймаут сессии: %v", sessionReadTimeout)
	log.Printf("[КЛИЕНТ] Запасной хеш: %v", tp.secondaryHash != "")
	log.Printf("[КЛИЕНТ] Расписание ротаций:")
	for i := range numGroups {
		firstTTL := staggerStep * time.Duration(i+1)
		if firstTTL > time.Duration(groupCycleMin)*time.Minute {
			firstTTL = time.Duration(groupCycleMin) * time.Minute
		}
		log.Printf("[КЛИЕНТ]   Группа %d: первая через %v, далее каждые %d мин",
			i+1, firstTTL, groupCycleMin)
	}
	log.Printf("[КЛИЕНТ] Хешей: %d", len(hashes))
	log.Printf("[КЛИЕНТ] Слушаю: %s | Пир: %s", *listen, *peerAddr)
	log.Printf("[КЛИЕНТ] Протокол: TCP=%v UDP=%v Both=%v", *useTCP, *useUDP, *useBoth)
	log.Printf("[КЛИЕНТ] Device ID: %s | Пароль: %s", *deviceID, *connPassword)
	log.Printf("[КЛИЕНТ] ═══════════════════════════════════════")

	// Запускаем статистику
	go statsLoop(ctx)

	// Создаём диспетчер
	disp := newDispatcher(ctx, localConn)
	defer disp.shutdown()

	// Канал конфигурации
	configChan := make(chan string, 1)
	configDone := make(chan struct{})
	go func() {
		defer close(configDone)
		select {
		case rawConf, ok := <-configChan:
			if !ok || rawConf == "" {
				return
			}
			finalConf := prepareConfig(rawConf, *splitTunnel, peer.IP)
			fmt.Println()
			fmt.Println("╔══════════════ WireGuard Конфиг ══════════════╗")
			for _, line := range strings.Split(finalConf, "\n") {
				fmt.Printf("║ %-44s ║\n", line)
			}
			fmt.Println("╚══════════════════════════════════════════════╝")
			if writeErr := os.WriteFile(configFile, []byte(finalConf+"\n"), 0600); writeErr != nil {
				log.Printf("[КОНФИГ] Ошибка сохранения: %v", writeErr)
			} else {
				log.Printf("[КОНФИГ] Сохранён в %s", configFile)
			}
		case <-ctx.Done():
		}
	}()

	// Запускаем группы воркеров
	var wg sync.WaitGroup
	workerIDCounter := 1
	groupCounter := 0

	launchGroup := func(gid int, ids []int, useGroupUDP bool, first bool, delay, firstTTL time.Duration) {
		var cc chan<- string
		if first {
			cc = configChan
		}
		wg.Add(1)
		go func() {
			defer wg.Done()
			if delay > 0 {
				timer := time.NewTimer(delay)
				select {
				case <-timer.C:
				case <-ctx.Done():
					timer.Stop()
					return
				}
			}
			workerGroup(ctx, gid, tp, peer, disp, localPort, useGroupUDP,
				first, cc, ids, firstTTL, *deviceID, *connPassword)
		}()
	}

	makeWorkerIDs := func(count int) []int {
		ids := make([]int, count)
		for i := range ids {
			ids[i] = workerIDCounter
			workerIDCounter++
		}
		return ids
	}

	for g := range *numW / workersPerGroup {
		isFirstGroup := (g == 0)

		if *useBoth {
			// TCP группа
			tcpIDs := makeWorkerIDs(workersPerGroup)
			groupCounter++
			tcpGID := groupCounter
			startDelay := time.Duration(groupCounter-1) * 3 * time.Second
			firstTTL := staggerStep * time.Duration(tcpGID)
			if firstTTL > time.Duration(groupCycleMin)*time.Minute {
				firstTTL = time.Duration(groupCycleMin) * time.Minute
			}
			launchGroup(tcpGID, tcpIDs, false, isFirstGroup, startDelay, firstTTL)

			// UDP группа
			udpIDs := makeWorkerIDs(workersPerGroup)
			groupCounter++
			udpGID := groupCounter
			startDelay = time.Duration(groupCounter-1) * 3 * time.Second
			firstTTL = staggerStep * time.Duration(udpGID)
			if firstTTL > time.Duration(groupCycleMin)*time.Minute {
				firstTTL = time.Duration(groupCycleMin) * time.Minute
			}
			launchGroup(udpGID, udpIDs, true, false, startDelay, firstTTL)
		} else {
			ids := makeWorkerIDs(workersPerGroup)
			groupCounter++
			gid := groupCounter
			startDelay := time.Duration(groupCounter-1) * 3 * time.Second
			firstTTL := staggerStep * time.Duration(gid)
			if firstTTL > time.Duration(groupCycleMin)*time.Minute {
				firstTTL = time.Duration(groupCycleMin) * time.Minute
			}
			launchGroup(gid, ids, *useUDP, isFirstGroup, startDelay, firstTTL)
		}
	}

	wg.Wait()
	close(configChan)
	<-configDone
	log.Println("[КЛИЕНТ] Все воркеры завершены")
}

func prepareConfig(rawConf string, split bool, peerIP net.IP) string {
	res := rawConf
	if !strings.Contains(res, "MTU =") {
		lines := strings.Split(res, "\n")
		var newLines []string
		for _, line := range lines {
			newLines = append(newLines, line)
			if strings.TrimSpace(line) == "[Interface]" {
				newLines = append(newLines, "MTU = 1280")
			}
		}
		res = strings.Join(newLines, "\n")
	}

	if split {
		res = modifyConfigForSplitTunnel(res, peerIP)
	}
	return res
}

func modifyConfigForSplitTunnel(conf string, peerIP net.IP) string {
	var excludeIPs []net.IP
	var excludeNets []*net.IPNet

	excludeIPs = append(excludeIPs, peerIP)

	vkCIDRs := []string{
		"95.163.0.0/16", "87.240.0.0/16", "93.186.224.0/20",
		"185.32.248.0/22", "185.29.130.0/24", "217.20.144.0/20",
	}
	privateCIDRs := []string{
		"10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16",
	}

	for _, cidr := range append(vkCIDRs, privateCIDRs...) {
		_, n, err := net.ParseCIDR(cidr)
		if err == nil && n != nil {
			excludeNets = append(excludeNets, n)
		}
	}

	allowedIPs := calcAllowedIPsExcluding(excludeIPs, excludeNets)

	lines := strings.Split(conf, "\n")
	var newLines []string
	for _, line := range lines {
		trimmed := strings.TrimSpace(line)
		if strings.HasPrefix(trimmed, "AllowedIPs") {
			newLines = append(newLines, fmt.Sprintf("AllowedIPs = %s", allowedIPs))
		} else {
			newLines = append(newLines, line)
		}
	}
	return strings.Join(newLines, "\n")
}

type cidrBlock struct {
	ip   uint32
	bits int
}

func (c cidrBlock) String() string {
	ip := make(net.IP, 4)
	binary.BigEndian.PutUint32(ip, c.ip)
	return fmt.Sprintf("%s/%d", ip.String(), c.bits)
}

func calcAllowedIPsExcluding(excludeIPs []net.IP, excludeNets []*net.IPNet) string {
	var excludes []cidrBlock

	for _, ip := range excludeIPs {
		if ip4 := ip.To4(); ip4 != nil {
			excludes = append(excludes, cidrBlock{
				ip:   binary.BigEndian.Uint32(ip4),
				bits: 32,
			})
		}
	}
	for _, n := range excludeNets {
		if ip4 := n.IP.To4(); ip4 != nil {
			ones, _ := n.Mask.Size()
			excludes = append(excludes, cidrBlock{
				ip:   binary.BigEndian.Uint32(ip4),
				bits: ones,
			})
		}
	}

	containsBlock := func(container, target cidrBlock) bool {
		if container.bits > target.bits {
			return false
		}
		mask := uint32(0xFFFFFFFF) << (32 - container.bits)
		return (container.ip & mask) == (target.ip & mask)
	}

	overlaps := func(a, b cidrBlock) bool {
		minBits := a.bits
		if b.bits < minBits {
			minBits = b.bits
		}
		mask := uint32(0xFFFFFFFF) << (32 - minBits)
		return (a.ip & mask) == (b.ip & mask)
	}

	var result []cidrBlock
	var split func(c cidrBlock)
	split = func(c cidrBlock) {
		for _, ex := range excludes {
			if containsBlock(ex, c) {
				return
			}
		}
		hasOverlap := false
		for _, ex := range excludes {
			if overlaps(c, ex) {
				hasOverlap = true
				break
			}
		}
		if !hasOverlap {
			result = append(result, c)
			return
		}
		if c.bits >= 32 {
			return
		}
		nextBits := c.bits + 1
		bit := uint32(1) << (32 - nextBits)
		split(cidrBlock{ip: c.ip, bits: nextBits})
		split(cidrBlock{ip: c.ip | bit, bits: nextBits})
	}
	split(cidrBlock{ip: 0, bits: 0})

	strs := make([]string, 0, len(result))
	for _, c := range result {
		strs = append(strs, c.String())
	}
	return strings.Join(strs, ", ")
}
