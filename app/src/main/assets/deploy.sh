#!/bin/bash
# ==============================================================================
#  xt_FULLCONENAT — Универсальный установщик для VPS
#  Поддержка: ядра Linux 6.1 — 6.18.x (и 7.x экспериментально)
#  Дистрибутивы: Debian 11+, Ubuntu 20.04+, CentOS/RHEL/Fedora/AlmaLinux/Rocky
#  Версия: 2.0  |  Дата: 2026-03-21
# ==============================================================================
set -uo pipefail

readonly SCRIPT_VERSION="2.0"
readonly REPO_URL="https://github.com/Chion82/netfilter-full-cone-nat.git"
readonly DKMS_NAME="xt_FULLCONENAT"
readonly DKMS_VER="1.0"
readonly DKMS_SRC="/usr/src/${DKMS_NAME}-${DKMS_VER}"
readonly BUILD_DIR="/tmp/fullconenat-build-$$"
readonly LOG_FILE="/var/log/fullconenat-install.log"
ROLLBACK_STEPS=()

# ─── Цвета (отключены для чистых логов) ────────────────────────────────────────────────────────
C_GREEN=''; C_YELLOW=''; C_RED=''
C_CYAN='';  C_BOLD='';      C_NC=''

log_info()  { echo -e "${C_GREEN}[✓]${C_NC} $*" | tee -a "$LOG_FILE"; }
log_warn()  { echo -e "${C_YELLOW}[!]${C_NC} $*" | tee -a "$LOG_FILE"; }
log_error() { echo -e "${C_RED}[✗]${C_NC} $*" | tee -a "$LOG_FILE"; }
log_step()  { echo -e "${C_CYAN}[►]${C_NC} ${C_BOLD}$*${C_NC}" | tee -a "$LOG_FILE"; }

die() {
    log_error "$*"
    rollback
    exit 1
}

# ─── Rollback при ошибках ─────────────────────────────────────────────────────
rollback() {
    if [ ${#ROLLBACK_STEPS[@]} -eq 0 ]; then return; fi
    log_warn "Откат изменений..."
    for ((i=${#ROLLBACK_STEPS[@]}-1; i>=0; i--)); do
        eval "${ROLLBACK_STEPS[$i]}" 2>/dev/null || true
    done
    log_info "Откат завершён."
}

cleanup() { rm -rf "$BUILD_DIR" 2>/dev/null || true; }
trap cleanup EXIT

# ─── Баннер ───────────────────────────────────────────────────────────────────
show_banner() {
    echo -e "${C_CYAN}"
    echo "╔══════════════════════════════════════════════════════════════╗"
    echo "║       xt_FULLCONENAT — Universal Installer v${SCRIPT_VERSION}            ║"
    echo "║       Ядра Linux 6.1 — 6.18.x  |  Debian / Ubuntu         ║"
    echo "╚══════════════════════════════════════════════════════════════╝"
    echo -e "${C_NC}"
}

# ─── Проверка root ────────────────────────────────────────────────────────────
check_root() {
    if [ "$(id -u)" -ne 0 ]; then
        die "Скрипт должен быть запущен от root! Используйте: sudo bash $0 $*"
    fi
}

# ─── Обработка аргументов ─────────────────────────────────────────────────────
ACTION="install"
parse_args() {
    case "${1:-install}" in
        install|--install|-i)   ACTION="install" ;;
        uninstall|--uninstall|-u) ACTION="uninstall" ;;
        status|--status|-s)     ACTION="status" ;;
        rebuild|--rebuild|-r)   ACTION="rebuild" ;;
        help|--help|-h)
            echo "Использование: $0 [install|uninstall|status|rebuild|help]"
            echo "  install   — полная установка (по умолчанию)"
            echo "  uninstall — полное удаление модуля, DKMS, расширений"
            echo "  status    — проверка состояния установки"
            echo "  rebuild   — пересборка модуля для текущего ядра"
            exit 0 ;;
        *) die "Неизвестная команда: $1. Используйте --help" ;;
    esac
}

# ─── Определение ОС ──────────────────────────────────────────────────────────
OS_ID="" ; OS_NAME="" ; PKG_MGR=""

detect_os() {
    log_step "Определение операционной системы..."

    if [ ! -f /etc/os-release ]; then
        die "Файл /etc/os-release не найден. Неподдерживаемая ОС."
    fi

    . /etc/os-release
    OS_ID="${ID:-unknown}"
    OS_NAME="${PRETTY_NAME:-$OS_ID}"

    case "$OS_ID" in
        ubuntu|debian|linuxmint|pop)
            PKG_MGR="apt" ;;
        centos|rhel|rocky|almalinux|oracle)
            PKG_MGR="yum"
            command -v dnf &>/dev/null && PKG_MGR="dnf" ;;
        fedora)
            PKG_MGR="dnf" ;;
        arch|manjaro|endeavouros)
            PKG_MGR="pacman" ;;
        *)
            die "Неподдерживаемый дистрибутив: $OS_ID ($OS_NAME)" ;;
    esac

    log_info "ОС: ${OS_NAME} | Пакетный менеджер: ${PKG_MGR}"
}

# ─── Определение ядра ─────────────────────────────────────────────────────────
KERN_FULL="" ; KERN_MAJOR=0 ; KERN_MINOR=0 ; KERN_PATCH=0

detect_kernel() {
    log_step "Определение версии ядра..."

    KERN_FULL=$(uname -r)

    # Извлекаем major.minor.patch из строки вида 6.8.12-amd64
    if [[ "$KERN_FULL" =~ ^([0-9]+)\.([0-9]+)\.?([0-9]*) ]]; then
        KERN_MAJOR="${BASH_REMATCH[1]}"
        KERN_MINOR="${BASH_REMATCH[2]}"
        KERN_PATCH="${BASH_REMATCH[3]:-0}"
    else
        die "Не удалось распарсить версию ядра: $KERN_FULL"
    fi

    log_info "Ядро: ${KERN_FULL} (разобрано: ${KERN_MAJOR}.${KERN_MINOR}.${KERN_PATCH})"

    # Проверка поддерживаемого диапазона
    if [ "$KERN_MAJOR" -lt 6 ]; then
        die "Ядро ${KERN_MAJOR}.${KERN_MINOR} не поддерживается. Минимальная версия: 6.1"
    fi

    if [ "$KERN_MAJOR" -eq 6 ] && [ "$KERN_MINOR" -lt 1 ]; then
        die "Ядро 6.0 не поддерживается. Минимальная версия: 6.1"
    fi

    if [ "$KERN_MAJOR" -ge 7 ]; then
        log_warn "Ядро ${KERN_MAJOR}.x — экспериментальная поддержка!"
    fi
}

# ─── Проверка конфигурации ядра ───────────────────────────────────────────────
check_kernel_config() {
    log_step "Проверка конфигурации ядра..."

    # ── Метод 1: Проверка через /sys/module (runtime, самый надёжный) ──────
    log_info "Проверка загруженных модулей Netfilter (runtime)..."
    local runtime_ok=true

    # nf_conntrack: модуль или встроен в ядро
    if [ -d /sys/module/nf_conntrack ] || lsmod | grep -q nf_conntrack 2>/dev/null; then
        log_info "  nf_conntrack — ОК (загружен)"
    else
        # Пробуем загрузить
        if modprobe nf_conntrack 2>/dev/null; then
            log_info "  nf_conntrack — ОК (загружен по запросу)"
        else
            log_warn "  nf_conntrack — НЕ ДОСТУПЕН"
            runtime_ok=false
        fi
    fi

    # nf_nat
    if [ -d /sys/module/nf_nat ] || lsmod | grep -q nf_nat 2>/dev/null; then
        log_info "  nf_nat — ОК (загружен)"
    else
        if modprobe nf_nat 2>/dev/null; then
            log_info "  nf_nat — ОК (загружен по запросу)"
        else
            log_warn "  nf_nat — НЕ ДОСТУПЕН"
            runtime_ok=false
        fi
    fi

    # x_tables (xtables)
    if [ -d /sys/module/x_tables ] || lsmod | grep -q x_tables 2>/dev/null; then
        log_info "  x_tables — ОК (загружен)"
    else
        if modprobe x_tables 2>/dev/null; then
            log_info "  x_tables — ОК (загружен по запросу)"
        else
            log_warn "  x_tables — НЕ ДОСТУПЕН"
            runtime_ok=false
        fi
    fi

    # nf_conntrack_events: проверяем через /proc (если conntrack загружен)
    if [ -f /proc/net/nf_conntrack ] || [ -d /proc/sys/net/netfilter ]; then
        log_info "  conntrack подсистема — ОК (events доступны)"
    else
        log_warn "  conntrack подсистема — /proc/sys/net/netfilter не найден"
    fi

    # ── Метод 2: Дополнительно смотрим config-файл (информационно) ─────────
    local config_file=""
    for path in \
        "/boot/config-$(uname -r)" \
        "/proc/config.gz" \
        "/lib/modules/$(uname -r)/build/.config" \
        "/usr/src/linux-headers-$(uname -r)/.config"; do
        if [ -f "$path" ]; then
            config_file="$path"
            break
        fi
    done

    if [ -n "$config_file" ]; then
        log_info "Конфигурация ядра (файл): $config_file"
        local read_cmd="cat"
        [[ "$config_file" == *.gz ]] && read_cmd="zcat"

        # На ядрах 6.12+ опции могли быть переименованы или объединены.
        # Проверяем все известные варианты.
        local ct_found=false
        for variant in CONFIG_NF_CONNTRACK CONFIG_NETFILTER_CONNTRACK; do
            if $read_cmd "$config_file" 2>/dev/null | grep -q "^${variant}=[ym]"; then
                log_info "  ${variant}=y|m в config — ОК"
                ct_found=true
                break
            fi
        done

        if ! $ct_found; then
            log_warn "  CONFIG_NF_CONNTRACK не найден в config-файле."
            log_warn "  На ядрах 6.12+ это нормально — опция может быть встроена без явного флага."
        fi

        # Проверяем conntrack events
        if $read_cmd "$config_file" 2>/dev/null | grep -q "^CONFIG_NF_CONNTRACK_EVENTS=[ym]"; then
            log_info "  CONFIG_NF_CONNTRACK_EVENTS — ОК"
        else
            if $runtime_ok; then
                log_warn "  CONFIG_NF_CONNTRACK_EVENTS не найден в config, но runtime-проверки пройдены."
                log_warn "  Опция скорее всего встроена (=y без отдельной строки) — продолжаем."
            else
                log_warn "  CONFIG_NF_CONNTRACK_EVENTS — НЕ НАЙДЕН!"
                log_warn "  Модуль может не загрузиться. Проверьте конфигурацию ядра."
            fi
        fi
    else
        log_warn "Config-файл ядра не найден (это нормально для некоторых VPS)."
        if $runtime_ok; then
            log_info "Runtime-проверки пройдены — продолжаем."
        fi
    fi

    if ! $runtime_ok; then
        log_warn "Некоторые модули Netfilter не загружены. Установка продолжится,"
        log_warn "но модуль xt_FULLCONENAT может не работать."
    fi
}

# ─── Проверка заголовков ядра ─────────────────────────────────────────────────
check_kernel_headers() {
    log_step "Проверка заголовков ядра..."

    local hdr="/lib/modules/$(uname -r)/build"
    if [ -d "$hdr" ] || [ -L "$hdr" ]; then
        if [ -f "$hdr/Makefile" ]; then
            log_info "Заголовки ядра: $hdr — ОК"
            return 0
        else
            log_warn "Заголовки ядра повреждены (нет Makefile): $hdr"
        fi
    fi

    log_warn "Заголовки ядра не найдены: $hdr"

    # ── Попытка 1: Ищем заголовки с похожей версией ──
    local kern_short="${KERN_MAJOR}.${KERN_MINOR}"
    local closest=""
    for d in /usr/src/linux-headers-${kern_short}*/; do
        if [ -d "$d" ] && [ -f "$d/Makefile" ]; then
            closest="$d"
        fi
    done

    if [ -n "$closest" ]; then
        log_info "Найдены ближайшие заголовки: $closest"
        mkdir -p "$(dirname "$hdr")"
        ln -sf "$closest" "$hdr"
        if [ -f "$hdr/Makefile" ]; then
            log_info "Создан симлинк: $hdr -> $closest"
            return 0
        fi
    fi

    # ── Попытка 2: Ищем вообще любые заголовки ──
    closest=""
    for d in /usr/src/linux-headers-*/; do
        if [ -d "$d" ] && [ -f "$d/Makefile" ]; then
            closest="$d"
        fi
    done

    if [ -n "$closest" ]; then
        log_warn "Точные заголовки не найдены, используем: $closest"
        mkdir -p "$(dirname "$hdr")"
        ln -sf "$closest" "$hdr"
        if [ -f "$hdr/Makefile" ]; then
            log_info "Создан симлинк: $hdr -> $closest"
            log_warn "Версия заголовков может не совпадать с ядром — модуль может не собраться."
            return 0
        fi
    fi

    # ── Fallback: работаем без Full Cone NAT ──
    log_warn "════════════════════════════════════════════════════════"
    log_warn "  Заголовки ядра НЕ НАЙДЕНЫ для $(uname -r)"
    log_warn "  Full Cone NAT будет НЕДОСТУПЕН."
    log_warn "  VPN будет работать в режиме MASQUERADE (Symmetric NAT)"
    log_warn "  Для установки вручную:"
    log_warn "    apt install linux-headers-$(uname -r)"
    log_warn "════════════════════════════════════════════════════════"
    export USE_MASQ_FALLBACK=true
    return 0  # НЕ прерываем установку!
}

# ─── Установка зависимостей ───────────────────────────────────────────────────
install_deps() {
    log_step "Установка зависимостей сборки..."

    case "$PKG_MGR" in
        apt)
            export DEBIAN_FRONTEND=noninteractive

            log_info "Обновление списка пакетов..."
            apt-get update -qq 2>>"$LOG_FILE" || log_warn "apt update завершился с предупреждениями"

            # Группа 1: Базовые инструменты сборки (КРИТИЧЕСКИ ВАЖНО)
            log_info "Установка базовых инструментов сборки..."
            if ! apt-get install -y -qq build-essential git dkms kmod pkg-config 2>>"$LOG_FILE"; then
                # Пробуем минимальный набор
                log_warn "build-essential не удалось установить целиком, пробуем по частям..."
                apt-get install -y -qq gcc make dpkg-dev git dkms kmod 2>>"$LOG_FILE" || \
                    die "Не удалось установить базовые инструменты сборки (gcc, make, git, dkms)"
            fi

            # Группа 2: Заголовки ядра (КРИТИЧЕСКИ ВАЖНО)
            log_info "Установка заголовков ядра..."
            local headers_installed=false

            # Попытка 1: точная версия
            if apt-get install -y -qq "linux-headers-$(uname -r)" 2>>"$LOG_FILE"; then
                headers_installed=true
                log_info "Установлены заголовки: linux-headers-$(uname -r)"
            else
                log_warn "Пакет linux-headers-$(uname -r) не найден в репозиториях."
            fi

            # Попытка 2: generic-пакет (подтянет ближайшую версию)
            if ! $headers_installed; then
                log_info "Пробуем linux-headers-generic..."
                if apt-get install -y -qq linux-headers-generic 2>>"$LOG_FILE"; then
                    headers_installed=true
                    log_info "Установлены заголовки: linux-headers-generic"
                else
                    log_warn "linux-headers-generic тоже не удалось."
                fi
            fi

            # Попытка 3: поиск любых совместимых заголовков
            if ! $headers_installed; then
                log_info "Поиск доступных пакетов linux-headers..."
                local avail_hdr
                avail_hdr=$(apt-cache search "^linux-headers-${KERN_MAJOR}\.${KERN_MINOR}" 2>/dev/null | head -1 | awk '{print $1}')
                if [ -n "$avail_hdr" ]; then
                    log_info "Найден пакет: $avail_hdr"
                    if apt-get install -y -qq "$avail_hdr" 2>>"$LOG_FILE"; then
                        headers_installed=true
                    fi
                fi
            fi

            if ! $headers_installed; then
                log_warn "Не удалось установить заголовки ядра!"
                log_warn "Full Cone NAT будет недоступен — используем MASQUERADE."
                log_warn "Для ручной установки: apt search linux-headers | grep $(uname -r | cut -d- -f1)"
                export USE_MASQ_FALLBACK=true
            fi

            # Группа 3: iptables-dev (ОПЦИОНАЛЬНО — нужен для libipt_FULLCONENAT.so)
            log_info "Установка iptables development headers (опционально)..."
            if apt-get install -y -qq libxtables-dev 2>>"$LOG_FILE"; then
                log_info "libxtables-dev установлен."
            elif apt-get install -y -qq iptables-dev 2>>"$LOG_FILE"; then
                log_info "iptables-dev установлен."
            else
                log_warn "iptables-dev/libxtables-dev не установлен."
                log_warn "Расширение iptables (-j FULLCONENAT) может не собраться."
                log_warn "Модуль ядра всё равно будет работать."
            fi
            ;;

        dnf)
            dnf install -y gcc make git dkms kmod 2>>"$LOG_FILE" || \
                die "Ошибка установки базовых зависимостей через dnf"

            dnf install -y "kernel-devel-$(uname -r)" "kernel-headers-$(uname -r)" 2>>"$LOG_FILE" || {
                log_warn "Точные kernel-devel не найдены, пробуем без указания версии..."
                dnf install -y kernel-devel kernel-headers 2>>"$LOG_FILE" || \
                    die "Не удалось установить kernel-devel"
            }

            dnf install -y iptables-devel 2>>"$LOG_FILE" || \
                log_warn "iptables-devel не установлен (опционально)"
            ;;

        yum)
            yum install -y epel-release 2>>"$LOG_FILE" || true

            yum install -y gcc make git dkms kmod 2>>"$LOG_FILE" || \
                die "Ошибка установки базовых зависимостей через yum"

            yum install -y "kernel-devel-$(uname -r)" "kernel-headers-$(uname -r)" 2>>"$LOG_FILE" || {
                log_warn "Точные kernel-devel не найдены, пробуем без указания версии..."
                yum install -y kernel-devel kernel-headers 2>>"$LOG_FILE" || \
                    die "Не удалось установить kernel-devel"
            }

            yum install -y iptables-devel 2>>"$LOG_FILE" || \
                log_warn "iptables-devel не установлен (опционально)"
            ;;

        pacman)
            pacman -Sy --noconfirm --needed \
                base-devel git dkms kmod linux-headers \
                2>>"$LOG_FILE" || die "Ошибка установки зависимостей через pacman"
            ;;
    esac

    log_info "Зависимости установлены."
}

# ─── Скачивание исходного кода ────────────────────────────────────────────────
download_source() {
    log_step "Скачивание исходного кода xt_FULLCONENAT..."

    mkdir -p "$BUILD_DIR"
    cd "$BUILD_DIR"

    if ! git clone --depth=1 "$REPO_URL" source 2>>"$LOG_FILE"; then
        die "Не удалось клонировать репозиторий: $REPO_URL"
    fi

    if [ ! -f "source/xt_FULLCONENAT.c" ]; then
        die "Файл xt_FULLCONENAT.c не найден в склонированном репозитории!"
    fi

    log_info "Исходный код загружен в $BUILD_DIR/source"
}

# ──────────────────────────────────────────────────────────────────────────────
# КЛЮЧЕВАЯ ЧАСТЬ: Создание compatibility-header
# ──────────────────────────────────────────────────────────────────────────────
create_compat_header() {
    log_step "Создание заголовка совместимости (compat.h)..."

    cat > "$BUILD_DIR/source/compat.h" << 'COMPAT_EOF'
#ifndef _XT_FULLCONENAT_COMPAT_H
#define _XT_FULLCONENAT_COMPAT_H

#include <linux/version.h>

/*
 * Compatibility layer for xt_FULLCONENAT across Linux kernels 4.x — 6.18+
 *
 * API changes by kernel version:
 *   4.13+  : xt_action_param: par->net → par->state->net (use xt_net())
 *   4.15+  : Timer callback signature changed
 *   4.18+  : struct nf_nat_range → struct nf_nat_range2
 *   5.15+  : nf_ct_event_notifier: .fcn → .ct_event
 *   5.18+  : prandom_u32() → get_random_u32()
 *   6.0+   : nf_conntrack_register_notifier returns void (was int)
 *   6.0+   : nf_conntrack_unregister_notifier: 2 args → 1 arg
 *   6.1+   : nf_nat_setup_info accepts const struct nf_nat_range2 *
 *   6.14+  : Strict function pointer types, const qualifiers
 */

/* ── NAT Range struct ─────────────────────────────────────────────────── */
#if LINUX_VERSION_CODE >= KERNEL_VERSION(4, 18, 0)
  #define COMPAT_NAT_RANGE       struct nf_nat_range2
#else
  #define COMPAT_NAT_RANGE       struct nf_nat_range
#endif

/* ── NF_NAT_RANGE flags ──────────────────────────────────────────────── */
#ifndef NF_NAT_RANGE_MAP_IPS
  #define NF_NAT_RANGE_MAP_IPS          (1 << 0)
#endif
#ifndef NF_NAT_RANGE_PROTO_SPECIFIED
  #define NF_NAT_RANGE_PROTO_SPECIFIED  (1 << 1)
#endif

/* ── Timer API (kernel 4.15+ changed timer callback signature) ──────── */
#if LINUX_VERSION_CODE >= KERNEL_VERSION(4, 15, 0)
  #define COMPAT_TIMER_SETUP(timer, callback) timer_setup(timer, callback, 0)
  #define COMPAT_TIMER_CALLBACK(name) static void name(struct timer_list *t)
#else
  #define COMPAT_TIMER_SETUP(timer, callback) \
      setup_timer(timer, callback, (unsigned long)(timer))
  #define COMPAT_TIMER_CALLBACK(name) \
      static void name(unsigned long data)
#endif

/*
 * ── prandom_u32() → get_random_u32() (kernel 5.18+) ─────────────────
 * prandom_u32() was removed in 5.18, replaced by get_random_u32().
 */
#if LINUX_VERSION_CODE >= KERNEL_VERSION(5, 18, 0)
  #define compat_prandom_u32() get_random_u32()
#else
  #define compat_prandom_u32() prandom_u32()
#endif

/*
 * ── nf_ct_event_notifier member rename (kernel 5.15+) ────────────────
 * struct nf_ct_event_notifier { .fcn } → { .ct_event }
 * The preprocessor expands macros even after the . operator.
 */
#if LINUX_VERSION_CODE >= KERNEL_VERSION(5, 15, 0)
  #define COMPAT_CT_NOTIFIER_FCN ct_event
#else
  #define COMPAT_CT_NOTIFIER_FCN fcn
#endif

/*
 * ── nf_conntrack_register_notifier returns void on 6.0+ ─────────────
 * Old: int nf_conntrack_register_notifier(net, notifier);
 * New: void nf_conntrack_register_notifier(net, notifier);
 * We wrap it in a GCC statement expression that always returns 0.
 */
#if LINUX_VERSION_CODE >= KERNEL_VERSION(6, 0, 0)
  #define compat_ct_reg(net, nb) ({ \
      nf_conntrack_register_notifier(net, nb); \
      0; \
  })
#else
  #define compat_ct_reg(net, nb) \
      nf_conntrack_register_notifier(net, nb)
#endif

/*
 * ── nf_conntrack_unregister_notifier API change (kernel 6.0+) ───────
 * Old: nf_conntrack_unregister_notifier(net, &notifier)  // 2 args
 * New: nf_conntrack_unregister_notifier(net)              // 1 arg
 */
#if LINUX_VERSION_CODE >= KERNEL_VERSION(6, 0, 0)
  #define compat_ct_unreg(net, nb) \
      nf_conntrack_unregister_notifier(net)
#else
  #define compat_ct_unreg(net, nb) \
      nf_conntrack_unregister_notifier(net, nb)
#endif

/* ── Хелпер для nf_ct_netns_get/put ──────────────────────────────────── */
#if LINUX_VERSION_CODE >= KERNEL_VERSION(4, 11, 0)
  #include <net/netfilter/nf_conntrack.h>
#endif

/* ── nf_nat.h ─────────────────────────────────────────────────────────── */
#include <net/netfilter/nf_nat.h>

/* ── GFP flags ────────────────────────────────────────────────────────── */
#ifndef GFP_ATOMIC
  #include <linux/gfp.h>
#endif

#endif /* _XT_FULLCONENAT_COMPAT_H */
COMPAT_EOF

    log_info "Заголовок совместимости создан."
}

# ─── Патчинг исходного кода ───────────────────────────────────────────────────
patch_source() {
    log_step "Применение универсальных патчей к исходному коду..."

    local src="$BUILD_DIR/source/xt_FULLCONENAT.c"
    local backup="$BUILD_DIR/source/xt_FULLCONENAT.c.orig"

    # Бэкап оригинала
    cp "$src" "$backup"

    # 1) Добавить include compat.h после первого #include
    if ! grep -q 'compat.h' "$src"; then
        sed -i '0,/^#include/s/^#include/#include "compat.h"\n#include/' "$src"
    fi

    # 2) Заменить struct nf_nat_range на COMPAT_NAT_RANGE
    log_info "  Патч: struct nf_nat_range → COMPAT_NAT_RANGE"
    sed -i 's/\bstruct nf_nat_range\b\([^2]\)/COMPAT_NAT_RANGE\1/g' "$src"
    sed -i 's/\bstruct nf_nat_range$/COMPAT_NAT_RANGE/g' "$src"

    # 3) par->net в TARGET-функции → xt_net(par)
    #    НЕ трогаем par->net в check/destroy — там это поле существует.
    log_info "  Патч: par->net → xt_net(par) только в target-функции"
    sed -i '/^static.*unsigned.*fullconenat_tg\b/,/^}$/s/\bpar->net\b/xt_net(par)/g' "$src"

    # 4) prandom_u32() → compat_prandom_u32() (удалена в 5.18+)
    log_info "  Патч: prandom_u32() → compat_prandom_u32()"
    sed -i 's/prandom_u32()/compat_prandom_u32()/g' "$src"

    # 5) .fcn → .COMPAT_CT_NOTIFIER_FCN (поле переименовано в 5.15+)
    log_info "  Патч: .fcn → .COMPAT_CT_NOTIFIER_FCN"
    sed -i 's/\.fcn *=/.COMPAT_CT_NOTIFIER_FCN =/g' "$src"
    sed -i 's/->fcn\b/->COMPAT_CT_NOTIFIER_FCN/g' "$src"

    # 6) nf_conntrack_register_notifier → compat_ct_reg (возвращает void на 6.0+)
    log_info "  Патч: nf_conntrack_register_notifier → compat_ct_reg"
    sed -i 's/nf_conntrack_register_notifier(/compat_ct_reg(/g' "$src"

    # 7) nf_conntrack_unregister_notifier → compat_ct_unreg (1 arg на 6.0+)
    log_info "  Патч: nf_conntrack_unregister_notifier → compat_ct_unreg"
    sed -i 's/nf_conntrack_unregister_notifier(\([^,]*\), *\([^)]*\))/compat_ct_unreg(\1, \2)/g' "$src"

    # 8) Проверяем наличие nf_ct_netns_get
    if grep -q 'nf_ct_netns_get' "$src"; then
        log_info "  nf_ct_netns_get обнаружен — совместимость через compat.h."
    fi

    # Подсчёт изменений
    local changes
    changes=$(diff "$backup" "$src" | grep -c '^[<>]' || true)
    log_info "Применено изменений: ${changes}"
}

# ─── Создание Makefile для сборки ─────────────────────────────────────────────
create_build_makefile() {
    log_step "Создание Makefile для сборки модуля..."

    cat > "$BUILD_DIR/source/Makefile" << 'MAKEFILE_EOF'
obj-m += xt_FULLCONENAT.o

KVER  ?= $(shell uname -r)
KDIR  ?= /lib/modules/$(KVER)/build
PWD   := $(shell pwd)

# Дополнительные флаги для подавления предупреждений на новых ядрах
ccflags-y += -Wno-incompatible-pointer-types -Wno-declaration-after-statement

all:
	$(MAKE) -C $(KDIR) M=$(PWD) modules

clean:
	$(MAKE) -C $(KDIR) M=$(PWD) clean

install: all
	$(MAKE) -C $(KDIR) M=$(PWD) modules_install
	depmod -a $(KVER)
MAKEFILE_EOF

    log_info "Makefile создан."
}

# ─── Тестовая сборка ──────────────────────────────────────────────────────────
build_module() {
    log_step "Сборка модуля ядра xt_FULLCONENAT..."

    cd "$BUILD_DIR/source"

    # Первая попытка сборки
    if make KVER="$(uname -r)" >/dev/null 2>&1; then
        log_info "Модуль собран успешно!"
        return 0
    fi

    log_warn "Первая попытка сборки не удалась. Применяем дополнительные патчи..."

    # ── Дополнительный патч: nf_nat_range2 напрямую ──
    local src="$BUILD_DIR/source/xt_FULLCONENAT.c"

    # Попытка заменить все COMPAT_NAT_RANGE обратно на nf_nat_range2 напрямую
    sed -i 's/COMPAT_NAT_RANGE/struct nf_nat_range2/g' "$src"

    if make clean >/dev/null 2>&1 && make KVER="$(uname -r)" >/dev/null 2>&1; then
        log_info "Модуль собран после дополнительного патча!"
        return 0
    fi

    log_warn "Вторая попытка не удалась. Пробуем с -Wno-error..."

    # Попытка с игнорированием предупреждений как ошибок
    if make clean >/dev/null 2>&1 && make KVER="$(uname -r)" EXTRA_CFLAGS="-Wno-error" >/dev/null 2>&1; then
        log_info "Модуль собран с ослабленными проверками предупреждений."
        return 0
    fi

    # Показываем последние ошибки для диагностики
    log_warn "Сборка модуля не удалась после всех попыток!"
    log_warn "VPN будет работать в режиме MASQUERADE (без Full Cone NAT)"
    log_warn "Последние 10 строк лога:"
    tail -10 "$LOG_FILE" 2>/dev/null | while read -r l; do log_warn "  $l"; done
    export USE_MASQ_FALLBACK=true
    return 0  # НЕ прерываем установку!
}

# ─── Сборка iptables extension ────────────────────────────────────────────────
build_iptables_ext() {
    log_step "Сборка расширения iptables (libipt_FULLCONENAT.so)..."

    local src="$BUILD_DIR/source/libipt_FULLCONENAT.c"

    if [ ! -f "$src" ]; then
        log_warn "Файл libipt_FULLCONENAT.c не найден — пропускаем."
        return 0
    fi

    # ── Определяем xtables directory ──
    local xt_libdir=""
    if command -v pkg-config &>/dev/null && pkg-config --exists xtables 2>/dev/null; then
        xt_libdir=$(pkg-config --variable=xtlibdir xtables 2>/dev/null || echo "")
    fi
    if [ -z "$xt_libdir" ]; then
        for dir in \
            /usr/lib/x86_64-linux-gnu/xtables \
            /usr/lib/aarch64-linux-gnu/xtables \
            /usr/lib64/xtables \
            /usr/lib/xtables \
            /lib/x86_64-linux-gnu/xtables \
            /lib/xtables; do
            [ -d "$dir" ] && { xt_libdir="$dir"; break; }
        done
    fi

    if [ -z "$xt_libdir" ]; then
        log_warn "Директория xtables не найдена."
        return 0
    fi

    log_info "Xtables directory: $xt_libdir"

    # ── Определяем include paths для xtables.h ──
    local xt_cflags=""
    if command -v pkg-config &>/dev/null && pkg-config --exists xtables 2>/dev/null; then
        xt_cflags=$(pkg-config --cflags xtables 2>/dev/null || echo "")
    fi

    # Ищем xtables.h вручную
    local xt_inc_dir=""
    for inc in /usr/include /usr/local/include /usr/include/xtables; do
        if [ -f "$inc/xtables.h" ]; then
            xt_inc_dir="$inc"
            break
        fi
    done
    if [ -n "$xt_inc_dir" ]; then
        xt_cflags="$xt_cflags -I$xt_inc_dir"
    fi

    cd "$BUILD_DIR/source"

    local err="" compiled=false

    # ── Попытка 1: стандартная ──
    log_info "  [1/5] Стандартная сборка..."
    err=$(gcc -O2 -Wall -fPIC -shared $xt_cflags \
        -o libipt_FULLCONENAT.so "$src" 2>&1) && compiled=true

    # ── Попытка 2: без -Wall + XTABLES_INTERNAL ──
    if ! $compiled; then
        log_warn "  [1/5] Ошибка: $(echo "$err" | tail -1)"
        log_info "  [2/5] С -DXTABLES_INTERNAL..."
        err=$(gcc -O2 -fPIC -shared $xt_cflags \
            -DXTABLES_INTERNAL \
            -o libipt_FULLCONENAT.so "$src" 2>&1) && compiled=true
    fi

    # ── Попытка 3: патчим исходник для новых xtables ──
    if ! $compiled; then
        log_warn "  [2/5] Ошибка: $(echo "$err" | tail -1)"
        log_info "  [3/5] С патчами для нового xtables API..."

        # Копируем и патчим
        cp "$src" "${src}.bak"

        # Современный xtables использует NFPROTO_IPV4 вместо AF_INET в .family
        sed -i 's/\.family.*=.*AF_INET/.family = NFPROTO_IPV4/g' "$src" 2>/dev/null
        # Добавляем include если не хватает
        grep -q '<linux/netfilter.h>' "$src" || \
            sed -i '1i #include <linux/netfilter.h>' "$src" 2>/dev/null

        err=$(gcc -O2 -fPIC -shared $xt_cflags \
            -DXTABLES_INTERNAL \
            -Wno-all \
            -o libipt_FULLCONENAT.so "$src" 2>&1) && compiled=true

        # Восстанавливаем если не помогло
        $compiled || cp "${src}.bak" "$src"
        rm -f "${src}.bak"
    fi

    # ── Попытка 4: голая компиляция с минимальным поиском include ──
    if ! $compiled; then
        log_warn "  [3/5] Ошибка: $(echo "$err" | tail -1)"
        log_info "  [4/5] Голая компиляция без warnings..."
        err=$(gcc -O2 -fPIC -shared \
            -I/usr/include -I/usr/local/include \
            -DXTABLES_INTERNAL -Wno-all \
            -o libipt_FULLCONENAT.so "$src" 2>&1) && compiled=true
    fi

    # ── Попытка 5: linkable shared lib через xtables ──
    if ! $compiled; then
        log_warn "  [4/5] Ошибка: $(echo "$err" | tail -1)"
        log_info "  [5/5] С -lxtables..."
        err=$(gcc -O2 -fPIC -shared \
            -I/usr/include $xt_cflags \
            -DXTABLES_INTERNAL -Wno-all \
            -lxtables \
            -o libipt_FULLCONENAT.so "$src" 2>&1) && compiled=true
    fi

    # ── Результат ──
    if $compiled && [ -f "libipt_FULLCONENAT.so" ]; then
        cp libipt_FULLCONENAT.so "$xt_libdir/"
        chmod 755 "$xt_libdir/libipt_FULLCONENAT.so"
        ROLLBACK_STEPS+=("rm -f '$xt_libdir/libipt_FULLCONENAT.so'")
        log_info "Расширение iptables установлено: $xt_libdir/libipt_FULLCONENAT.so"
    else
        log_warn "Все 5 попыток сборки libipt_FULLCONENAT.so не удались."
        log_warn "Последняя ошибка:"
        echo "$err" | tail -5 | while read -r line; do log_warn "  $line"; done
        log_warn ""
        log_warn "Модуль ядра загружен, но iptables -j FULLCONENAT недоступен."
        log_warn "Для диагностики: cat $LOG_FILE | grep -A5 'libipt'"
    fi
}

# ─── Настройка DKMS ──────────────────────────────────────────────────────────
setup_dkms() {
    log_step "Настройка DKMS для автопересборки при обновлении ядра..."

    # Удаляем старую версию DKMS если есть
    dkms status "$DKMS_NAME/$DKMS_VER" 2>/dev/null | grep -q "$DKMS_NAME" && {
        log_info "Удаление старой версии DKMS..."
        dkms remove "$DKMS_NAME/$DKMS_VER" --all 2>/dev/null || true
    }

    # Удаляем старые исходники
    rm -rf "$DKMS_SRC"
    mkdir -p "$DKMS_SRC"

    # Копируем ЗАПАТЧЕННЫЕ исходники (с compat.h) в DKMS
    cp "$BUILD_DIR/source/xt_FULLCONENAT.c" "$DKMS_SRC/"
    cp "$BUILD_DIR/source/compat.h"         "$DKMS_SRC/"
    cp "$BUILD_DIR/source/Makefile"         "$DKMS_SRC/"

    # Создаём dkms.conf
    cat > "$DKMS_SRC/dkms.conf" << EOF
PACKAGE_NAME="${DKMS_NAME}"
PACKAGE_VERSION="${DKMS_VER}"
DEST_MODULE_LOCATION[0]="/extra"
BUILT_MODULE_NAME[0]="xt_FULLCONENAT"
MAKE[0]="make -C /lib/modules/\${kernelver}/build M=\${dkms_tree}/\${PACKAGE_NAME}/\${PACKAGE_VERSION}/build modules"
CLEAN="make -C /lib/modules/\${kernelver}/build M=\${dkms_tree}/\${PACKAGE_NAME}/\${PACKAGE_VERSION}/build clean"
AUTOINSTALL="yes"
EOF

    ROLLBACK_STEPS+=("dkms remove '$DKMS_NAME/$DKMS_VER' --all 2>/dev/null; rm -rf '$DKMS_SRC'")

    # Добавляем, собираем, устанавливаем
    log_info "DKMS add..."
    if ! dkms add -m "$DKMS_NAME" -v "$DKMS_VER" >/dev/null 2>&1; then
        log_error "Ошибка dkms add"
        export USE_MASQ_FALLBACK=true
    fi

    log_info "DKMS build..."
    if ! dkms build -m "$DKMS_NAME" -v "$DKMS_VER" >/dev/null 2>&1; then
        log_error "Ошибка сборки через DKMS!"
        export USE_MASQ_FALLBACK=true
    fi

    log_info "DKMS install..."
    if ! dkms install -m "$DKMS_NAME" -v "$DKMS_VER" >/dev/null 2>&1; then
        log_error "Ошибка dkms install"
        export USE_MASQ_FALLBACK=true
    fi

    # Обновляем зависимости модулей
    depmod -a "$(uname -r)"

    log_info "DKMS настроен. Модуль будет автоматически пересобираться при обновлении ядра."
}

# ─── Загрузка модуля ──────────────────────────────────────────────────────────
load_module() {
    log_step "Загрузка модуля xt_FULLCONENAT..."

    # Выгружаем старый если загружен
    if lsmod | grep -q xt_FULLCONENAT; then
        log_info "Выгрузка старого модуля..."
        rmmod xt_FULLCONENAT 2>/dev/null || true
    fi

    if modprobe xt_FULLCONENAT 2>>"$LOG_FILE"; then
        log_info "Модуль xt_FULLCONENAT загружен!"
    else
        log_warn "Не удалось загрузить модуль. Переход к резервному режиму MASQUERADE."
        export USE_MASQ_FALLBACK=true
    fi
}

# ─── Настройка автозагрузки ───────────────────────────────────────────────────
setup_persistence() {
    log_step "Настройка автозагрузки модуля..."

    if [ -d "/etc/modules-load.d" ]; then
        echo "xt_FULLCONENAT" > /etc/modules-load.d/xt_FULLCONENAT.conf
        ROLLBACK_STEPS+=("rm -f /etc/modules-load.d/xt_FULLCONENAT.conf")
        log_info "Автозагрузка: /etc/modules-load.d/xt_FULLCONENAT.conf"
    elif [ -f "/etc/modules" ]; then
        if ! grep -q "xt_FULLCONENAT" /etc/modules; then
            echo "xt_FULLCONENAT" >> /etc/modules
            log_info "Модуль добавлен в /etc/modules"
        fi
    fi
}

# ─── Финальная верификация ────────────────────────────────────────────────────
verify_installation() {
    log_step "Финальная проверка установки..."

    local ok=true

    if [ "${FALLBACK_ACTIVE:-false}" = "true" ]; then
        log_warn "Используется резервный режим (Symmetric NAT / MASQUERADE)."
        ok=true
    else
        # 1) Модуль загружен?
        if lsmod | grep -q xt_FULLCONENAT; then
            log_info "  [1/4] Модуль загружен в ядро — ОК"
        else
            log_error "  [1/4] Модуль НЕ загружен!"; ok=false
        fi

        # 2) Модуль в файловой системе?
        local mod_path
        mod_path=$(modinfo xt_FULLCONENAT 2>/dev/null | grep "^filename:" | awk '{print $2}')
        if [ -n "$mod_path" ]; then
            log_info "  [2/4] Файл модуля: $mod_path — ОК"
        else
            log_error "  [2/4] Файл модуля НЕ НАЙДЕН!"; ok=false
        fi

        # 3) DKMS статус?
        if dkms status "$DKMS_NAME" 2>/dev/null | grep -q "installed"; then
            log_info "  [3/4] DKMS статус: installed — ОК"
        else
            log_warn "  [3/4] DKMS статус: нет данных"
        fi

        # 4) iptables extension?
        if iptables -t nat -L -n 2>/dev/null | head -1 >/dev/null; then
            if iptables -t nat -A POSTROUTING -j FULLCONENAT --help 2>&1 | grep -qi "fullcone\|FULLCONE"; then
                log_info "  [4/4] iptables extension FULLCONENAT — ОК"
            else
                log_warn "  [4/4] iptables extension — не удалось проверить (может работать)"
            fi
        fi
    fi

    if $ok; then
        echo ""
        echo -e "${C_GREEN}═══════════════════════════════════════════════════════════════${C_NC}"
        echo -e "${C_GREEN}  УСТАНОВКА ЗАВЕРШЕНА УСПЕШНО!${C_NC}"
        echo -e "${C_GREEN}═══════════════════════════════════════════════════════════════${C_NC}"
        echo ""
    else
        log_error "Установка завершилась с ошибками модуля. Но сервис VPN всё равно запущен."
    fi
}

# ─── Автоопределение WAN-интерфейса ──────────────────────────────────────────
detect_wan_interface() {
    local iface=""
    iface=$(ip route show default 2>/dev/null | head -1 | awk '{for(i=1;i<=NF;i++) if($i=="dev") print $(i+1)}')
    if [ -z "$iface" ]; then
        iface=$(ip -4 addr show scope global 2>/dev/null | grep -oP '(?<=dev )\S+' | head -1)
    fi
    if [ -z "$iface" ]; then
        iface=$(ls /sys/class/net/ | grep -v lo | head -1)
    fi
    echo "$iface"
}

# ─── Применение правил iptables FULLCONENAT ───────────────────────────────────
setup_fullconenat_rules() {
    log_step "Настройка правил iptables Full Cone NAT..."

    local iface
    iface=$(detect_wan_interface)

    if [ -z "$iface" ]; then
        log_warn "Не удалось определить WAN-интерфейс."
        log_warn "Укажите вручную: iptables -t nat -A POSTROUTING -o <iface> -j FULLCONENAT"
        return 0
    fi

    log_info "WAN-интерфейс: $iface"

    # Удаляем старые правила
    while iptables -t nat -D POSTROUTING -o "$iface" -j FULLCONENAT 2>/dev/null; do :; done
    while iptables -t nat -D PREROUTING  -i "$iface" -j FULLCONENAT 2>/dev/null; do :; done
    while iptables -t nat -D POSTROUTING -o "$iface" -j MASQUERADE 2>/dev/null; do :; done
    while iptables -D INPUT -i "$iface" -p udp --dport 1024:65535 -j ACCEPT 2>/dev/null; do :; done

    local fallback=false
    if [ "${USE_MASQ_FALLBACK:-false}" = "true" ]; then
        fallback=true
    elif ! lsmod | grep -q xt_FULLCONENAT >/dev/null 2>&1; then
        fallback=true
    elif ! iptables -t nat -A POSTROUTING -o "$iface" -j FULLCONENAT 2>/dev/null; then
        fallback=true
    fi

    if [ "$fallback" = "true" ]; then
        log_warn "Активация режима FALLBACK: MASQUERADE + UDP(1024-65535)"
        while iptables -t nat -D POSTROUTING -o "$iface" -j FULLCONENAT 2>/dev/null; do :; done
        iptables -t nat -A POSTROUTING -o "$iface" -j MASQUERADE 2>>"$LOG_FILE"
        iptables -I INPUT -i "$iface" -p udp --dport 1024:65535 -j ACCEPT 2>>"$LOG_FILE" || true
        log_info "MASQUERADE настроен на $iface"
        export FALLBACK_ACTIVE=true
    else
        iptables -t nat -A PREROUTING -i "$iface" -j FULLCONENAT 2>>"$LOG_FILE" || true
        log_info "Full Cone NAT активирован на интерфейсе $iface"
        export FALLBACK_ACTIVE=false
    fi
}

# ─── Systemd-сервис для persistency ──────────────────────────────────────────
setup_systemd_service() {
    log_step "Создание systemd-сервиса для автоприменения правил..."

    local script_file="/usr/local/bin/fullconenat-apply.sh"

    cat > "$script_file" << 'APPLY_EOF'
#!/bin/bash
# fullconenat-apply.sh — автоприменение Full Cone NAT при загрузке
set -e

# Ждём готовности сети (до 30 сек)
for i in $(seq 1 30); do
    if ip route show default 2>/dev/null | grep -q "dev"; then break; fi
    sleep 1
done

# Загрузка модуля
if ! lsmod | grep -q xt_FULLCONENAT; then
    modprobe xt_FULLCONENAT 2>/dev/null || echo "[fullconenat] WARN: Модуль не загружен (возможно fallback-режим)"
fi

# Определяем WAN
IFACE=$(ip route show default 2>/dev/null | head -1 | awk '{for(i=1;i<=NF;i++) if($i=="dev") print $(i+1)}')
[ -z "$IFACE" ] && IFACE=$(ip -4 addr show scope global 2>/dev/null | grep -oP '(?<=dev )\S+' | head -1)
[ -z "$IFACE" ] && { echo "[fullconenat] WARN: WAN не найден"; exit 0; }

# Чистка и применение
while iptables -t nat -D POSTROUTING -o "$IFACE" -j FULLCONENAT 2>/dev/null; do :; done
while iptables -t nat -D PREROUTING  -i "$IFACE" -j FULLCONENAT 2>/dev/null; do :; done
while iptables -t nat -D POSTROUTING -o "$IFACE" -j MASQUERADE  2>/dev/null; do :; done
while iptables -D INPUT -i "$IFACE" -p udp --dport 1024:65535 -j ACCEPT 2>/dev/null; do :; done

APPLY_EOF

    if [ "${FALLBACK_ACTIVE:-false}" = "true" ]; then
        cat >> "$script_file" << 'APPLY_EOF_FALLBACK'
iptables -t nat -A POSTROUTING -o "$IFACE" -j MASQUERADE
iptables -I INPUT -i "$IFACE" -p udp --dport 1024:65535 -j ACCEPT
echo "[fullconenat] FALLBACK MASQUERADE активирован на $IFACE"
APPLY_EOF_FALLBACK
    else
        cat >> "$script_file" << 'APPLY_EOF_FULLCONE'
iptables -t nat -A POSTROUTING -o "$IFACE" -j FULLCONENAT
iptables -t nat -A PREROUTING  -i "$IFACE" -j FULLCONENAT
echo "[fullconenat] Full Cone NAT активирован на $IFACE"
APPLY_EOF_FULLCONE
    fi

    chmod +x "$script_file"
    ROLLBACK_STEPS+=("rm -f '$script_file'")

    cat > /etc/systemd/system/fullconenat.service << 'SVC_EOF'
[Unit]
Description=Full Cone NAT (xt_FULLCONENAT) iptables rules
After=network-online.target systemd-modules-load.service
Wants=network-online.target

[Service]
Type=oneshot
RemainAfterExit=yes
ExecStart=/usr/local/bin/fullconenat-apply.sh
ExecReload=/usr/local/bin/fullconenat-apply.sh

[Install]
WantedBy=multi-user.target
SVC_EOF

    ROLLBACK_STEPS+=("systemctl disable fullconenat.service 2>/dev/null; rm -f /etc/systemd/system/fullconenat.service")

    systemctl daemon-reload
    systemctl enable fullconenat.service 2>>"$LOG_FILE"
    log_info "Сервис fullconenat.service включён — правила будут восстанавливаться после ребута."
}

# ─── Команда: status ──────────────────────────────────────────────────────────
do_status() {
    echo -e "${C_BOLD}Статус xt_FULLCONENAT:${C_NC}"
    echo ""

    if lsmod | grep -q xt_FULLCONENAT; then
        log_info "Модуль: ЗАГРУЖЕН"
    else
        log_warn "Модуль: НЕ загружен"
    fi

    if modinfo xt_FULLCONENAT &>/dev/null; then
        log_info "Файл: $(modinfo xt_FULLCONENAT 2>/dev/null | grep '^filename:' | awk '{print $2}')"
    else
        log_warn "Файл модуля: не найден"
    fi

    if dkms status "$DKMS_NAME" 2>/dev/null | grep -q .; then
        log_info "DKMS: $(dkms status "$DKMS_NAME" 2>/dev/null)"
    else
        log_warn "DKMS: не настроен"
    fi

    [ -f /etc/modules-load.d/xt_FULLCONENAT.conf ] && log_info "Автозагрузка: ДА" || log_warn "Автозагрузка: НЕТ"

    if systemctl is-enabled fullconenat.service &>/dev/null; then
        log_info "Systemd-сервис: ВКЛЮЧЁН"
    else
        log_warn "Systemd-сервис: НЕТ"
    fi

    if iptables -t nat -L POSTROUTING -n 2>/dev/null | grep -q "FULLCONENAT"; then
        log_info "iptables правила: АКТИВНЫ"
    else
        log_warn "iptables правила: НЕ АКТИВНЫ"
    fi
}

# ─── Команда: uninstall ──────────────────────────────────────────────────────
do_uninstall() {
    log_step "Полное удаление xt_FULLCONENAT..."

    local iface
    iface=$(detect_wan_interface)
    if [ -n "$iface" ]; then
        while iptables -t nat -D POSTROUTING -o "$iface" -j FULLCONENAT 2>/dev/null; do :; done
        while iptables -t nat -D PREROUTING  -i "$iface" -j FULLCONENAT 2>/dev/null; do :; done
        log_info "Правила iptables удалены."
    fi

    if [ -f /etc/systemd/system/fullconenat.service ]; then
        systemctl stop fullconenat.service 2>/dev/null || true
        systemctl disable fullconenat.service 2>/dev/null || true
        rm -f /etc/systemd/system/fullconenat.service
        rm -f /usr/local/bin/fullconenat-apply.sh
        systemctl daemon-reload
        log_info "Systemd-сервис удалён."
    fi

    if lsmod | grep -q xt_FULLCONENAT; then
        rmmod xt_FULLCONENAT 2>/dev/null || log_warn "Не удалось выгрузить модуль"
    fi

    if dkms status "$DKMS_NAME" 2>/dev/null | grep -q .; then
        dkms remove "$DKMS_NAME/$DKMS_VER" --all 2>/dev/null || true
        log_info "DKMS удалён."
    fi

    rm -rf "$DKMS_SRC" 2>/dev/null
    rm -f /etc/modules-load.d/xt_FULLCONENAT.conf 2>/dev/null
    [ -f /etc/modules ] && sed -i '/xt_FULLCONENAT/d' /etc/modules 2>/dev/null || true

    for dir in /usr/lib/x86_64-linux-gnu/xtables /usr/lib/aarch64-linux-gnu/xtables \
               /usr/lib64/xtables /usr/lib/xtables /lib/x86_64-linux-gnu/xtables /lib/xtables; do
        [ -f "$dir/libipt_FULLCONENAT.so" ] && rm -f "$dir/libipt_FULLCONENAT.so" && log_info "Удалено: $dir/libipt_FULLCONENAT.so"
    done

    depmod -a "$(uname -r)" 2>/dev/null || true
    log_info "xt_FULLCONENAT полностью удалён."
}

# ─── Команда: rebuild ─────────────────────────────────────────────────────────
do_rebuild() {
    log_step "Пересборка модуля для ядра $(uname -r)..."

    if ! dkms status "$DKMS_NAME" 2>/dev/null | grep -q .; then
        die "DKMS не настроен. Сначала выполните полную установку."
    fi

    dkms remove "$DKMS_NAME/$DKMS_VER" --all 2>/dev/null || true
    dkms add -m "$DKMS_NAME" -v "$DKMS_VER" 2>/dev/null || true

    if dkms build -m "$DKMS_NAME" -v "$DKMS_VER" && \
       dkms install -m "$DKMS_NAME" -v "$DKMS_VER"; then
        depmod -a "$(uname -r)"
        rmmod xt_FULLCONENAT 2>/dev/null || true
        modprobe xt_FULLCONENAT
        log_info "Пересборка и загрузка завершены!"
    else
        die "Пересборка не удалась."
    fi
}

# ══════════════════════════════════════════════════════════════════════════════
#  WDTT VPN SERVER DEPLOYMENT
# ══════════════════════════════════════════════════════════════════════════════

prog() { echo "WDTT_PROGRESS|$1|$2"; }

# ─── Очистка старого WDTT ─────────────────────────────────────────────────────
wdtt_cleanup() {
    prog 0.05 "Глубокая очистка..."
    echo "🧹 Очистка старой установки WDTT..."

    # ══ ЗАЩИТА SSH ══ Первым делом гарантируем что порт 22 открыт!
    # Это нужно ДО любых манипуляций с iptables/ufw/nftables
    iptables -C INPUT -p tcp --dport 22 -j ACCEPT 2>/dev/null || \
        iptables -I INPUT 1 -p tcp --dport 22 -j ACCEPT 2>/dev/null || true
    # Также гарантируем что policy не DROP (на случай если ufw оставил)
    iptables -P INPUT ACCEPT 2>/dev/null || true

    systemctl unmask wdtt 2>/dev/null || true
    systemctl stop wdtt 2>/dev/null || true
    systemctl disable wdtt 2>/dev/null || true
    rm -f /etc/systemd/system/wdtt.service 2>/dev/null || true
    systemctl daemon-reload 2>/dev/null || true
    pkill -9 -f wdtt-server 2>/dev/null || killall -9 wdtt-server 2>/dev/null || true
    ip link del wg0 2>/dev/null || true

    # Удаляем старые правила NAT
    for i in {1..5}; do
        iptables -t nat -D POSTROUTING -s 10.66.66.0/24 -j FULLCONENAT 2>/dev/null || true
        iptables -t nat -D PREROUTING -j FULLCONENAT 2>/dev/null || true
        iptables -t nat -D POSTROUTING -s 10.66.66.0/24 -j MASQUERADE 2>/dev/null || true
    done

    rm -f /usr/local/bin/wdtt-server 2>/dev/null || true
    rm -rf /etc/wireguard/wg-keys.dat 2>/dev/null || true
    rm -rf /tmp/fullconenat 2>/dev/null || true
    
    # Жестко освобождаем порты 51820 и 56000 от вообще любых процессов
    fuser -k -9 51820/udp 56000/udp 2>/dev/null || true
    
    # Уничтожаем все виртуальные интерфейсы WireGuard ядра, которые держат порт 51820,
    # невидимо для fuser (так как работают в Ring 0)
    for wg_if in $(ip -o link show type wireguard 2>/dev/null | awk -F': ' '{print $2}' | cut -d'@' -f1); do
        ip link del "$wg_if" 2>/dev/null || true
    done

    echo "✓ Очистка завершена"
}

# ─── Миграция фаервола: ufw/nftables → iptables ──────────────────────────────
migrate_firewall() {
    prog 0.08 "Миграция фаервола..."
    echo "🔥 Анализ текущего фаервола..."

    local saved_ports_file="/tmp/migrated_ports_$$"
    : > "$saved_ports_file"

    # ── Сканируем ВСЕ активные порты (Trojan, Xray, Nginx, и т.д.) ──
    echo "🔍 Поиск слушающих портов для защиты от блокировки..."
    if command -v ss >/dev/null 2>&1; then
        ss -tuln | awk 'NR>1 {print $5}' | grep -vE '127\.[0-9]+\.[0-9]+\.[0-9]+:|::1:' | awk -F':' '{print $NF}' | grep -E '^[0-9]+$' | sort -nu | while read -r port; do
            echo "${port}/tcp" >> "$saved_ports_file"
            echo "${port}/udp" >> "$saved_ports_file"
            echo "   📌 Авто-открытие (ss): ${port}"
        done
    elif command -v netstat >/dev/null 2>&1; then
        netstat -tuln | awk 'NR>2 {print $4}' | grep -vE '127\.[0-9]+\.[0-9]+\.[0-9]+:|::1:' | awk -F':' '{print $NF}' | grep -E '^[0-9]+$' | sort -nu | while read -r port; do
            echo "${port}/tcp" >> "$saved_ports_file"
            echo "${port}/udp" >> "$saved_ports_file"
            echo "   📌 Авто-открытие (netstat): ${port}"
        done
    fi

    # ── Извлекаем открытые порты из UFW ──────────────
    if command -v ufw &>/dev/null && ufw status 2>/dev/null | grep -q "Status: active"; then
        echo "📋 UFW активен — сохраняем порты..."

        ufw status 2>/dev/null | grep "ALLOW" | while read -r line; do
            local port_proto
            port_proto=$(echo "$line" | awk '{print $1}')
            # Формат: "22/tcp", "80", "443/tcp"
            local port=$(echo "$port_proto" | cut -d'/' -f1)
            local proto=$(echo "$port_proto" | grep -o '/[a-z]*' | tr -d '/' || echo "tcp")
            [ -z "$proto" ] && proto="tcp"
            # Пропускаем если не число
            if [[ "$port" =~ ^[0-9]+$ ]]; then
                echo "${port}/${proto}" >> "$saved_ports_file"
                echo "   📌 Сохранён: ${port}/${proto}"
            fi
        done

        echo "🗑  Удаление UFW..."
        ufw disable 2>/dev/null || true
        apt-get remove -y --purge ufw 2>>"$LOG_FILE" || \
            yum remove -y ufw 2>>"$LOG_FILE" || true
        rm -rf /etc/ufw 2>/dev/null || true
        echo "✓ UFW удалён"
    else
        echo "   UFW не активен — пропускаем"
    fi

    # ── Извлекаем открытые порты из nftables ──────────
    if command -v nft &>/dev/null; then
        local nft_rules
        nft_rules=$(nft list ruleset 2>/dev/null || echo "")

        if [ -n "$nft_rules" ] && echo "$nft_rules" | grep -q "dport"; then
            echo "📋 nftables содержит правила — сохраняем порты..."

            echo "$nft_rules" | grep -oP 'dport\s+\K[0-9]+' | sort -un | while read -r port; do
                if [[ "$port" =~ ^[0-9]+$ ]] && [ "$port" -gt 0 ] && [ "$port" -le 65535 ]; then
                    echo "${port}/tcp" >> "$saved_ports_file"
                    echo "   📌 Сохранён из nftables: ${port}/tcp"
                fi
            done

            # Также ищем UDP
            echo "$nft_rules" | grep -E "udp.*dport" | grep -oP 'dport\s+\K[0-9]+' | sort -un | while read -r port; do
                if [[ "$port" =~ ^[0-9]+$ ]]; then
                    echo "${port}/udp" >> "$saved_ports_file"
                fi
            done
        fi

        echo "🗑  Очистка nftables..."
        nft flush ruleset 2>/dev/null || true
        systemctl stop nftables 2>/dev/null || true
        systemctl disable nftables 2>/dev/null || true
        apt-get remove -y --purge nftables 2>>"$LOG_FILE" || \
            yum remove -y nftables 2>>"$LOG_FILE" || true
        echo "✓ nftables удалён"
    else
        echo "   nftables не установлен — пропускаем"
    fi

    # ── Всегда добавляем SSH (22) для безопасности ──────
    grep -q "^22/" "$saved_ports_file" 2>/dev/null || echo "22/tcp" >> "$saved_ports_file"

    # ── Восстанавливаем порты через iptables ──────────
    if [ -s "$saved_ports_file" ]; then
        echo "🔄 Восстановление портов в iptables..."
        sort -u "$saved_ports_file" | while read -r entry; do
            local port=$(echo "$entry" | cut -d'/' -f1)
            local proto=$(echo "$entry" | cut -d'/' -f2)
            [ -z "$proto" ] && proto="tcp"
            # Проверяем что правило не дублируется
            if ! iptables -C INPUT -p "$proto" --dport "$port" -j ACCEPT 2>/dev/null; then
                iptables -I INPUT -p "$proto" --dport "$port" -j ACCEPT 2>/dev/null || true
                echo "   ✓ Открыт: ${port}/${proto}"
            fi
        done
    fi

    rm -f "$saved_ports_file"
    echo "✓ Миграция фаервола завершена"
}

# ─── Sysctl тюнинг ───────────────────────────────────────────────────────────
setup_sysctl() {
    prog 0.55 "Sysctl..."
    echo "⚙️  Настройка сетевых параметров..."

    echo 1 > /proc/sys/net/ipv4/ip_forward
    cat > /etc/sysctl.d/99-wdtt.conf << 'SYSEOF'
net.ipv4.ip_forward = 1
net.ipv6.conf.all.disable_ipv6 = 1
net.netfilter.nf_conntrack_udp_timeout = 300
net.netfilter.nf_conntrack_udp_timeout_stream = 300
SYSEOF

    echo 1 > /proc/sys/net/ipv6/conf/all/disable_ipv6 2>/dev/null || true
    echo 300 > /proc/sys/net/netfilter/nf_conntrack_udp_timeout 2>/dev/null || true
    echo 300 > /proc/sys/net/netfilter/nf_conntrack_udp_timeout_stream 2>/dev/null || true
    sysctl -p /etc/sysctl.d/99-wdtt.conf >/dev/null 2>&1 || true

    echo "✓ Sysctl настроен"
}

# ─── Открытие портов WDTT ────────────────────────────────────────────────────
setup_wdtt_firewall() {
    prog 0.65 "Firewall..."
    echo "🛡  Настройка фаервола для WDTT..."

    # ══ SSH PROTECTION ══ Всегда первое правило!
    iptables -C INPUT -p tcp --dport 22 -j ACCEPT 2>/dev/null || \
        iptables -I INPUT 1 -p tcp --dport 22 -j ACCEPT 2>/dev/null || true

    # ESTABLISHED/RELATED для стабильности SSH и других соединений
    iptables -C INPUT -m state --state ESTABLISHED,RELATED -j ACCEPT 2>/dev/null || \
        iptables -I INPUT 2 -m state --state ESTABLISHED,RELATED -j ACCEPT 2>/dev/null || true

    # WDTT control port
    iptables -C INPUT -p udp --dport 56000 -j ACCEPT 2>/dev/null || \
        iptables -I INPUT -p udp --dport 56000 -j ACCEPT 2>/dev/null || true

    # WireGuard port
    iptables -C INPUT -p udp --dport 51820 -j ACCEPT 2>/dev/null || \
        iptables -I INPUT -p udp --dport 51820 -j ACCEPT 2>/dev/null || true

    # Forward
    iptables -C FORWARD -j ACCEPT 2>/dev/null || \
        iptables -I FORWARD -j ACCEPT 2>/dev/null || true

    echo "✓ Порты открыты: 22/tcp(SSH), 56000/udp, 51820/udp, FORWARD"
}

# ─── Установка бинарника wdtt-server ──────────────────────────────────────────
setup_wdtt_binary() {
    prog 0.72 "Бинарник..."
    echo "📦 Установка wdtt-server..."

    if [ -f /tmp/wdtt-server ]; then
        chmod +x /tmp/wdtt-server
        mv /tmp/wdtt-server /usr/local/bin/wdtt-server
        echo "✓ wdtt-server установлен"
    elif [ -f /usr/local/bin/wdtt-server ]; then
        echo "✓ wdtt-server уже установлен"
    else
        echo "⚠ wdtt-server не найден в /tmp/ — пропускаем"
        echo "  Загрузите бинарник вручную в /usr/local/bin/wdtt-server"
    fi

    mkdir -p /etc/wireguard
}

# ─── Systemd-сервис WDTT ─────────────────────────────────────────────────────
setup_wdtt_service() {
    prog 0.85 "Сервис..."
    echo "🔧 Создание systemd-сервиса WDTT..."

    cat > /etc/systemd/system/wdtt.service << WDTTSVC
[Unit]
Description=WDTT VPN Server (Full Cone NAT)
After=network.target network-online.target fullconenat.service
Wants=network-online.target

[Service]
Type=simple
ExecStartPre=-/sbin/modprobe xt_FULLCONENAT
ExecStartPre=-/usr/bin/env bash -c "fuser -k -9 51820/udp 56000/udp || true"
ExecStartPre=-/usr/bin/env bash -c "for i in \$(ip -o link show type wireguard 2>/dev/null | awk -F': ' '{print \$2}' | cut -d'@' -f1); do ip link del \"\$i\" || true; done"
ExecStartPre=-/sbin/iptables -I INPUT -p udp --dport 56000 -j ACCEPT
ExecStartPre=-/sbin/iptables -I INPUT -p udp --dport 51820 -j ACCEPT
ExecStart=/usr/local/bin/wdtt-server -listen 0.0.0.0:56000 -config-dir /etc/wireguard ${WDTT_ARGS:-}
ExecStopPost=-/sbin/iptables -D INPUT -p udp --dport 56000 -j ACCEPT
ExecStopPost=-/sbin/iptables -D INPUT -p udp --dport 51820 -j ACCEPT
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
    prog 0.92 "Запуск..."
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
        if iptables -t nat -L POSTROUTING -n 2>/dev/null | grep -q FULLCONENAT; then
            echo "   NAT Режим: Full Cone ✅"
        else
            echo "   NAT Режим: Symmetric/MASQUERADE 🛡"
        fi
    else
        echo "⚠️ Сервис wdtt не запустился. Статус: $status"
        echo "   Последние логи ошибки:"
        journalctl -u wdtt -n 7 --no-pager 2>/dev/null | sed 's/^/   >> /'
    fi

    echo "   Логи:   journalctl -u wdtt -f"
    echo "   Статус: systemctl status wdtt"
    echo "══════════════════════════════════════════════════════════════"
    echo ""
}

# ══════════════════════════════════════════════════════════════════════════════
#  MAIN
# ══════════════════════════════════════════════════════════════════════════════
main() {
    show_banner
    parse_args "${1:-install}"
    check_root

    mkdir -p "$(dirname "$LOG_FILE")"
    echo "=== xt_FULLCONENAT + WDTT Installer v${SCRIPT_VERSION} — $(date) ===" >> "$LOG_FILE"

    detect_os
    detect_kernel

    case "$ACTION" in
        status)    do_status ;;
        uninstall) do_uninstall ;;
        rebuild)   do_rebuild ;;
        install)
            # ── Фаза 1: Очистка и подготовка ──
            wdtt_cleanup
            migrate_firewall

            # ── Фаза 2: Full Cone NAT (ядро + iptables) ──
            prog 0.10 "Зависимости..."
            check_kernel_config
            install_deps
            check_kernel_headers

            # Если заголовки не найдены — пропускаем сборку модуля
            if [ "${USE_MASQ_FALLBACK:-false}" != "true" ]; then
                prog 0.25 "Full Cone NAT..."
                download_source
                create_compat_header
                patch_source
                create_build_makefile

                prog 0.35 "Сборка NAT..."
                build_module
                prog 0.45 "Extension..."
                build_iptables_ext

                prog 0.50 "DKMS..."
                setup_dkms
                load_module
                setup_persistence
            else
                log_warn "Пропуск сборки Full Cone NAT (не найдены заголовки ядра)"
                log_info "VPN будет работать в режиме MASQUERADE"
            fi

            setup_fullconenat_rules
            setup_systemd_service
            verify_installation

            # ── Фаза 3: WDTT VPN Server ──
            setup_sysctl
            setup_wdtt_firewall
            setup_wdtt_binary
            setup_wdtt_service
            start_wdtt
            ;;
    esac
}

main "$@"

