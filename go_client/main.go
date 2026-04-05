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

var (
	vkAppID     atomic.Value // string
	vkAppSecret atomic.Value // string
	captchaMode atomic.Value // string — "reverse_js" или "webview"
)

// CaptchaSolver — канал для получения токена капчи из внешнего решателя (WebView)
// Формат: "token:<success_token>" или "error:<message>"
var CaptchaResultCh = make(chan string, 1)

// drainCaptchaResult удаляет устаревший результат капчи из канала (если остался)
func drainCaptchaResult() {
	select {
	case <-CaptchaResultCh:
	default:
	}
}

func init() {
	vkAppID.Store("6287487")
	vkAppSecret.Store("QbYic1K3lEV5kTGiqlq2")
}

func main() {
	log.SetFlags(log.Ldate | log.Ltime | log.Lmicroseconds)

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

	var pauseFlag int32 // 0 = активен, 1 = пауза (Doze-mode)

	// STDIN для PAUSE/RESUME/STOP (Doze-mode) и CAPTCHA_RESULT (WebView mode)
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
				// Формат: CAPTCHA_RESULT|token или CAPTCHA_RESULT|error:msg
				result := strings.TrimPrefix(line, "CAPTCHA_RESULT|")
				// Дренируем старый результат, если он не был прочитан
				drainCaptchaResult()
				// Гарантированная запись нового результата
				CaptchaResultCh <- result
				log.Printf("[КАПЧА] Результат от Kotlin записан в канал")
			}
		}
	}()

	host := flag.String("turn", "", "переопределить IP TURN")
	port := flag.String("port", "", "переопределить порт TURN")
	listen := flag.String("listen", "127.0.0.1:9000", "локальный адрес")
	vkHash := flag.String("vk", "", "хеши VK-звонков (через запятую)")
	secondaryHash := flag.String("vk2", "", "запасной VK хеш")
	peerAddr := flag.String("peer", "", "адрес:порт VPS сервера")
	numW := flag.Int("n", 24, "количество воркеров (кратно 12)")
	useTCP := flag.Bool("tcp", false, "TURN через TCP")
	useUDP := flag.Bool("udp", false, "TURN через UDP")
	splitTunnel := flag.Bool("split", false, "split tunneling")
	sni := flag.String("sni", "", "SNI для DTLS")
	noDns := flag.Bool("nodns", false, "отключить DNS Яндекса")

	appID := flag.String("vk-app-id", "6287487", "VK App ID")
	appSecret := flag.String("vk-app-secret", "QbYic1K3lEV5kTGiqlq2", "VK App Secret")

	deviceID := flag.String("device-id", "unknown", "уникальный ID устройства")
	userAgent := flag.String("user-agent", "", "User-Agent строка устройства")
	connPassword := flag.String("password", "", "пароль подключения")
	captchaModeFlag := flag.String("captcha-mode", "rjs", "режим капчи: rjs или wv")

	flag.Parse()

	vkAppID.Store(*appID)
	vkAppSecret.Store(*appSecret)
	captchaMode.Store(*captchaModeFlag)
	SetCaptchaModeEnv(*captchaModeFlag)
	noDnsFlag.Store(*noDns)
	SetUserAgent(*userAgent)

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

	// Протокол по умолчанию
	if !*useTCP && !*useUDP {
		*useTCP = true
	}

	// Лимит воркеров
	maxWorkers := 72
	if *numW > maxWorkers {
		*numW = maxWorkers
	}
	if *numW < workersPerGroup {
		*numW = workersPerGroup
	}
	*numW = (*numW / workersPerGroup) * workersPerGroup

	tp := &TurnParams{
		Host:          *host,
		Port:          *port,
		Hashes:        hashes,
		SecondaryHash: strings.TrimSpace(*secondaryHash),
		Sni:           *sni,
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
	ValidateSchedule(numGroups)

	log.Println("[КЛИЕНТ] ═══════════════════════════════════════")
	log.Printf("[КЛИЕНТ] VK App: %s", *appID)
	log.Printf("[КЛИЕНТ] Воркеров: %d (групп: %d, по %d)", *numW, numGroups, workersPerGroup)
	log.Printf("[КЛИЕНТ] Хешей: %d", len(hashes))
	log.Printf("[КЛИЕНТ] Слушаю: %s | Пир: %s", *listen, *peerAddr)
	proto := "TCP"
	if *useUDP {
		proto = "UDP"
	}
	log.Printf("[КЛИЕНТ] Протокол: %s", proto)
	log.Printf("[КЛИЕНТ] Device ID: %s", *deviceID)
	log.Printf("[КЛИЕНТ] Обход капчи: %s", captchaMode.Load().(string))
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
			if *splitTunnel {
				finalConf = ModifyConfigForSplitTunnel(finalConf, peer.IP)
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

	for g := 0; g < numGroups; g++ {
		isFirst := (g == 0)

		ids := make([]int, workersPerGroup)
		for i := range ids {
			ids[i] = workerIDCounter
			workerIDCounter++
		}

		gID := g + 1
		initialDelay, cycle := ComputeGroupTiming(g)

		var cc chan<- string
		if isFirst {
			cc = configCh
		}

		wg.Add(1)
		go func(groupID int, startDelay, cycleDir time.Duration, isFirstGroup bool, configChan chan<- string, workerIds []int, startHashIndex int) {
			defer wg.Done()
			WorkerGroup(ctx, groupID, startHashIndex, tp, peer, disp, localPort, *useUDP,
				isFirstGroup, configChan, workerIds, startDelay, cycleDir, &pauseFlag, *deviceID, *connPassword, stats)
		}(gID, initialDelay, cycle, isFirst, cc, ids, g)
	}

	wg.Wait()
	close(configCh)
	<-configDone
	log.Println("[КЛИЕНТ] Все воркеры завершены")
}
