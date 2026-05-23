package main

import (
	"context"
	"crypto/tls"
	"fmt"
	"log"
	"net"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/cbeuw/connutil"
	"github.com/pion/dtls/v3"
	"github.com/pion/dtls/v3/pkg/crypto/selfsign"
	"github.com/pion/logging"
	"github.com/pion/turn/v5"
)

const (
	workerSendBuf      = 128
	sessionReadTimeout = 30 * time.Minute // Increased from 60s to 30min
	readBufSize        = 1600
	socketBufSize      = 625 * 1024
	keepaliveByte      = 0xFF // DTLS-level keepalive marker
	keepaliveInterval  = 15 * time.Second
)

// Handshake semaphore: limit to 3 concurrent DTLS handshakes
var handshakeSem = make(chan struct{}, 3)

// NullLoggerFactory подавляет логи pion
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

// connectedUDPConn — обёртка для connected UDP socket → PacketConn
type connectedUDPConn struct{ *net.UDPConn }

func (c *connectedUDPConn) WriteTo(p []byte, _ net.Addr) (int, error) { return c.Write(p) }

func RunSession(
	ctx context.Context,
	tp *TurnParams,
	peer *net.UDPAddr,
	d *Dispatcher,
	localPort string,
	getConfig bool,
	configCh chan<- string,
	sessionID int,
	creds *Credentials,
	deviceID, password string,
	stats *Stats,
) (bool, error) {
	configDelivered := false

	if len(creds.TurnURLs) == 0 {
		return false, fmt.Errorf("нет TURN URL в учетных данных")
	}
	selectedURL := creds.TurnURLs[sessionID%len(creds.TurnURLs)]

	urlhost, urlport, err := net.SplitHostPort(selectedURL)
	if err != nil {
		return false, fmt.Errorf("разбор TURN URL %q: %w", selectedURL, err)
	}
	if tp.Host != "" {
		urlhost = tp.Host
	}
	if tp.Port != "" {
		urlport = tp.Port
	}
	turnAddr := net.JoinHostPort(urlhost, urlport)

	// Транспорт: всегда UDP
	resolved, err := net.ResolveUDPAddr("udp", turnAddr)
	if err != nil {
		return false, fmt.Errorf("резолв TURN: %w", err)
	}
	c, err := net.DialUDP("udp", nil, resolved)
	if err != nil {
		return false, fmt.Errorf("подключение TURN UDP: %w", err)
	}
	defer c.Close()
	_ = c.SetReadBuffer(socketBufSize)
	_ = c.SetWriteBuffer(socketBufSize)
	var turnConn net.PacketConn = &connectedUDPConn{c}

	log.Printf("[СЕССИЯ #%d] TURN UDP (%s)", sessionID, turnAddr)

	// RequestedAddressFamily
	var addrFamily turn.RequestedAddressFamily
	if peer.IP.To4() != nil {
		addrFamily = turn.RequestedAddressFamilyIPv4
	} else {
		addrFamily = turn.RequestedAddressFamilyIPv6
	}

	// TURN Client (pion/turn/v5)
	tc, err := turn.NewClient(&turn.ClientConfig{
		STUNServerAddr:         turnAddr,
		TURNServerAddr:         turnAddr,
		Conn:                   turnConn,
		Username:               creds.User,
		Password:               creds.Pass,
		RequestedAddressFamily: addrFamily,
		LoggerFactory:          &NullLoggerFactory{},
	})
	if err != nil {
		return false, fmt.Errorf("TURN клиент: %w", err)
	}
	defer tc.Close()

	if err = tc.Listen(); err != nil {
		return false, fmt.Errorf("TURN Listen: %w", err)
	}

	relay, err := tc.Allocate()
	if err != nil {
		if isAuthError(err) {
			handleAuthError(creds.CacheStreamID)
		}
		errStr := err.Error()
		if strings.Contains(errStr, "Quota") || strings.Contains(errStr, "486") {
			return false, fmt.Errorf("TURN квота: %w", err)
		}
		return false, fmt.Errorf("TURN Allocate: %w", err)
	}
	defer relay.Close()

	// Reset error count on successful allocation
	getStreamCache(creds.CacheStreamID).errorCount.Store(0)

	log.Printf("[СЕССИЯ #%d] Relay: %s", sessionID, relay.LocalAddr())

	// Pipe для DTLS ↔ TURN relay
	pipeA, pipeB := connutil.AsyncPacketPipe()

	sessCtx, sessCancel := context.WithCancel(ctx)
	defer sessCancel()

	// Keepalive goroutine (TURN binding request)
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

	// Relay ↔ Pipe proxy (with RTP obfuscation)
	var relayWg sync.WaitGroup
	relayWg.Add(2)

	useWrap := len(tp.WrapKey) == wrapKeyLen

	// Initialize obfs config per session
	var obfsCfg *ObfsConfig
	var obfsWriteState *ObfsState
	if useWrap {
		obfsCfg = NewObfsConfig()
		obfsWriteState = NewObfsState()
	}

	stopRelay := context.AfterFunc(sessCtx, func() {
		_ = relay.SetDeadline(time.Now())
		_ = pipeA.SetDeadline(time.Now())
	})
	defer stopRelay()

	// relay → pipeA (UNWRAP: strip RTP header + decrypt)
	go func() {
		defer relayWg.Done()
		defer sessCancel()
		// Max incoming: RTP header (12) + AEAD tag (16) + padding.
		readBufLen := readBufSize + 80
		buf := make([]byte, readBufLen)
		plain := make([]byte, readBufSize)
		for {
			n, _, readErr := relay.ReadFrom(buf)
			if readErr != nil {
				return
			}
			payload := buf[:n]
			if useWrap {
				if !obfsIsRTPPacket(payload) {
					log.Printf("[СЕССИЯ #%d] OBFS unwrap: unexpected packet (n=%d)", sessionID, n)
					continue
				}
				m, wrapErr := obfsUnwrapPacket(tp.WrapKey, payload, plain)
				if wrapErr != nil {
					log.Printf("[СЕССИЯ #%d] OBFS unwrap: %v (n=%d)", sessionID, wrapErr, n)
					continue
				}
				payload = plain[:m]
			}
			if _, writeErr := pipeA.WriteTo(payload, peer); writeErr != nil {
				return
			}
		}
	}()

	// pipeA → relay (WRAP: add RTP header + encrypt)
	go func() {
		defer relayWg.Done()
		defer sessCancel()
		b := make([]byte, readBufSize)
		for {
			n, _, readErr := pipeA.ReadFrom(b)
			if readErr != nil {
				return
			}
			out := b[:n]
			if useWrap {
				if obfsCfg != nil && obfsWriteState != nil {
					wrapped, wrapErr := obfsWrapPacket(tp.WrapKey, out, obfsCfg, obfsWriteState)
					if wrapErr != nil {
						log.Printf("[СЕССИЯ #%d] OBFS wrap: %v", sessionID, wrapErr)
						return
					}
					out = wrapped
				}
			}
			if _, writeErr := relay.WriteTo(out, peer); writeErr != nil {
				return
			}
		}
	}()

	// DTLS с поддержкой Connection ID (без SNI)
	cert, err := selfsign.GenerateSelfSigned()
	if err != nil {
		return false, fmt.Errorf("генерация сертификата: %w", err)
	}

	// Acquire handshake semaphore
	select {
	case handshakeSem <- struct{}{}:
	case <-sessCtx.Done():
		return false, sessCtx.Err()
	}

	dtlsCfg := &dtls.Config{
		Certificates:          []tls.Certificate{cert},
		InsecureSkipVerify:    true,
		ExtendedMasterSecret:  dtls.RequireExtendedMasterSecret,
		CipherSuites:          []dtls.CipherSuiteID{dtls.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256},
		ConnectionIDGenerator: dtls.OnlySendCIDGenerator(),
		// No ServerName (SNI) — less detectable by DPI
	}

	dtlsConn, err := dtls.Client(pipeB, peer, dtlsCfg)
	if err != nil {
		<-handshakeSem
		return false, fmt.Errorf("DTLS клиент: %w", err)
	}
	defer dtlsConn.Close()

	hctx, hcancel := context.WithTimeout(sessCtx, 20*time.Second)
	log.Printf("[ВОРКЕР #%d] [DTLS] Рукопожатие (Handshake)...", sessionID)
	err = dtlsConn.HandshakeContext(hctx)
	hcancel()
	<-handshakeSem // RELEASE SEMAPHORE IMMEDIATELY AFTER HANDSHAKE

	if err != nil {
		if useWrap {
			errStr := strings.ToLower(err.Error())
			if strings.Contains(errStr, "deadline") || strings.Contains(errStr, "timeout") {
				return false, fmt.Errorf("WRAP_AUTH_TIMEOUT: DTLS timeout, пароль/WRAP не подтверждён")
			}
		}
		return false, fmt.Errorf("DTLS хендшейк: %w", err)
	}
	log.Printf("[ВОРКЕР #%d] [DTLS] Соединение установлено ✓", sessionID)

	atomic.AddInt32(&stats.ActiveConnections, 1)
	defer atomic.AddInt32(&stats.ActiveConnections, -1)

	// Запрос конфига
	if getConfig && configCh != nil {
		conf, confErr := RequestConfig(dtlsConn, localPort, deviceID, password)
		if confErr != nil {
			errStr := confErr.Error()
			if strings.Contains(errStr, "FATAL_AUTH") {
				return false, confErr
			}
			log.Printf("[ВОРКЕР #%d] Ошибка конфига: %v", sessionID, confErr)
		} else if conf != "" {
			select {
			case configCh <- conf:
				configDelivered = true
				log.Printf("[ВОРКЕР #%d] Конфиг получен", sessionID)
			default:
				configDelivered = true
				log.Printf("[ВОРКЕР #%d] Конфиг уже был доставлен другим воркером", sessionID)
			}
		} else {
			log.Printf("[ВОРКЕР #%d] Сервер ещё не выдал WireGuard-конфиг, повторим позже", sessionID)
		}
	}

	log.Printf("[ВОРКЕР #%d] [READY] Туннель готов к работе ✓", sessionID)

	// Регистрация в диспетчере
	slot := &WorkerSlot{
		ID:     sessionID,
		SendCh: make(chan []byte, workerSendBuf),
	}
	d.Register(slot)
	defer d.Unregister(slot)

	// Proxy DTLS ↔ Dispatcher
	var proxyWg sync.WaitGroup
	proxyWg.Add(3) // +1 for keepalive goroutine

	stopDTLS := context.AfterFunc(sessCtx, func() {
		_ = dtlsConn.SetDeadline(time.Now())
	})
	defer stopDTLS()

	// DTLS Keepalive: prevents TURN allocation timeout and DTLS idle disconnect
	go func() {
		defer proxyWg.Done()
		t := time.NewTicker(keepaliveInterval)
		defer t.Stop()
		ping := []byte{keepaliveByte}
		for {
			select {
			case <-sessCtx.Done():
				return
			case <-t.C:
				_ = dtlsConn.SetWriteDeadline(time.Now().Add(5 * time.Second))
				if _, err := dtlsConn.Write(ping); err != nil {
					return
				}
			}
		}
	}()

	// Writer: dispatcher → DTLS
	go func() {
		defer proxyWg.Done()
		defer sessCancel()
		for {
			select {
			case <-sessCtx.Done():
				return
			case pkt, ok := <-slot.SendCh:
				if !ok {
					return
				}
				_ = dtlsConn.SetWriteDeadline(time.Now().Add(sessionReadTimeout))
				if _, writeErr := dtlsConn.Write(pkt); writeErr != nil {
					log.Printf("[ВОРКЕР #%d] Ошибка Writer: %v", sessionID, writeErr)
					return
				}
			}
		}
	}()

	// Reader: DTLS → dispatcher
	go func() {
		defer proxyWg.Done()
		defer sessCancel()
		b := make([]byte, 2000)
		for {
			_ = dtlsConn.SetReadDeadline(time.Now().Add(sessionReadTimeout))
			n, readErr := dtlsConn.Read(b)
			if readErr != nil {
				if sessCtx.Err() != nil {
					return
				}
				if ne, ok := readErr.(net.Error); ok && ne.Timeout() {
					continue
				}
				log.Printf("[ВОРКЕР #%d] Ошибка Reader: %v", sessionID, readErr)
				return
			}

			// Skip keepalive pong from server
			if n == 1 && b[0] == keepaliveByte {
				continue
			}

			pkt := make([]byte, n)
			copy(pkt, b[:n])
			select {
			case d.ReturnCh <- pkt:
			case <-sessCtx.Done():
				return
			}
		}
	}()

	proxyWg.Wait()
	sessCancel()
	relayWg.Wait()
	sessionWg.Wait()
	_ = pipeA.Close()
	_ = pipeB.Close()
	log.Printf("[СЕССИЯ #%d] Завершена", sessionID)
	return configDelivered, nil
}
