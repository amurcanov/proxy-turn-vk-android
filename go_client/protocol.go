package main

import (
	"fmt"
	"net"
	"strings"
	"time"
)

// RequestConfig запрашивает WireGuard конфиг через DTLS-соединение.
func RequestConfig(conn net.Conn, localPort, deviceID, password string) (string, error) {
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

	return resp, nil
}


