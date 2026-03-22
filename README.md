<div align="center">

# WDTT — WireGuard over TURN Tunnel
<img width="192" height="192" alt="ic_launcher" src="https://github.com/user-attachments/assets/3712b08f-27be-4a7c-88f2-817a81a9a320" />

![Go](https://img.shields.io/badge/Go-1.21+-00ADD8?style=for-the-badge&logo=go&logoColor=white)
![Android](https://img.shields.io/badge/Android-SDK_26+-3DDC84?style=for-the-badge&logo=android&logoColor=white)
![License](https://img.shields.io/badge/License-GPL_3.0-blue?style=for-the-badge&logo=gnu&logoColor=white)
![WireGuard](https://img.shields.io/badge/WireGuard-88171A?style=for-the-badge&logo=wireguard&logoColor=white)

---

</div>

## 🧠 Идея

> Операторы связи и ТСПУ активно блокируют VPN-трафик. Но звонки в VK — это **святое**.

WDTT прокладывает WireGuard-туннель **внутри** DTLS-соединения через TURN-серверы VK. Для оператора ваш трафик неотличим от обычного видеозвонка — тот же протокол, те же серверы, те же порты.

## 🏗 Схема

```
╔═══════════════╗        DTLS / UDP      ╔═══════════════╗       UDP     ╔══════════════════╗
║               ║   (имитация звонка VK) ║               ║   (WireGuard) ║                  ║
║   📱 Клиент   ║ ════════════════════► ║  ☁️  VK TURN  ║ ════════════► ║  🖥  Ваш VPS     ║ ══► 🌍
║   (Android)   ║       155.212.*.*      ║     Серверы   ║               ║     (Server)     ║
╚═══════════════╝                        ╚═══════════════╝               ╚══════════════════╝
```

### Что видит DPI

```diff
- Без WDTT:   Клиент ──── [VPN трафик] ──────────── VPS          ← ❌ блокируется
+ С WDTT:     Клиент ──── [DTLS "звонок"] ────── 155.212.*.*     ← ✅ звонок в VK
```

## ⚠️ Рекомендации

> [!WARNING]
> **Рекомендуемое количество соединений: 8–32.** Больше 32 — повышается риск обнаружения со стороны VK. Максимум (80) предусмотрен для экспериментов — на свой страх и риск.

## 🚀 Быстрый старт

### 1. VPS

Любой Linux VPS (Ubuntu/Debian) с публичным IP. После покупки провайдер пришлёт:

- 🌐 **IP-адрес** — например `185.x.x.x`
- 👤 **Логин** — обычно `root`
- 🔑 **Пароль** — из письма / личного кабинета

### 2. Хеш VK-звонка

```
1.  Откройте VK → создайте группу (или используйте существующую)
2.  Начните звонок в группе
3.  Скопируйте ссылку — она выглядит как vk.com/call/join/xxxxxxxxxxx
4.  Ваш хеш — код после последнего слэша (xxxxxxxxxxx)
```

> [!IMPORTANT]
> При выходе из звонка нажмите **«Просто завершить»**, а не «Завершить для всех» — иначе звонок закроется и хеш перестанет работать.

### 3. Установка сервера (из приложения)

```
1.  Откройте вкладку «Deploy»
2.  Введите IP, логин и пароль VPS
3.  Задайте пароль туннеля в «Секретах»
4.  Нажмите «Установить»
```

### 4. Подключение

```
1.  Введите хеш VK-звонка
2.  Введите пароль туннеля
3.  Нажмите «Подключить»
```

## 📄 Лицензия

<div align="center">

**[GNU General Public License v3.0](LICENSE)**

---
<img width="2000" height="1078" alt="Blank 4 Grids Collage (1)" src="https://github.com/user-attachments/assets/8f65f9d9-4873-4ad5-941c-f3abd8a50d9a" />

**Сделано для свободного интернета** 🕊

</div>
