#!/bin/bash
# ==============================================================================
#  WDTT VPN Server — Универсальный установщик для VPS
#  Поддержка: Debian 11+, Ubuntu 20.04+, CentOS/RHEL/Fedora/AlmaLinux/Rocky
#  Версия: 3.2  |  Дата: 2026-05-13
#  NAT:  MASQUERADE через iptables
#  WG:   порт 56001 (не конфликтует с существующим WG на 51820)
#  DTLS: порт 56000
# ==============================================================================
set -uo pipefail

readonly SCRIPT_VERSION="3.2"
readonly LOG_FILE="/var/log/wdtt-install.log"
readonly WG_PORT="${WDTT_WG_PORT:-56001}"
readonly DTLS_PORT="${WDTT_DTLS_PORT:-56000}"
readonly SSH_PORT="${WDTT_SSH_PORT:-22}"
readonly WDTT_ARGS="${WDTT_ARGS:-}"
readonly WDTT_IFACE="wdtt0"
readonly WDTT_CONFIG_DIR="/etc/wdtt"
readonly WDTT_ACCESS_DB="passwords.json"
readonly IPT_COMMENT="WDTT_MANAGED"
readonly IPT_MIRROR_COMMENT="WDTT_MIRRORED"

validate_port() {
    local name="$1" value="$2"
    case "$value" in
        ''|*[!0-9]*) die "$name должен быть числом от 1 до 65535, получено: $value" ;;
    esac
    if [ "$value" -lt 1 ] || [ "$value" -gt 65535 ]; then
        die "$name должен быть в диапазоне 1..65535, получено: $value"
    fi
}

# ─── Цвета ───────────────────────────────────────────────────────────────────
C_GREEN=''; C_YELLOW=''; C_RED=''
C_CYAN='';  C_BOLD='';      C_NC=''

log_info()  { echo -e "${C_GREEN}[✓]${C_NC} $*" | tee -a "$LOG_FILE"; }
log_warn()  { echo -e "${C_YELLOW}[!]${C_NC} $*" | tee -a "$LOG_FILE"; }
log_error() { echo -e "${C_RED}[✗]${C_NC} $*" | tee -a "$LOG_FILE"; }
log_step()  { echo -e "${C_CYAN}[►]${C_NC} ${C_BOLD}$*${C_NC}" | tee -a "$LOG_FILE"; }

die() { log_error "$*"; exit 1; }

prog() { echo "WDTT_PROGRESS|$1|$2"; }

# ─── Проверка root ────────────────────────────────────────────────────────────
check_root() {
    if [ "$(id -u)" -ne 0 ]; then
        die "Скрипт должен быть запущен от root. Если sudo отсутствует, зайдите под root и запустите: bash $0 $*"
    fi
}

# ─── Определение ОС ──────────────────────────────────────────────────────────
OS_ID="" ; PKG_MGR=""

detect_os() {
    log_step "Определение операционной системы..."
    if [ ! -f /etc/os-release ]; then
        die "Файл /etc/os-release не найден."
    fi
    . /etc/os-release
    OS_ID="${ID:-unknown}"
    case "$OS_ID" in
        ubuntu|debian|linuxmint|pop)     PKG_MGR="apt" ;;
        centos|rhel|rocky|almalinux|oracle) PKG_MGR="yum"
            command -v dnf &>/dev/null && PKG_MGR="dnf" ;;
        fedora)                          PKG_MGR="dnf" ;;
        arch|manjaro|endeavouros)        PKG_MGR="pacman" ;;
        *) die "Неподдерживаемый дистрибутив: $OS_ID" ;;
    esac
    log_info "ОС: ${PRETTY_NAME:-$OS_ID} | PM: $PKG_MGR"
}

# ─── Пакеты ──────────────────────────────────────────────────────────────────
pkg_update_done=0

pkg_update() {
    [ "$pkg_update_done" = "1" ] && return 0
    log_step "Обновление индексов пакетов..."
    case "$PKG_MGR" in
        apt)
            export DEBIAN_FRONTEND=noninteractive
            apt-get update -y >>"$LOG_FILE" 2>&1 || log_warn "apt update завершился с ошибкой, пробую продолжить"
            ;;
        dnf)    dnf makecache -y >>"$LOG_FILE" 2>&1 || true ;;
        yum)    yum makecache -y >>"$LOG_FILE" 2>&1 || true ;;
        pacman) pacman -Sy --noconfirm >>"$LOG_FILE" 2>&1 || true ;;
    esac
    pkg_update_done=1
}

pkg_install() {
    [ "$#" -eq 0 ] && return 0
    case "$PKG_MGR" in
        apt)
            export DEBIAN_FRONTEND=noninteractive
            apt-get install -y -qq "$@" >>"$LOG_FILE" 2>&1
            ;;
        dnf)    dnf install -y "$@" >>"$LOG_FILE" 2>&1 ;;
        yum)    yum install -y "$@" >>"$LOG_FILE" 2>&1 ;;
        pacman) pacman -S --noconfirm --needed "$@" >>"$LOG_FILE" 2>&1 ;;
    esac
}

install_prerequisites() {
    prog 0.08 "Пакеты..."
    pkg_update
    log_step "Установка базовых зависимостей..."

    case "$PKG_MGR" in
        apt)
            pkg_install ca-certificates iproute2 iptables nftables procps psmisc || \
                log_warn "Часть apt-пакетов не установилась, продолжаю с доступными утилитами"
            ;;
        dnf|yum)
            pkg_install ca-certificates iproute iptables nftables procps-ng psmisc || \
                log_warn "Часть rpm-пакетов не установилась, продолжаю с доступными утилитами"
            ;;
        pacman)
            pkg_install ca-certificates iproute2 iptables nftables procps-ng psmisc || \
                log_warn "Часть pacman-пакетов не установилась, продолжаю с доступными утилитами"
            ;;
    esac
}

require_runtime_tools() {
    command -v ip >/dev/null 2>&1 || die "Команда ip не найдена. Установите iproute2/iproute."
    command -v systemctl >/dev/null 2>&1 || die "systemctl не найден. Нужен VPS с systemd."
}

# ─── Автоопределение WAN-интерфейса ──────────────────────────────────────────
detect_wan_interface() {
    local iface=""
    iface=$(ip route show default 2>/dev/null | head -1 | awk '{for(i=1;i<=NF;i++) if($i=="dev") print $(i+1)}')
    [ -z "$iface" ] && iface=$(ip -4 addr show scope global 2>/dev/null | grep -oP '(?<=dev )\S+' | head -1)
    [ -z "$iface" ] && iface=$(ls /sys/class/net/ | grep -v lo | head -1)
    echo "$iface"
}

# ─── Firewall helpers ────────────────────────────────────────────────────────
FW_BACKEND=""

iptables_add_input() {
    local proto="$1" port="$2" comment="$3"
    [ "$FW_BACKEND" = "iptables" ] || return 0
    case "$proto:$port" in
        tcp:[0-9]*|udp:[0-9]*) ;;
        *) return 0 ;;
    esac
    [ "$port" -ge 1 ] 2>/dev/null && [ "$port" -le 65535 ] 2>/dev/null || return 0
    iptables -C INPUT -p "$proto" --dport "$port" -m comment --comment "$comment" -j ACCEPT 2>/dev/null || \
        iptables -I INPUT -p "$proto" --dport "$port" -m comment --comment "$comment" -j ACCEPT 2>/dev/null || true
}

mirror_port_to_iptables() {
    local proto="$1" port="$2" source="$3"
    iptables_add_input "$proto" "$port" "$IPT_MIRROR_COMMENT"
    log_info "iptables: сохранён доступ $port/$proto из $source"
}

mirror_existing_firewall_ports_to_iptables() {
    [ "$FW_BACKEND" = "iptables" ] || return 0
    local tmp
    tmp="$(mktemp)"

    if command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -qi "Status: active"; then
        log_info "UFW активен: переношу разрешённые tcp/udp порты в iptables"
        ufw status 2>/dev/null | sed -nE 's#^([0-9]{1,5})/(tcp|udp)[[:space:]].*ALLOW IN.*#\2 \1 ufw#p' >> "$tmp" || true
    fi

    if command -v nft >/dev/null 2>&1; then
        local nft_ports
        nft_ports="$(nft -a list ruleset 2>/dev/null | sed -nE 's/.*(tcp|udp) dport ([0-9]{1,5}).*accept.*/\1 \2 nft/p' | sort -u || true)"
        if [ -n "$nft_ports" ]; then
            log_info "nftables найден: переношу простые accept dport правила в iptables"
            printf '%s\n' "$nft_ports" >> "$tmp"
        fi
    fi

    if [ -s "$tmp" ]; then
        sort -u "$tmp" | while read -r proto port source; do
            mirror_port_to_iptables "$proto" "$port" "$source"
        done
    else
        log_info "UFW/nftables разрешённых tcp/udp портов для переноса не найдено"
    fi
    rm -f "$tmp"
}

detect_firewall() {
    if ! command -v iptables &>/dev/null; then
        log_warn "iptables не найден. Пытаюсь установить firewall-пакеты..."
        pkg_update
        pkg_install iptables nftables || true
    fi
    if command -v iptables &>/dev/null; then
        FW_BACKEND="iptables"
        log_info "Firewall backend: iptables (принудительно)"
        mirror_existing_firewall_ports_to_iptables
    else
        FW_BACKEND="none"
        log_warn "iptables не найден. Установка продолжится, но NAT/firewall нужно настроить вручную."
    fi
}

# ─── Firewall-абстракция ─────────────────────────────────────────────────────
fw_add_input_udp() {
    local port="$1"
    case "$FW_BACKEND" in
        iptables)
            iptables -C INPUT -p udp --dport "$port" -m comment --comment "$IPT_COMMENT" -j ACCEPT 2>/dev/null || \
                iptables -I INPUT -p udp --dport "$port" -m comment --comment "$IPT_COMMENT" -j ACCEPT 2>/dev/null || true
            ;;
        nft)
            ensure_nft_wdtt
            nft add rule inet wdtt input udp dport "$port" accept 2>/dev/null || true
            ;;
        none) ;;
    esac
}

fw_add_input_tcp() {
    local port="$1"
    case "$FW_BACKEND" in
        iptables)
            iptables -C INPUT -p tcp --dport "$port" -m comment --comment "$IPT_COMMENT" -j ACCEPT 2>/dev/null || \
                iptables -I INPUT -p tcp --dport "$port" -m comment --comment "$IPT_COMMENT" -j ACCEPT 2>/dev/null || true
            ;;
        nft)
            ensure_nft_wdtt
            nft add rule inet wdtt input tcp dport "$port" accept 2>/dev/null || true
            ;;
        none) ;;
    esac
}

fw_add_input_udp_range() {
    local from="$1" to="$2"
    case "$FW_BACKEND" in
        iptables|nft) log_warn "Пропускаю широкий UDP range $from-$to: это не изолировано и может влиять на чужие сервисы." ;;
        none) ;;
    esac
}

fw_add_forward() {
    case "$FW_BACKEND" in
        iptables)
            iptables -C FORWARD -i "$WDTT_IFACE" -m comment --comment "$IPT_COMMENT" -j ACCEPT 2>/dev/null || \
                iptables -I FORWARD -i "$WDTT_IFACE" -m comment --comment "$IPT_COMMENT" -j ACCEPT 2>/dev/null || true
            iptables -C FORWARD -o "$WDTT_IFACE" -m comment --comment "$IPT_COMMENT" -j ACCEPT 2>/dev/null || \
                iptables -I FORWARD -o "$WDTT_IFACE" -m comment --comment "$IPT_COMMENT" -j ACCEPT 2>/dev/null || true
            ;;
        nft)
            ensure_nft_wdtt
            nft add rule inet wdtt forward iifname "$WDTT_IFACE" accept 2>/dev/null || true
            nft add rule inet wdtt forward oifname "$WDTT_IFACE" accept 2>/dev/null || true
            ;;
        none) ;;
    esac
}

fw_add_masquerade() {
    local iface="$1" subnet="$2"
    case "$FW_BACKEND" in
        iptables)
            iptables -t nat -C POSTROUTING -s "$subnet" -o "$iface" -m comment --comment "$IPT_COMMENT" -j MASQUERADE 2>/dev/null || \
                iptables -t nat -A POSTROUTING -s "$subnet" -o "$iface" -m comment --comment "$IPT_COMMENT" -j MASQUERADE 2>/dev/null || true
            ;;
        nft)
            nft add table ip wdtt 2>/dev/null || true
            nft add chain ip wdtt postrouting '{ type nat hook postrouting priority 100; }' 2>/dev/null || true
            nft add rule ip wdtt postrouting ip saddr "$subnet" oifname "$iface" masquerade 2>/dev/null || true
            ;;
        none) ;;
    esac
}

fw_add_mss_clamping() {
    local subnet="$1"
    case "$FW_BACKEND" in
        iptables)
            # Применяем правило ТОЛЬКО к нашей подсети WDTT
            iptables -t mangle -C FORWARD -s "$subnet" -p tcp -m tcp --tcp-flags SYN,RST SYN -m comment --comment "$IPT_COMMENT" -j TCPMSS --clamp-mss-to-pmtu 2>/dev/null || \
                iptables -t mangle -I FORWARD -s "$subnet" -p tcp -m tcp --tcp-flags SYN,RST SYN -m comment --comment "$IPT_COMMENT" -j TCPMSS --clamp-mss-to-pmtu 2>/dev/null || true
            iptables -t mangle -C FORWARD -d "$subnet" -p tcp -m tcp --tcp-flags SYN,RST SYN -m comment --comment "$IPT_COMMENT" -j TCPMSS --clamp-mss-to-pmtu 2>/dev/null || \
                iptables -t mangle -I FORWARD -d "$subnet" -p tcp -m tcp --tcp-flags SYN,RST SYN -m comment --comment "$IPT_COMMENT" -j TCPMSS --clamp-mss-to-pmtu 2>/dev/null || true
            ;;
        nft)
            nft add table inet wdtt_mangle 2>/dev/null || true
            nft add chain inet wdtt_mangle forward '{ type filter hook forward priority -150; policy accept; }' 2>/dev/null || true
            nft add rule inet wdtt_mangle forward ip saddr "$subnet" tcp flags syn tcp option maxseg size set rt mtu 2>/dev/null || true
            nft add rule inet wdtt_mangle forward ip daddr "$subnet" tcp flags syn tcp option maxseg size set rt mtu 2>/dev/null || true
            ;;
        none) ;;
    esac
}

fw_add_established() {
    return 0
}

fw_cleanup_wdtt_rules() {
    local iface="$1"
    if command -v iptables >/dev/null 2>&1; then
        for i in {1..5}; do
            local nat_iface
            for nat_iface in "$iface" $(ls /sys/class/net 2>/dev/null || true); do
                [ -n "$nat_iface" ] && iptables -t nat -D POSTROUTING -s 10.66.66.0/24 -o "$nat_iface" -m comment --comment "$IPT_COMMENT" -j MASQUERADE 2>/dev/null || true
            done
            iptables -t mangle -D FORWARD -s 10.66.66.0/24 -p tcp -m tcp --tcp-flags SYN,RST SYN -m comment --comment "$IPT_COMMENT" -j TCPMSS --clamp-mss-to-pmtu 2>/dev/null || true
            iptables -t mangle -D FORWARD -d 10.66.66.0/24 -p tcp -m tcp --tcp-flags SYN,RST SYN -m comment --comment "$IPT_COMMENT" -j TCPMSS --clamp-mss-to-pmtu 2>/dev/null || true
            iptables -D INPUT -p udp --dport ${DTLS_PORT} -m comment --comment "$IPT_COMMENT" -j ACCEPT 2>/dev/null || true
            iptables -D INPUT -p udp --dport ${WG_PORT} -m comment --comment "$IPT_COMMENT" -j ACCEPT 2>/dev/null || true
            iptables -D INPUT -p tcp --dport ${SSH_PORT} -m comment --comment "$IPT_COMMENT" -j ACCEPT 2>/dev/null || true
            iptables -D INPUT -p tcp --dport 22 -m comment --comment "$IPT_COMMENT" -j ACCEPT 2>/dev/null || true
            iptables -D FORWARD -i "$WDTT_IFACE" -m comment --comment "$IPT_COMMENT" -j ACCEPT 2>/dev/null || true
            iptables -D FORWARD -o "$WDTT_IFACE" -m comment --comment "$IPT_COMMENT" -j ACCEPT 2>/dev/null || true
        done
    fi
    if command -v nft >/dev/null 2>&1; then
        nft delete table ip wdtt 2>/dev/null || true
        nft delete table inet wdtt 2>/dev/null || true
        nft delete table inet wdtt_mangle 2>/dev/null || true
    fi
}

cleanup_config_dir_keep_access_db() {
    [ -d "$WDTT_CONFIG_DIR" ] || return 0
    find "$WDTT_CONFIG_DIR" -mindepth 1 -maxdepth 1 ! -name "$WDTT_ACCESS_DB" -exec rm -rf {} + 2>/dev/null || true
    [ -f "$WDTT_CONFIG_DIR/$WDTT_ACCESS_DB" ] && chmod 600 "$WDTT_CONFIG_DIR/$WDTT_ACCESS_DB" 2>/dev/null || true
}

# ══════════════════════════════════════════════════════════════════════════════
#  WDTT VPN SERVER DEPLOYMENT
# ══════════════════════════════════════════════════════════════════════════════

# ─── Очистка старого WDTT ─────────────────────────────────────────────────────
wdtt_cleanup() {
    prog 0.05 "Очистка..."
    echo "🧹 Очистка старой установки WDTT..."

    systemctl unmask wdtt 2>/dev/null || true
    systemctl stop wdtt 2>/dev/null || true
    systemctl disable wdtt 2>/dev/null || true
    rm -f /etc/systemd/system/wdtt.service 2>/dev/null || true
    systemctl daemon-reload 2>/dev/null || true
    pkill -x wdtt-server 2>/dev/null || killall wdtt-server 2>/dev/null || true

    # Удаляем только собственный интерфейс WDTT.
    ip link show "$WDTT_IFACE" >/dev/null 2>&1 && ip link del "$WDTT_IFACE" 2>/dev/null || true

    # Удаляем старые правила NAT для WDTT подсети
    fw_cleanup_wdtt_rules "$(detect_wan_interface)"

    rm -f /usr/local/bin/wdtt-server 2>/dev/null || true
    cleanup_config_dir_keep_access_db

    echo "✓ Очистка завершена (база доступа сохранена)"
}

# ─── Sysctl тюнинг ───────────────────────────────────────────────────────────
setup_sysctl() {
    prog 0.20 "Sysctl..."
    echo "⚙️  Настройка сетевых параметров..."

    echo 1 > /proc/sys/net/ipv4/ip_forward 2>/dev/null || true
    mkdir -p /etc/sysctl.d
    cat > /etc/sysctl.d/99-wdtt.conf << 'SYSEOF'
net.ipv4.ip_forward = 1
SYSEOF

    sysctl -p /etc/sysctl.d/99-wdtt.conf >/dev/null 2>&1 || true

    echo "✓ Sysctl настроен"
}

# ─── Настройка NAT + Firewall ─────────────────────────────────────────────────
setup_nat_and_firewall() {
    prog 0.40 "NAT + Firewall..."
    echo "🛡  Настройка NAT и фаервола..."

    local iface
    iface=$(detect_wan_interface)

    if [ -z "$iface" ]; then
        log_warn "Не удалось определить WAN-интерфейс!"
        log_warn "Настройте NAT вручную для подсети 10.66.66.0/24."
        return 0
    fi

    log_info "WAN-интерфейс: $iface"

    # === WDTT порты ===
    fw_add_input_udp "$DTLS_PORT"   # 56000 — DTLS сервер
    fw_add_input_udp "$WG_PORT"     # 56001 — WireGuard
    fw_add_input_tcp "$SSH_PORT"    # SSH порт, указанный пользователем в приложении

    # === Forward ===
    fw_add_forward

    # === NAT: MASQUERADE для подсети WireGuard ===
    fw_add_masquerade "$iface" "10.66.66.0/24"
    
    # === MSS Clamping для исправления MTU (DonationAlerts / Cloudflare) ===
    fw_add_mss_clamping "10.66.66.0/24"

    if [ "$FW_BACKEND" = "none" ]; then
        echo "⚠ NAT не настроен автоматически: firewall-бэкенд отсутствует"
    else
        echo "✓ NAT: MASQUERADE на $iface для 10.66.66.0/24"
    fi
    echo "✓ Порты: ${DTLS_PORT}/udp(DTLS), ${WG_PORT}/udp(WG), ${SSH_PORT}/tcp(SSH)"
    echo "✓ TCP MSS Clamping включен"
}

# ─── Установка бинарника wdtt-server ──────────────────────────────────────────
setup_wdtt_binary() {
    prog 0.60 "Бинарник..."
    echo "📦 Установка wdtt-server..."

    if [ -f /tmp/wdtt-server ]; then
        chmod +x /tmp/wdtt-server
        install -m 0755 /tmp/wdtt-server /usr/local/bin/wdtt-server 2>/dev/null || mv /tmp/wdtt-server /usr/local/bin/wdtt-server
        echo "✓ wdtt-server установлен"
    elif [ -f /usr/local/bin/wdtt-server ]; then
        echo "✓ wdtt-server уже установлен"
    else
        echo "⚠ wdtt-server не найден в /tmp/ — пропускаем"
        echo "  Загрузите бинарник вручную в /usr/local/bin/wdtt-server"
    fi

    mkdir -p "$WDTT_CONFIG_DIR"
}

# ─── Systemd-сервис WDTT ─────────────────────────────────────────────────────
setup_wdtt_service() {
    prog 0.75 "Сервис..."
    echo "🔧 Создание systemd-сервиса WDTT..."

    cat > /etc/systemd/system/wdtt.service << WDTTSVC
[Unit]
Description=WDTT VPN Server
After=network.target network-online.target
Wants=network-online.target

[Service]
Type=simple
ExecStartPre=-/usr/bin/env bash -c "ip link show ${WDTT_IFACE} >/dev/null 2>&1 && ip link del ${WDTT_IFACE} 2>/dev/null || true"
ExecStartPre=-/usr/bin/env bash -c "if command -v iptables >/dev/null 2>&1; then iptables -C INPUT -p udp --dport ${DTLS_PORT} -m comment --comment ${IPT_COMMENT} -j ACCEPT 2>/dev/null || iptables -I INPUT -p udp --dport ${DTLS_PORT} -m comment --comment ${IPT_COMMENT} -j ACCEPT; iptables -C INPUT -p udp --dport ${WG_PORT} -m comment --comment ${IPT_COMMENT} -j ACCEPT 2>/dev/null || iptables -I INPUT -p udp --dport ${WG_PORT} -m comment --comment ${IPT_COMMENT} -j ACCEPT; iptables -C INPUT -p tcp --dport ${SSH_PORT} -m comment --comment ${IPT_COMMENT} -j ACCEPT 2>/dev/null || iptables -I INPUT -p tcp --dport ${SSH_PORT} -m comment --comment ${IPT_COMMENT} -j ACCEPT; fi"
ExecStart=/usr/local/bin/wdtt-server -listen 0.0.0.0:${DTLS_PORT} -wg-port ${WG_PORT} -config-dir ${WDTT_CONFIG_DIR} ${WDTT_ARGS}
Restart=always
RestartSec=5
LimitNOFILE=65535

[Install]
WantedBy=multi-user.target
WDTTSVC

    systemctl daemon-reload
    systemctl unmask wdtt >/dev/null 2>&1 || true
    systemctl enable wdtt >/dev/null 2>&1 || true
    echo "✓ Сервис wdtt.service создан и включён"
}

# ─── Запуск WDTT ─────────────────────────────────────────────────────────────
start_wdtt() {
    prog 0.90 "Запуск..."
    echo "🚀 Запуск WDTT VPN Server..."

    if [ ! -f /usr/local/bin/wdtt-server ]; then
        echo "⚠ wdtt-server не установлен — запуск пропущен"
        return 0
    fi

    systemctl restart wdtt

    sleep 2
    local status
    status=$(systemctl is-active wdtt 2>/dev/null || echo "unknown")

    prog 1.0 "Готово!"

    echo ""
    echo "══════════════════════════════════════════════════════════════"

    if [ "$status" = "active" ]; then
        echo "✅ Деплой успешно завершён!"
        echo "   NAT:  MASQUERADE (стандартный)"
        echo "   DTLS: порт ${DTLS_PORT}"
        echo "   WG:   порт ${WG_PORT}"
        echo "   SSH:  порт ${SSH_PORT}"
    else
        echo "⚠️ Сервис wdtt не запустился. Статус: $status"
        echo "   Последние логи:"
        journalctl -u wdtt -n 7 --no-pager 2>/dev/null | sed 's/^/   >> /'
    fi

    echo "   Логи:   journalctl -u wdtt -f"
    echo "   Статус: systemctl status wdtt"
    echo "══════════════════════════════════════════════════════════════"
    echo ""
}

# ─── Команда: uninstall ──────────────────────────────────────────────────────
do_uninstall() {
    log_step "Удаление WDTT..."

    systemctl stop wdtt 2>/dev/null || true
    systemctl disable wdtt 2>/dev/null || true
    rm -f /etc/systemd/system/wdtt.service
    systemctl daemon-reload

    ip link show "$WDTT_IFACE" >/dev/null 2>&1 && ip link del "$WDTT_IFACE" 2>/dev/null || true
    pkill -x wdtt-server 2>/dev/null || true

    fw_cleanup_wdtt_rules "$(detect_wan_interface)"

    rm -f /usr/local/bin/wdtt-server
    cleanup_config_dir_keep_access_db
    rm -f /etc/sysctl.d/99-wdtt.conf
    sysctl --system >/dev/null 2>&1 || true

    log_info "WDTT удалён. База доступа сохранена: ${WDTT_CONFIG_DIR}/${WDTT_ACCESS_DB}"
}

# ─── Команда: status ─────────────────────────────────────────────────────────
do_status() {
    echo "Статус WDTT:"
    echo ""
    if systemctl is-active wdtt &>/dev/null; then
        log_info "Сервис: АКТИВЕН"
    else
        log_warn "Сервис: НЕ АКТИВЕН"
    fi
    if [ -f /usr/local/bin/wdtt-server ]; then
        log_info "Бинарник: установлен"
    else
        log_warn "Бинарник: НЕ найден"
    fi
    if ip link show "$WDTT_IFACE" &>/dev/null; then
        log_info "WDTT интерфейс ($WDTT_IFACE): активен"
    else
        log_warn "WDTT интерфейс ($WDTT_IFACE): не активен"
    fi
}

# ══════════════════════════════════════════════════════════════════════════════
#  MAIN
# ══════════════════════════════════════════════════════════════════════════════
main() {
    echo "╔══════════════════════════════════════════════════════════════╗"
    echo "║       WDTT VPN Server — Installer v${SCRIPT_VERSION}                    ║"
    echo "║       DTLS: ${DTLS_PORT}  |  WG: ${WG_PORT}  |  SSH: ${SSH_PORT}       ║"
    echo "╚══════════════════════════════════════════════════════════════╝"

    local action="${1:-install}"
    check_root
    validate_port "WDTT_DTLS_PORT" "$DTLS_PORT"
    validate_port "WDTT_WG_PORT" "$WG_PORT"
    validate_port "WDTT_SSH_PORT" "$SSH_PORT"

    mkdir -p "$(dirname "$LOG_FILE")"
    echo "=== WDTT Installer v${SCRIPT_VERSION} — $(date) ===" >> "$LOG_FILE"

    detect_os
    install_prerequisites
    require_runtime_tools
    detect_firewall

    case "$action" in
        status|--status|-s)       do_status ;;
        uninstall|--uninstall|-u) do_uninstall ;;
        install|--install|-i|*)
            wdtt_cleanup
            setup_sysctl
            setup_nat_and_firewall
            setup_wdtt_binary
            setup_wdtt_service
            start_wdtt
            ;;
    esac
}

main "$@"
