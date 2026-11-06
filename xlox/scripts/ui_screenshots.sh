#!/bin/sh
# UI QA screenshot harness for the Android XLOX loader.
#
# reactivecircus/android-emulator-runner parses its `script:` input into an
# array of independent single-line `sh -c` commands, so all multi-line logic
# must live in a real script file like this one and be invoked with ONE line.
#
# Runs with cwd = xlox/ and writes traces to repo-root qa-logs/.

set -eu

LOG=../qa-logs/emulator-trace.txt
OUT=../docs/ui-redesign/screenshots
APK=app/build/outputs/apk/debug/app-debug.apk

mkdir -p ../qa-logs "$OUT"
: > "$LOG"

log() { echo "$*"; echo "$*" >> "$LOG"; }
fatal() { echo "::error::$*"; log "FATAL: $*"; exit 1; }

log "=== emulator QA started ==="
log "cwd=$(pwd)  serial=${ANDROID_SERIAL:-unknown}"
test -f "$APK" || fatal "debug APK missing at $APK"

adb wait-for-device || log "WARN: adb wait-for-device failed"
adb devices >> "$LOG" 2>&1 || true

# The loader ships arm64-v8a JNI only. On an x86_64 emulator a direct install
# usually returns INSTALL_FAILED_NO_MATCHING_ABIS, so build an ABI-neutral
# QA-only copy (native libs removed, resigned) when that happens. Every JNI
# call is already guarded by try/catch, so all screens still render.
adb uninstall com.xlox >/dev/null 2>&1 || true
INSTALL_OUT=$(adb install -r -g "$APK" 2>&1) && INSTALL_OK=1 || INSTALL_OK=0
if [ "$INSTALL_OK" = 1 ]; then
    log "direct install ok: $INSTALL_OUT"
else
    log "WARN: direct install failed: $INSTALL_OUT"
    adb uninstall com.xlox >/dev/null 2>&1 || true
    cp "$APK" /tmp/qa-unsigned.apk
    zip -q -d /tmp/qa-unsigned.apk 'lib/arm64-v8a/*' >/dev/null 2>&1 || true
    keytool -genkeypair -keystore /tmp/qa.keystore -alias qa \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass android -keypass android -dname "CN=Anoy UI QA" \
        >/dev/null 2>&1 || fatal "cannot create QA keystore"
    APKSIGNER=$(ls "${ANDROID_SDK_ROOT:-/nonexistent}"/build-tools/*/apksigner 2>/dev/null | sort | tail -1 || true)
    test -n "$APKSIGNER" || APKSIGNER=$(ls "${ANDROID_HOME:-/nonexistent}"/build-tools/*/apksigner 2>/dev/null | sort | tail -1 || true)
    test -n "$APKSIGNER" || fatal "apksigner not found in the installed build-tools"
    "$APKSIGNER" sign --ks /tmp/qa.keystore \
        --ks-pass pass:android --key-pass pass:android \
        --out /tmp/qa-signed.apk /tmp/qa-unsigned.apk \
        >> "$LOG" 2>&1 || fatal "cannot resign QA APK"
    INSTALL_OUT=$(adb install -r -g /tmp/qa-signed.apk 2>&1) && INSTALL_OK=1 || INSTALL_OK=0
    if [ "$INSTALL_OK" = 1 ]; then
        log "ABI-neutral install ok: $INSTALL_OUT"
    else
        fatal "ABI-neutral install failed: $INSTALL_OUT"
    fi
fi

adb shell appops set com.xlox MANAGE_EXTERNAL_STORAGE allow >> "$LOG" 2>&1 || true
adb shell appops set com.xlox REQUEST_INSTALL_PACKAGES allow >> "$LOG" 2>&1 || true
adb shell settings put secure show_ime_with_hard_keyboard 0 >> "$LOG" 2>&1 || true
adb shell settings put system font_scale 1.0 >> "$LOG" 2>&1 || true

capture() {
    name="$1"
    component="$2"
    appearance="$3"
    adb shell am force-stop com.xlox >/dev/null 2>&1 || true
    if ! adb shell am start -W -n "com.xlox/$component" --es qa_appearance "$appearance" >> "$LOG" 2>&1; then
        log "WARN: am start failed for $component"
    fi
    sleep 4
    adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1 || true
    adb shell dumpsys activity activities 2>/dev/null | grep -m1 'mResumedActivity' >> "$LOG" || true
    sleep 1
    if ! adb exec-out screencap -p > "$OUT/$name.png"; then
        fatal "screencap failed for $name"
    fi
    if ! test -s "$OUT/$name.png"; then
        fatal "empty screenshot for $name"
    fi
    log "captured $name.png ($(wc -c < "$OUT/$name.png") bytes)"
}

set_mode() {
    mode="$1"
    # Align the system bars too; the per-launch qa_appearance extra fixes the
    # app palette on debug builds (see ThemeMode.apply).
    if [ "$mode" = dark ]; then
        adb shell cmd uimode night yes >> "$LOG" 2>&1 || fatal "cannot enable night mode"
    else
        adb shell cmd uimode night no >> "$LOG" 2>&1 || fatal "cannot enable day mode"
    fi
    adb shell am force-stop com.xlox >/dev/null 2>&1 || true
    APPEARANCE="$mode"
    sleep 3
    log "appearance set to $mode"
}

for mode in light dark; do
    set_mode "$mode"
    capture "login_${mode}" ".LogAct" "$APPEARANCE"
    capture "main_${mode}" ".MAct" "$APPEARANCE"
    capture "facebook_${mode}" ".FacebookActivity" "$APPEARANCE"
    capture "components_${mode}" ".ui.designsystem.DsPreviewActivity" "$APPEARANCE"
done

# Font scale 1.3, then restore 1.0.
adb shell settings put system font_scale 1.3 >> "$LOG" 2>&1 || true
set_mode light
capture "login_font_130" ".LogAct" "$APPEARANCE"
capture "main_font_130" ".MAct" "$APPEARANCE"
adb shell settings put system font_scale 1.0 >> "$LOG" 2>&1 || true

# Narrow 320dp width to check clipping/overlap.
adb shell wm size 320x640 >> "$LOG" 2>&1 || true
adb shell wm density 160 >> "$LOG" 2>&1 || true
sleep 2
capture "login_320" ".LogAct" "$APPEARANCE"
capture "main_320" ".MAct" "$APPEARANCE"
adb shell wm size reset >> "$LOG" 2>&1 || true
adb shell wm density reset >> "$LOG" 2>&1 || true

log "=== all captures complete ==="
ls -lh "$OUT" >> "$LOG" 2>&1 || true
exit 0
