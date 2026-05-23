package main

import (
	"bufio"
	"context"
	"flag"
	"fmt"
	"log"
	"net"
	"os"
	"os/signal"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"
)

// CaptchaResultChan — канал для получения токена капчи из внешнего решателя (WebView)
var CaptchaResultChan = make(chan string, 1)

var captchaModeValue atomic.Value

func init() {
	captchaModeValue.Store("auto")
}

func normalizeCaptchaMode(mode string) string {
	switch strings.ToLower(strings.TrimSpace(mode)) {
	case "auto", "rjs", "wv":
		return strings.ToLower(strings.TrimSpace(mode))
	default:
		return "auto"
	}
}

func setCaptchaMode(mode string) string {
	normalized := normalizeCaptchaMode(mode)
	captchaModeValue.Store(normalized)
	return normalized
}

func getCaptchaMode() string {
	mode, _ := captchaModeValue.Load().(string)
	if mode == "" {
		return "auto"
	}
	return mode
}

// drainCaptchaResult удаляет устаревший результат капчи из канала
func drainCaptchaResult() {
	select {
	case <-CaptchaResultChan:
	default:
	}
}

func main() {
	log.SetFlags(log.Ldate | log.Ltime | log.Lmicroseconds)

	setupGlobalResolver()

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	// Сигналы
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
		select {
		case s := <-sig:
			log.Printf("[КЛИЕНТ] Повторный %v, принудительный выход", s)
			os.Exit(1)
		case <-ctx.Done():
		}
	}()

	var pauseFlag int32

	// STDIN для PAUSE/RESUME/STOP и CAPTCHA_RESULT
	go func() {
		scanner := bufio.NewScanner(os.Stdin)
		for scanner.Scan() {
			line := strings.TrimSpace(scanner.Text())
			if !strings.Contains(line, "error:tunnel stopped") {
				log.Printf("[STDIN] %s", line)
			}
			switch {
			case line == "PAUSE":
				atomic.StoreInt32(&pauseFlag, 1)
			case line == "RESUME":
				atomic.StoreInt32(&pauseFlag, 0)
			case line == "STOP":
				cancel()
				return
			case strings.HasPrefix(line, "CAPTCHA_RESULT|"):
				result := strings.TrimPrefix(line, "CAPTCHA_RESULT|")
				drainCaptchaResult()
				CaptchaResultChan <- result
				log.Printf("[КАПЧА] Результат от Kotlin записан в канал")
			}
		}
	}()

	host := flag.String("turn", "", "переопределить IP TURN")
	port := flag.String("port", "", "переопределить порт TURN")
	listen := flag.String("listen", "127.0.0.1:9000", "локальный адрес")
	vkHash := flag.String("vk", "", "хеши VK-звонков (через запятую)")
	peerAddr := flag.String("peer", "", "адрес:порт VPS сервера")
	numW := flag.Int("n", 24, "количество воркеров (кратно 12)")

	deviceID := flag.String("device-id", "unknown", "уникальный ID устройства")
	connPassword := flag.String("password", "", "пароль подключения")
	captchaMode := flag.String("captcha-mode", "auto", "режим обхода капчи (auto/wv/rjs)")

	flag.Parse()
	activeCaptchaMode := setCaptchaMode(*captchaMode)

	if *peerAddr == "" || *vkHash == "" {
		log.Fatal("[КЛИЕНТ] Нужны -peer и -vk")
	}

	peer, err := net.ResolveUDPAddr("udp", *peerAddr)
	if err != nil {
		log.Fatalf("[КЛИЕНТ] Ошибка разбора пира: %v", err)
	}

	hashes := ParseHashes(*vkHash)
	if len(hashes) == 0 {
		log.Fatal("[КЛИЕНТ] Нет хешей VK")
	}

	if *connPassword == "" {
		log.Fatal("[КЛИЕНТ] Нужен -password: WRAP ключ теперь выводится из пароля подключения")
	}

	// WRAP key
	wrapKey, err := deriveWrapKey(*connPassword)
	if err != nil {
		log.Fatalf("[КЛИЕНТ] WRAP key derive: %v", err)
	}

	// Лимит воркеров
	maxWorkers := 108
	if *numW > maxWorkers {
		*numW = maxWorkers
	}
	if *numW < workersPerGroup {
		*numW = workersPerGroup
	}
	*numW = (*numW / workersPerGroup) * workersPerGroup

	tp := &TurnParams{
		Host:    *host,
		Port:    *port,
		Hashes:  hashes,
		WrapKey: wrapKey,
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
	stopLocalConn := context.AfterFunc(ctx, func() { _ = localConn.Close() })
	defer stopLocalConn()

	_, localPort, _ := net.SplitHostPort(*listen)
	if localPort == "" {
		localPort = "9000"
	}

	numGroups := *numW / workersPerGroup

	wrapStatus := "OFF"
	if len(wrapKey) == wrapKeyLen {
		wrapStatus = "ON (password HKDF + RTP AEAD)"
	}

	captchaStatus := "AUTO: Go v2 x2 -> WBV Auto x2 -> Go v2 x1 -> Manual WBV"
	switch activeCaptchaMode {
	case "wv":
		captchaStatus = "WBV selected in Android"
	case "rjs":
		captchaStatus = "RJS Go v2 with WBV Auto fallback"
	}

	log.Println("[КЛИЕНТ] ═══════════════════════════════════════")
	log.Printf("[КЛИЕНТ] VK Creds: 2 stable app_id с циклическим fallback")
	log.Printf("[КЛИЕНТ] TLS: Chrome 146 fingerprint")
	log.Printf("[КЛИЕНТ] Воркеров: %d (групп: %d, по %d)", *numW, numGroups, workersPerGroup)
	log.Printf("[КЛИЕНТ] Хешей: %d", len(hashes))
	log.Printf("[КЛИЕНТ] Слушаю: %s | Пир: %s", *listen, *peerAddr)
	log.Printf("[КЛИЕНТ] Протокол: UDP")
	log.Printf("[КЛИЕНТ] WRAP: %s", wrapStatus)
	log.Printf("[WRAP] Ключ выведен из пароля, режим RTP AEAD активен")
	log.Printf("[КЛИЕНТ] Device ID: %s", *deviceID)
	log.Printf("[КЛИЕНТ] Captcha: %s", captchaStatus)
	log.Println("[КЛИЕНТ] ═══════════════════════════════════════")

	stats := NewStats()
	shutdownCh := make(chan struct{})
	go func() {
		<-ctx.Done()
		close(shutdownCh)
	}()
	go stats.RunLoop(shutdownCh)

	disp := NewDispatcher(ctx, localConn, stats)
	defer disp.Shutdown()

	configCh := make(chan string, 1)
	configDone := make(chan struct{})
	go func() {
		defer close(configDone)
		select {
		case rawConf, ok := <-configCh:
			if !ok || rawConf == "" {
				return
			}
			finalConf := rawConf
			if !strings.Contains(finalConf, "MTU =") {
				lines := strings.Split(finalConf, "\n")
				var newLines []string
				for _, line := range lines {
					newLines = append(newLines, line)
					if strings.TrimSpace(line) == "[Interface]" {
						newLines = append(newLines, "MTU = 1280")
					}
				}
				finalConf = strings.Join(newLines, "\n")
			}
			fmt.Println()
			fmt.Println("╔══════════════ WireGuard Конфиг ══════════════╗")
			for _, line := range strings.Split(finalConf, "\n") {
				fmt.Printf("║ %-44s ║\n", line)
			}
			fmt.Println("╚══════════════════════════════════════════════╝")
			if err := os.WriteFile("wg-turn.conf", []byte(finalConf+"\n"), 0600); err != nil {
				log.Printf("[КОНФИГ] Ошибка сохранения: %v", err)
			} else {
				log.Println("[КОНФИГ] Сохранён в wg-turn.conf")
			}
		case <-ctx.Done():
		}
	}()

	var wg sync.WaitGroup
	workerIDCounter := 1

	var prevWaitReady <-chan struct{}

	for g := 0; g < numGroups; g++ {
		isFirst := (g == 0)

		var myWaitReady <-chan struct{}
		var mySignalReady chan<- struct{}

		if g > 0 {
			myWaitReady = prevWaitReady
		}
		if g < numGroups-1 {
			ch := make(chan struct{})
			mySignalReady = ch
			prevWaitReady = ch
		}

		ids := make([]int, workersPerGroup)
		for i := range ids {
			ids[i] = workerIDCounter
			workerIDCounter++
		}

		gID := g + 1
		cycle := time.Duration(defaultCycleSecs) * time.Second
		var cc chan<- string
		if isFirst {
			cc = configCh
		}

		wg.Add(1)
		go func(groupID int, cycleDir time.Duration, isFirstGroup bool, configChan chan<- string, workerIds []int, startHashIndex int, waitR <-chan struct{}, sigR chan<- struct{}) {
			defer wg.Done()
			WorkerGroup(ctx, groupID, startHashIndex, tp, peer, disp, localPort,
				isFirstGroup, configChan, workerIds, cycleDir, &pauseFlag, *deviceID, *connPassword, stats, waitR, sigR)
		}(gID, cycle, isFirst, cc, ids, g, myWaitReady, mySignalReady)
	}

	wg.Wait()
	close(configCh)
	<-configDone
	log.Println("[КЛИЕНТ] Все воркеры завершены")
}
