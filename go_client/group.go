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
	workersPerGroup  = 12
	defaultCycleSecs = 36000
)

// WorkerGroup:
// бесшовная ротация: получить новые креды → запустить новый батч → убить старый.
func WorkerGroup(
	ctx context.Context,
	groupID int,
	hashIndex int,
	tp *TurnParams,
	peer *net.UDPAddr,
	d *Dispatcher,
	localPort string,
	useUDP bool,
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

	cycleNumber := 0
	var configSent int32
	if !getConfig {
		configSent = 1
	}

	// Предыдущий батч
	var prevCancel context.CancelFunc
	var prevDoneChs []chan struct{}
	var commonSignalOnce sync.Once

	killBatch := func() {
		if prevCancel != nil {
			prevCancel()
			for _, ch := range prevDoneChs {
				select {
				case <-ch:
				case <-time.After(3 * time.Second):
				}
			}
			prevCancel = nil
			prevDoneChs = nil
		}
	}
	defer killBatch()

	for {
		if ctx.Err() != nil {
			return
		}

		// Doze-mode пауза: убиваем воркеров и ждём RESUME
		if atomic.LoadInt32(pauseFlag) != 0 {
			killBatch()
			log.Printf("[ГРУППА #%d] Пауза (Doze)", groupID)
			for {
				if ctx.Err() != nil {
					return
				}
				if atomic.LoadInt32(pauseFlag) == 0 {
					log.Printf("[ГРУППА #%d] Возобновление — новые креды", groupID)
					break
				}
				time.Sleep(1 * time.Second)
			}
		}

		// Получаем креды ДО убийства старого батча (бесшовная ротация)
		hash := tp.Hashes[hashIndex%len(tp.Hashes)]
		shortHash := hash
		if len(shortHash) > 8 {
			shortHash = shortHash[:8]
		}
		log.Printf("[ГРУППА #%d] Цикл %d: ожидание очереди получения кредов (хеш: %s...)", groupID, cycleNumber, shortHash)

		groupAuthMutex.Lock()
		log.Printf("[ГРУППА #%d] Цикл %d: запрос кредов", groupID, cycleNumber)
		creds, err := GetCredsWithFallback(ctx, tp, hash, stats)
		groupAuthMutex.Unlock()

		if err != nil {
			if ctx.Err() != nil {
				return
			}
			log.Printf("[ГРУППА #%d] Ошибка кредов: %v", groupID, err)
			select {
			case <-time.After(30 * time.Second):
			case <-ctx.Done():
				return
			}
			continue
		}

		// Вычисляем точное время жизни на основе ответа VK (минус 2 минуты для надёжности)
		sleepDuration := defaultCycleSecs
		if creds.Lifetime > 120 {
			sleepDuration = creds.Lifetime - 120
		}
		cycleDurationLocal := time.Duration(sleepDuration) * time.Second

		log.Printf("[ГРУППА #%d] Запуск %d потоков (до смены кредов: %d сек)", groupID, workersPerGroup, sleepDuration)

		log.Printf("[ГРУППА #%d] Креды OK, TURN: %v, %d воркеров", groupID, creds.TurnURLs, len(workerIDs))

		// ТЕПЕРЬ убиваем старый батч (креды уже готовы — минимальный простой)
		killBatch()

		// Создаём новый batch
		batchCtx, batchCancel := context.WithCancel(ctx)
		var configRequestInFlight int32

		refreshCh := make(chan struct{}, 1)
		doneChs := make([]chan struct{}, len(workerIDs))
		var quotaErrorWorkers sync.Map
		var notFoundErrorWorkers sync.Map

		// Сигнализируем следующей группе, что мы успешно запустились (креды получены + 2 сек форы)
		go func() {
			commonSignalOnce.Do(func() {
				if signalReady != nil {
					time.Sleep(2000 * time.Millisecond) // Запас времени для рукопожатий (3*500ms + 500ms)
					close(signalReady)
					log.Printf("[ГРУППА #%d] Успешный старт! Передача эстафеты следующей группе...", groupID)
				}
			})
		}()

		for i, wid := range workerIDs {
			doneCh := make(chan struct{})
			doneChs[i] = doneCh

			// Stagger: 500мс между воркерами
			workerDelay := time.Duration(i) * 500 * time.Millisecond

			go func(wid int, delay time.Duration, doneCh chan struct{}) {
				defer close(doneCh)

				if delay > 0 {
					select {
					case <-time.After(delay):
					case <-batchCtx.Done():
						return
					}
				}

				shouldGetConfig := getConfig

				// Retry loop: воркер переподключается при ошибке
				attempt := 0
				for {
					if batchCtx.Err() != nil {
						return
					}

					getConf := false
					if shouldGetConfig && attempt == 0 && atomic.LoadInt32(&configSent) == 0 {
						getConf = atomic.CompareAndSwapInt32(&configRequestInFlight, 0, 1)
					}
					var cc chan<- string
					if getConf {
						cc = configCh
					}

					configDelivered, sessErr := RunSession(batchCtx, tp, peer, d, localPort, useUDP,
						getConf, cc, wid, creds, deviceID, password, stats)

					if getConf {
						if configDelivered {
							atomic.StoreInt32(&configSent, 1)
						} else {
							atomic.StoreInt32(&configRequestInFlight, 0)
						}
					}

					if sessErr != nil {
						if batchCtx.Err() != nil {
							return
						}
						errStr := sessErr.Error()

						// Дописываем понятные пояснения для типичных ошибок со стороны балансировщиков ВК
						errStrLower := strings.ToLower(errStr)
						if strings.Contains(errStrLower, "attribute not found") ||
							strings.Contains(errStrLower, "rate limit") ||
							strings.Contains(errStrLower, "flood control") ||
							strings.Contains(errStrLower, "ip mismatch") ||
							strings.Contains(errStrLower, "error 29") {
							errStr += " (ошибка со стороны ВК)"
						}

						// Фатальные ошибки — смерть аккаунта
						if strings.Contains(errStr, "хеш мёртв") ||
							strings.Contains(errStr, "FATAL_AUTH") {
							log.Printf("[ВОРКЕР #%d] Фатальная ошибка: %s", wid, errStr)
							return
						}

						// Исчерпана ли квота TURN?
						if strings.Contains(errStrLower, "turn квота") || strings.Contains(errStrLower, "quota") {
							quotaErrorWorkers.Store(wid, true)
							qCount := 0
							quotaErrorWorkers.Range(func(k, v any) bool { qCount++; return true })
							if qCount >= 5 {
								select {
								case refreshCh <- struct{}{}:
									log.Printf("[ГРУППА #%d] Досрочная ротация: исчерпана квота TURN у %d воркеров", groupID, qCount)
								default:
								}
							}
							log.Printf("[ВОРКЕР #%d] Ошибка квоты TURN: %s", wid, errStr)
							return // Воркер завершается, на текущих кредах он больше не поднимется
						}

						attempt++
						log.Printf("[ВОРКЕР #%d] Ошибка (попытка %d): %s", wid, attempt, errStr)

						// Умерли ли креды? (Строго STUN/TURN ошибки: интернет работает, но сервер отвергает ключи)
						isStunDeath := strings.Contains(errStrLower, "attribute not found") ||
							strings.Contains(errStrLower, "error 29") ||
							strings.Contains(errStrLower, "unauthorized") ||
							strings.Contains(errStrLower, "allocation mismatch") ||
							strings.Contains(errStrLower, "error 508") ||
							strings.Contains(errStrLower, "cannot create socket")

						isStreamClosed := strings.Contains(errStrLower, "stream closed")

						if isStreamClosed {
							select {
							case refreshCh <- struct{}{}:
								log.Printf("[ГРУППА #%d] Мгновенная ротация: сервер ВК закрыл поток (Stream Closed)", groupID)
							default:
							}
						} else if isStunDeath {
							notFoundErrorWorkers.Store(wid, true)
							nfCount := 0
							notFoundErrorWorkers.Range(func(k, v any) bool { nfCount++; return true })

							// Если 8 уникальных воркеров получили явный отказ от сервера — ключи 100% протухли
							if nfCount >= 8 {
								select {
								case refreshCh <- struct{}{}:
									log.Printf("[ГРУППА #%d] Досрочная ротация: сервер ВК убил сессию (у %d воркеров)", groupID, nfCount)
								default:
								}
							}
						}
					}

					if batchCtx.Err() != nil {
						return
					}

					// Пауза перед ретраем с джиттером 5-15 сек
					retryDelay := time.Duration(5+rand.Intn(11)) * time.Second
					select {
					case <-time.After(retryDelay):
					case <-batchCtx.Done():
						return
					}
				}
			}(wid, workerDelay, doneCh)
		}

		// Сохраняем батч для бесшовной ротации
		prevCancel = batchCancel
		prevDoneChs = doneChs

		// Ждём TTL либо сигнала досрочной ротации
		select {
		case <-time.After(cycleDurationLocal):
			log.Printf("[ГРУППА #%d] TTL %v истёк, ротация", groupID, cycleDurationLocal)
		case <-refreshCh:
			log.Printf("[ГРУППА #%d] Вызвана досрочная ротация (креды не отвечали)", groupID)
		case <-ctx.Done():
			return
		}

		cycleNumber++
	}
}

// ParseHashes — парсит строку хешей
func ParseHashes(raw string) []string {
	var result []string
	for _, h := range strings.Split(raw, ",") {
		h = strings.TrimSpace(h)
		if idx := strings.IndexAny(h, "/?#"); idx != -1 {
			h = h[:idx]
		}
		if h != "" {
			result = append(result, h)
		}
	}
	return result
}

// TurnParams — конфигурация TURN
type TurnParams struct {
	Host          string
	Port          string
	Hashes        []string
	SecondaryHash string
	Sni           string
}

// Unused import suppressor
var _ = fmt.Sprintf
