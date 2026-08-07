#!/bin/bash
# On-device e2e for the WebSocket/wstunnel transport.
#
# Drives the DEBUG build over adb against a live WireGuard/wstunnel server and asserts that a config
# imports, the tunnel comes up (handshake + traffic), and the VPN survives a Wi-Fi -> cellular switch.
# Runs two variants: a full-tunnel config (AllowedIPs = 0.0.0.0/0), asserting the ifconfig.me egress
# IP is the tunnel's and stays unchanged after Wi-Fi is turned off; and a split-tunnel config
# (AllowedIPs = 192.168.178.0/24), asserting the LAN gateway is reachable through the tunnel.
#
# Usage: scripts/e2e-android.sh <full-tunnel.conf> <split-tunnel.conf>
#
# Requires: an attached device with a SIM (for the cellular switch), the debug APK buildable, and the
# live server reachable. VPN consent is pre-granted via the ACTIVATE_VPN appop.
set -u

PKG=com.wireguard.android.debug
RCV="$PKG/com.wireguard.android.debug.TestReceiver"
LOG=/tmp/wireguard-android-e2e.log
FULL_NAME=e2e-full
SPLIT_NAME=e2e-split
GATEWAY=192.168.178.1

: >"$LOG"
exec > >(tee -a "$LOG") 2>&1

fail() {
    echo "FAIL: $1"
    exit 1
}

# bcast <ACTION> [am-broadcast args...] -> echoes the payload after the OK: prefix; fails on ERR:.
bcast() {
    local action="$1"; shift
    local out data
    out=$(adb shell am broadcast -n "$RCV" -a "com.wireguard.android.debug.$action" "$@" 2>&1)
    # am broadcast prints: Broadcast completed: result=0, data="OK:..."
    data=$(printf '%s\n' "$out" | sed -n 's/.*data="\(.*\)"/\1/p')
    if [ -z "$data" ]; then
        echo "  (no result data for $action: $out)" >&2
        return 1
    fi
    case "$data" in
        OK:*) printf '%s' "${data#OK:}" ;;
        *) echo "  ($action -> $data)" >&2; return 1 ;;
    esac
}

# wait_online <timeout-seconds>: poll until the device has raw internet connectivity (off-VPN).
wait_online() {
    local timeout="$1" waited=0
    while [ "$waited" -lt "$timeout" ]; do
        adb shell ping -c 1 -W 2 8.8.8.8 >/dev/null 2>&1 && return 0
        sleep 3
        waited=$((waited + 3))
    done
    return 1
}

wifi_off() {
    adb shell svc wifi disable >/dev/null 2>&1 || adb shell cmd wifi set-wifi-enabled disabled >/dev/null 2>&1
    # Fall back to cellular; wait until the underlying transport actually carries traffic so the VPN
    # has a path to re-dial over.
    wait_online 30 || fail "no cellular connectivity after disabling Wi-Fi (is mobile data on with a working SIM?)"
}

wifi_on() {
    adb shell svc wifi enable >/dev/null 2>&1 || adb shell cmd wifi set-wifi-enabled enabled >/dev/null 2>&1
    # Wait for Wi-Fi to actually associate + get DNS, not a fixed sleep.
    wait_online 30 || fail "no Wi-Fi connectivity after enabling Wi-Fi"
}

# wait_handshake <name> <timeout-seconds>: poll GET_STATE until handshake_epoch_ms > 0.
wait_handshake() {
    local name="$1" timeout="$2" waited=0 state
    while [ "$waited" -lt "$timeout" ]; do
        state=$(bcast GET_STATE --es name "$name") || true
        case "$state" in
            *handshake_epoch_ms=0\ *) ;;
            *handshake_epoch_ms=*) echo "$state"; return 0 ;;
        esac
        sleep 3
        waited=$((waited + 3))
    done
    return 1
}

cleanup() {
    # Best-effort restore (must not itself abort the trap).
    adb shell svc wifi enable >/dev/null 2>&1 || adb shell cmd wifi set-wifi-enabled enabled >/dev/null 2>&1
    bcast TUNNEL_DOWN --es name "$FULL_NAME" >/dev/null 2>&1 || true
    bcast TUNNEL_DOWN --es name "$SPLIT_NAME" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# --- Preconditions ---
[ "$#" -eq 2 ] || fail "usage: $0 <full-tunnel.conf> <split-tunnel.conf>"
FULL_CONF="$1"; SPLIT_CONF="$2"
[ -r "$FULL_CONF" ] || fail "cannot read $FULL_CONF"
[ -r "$SPLIT_CONF" ] || fail "cannot read $SPLIT_CONF"
[ "$(adb get-state 2>/dev/null)" = "device" ] || fail "no adb device attached"

# Enable cellular data so the Wi-Fi->cellular switch has a path (the SIM must be present/working).
adb shell svc data enable >/dev/null 2>&1 || true

# --- Build, install, pre-grant VPN consent ---
echo "== building and installing the debug APK =="
./gradlew :ui:assembleDebug || fail "assembleDebug"
APK=$(ls -t ui/build/outputs/apk/debug/*.apk 2>/dev/null | head -1)
[ -n "$APK" ] || fail "debug APK not found"
adb install -r "$APK" || fail "install"
adb shell appops set "$PKG" ACTIVATE_VPN allow || fail "appops ACTIVATE_VPN"

# --- Baseline egress IP (VPN down) ---
wifi_on
BASELINE_IP=$(bcast HTTP_GET --es url https://ifconfig.me/ip) || fail "baseline HTTP_GET"
echo "baseline egress IP: $BASELINE_IP"

# --- Variant A: full tunnel (0.0.0.0/0) ---
echo "== variant A: full tunnel =="
FULL_B64=$(base64 <"$FULL_CONF" | tr -d '\n')
bcast IMPORT_CONFIG --es name "$FULL_NAME" --es config_b64 "$FULL_B64" >/dev/null || fail "import full"
bcast TUNNEL_UP --es name "$FULL_NAME" >/dev/null || fail "up full"
wait_handshake "$FULL_NAME" 60 >/dev/null || fail "full-tunnel handshake"
VPN_IP=$(bcast HTTP_GET --es url https://ifconfig.me/ip) || fail "full HTTP_GET (wifi)"
[ -n "$VPN_IP" ] || fail "empty VPN egress IP"
echo "full-tunnel egress IP (wifi): $VPN_IP"
wifi_off
STATE=$(wait_handshake "$FULL_NAME" 90) || fail "full-tunnel handshake after wifi off"
echo "  $STATE"
VPN_IP_CELL=$(bcast HTTP_GET --es url https://ifconfig.me/ip) || fail "full HTTP_GET (cellular)"
[ "$VPN_IP_CELL" = "$VPN_IP" ] || fail "egress IP changed after wifi off ($VPN_IP -> $VPN_IP_CELL)"
echo "full-tunnel egress IP unchanged across wifi->cellular: $VPN_IP_CELL"
wifi_on
bcast TUNNEL_DOWN --es name "$FULL_NAME" >/dev/null || fail "down full"

# --- Variant B: split tunnel (192.168.178.0/24) ---
echo "== variant B: split tunnel =="
SPLIT_B64=$(base64 <"$SPLIT_CONF" | tr -d '\n')
bcast IMPORT_CONFIG --es name "$SPLIT_NAME" --es config_b64 "$SPLIT_B64" >/dev/null || fail "import split"
bcast TUNNEL_UP --es name "$SPLIT_NAME" >/dev/null || fail "up split"
wait_handshake "$SPLIT_NAME" 60 >/dev/null || fail "split-tunnel handshake"
PING=$(bcast PING --es host "$GATEWAY") || fail "ping gateway (wifi)"
[ "$PING" = "exit=0" ] || fail "gateway unreachable through tunnel (wifi): $PING"
echo "gateway reachable through tunnel (wifi)"
wifi_off
wait_handshake "$SPLIT_NAME" 90 >/dev/null || fail "split-tunnel handshake after wifi off"
PING_CELL=$(bcast PING --es host "$GATEWAY") || fail "ping gateway (cellular)"
[ "$PING_CELL" = "exit=0" ] || fail "gateway unreachable through tunnel (cellular): $PING_CELL"
echo "gateway reachable through tunnel across wifi->cellular"
wifi_on
bcast TUNNEL_DOWN --es name "$SPLIT_NAME" >/dev/null || fail "down split"

echo "PASS"
