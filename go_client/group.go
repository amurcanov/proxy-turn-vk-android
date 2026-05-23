package main

import (
	"context"
	"fmt"
	"log"
	"math/rand"
	"net"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

var groupAuthMutex sync.Mutex

const (
	workersPerGroup  = 9
	defaultCycleSecs = 36000
)

// WorkerGroup:
// Запускает 9 потоков с одними кредами. Ротации нет — работает до смерти воркеров.
func WorkerGroup(
	ctx context.Context,
	groupID int,
	hashIndex int,
	tp *TurnParams,
	peer *net.UDPAddr,
	d *Dispatcher,
	localPort string,
	getConfig bool,
	configCh chan<- string,
	workerIDs []int,
	cycleDuration time.Duration,
	pauseFlag *int32,
	deviceID, password string,
	stats *Stats,
	waitReady <-chan struct{},
	signalReady chan<- struct{},
) {
	// Каскадный запуск: ждем свою очередь
	if waitReady != nil {
		log.Printf("[ГРУППА #%d] Ожидание сигнала от предыдущей группы...", groupID)
		select {
		case <-waitReady:
		case <-ctx.Done():
			return
		}
	}

	var configSent int32
	if !getConfig {
		configSent = 1
	}

	// Doze-mode пауза
	for atomic.LoadInt32(pauseFlag) != 0 {
		if ctx.Err() != nil {
			return
		}
		time.Sleep(1 * time.Second)
	}

	hash := tp.Hashes[hashIndex%len(tp.Hashes)]
	shortHash := hash
	if len(shortHash) > 8 {
		shortHash = shortHash[:8]
	}
	log.Printf("[ГРУППА #%d] Запрос кредов (хеш: %s...)", groupID, shortHash)

	credStreamID := groupID * 100
	user, pass, turnURLs, err := GetCreds(ctx, hash, credStreamID)
	var creds *Credentials
	if err == nil {
		creds = &Credentials{User: user, Pass: pass, TurnURLs: turnURLs, CacheStreamID: credStreamID}
	} else {
		log.Printf("[ГРУППА #%d] Ошибка кредов: %v", groupID, err)
		return
	}

	log.Printf("[ГРУППА #%d] Креды OK, TURN: %v, %d воркеров", groupID, creds.TurnURLs, len(workerIDs))

	var configRequestInFlight int32
	var wg sync.WaitGroup
	var credsMu sync.RWMutex
	var refreshMu sync.Mutex
	var lastCredRefresh atomic.Int64

	refreshCreds := func(reason string) bool {
		refreshMu.Lock()
		defer refreshMu.Unlock()

		now := time.Now().Unix()
		last := lastCredRefresh.Load()
		if last > 0 && now-last < 15 {
			log.Printf("[TURN] Креды уже обновлялись %d сек назад, ждём следующий retry (%s)", now-last, reason)
			return true
		}

		getStreamCache(credStreamID).invalidate(credStreamID)
		u, p, urls, refreshErr := GetCreds(ctx, hash, credStreamID)
		if refreshErr != nil {
			log.Printf("[TURN] Не удалось обновить креды после %s: %v", reason, refreshErr)
			return false
		}

		credsMu.Lock()
		creds = &Credentials{User: u, Pass: p, TurnURLs: urls, CacheStreamID: credStreamID}
		credsMu.Unlock()
		lastCredRefresh.Store(time.Now().Unix())
		log.Printf("[TURN] Креды обновлены после %s, TURN urls=%d", reason, len(urls))
		return true
	}

	// Сигнализируем следующей группе, что мы успешно запустились (креды получены + 2 сек форы)
	if signalReady != nil {
		go func() {
			time.Sleep(2000 * time.Millisecond)
			close(signalReady)
			log.Printf("[ГРУППА #%d] Успешный старт! Передача эстафеты следующей группе...", groupID)
		}()
	}

	for i, wid := range workerIDs {
		wg.Add(1)

		// Stagger: 500мс между воркерами
		workerDelay := time.Duration(i) * 500 * time.Millisecond

		go func(wid int, delay time.Duration) {
			defer wg.Done()

			if delay > 0 {
				select {
				case <-time.After(delay):
				case <-ctx.Done():
					return
				}
			}

			shouldGetConfig := getConfig
			attempt := 0

			for {
				if ctx.Err() != nil {
					return
				}

				getConf := false
				if shouldGetConfig && atomic.LoadInt32(&configSent) == 0 {
					getConf = atomic.CompareAndSwapInt32(&configRequestInFlight, 0, 1)
				}
				var cc chan<- string
				if getConf {
					cc = configCh
				}

				credsMu.RLock()
				credsSnapshot := *creds
				credsSnapshot.TurnURLs = cloneStringSlice(creds.TurnURLs)
				credsMu.RUnlock()

				configDelivered, sessErr := RunSession(ctx, tp, peer, d, localPort,
					getConf, cc, wid, &credsSnapshot, deviceID, password, stats)

				if getConf {
					if configDelivered {
						atomic.StoreInt32(&configSent, 1)
					} else {
						atomic.StoreInt32(&configRequestInFlight, 0)
					}
				}

				if sessErr != nil {
					if ctx.Err() != nil {
						return
					}
					errStr := sessErr.Error()
					errStrLower := strings.ToLower(errStr)

					turnAllocAttrMissing := strings.Contains(errStrLower, "turn allocate") &&
						strings.Contains(errStrLower, "attribute not found")
					turnCredRefreshNeeded := turnAllocAttrMissing ||
						strings.Contains(errStrLower, "turn allocate auth") ||
						strings.Contains(errStrLower, "invalid credential") ||
						strings.Contains(errStrLower, "stale nonce") ||
						strings.Contains(errStrLower, "allocation mismatch") ||
						strings.Contains(errStrLower, "error 508") ||
						strings.Contains(errStrLower, "turn квота") ||
						strings.Contains(errStrLower, "quota")

					if strings.Contains(errStrLower, "rate limit") ||
						strings.Contains(errStrLower, "flood control") ||
						strings.Contains(errStrLower, "ip mismatch") ||
						strings.Contains(errStrLower, "error 29") {
						errStr += " (ошибка со стороны ВК)"
					}

					if strings.Contains(errStr, "хеш мёртв") ||
						strings.Contains(errStr, "FATAL_AUTH") {
						log.Printf("[ВОРКЕР #%d] Фатальная ошибка: %s", wid, errStr)
						return
					}

					attempt++
					if turnAllocAttrMissing {
						log.Printf("[ВОРКЕР #%d] [TURN] Allocate вернул неполный ответ, обновляем TURN-креды и повторяем (попытка %d): %s", wid, attempt, errStr)
						refreshCreds("TURN Allocate attribute-not-found")
					} else if turnCredRefreshNeeded {
						log.Printf("[ВОРКЕР #%d] [TURN] Ошибка allocation/кредов, обновляем TURN-креды и повторяем (попытка %d): %s", wid, attempt, errStr)
						refreshCreds("TURN allocation error")
					} else {
						log.Printf("[ВОРКЕР #%d] Ошибка (попытка %d): %s", wid, attempt, errStr)
					}

					// Если ошибка STUN (credentials invalid), воркер не сможет переподключиться. Завершаем.
					isStunDeath := strings.Contains(errStrLower, "error 29") ||
						strings.Contains(errStrLower, "cannot create socket")

					if isStunDeath {
						log.Printf("[ВОРКЕР #%d] Невосстановимая TURN/STUN ошибка, завершение: %s", wid, errStr)
						return
					}
				}

				if ctx.Err() != nil {
					return
				}

				retryDelay := time.Duration(5+rand.Intn(11)) * time.Second
				select {
				case <-time.After(retryDelay):
				case <-ctx.Done():
					return
				}
			}
		}(wid, workerDelay)
	}

	wg.Wait()
	log.Printf("[ГРУППА #%d] Все воркеры группы завершились.", groupID)
}

// ParseHashes — парсит строку хешей
func ParseHashes(raw string) []string {
	var result []string
	seen := make(map[string]struct{})
	for _, h := range strings.FieldsFunc(raw, func(r rune) bool {
		return r == ',' || r == ';' || r == '\n' || r == '\r' || r == '\t' || r == ' '
	}) {
		h = normalizeVKJoinHash(h)
		if h != "" {
			if _, exists := seen[h]; exists {
				continue
			}
			seen[h] = struct{}{}
			result = append(result, h)
		}
	}
	return result
}

func normalizeVKJoinHash(input string) string {
	s := strings.Trim(strings.TrimSpace(input), "<>\"'")
	if s == "" {
		return ""
	}

	lower := strings.ToLower(s)
	if idx := strings.Index(lower, "/call/join/"); idx >= 0 {
		s = s[idx+len("/call/join/"):]
	} else if strings.HasPrefix(lower, "http://") || strings.HasPrefix(lower, "https://") {
		return ""
	}

	if idx := strings.IndexAny(s, "?#/"); idx != -1 {
		s = s[:idx]
	}
	return strings.Trim(strings.TrimSpace(s), "/")
}

// TurnParams — конфигурация TURN
type TurnParams struct {
	Host    string
	Port    string
	Hashes  []string
	WrapKey []byte // Password-derived WRAP key (32 bytes), nil = disabled
}

// Credentials — учетные данные TURN
type Credentials struct {
	User          string
	Pass          string
	TurnURLs      []string
	CacheStreamID int
}

// Unused import suppressor
var _ = fmt.Sprintf
