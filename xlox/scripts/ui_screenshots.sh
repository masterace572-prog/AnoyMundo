#!/bin/sh
# UI QA screenshot harness for the Android loader (v2.0 black/blue UI).
# Runs with cwd = xlox/ on a booted emulator. Writes screenshots to
# docs/ui-redesign/screenshots and traces to qa-logs/ (repo root).
#
# Flow covered: splash -> update gate (required / downloading) -> sign in
# (keyboard, error) -> home (debug preview) -> font scale 1.3 -> 320 dp width
# -> minified release APK launch.
set -eu

LOG=../qa-logs/emulator-trace.txt
OUT=../docs/ui-redesign/screenshots
APK=app/build/outputs/apk/debug/app-debug.apk
RELEASE_APK=app/build/outputs/apk/release/app-release.apk

mkdir -p ../qa-logs "$OUT"
rm -f "$OUT"/*.png
: > "$LOG"

log() { echo "$*"; echo "$*" >> "$LOG"; }
fatal() { echo "::error::$*"; log "FATAL: $*"; exit 1; }

APKSIGNER=$(ls "${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/nonexistent}}"/build-tools/*/apksigner 2>/dev/null | sort | tail -1 || true)
keytool -genkeypair -keystore /tmp/qa.keystore -alias qa -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass android -keypass android -dname "CN=Anoy UI QA" >/dev/null 2>&1 || true

# The loader ships arm64-v8a JNI only. On the x86_64 emulator build an
# ABI-neutral, re-signed copy (native libs removed). Every JNI call is
# guarded, so every screen still renders.
install_apk() {
    src="$1"
    adb uninstall com.xlox >/dev/null 2>&1 || true
    if out=$(adb install -r -g "$src" 2>&1); then
        log "install ok: $src"
        return 0
    fi
    log "direct install failed ($out); stripping native libs"
    test -n "$APKSIGNER" || fatal "apksigner not found"
    cp "$src" /tmp/qa-unsigned.apk
    zip -q -d /tmp/qa-unsigned.apk 'lib/arm64-v8a/*' >/dev/null 2>&1 || true
    "$APKSIGNER" sign --ks /tmp/qa.keystore --ks-pass pass:android --key-pass pass:android \
        --out /tmp/qa-signed.apk /tmp/qa-unsigned.apk >> "$LOG" 2>&1 || fatal "cannot re-sign $src"
    out=$(adb install -r -g /tmp/qa-signed.apk 2>&1) || fatal "install failed: $out"
    log "ABI-neutral install ok: $src"
}

grant() {
    adb shell appops set com.xlox MANAGE_EXTERNAL_STORAGE allow >/dev/null 2>&1 || true
    adb shell pm grant com.xlox android.permission.READ_EXTERNAL_STORAGE >/dev/null 2>&1 || true
    adb shell pm grant com.xlox android.permission.WRITE_EXTERNAL_STORAGE >/dev/null 2>&1 || true
}

focus() { adb shell dumpsys window 2>/dev/null | grep -m1 'mCurrentFocus' | tr -d '\r' || true; }

shot() {
    name="$1"
    adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1 || true
    sleep 0.4
    adb exec-out screencap -p > "$OUT/$name.png" || log "WARN: screencap failed for $name"
    log "focus for $name: $(focus)"
    log "captured $name.png ($(wc -c < "$OUT/$name.png") bytes)"
}

dump_ui() {
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || true
    adb shell cat /sdcard/ui.xml 2>/dev/null || true
}

# Prints "x y" (centre) of the node with the given resource id, or nothing.
centre_of() {
    dump_ui | python3 -c '
import re, sys
xml = sys.stdin.read()
m = re.search(r"resource-id=\"com\.xlox:id/%s\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"" % sys.argv[1], xml)
if m:
    x1, y1, x2, y2 = map(int, m.groups())
    print((x1 + x2) // 2, (y1 + y2) // 2)
' "$1"
}

has_id() { dump_ui | grep -q "com.xlox:id/$1"; }

tap_id() {
    xy=$(centre_of "$1")
    if [ -z "$xy" ]; then log "WARN: $1 not on screen"; return 1; fi
    # shellcheck disable=SC2086
    adb shell input tap $xy
}

wait_for_id() {
    id="$1"; secs="$2"; i=0
    while [ "$i" -lt "$secs" ]; do
        if has_id "$id"; then return 0; fi
        sleep 1; i=$((i + 1))
    done
    return 1
}

wait_gone() {
    id="$1"; secs="$2"; i=0
    while [ "$i" -lt "$secs" ]; do
        if ! has_id "$id"; then return 0; fi
        sleep 1; i=$((i + 1))
    done
    return 1
}

# Opens sign-in directly and waits until the update gate has let it through.
open_login() {
    adb shell am force-stop com.xlox >/dev/null 2>&1 || true
    adb shell am start -W -n com.xlox/.LogAct >> "$LOG" 2>&1 || log "WARN: am start LogAct failed"
    sleep 1
    wait_gone gateTitle 45 || log "WARN: update dialog still visible"
    wait_for_id keyField 10 || log "WARN: sign-in form missing"
    sleep 1
}

open_home_preview() {
    adb shell am force-stop com.xlox >/dev/null 2>&1 || true
    adb shell am start -W -n com.xlox/.MAct --ez qa_preview true >> "$LOG" 2>&1 || log "WARN: am start MAct failed"
    sleep 2
}

log "=== emulator QA started $(date -u) ==="
test -f "$APK" || fatal "debug APK missing at $APK"
adb wait-for-device
adb shell settings put system font_scale 1.0 >/dev/null 2>&1 || true
adb shell settings put secure show_ime_with_hard_keyboard 1 >/dev/null 2>&1 || true
adb logcat -c || true

install_apk "$APK"
grant

# 1. Splash (fresh start through the launcher activity).
# qa_hold (debug builds only) keeps the splash up so the shot is deterministic.
adb shell am start -W -n com.xlox/.SplashActivity --ez qa_hold true >> "$LOG" 2>&1 || fatal "cannot start SplashActivity"
sleep 1.5   # let the fade-in finish
shot splash
# Real cold start through the launcher activity (no hold) for the update gate.
adb shell am force-stop com.xlox >/dev/null 2>&1 || true
adb shell am start -W -n com.xlox/.SplashActivity >> "$LOG" 2>&1 || fatal "cannot start SplashActivity"

# 2. Update gate: a fresh install has no library, so the live panel's
#    release must be installed before sign in is possible.
if wait_for_id gateAction 40; then
    shot update_required
    gate_text=$(dump_ui | grep -o 'text="[^"]*"' | tr '\n' ' ')
    log "gate texts: $gate_text"
    tap_id gateAction || true
    sleep 0.3
    shot update_downloading
    if wait_gone gateTitle 90; then
        log "update gate passed"
    else
        shot update_stuck
        log "WARN: update did not finish within 90 s"
    fi
else
    log "WARN: no update action shown (already current or check failed)"
    shot update_state
    wait_gone gateTitle 45 || true
fi
log "gate result focus: $(focus)"

# 3. Sign in: idle, keyboard, validation error.
wait_for_id keyField 10 || log "WARN: sign-in form missing after the gate"
sleep 1
shot login
tap_id keyField || true
sleep 1.5
log "keyboard state: $(adb shell dumpsys input_method | grep -m1 -E 'mInputShown|isInputViewShown' | tr -d '\r')"
shot login_keyboard
adb shell input text 'ab' || true
adb shell input keyevent 111 || adb shell input keyevent 4 || true   # hide keyboard
sleep 0.8
tap_id login || true
sleep 1.2
shot login_error
tap_id toggleKey || true   # eye icon button: key shown, icon turns blue
sleep 0.8
shot login_key_visible

# 4. Home (debug-only preview with sample data, no native session needed).
open_home_preview
shot home

# 5. Large text.
adb shell settings put system font_scale 1.3
open_login
shot login_font130
open_home_preview
shot home_font130
adb shell settings put system font_scale 1.0

# 6. Narrow phone (320 dp wide).
adb shell wm size 640x1280 || true
adb shell wm density 320 || true
sleep 2
open_login
shot login_320
open_home_preview
shot home_320
adb shell wm size reset || true
adb shell wm density reset || true
sleep 2

adb logcat -d > ../qa-logs/logcat.txt 2>/dev/null || true

# 7. Minified release build: must launch, pass the gate and reach sign in.
if [ -f "$RELEASE_APK" ]; then
    adb logcat -c || true
    install_apk "$RELEASE_APK"
    grant
    adb shell am start -W -n com.xlox/.SplashActivity >> "$LOG" 2>&1 || log "WARN: release launch failed"
    sleep 3
    if wait_for_id gateAction 30; then
        tap_id gateAction || true
        wait_gone gateTitle 90 || log "WARN: release update did not finish"
    fi
    sleep 2
    log "release focus: $(focus)"
    shot release_login
    adb logcat -d > ../qa-logs/release-logcat.txt 2>/dev/null || true
else
    log "WARN: release APK missing at $RELEASE_APK"
fi

log "=== emulator QA finished $(date -u) ==="
