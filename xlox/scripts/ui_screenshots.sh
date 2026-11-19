#!/bin/sh
# UI QA harness for the Android loader (2.1 redesign).
# Runs with cwd = xlox/ on a booted emulator (animations off = reduced motion).
# Screenshots -> $SHOT_DIR (default docs/ui-redesign/after); traces -> ../qa-logs.
#
# Real walkthrough: splash -> live update gate (required / downloading / done)
# -> sign in (empty, keyboard, malformed, key visible) -> airplane mode (gate
# check failed + offline notice) -> sign out confirm -> sign in again.
# Debug-only extras (BuildConfig.DEBUG) render the remaining states with sample
# data: every update-gate state, sign-in loading / wrong key / maintenance /
# success, home expired / notes / skeletons / launch states / Snackbar.
# Then: animations on (live splash frames + screenrecord), font 0.85 and 2.0,
# 360 dp phone, tablet, minified release build.
set -eu

LOG=../qa-logs/emulator-trace.txt
OUT=${SHOT_DIR:-docs/ui-redesign/after}
APK=app/build/outputs/apk/debug/app-debug.apk
RELEASE_APK=app/build/outputs/apk/release/app-release.apk
# Without the signing secrets Gradle names the release build *-unsigned.apk.
[ -f "$RELEASE_APK" ] || RELEASE_APK=app/build/outputs/apk/release/app-release-unsigned.apk
PKG=com.xlox

mkdir -p ../qa-logs "$OUT"
rm -f "$OUT"/*.png "$OUT"/*.mp4
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
    adb uninstall $PKG >/dev/null 2>&1 || true
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
    adb shell appops set $PKG MANAGE_EXTERNAL_STORAGE allow >/dev/null 2>&1 || true
    adb shell pm grant $PKG android.permission.READ_EXTERNAL_STORAGE >/dev/null 2>&1 || true
    adb shell pm grant $PKG android.permission.WRITE_EXTERNAL_STORAGE >/dev/null 2>&1 || true
    adb shell appops set $PKG REQUEST_INSTALL_PACKAGES allow >/dev/null 2>&1 || true
}

focus() { adb shell dumpsys window 2>/dev/null | grep -m1 'mCurrentFocus' | tr -d '\r' || true; }

# Emulator flake (run 74): a "System UI isn't responding" dialog can sit over
# every screen from boot. Dismiss another app's ANR dialog ("Wait"); an ANR
# of Anoy itself is left on screen and logged so the run fails visibly.
dismiss_anr() {
    f=$(adb shell dumpsys window 2>/dev/null | grep -m1 'mCurrentFocus=' | tr -d '\r' || true)
    case "$f" in
        *"Not Responding: com.xlox"*) log "ERROR: Anoy itself is not responding: $f" ;;
        *"Not Responding"*)
            log "dismissing a system ANR dialog: $f"
            xy=$(dump_ui | python3 -c '
import re, sys
m = re.search(r"text=\"Wait\"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", sys.stdin.read())
print("%d %d" % ((int(m.group(1)) + int(m.group(3))) // 2, (int(m.group(2)) + int(m.group(4))) // 2) if m else "")
')
            # shellcheck disable=SC2086
            if [ -n "$xy" ]; then adb shell input tap $xy; else adb shell input keyevent 4; fi
            sleep 1 ;;
    esac
    return 0
}

shot() {
    name="$1"
    dismiss_anr
    sleep 0.4
    adb exec-out screencap -p > "$OUT/$name.png" || log "WARN: screencap failed for $name"
    log "focus for $name: $(focus)"
    log "captured $name.png ($(wc -c < "$OUT/$name.png") bytes)"
}

dump_ui() {
    # No stale screen: a dump that can't reach an idle UI leaves nothing behind.
    adb shell rm -f /sdcard/ui.xml >/dev/null 2>&1 || true
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

has_id() { dump_ui | grep -q "com.xlox:id/$1\""; }

texts() { dump_ui | grep -o 'text="[^"]*"' | grep -v 'text=""' | tr '\n' ' '; }

node_text() { # $1 = view id: its text
    dump_ui | python3 -c '
import re, sys
xml = sys.stdin.read()
m = re.search(r"<node[^>]*resource-id=\"com\.xlox:id/%s\"[^>]*>" % sys.argv[1], xml)
t = re.search(r" text=\"([^\"]*)\"", m.group(0)) if m else None
print(t.group(1).replace("&#10;", " | ").replace("\n", " | ") if t else "missing")
' "$1"
}

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

stop_app() { adb shell am force-stop $PKG >/dev/null 2>&1 || true; }

hide_keyboard() { adb shell input keyevent 111 >/dev/null 2>&1 || true; sleep 0.6; }

# Opens sign in and waits until the (live) update gate has let it through.
# Extra arguments are passed to am start (e.g. --es qa_result wrong).
open_login() {
    stop_app
    # shellcheck disable=SC2068
    adb shell am start -W -n $PKG/.LogAct $@ >> "$LOG" 2>&1 || log "WARN: am start LogAct failed"
    dismiss_anr
    sleep 1
    wait_gone gateTitle 45 || log "WARN: update sheet still visible"
    wait_for_id keyField 10 || log "WARN: sign-in form missing"
    hide_keyboard
}

# Update-gate state rendered by the debug preview (no network).
open_gate() {
    stop_app
    adb shell am start -W -n $PKG/.LogAct --es qa_gate "$1" >> "$LOG" 2>&1 || log "WARN: gate preview failed"
    dismiss_anr
    wait_for_id gateTitle 10 || log "WARN: gate preview $1 missing"
    sleep 1.2
}

# Home with sample data (debug builds; no native session needed).
open_home() {
    stop_app
    # shellcheck disable=SC2068
    adb shell am start -W -n $PKG/.MAct --ez qa_preview true --ez qa_freeze_clock true $@ >> "$LOG" 2>&1 || log "WARN: am start MAct failed"
    dismiss_anr
    wait_for_id primaryAction 10 || log "WARN: home missing"
    sleep 1.5
}

# Home preview, then the Settings icon in the header (2.2.0).
open_settings() {
    # shellcheck disable=SC2068
    open_home $@
    tap_id settingsButton || true
    wait_for_id settingsPage 6 || log "WARN: settings page missing"
    sleep 0.8
}

# Scrolls the Settings page to its end (Sign out sits at the bottom).
scroll_settings() {
    xy=$(centre_of settingsScroll)
    if [ -z "$xy" ]; then log "WARN: settingsScroll not on screen"; return 1; fi
    x=${xy% *}; y=${xy#* }
    for _ in 1 2 3; do
        adb shell input swipe "$x" $((y + 500)) "$x" $((y - 500)) 250 >/dev/null 2>&1 || true
        sleep 0.5
    done
    sleep 0.6
}

airplane() {
    adb shell cmd connectivity airplane-mode "$1" >/dev/null 2>&1 \
        || adb shell settings put global airplane_mode_on "$([ "$1" = enable ] && echo 1 || echo 0)" >/dev/null 2>&1 || true
    sleep 3
    log "airplane mode $1"
}

log "=== emulator QA started $(date -u) ==="
test -f "$APK" || fatal "debug APK missing at $APK"
adb wait-for-device
# No crash / ANR dialogs from other processes over the screenshots (Anoy's own
# crashes are still caught from logcat by the validator).
adb shell settings put global hide_error_dialogs 1 >/dev/null 2>&1 || true
dismiss_anr
adb shell settings put system font_scale 1.0 >/dev/null 2>&1 || true
adb shell settings put secure show_ime_with_hard_keyboard 1 >/dev/null 2>&1 || true
adb logcat -c || true

install_apk "$APK"
grant

# ---------------------------------------------------------------- 1. splash
# Still frames of the ~3 s film (qa_frame = film time in seconds):
#   0.00 hand-off frame (= system splash wordmark)   0.25 arriving
#   0.80 welcome lockup   1.40 greeting fading upward, name gliding to centre
#   1.82 "Hax" arriving over a still-solid "Loader" (overlap starts)
#   1.95 both words overlapping mid cross-dissolve, re-centring
#   2.40 final "Anoy Hax"
for f in 0.00 0.25 0.80 1.40 1.82 1.95 2.40; do
    stop_app
    adb shell am start -W -n $PKG/.SplashActivity --ef qa_frame "$f" >> "$LOG" 2>&1 || fatal "cannot start SplashActivity"
    sleep 1.2
    shot "splash_t$(echo "$f" | tr -d '.')"
done
# Reduced motion (animations are off on the QA emulator): the welcome lockup
# holds for 1 s, then cuts straight to "Anoy Hax" (held, no hand-off).
stop_app
adb shell am start -W -n $PKG/.SplashActivity --ez qa_hold true >> "$LOG" 2>&1 || fatal "cannot start SplashActivity"
sleep 0.3
shot splash_reduced_welcome
sleep 1.7
shot splash

# ----------------------------------- 1b. splash status line + app update (2.2.0)
# The check's answers, simulated (qa_app_update, debug only, no network), on
# the final film frame; the version sits top right.
for st in up_to_date available fail; do
    stop_app
    adb shell am start -W -n $PKG/.SplashActivity --ef qa_frame 2.40 --es qa_app_update "$st" >> "$LOG" 2>&1 || true
    sleep 2.2
    shot "splash_status_$st"
    log "splash status $st texts: $(texts)"
done
# Normal update: changelog, Update now + Not now; a simulated download ends in
# a clear failure with Retry; Not now continues to sign in.
stop_app
adb shell am start -n $PKG/.SplashActivity --es qa_app_update available >> "$LOG" 2>&1 || true
if wait_for_id appUpdatePrimary 20; then
    sleep 0.8
    shot app_update_normal
    log "app update normal texts: $(texts)"
    tap_id appUpdatePrimary || true
    sleep 1.5
    shot app_update_downloading
    i=0
    while [ $i -lt 20 ]; do
        case "$(texts)" in *"text=\"Retry\""*) break ;; esac
        sleep 0.5; i=$((i + 1))
    done
    shot app_update_failed
    log "app update failed texts: $(texts)"
    tap_id appUpdateLater || true
    sleep 2
    log "app update not now focus: $(focus)"
else
    log "WARN: app update screen did not open"
fi
# Mandatory: no Not now, back does not leave.
stop_app
adb shell am start -n $PKG/.SplashActivity --es qa_app_update mandatory >> "$LOG" 2>&1 || true
if wait_for_id appUpdatePrimary 20; then
    sleep 0.8
    shot app_update_mandatory
    log "app update mandatory texts: $(texts)"
    adb shell input keyevent 4 || true
    sleep 1.2
    log "app update mandatory back focus: $(focus)"
else
    log "WARN: mandatory update screen did not open"
fi
# Installing state, straight from the screen's own preview.
stop_app
adb shell am start -W -n $PKG/.AppUpdateActivity --es app_update_name 2.4.0 --ei app_update_code 99 \
    --es app_update_url https://github.com/masterace572-prog/AnoyMundo/releases/download/sample/Anoy.apk \
    --ez qa_update_preview true --es qa_update_state installing >> "$LOG" 2>&1 || true
sleep 1.5
shot app_update_state_installing
log "app update installing texts: $(texts)"
stop_app
adb shell am start -W -n $PKG/.AppUpdateActivity --es app_update_name 2.4.0 --ei app_update_code 99 \
    --es app_update_url https://github.com/masterace572-prog/AnoyMundo/releases/download/sample/Anoy.apk \
    --ez qa_update_preview true --es qa_update_state downloading >> "$LOG" 2>&1 || true
sleep 1.5
log "app update downloading texts: $(texts)"

# ------------------------------------------- 2. cold start + live update gate
stop_app
adb shell am start -n $PKG/.SplashActivity >> "$LOG" 2>&1 || fatal "cannot start SplashActivity"
sleep 0.8
shot splash_live_cold
if wait_for_id gateAction 40; then
    shot live_gate_required
    log "gate texts: $(texts)"
    tap_id gateAction || true
    sleep 0.3
    shot live_gate_downloading
    i=0
    while [ $i -lt 90 ]; do
        t=$(texts)
        case "$t" in *"up to date"*) shot live_gate_done; break ;; esac
        has_id gateTitle || break
        sleep 0.5; i=$((i + 1))
    done
    if wait_gone gateTitle 90; then log "update gate passed"; else shot live_gate_stuck; log "WARN: update did not finish"; fi
else
    log "WARN: no update action shown (already current or check failed)"
    shot live_gate_state
    wait_gone gateTitle 45 && log "update gate passed" || true
fi
log "gate result focus: $(focus)"
log "splash live log: $(adb logcat -d -v tag -s SplashActivity:V 2>/dev/null | grep -E 'Checking for an app update|App is up to date|App update' | tail -4 | tr -d '\r' | tr '\n' '|')"

# ------------------------------------------------------- 3. sign in (real)
wait_for_id keyField 10 || log "WARN: sign-in form missing after the gate"
hide_keyboard
shot login
log "login texts: $(texts)"
log "login button: $(node_text login)"
tap_id userkey || tap_id keyField || true
sleep 1.5
log "keyboard state: $(adb shell dumpsys input_method | grep -m1 -E 'mInputShown|isInputViewShown' | tr -d '\r')"
shot login_keyboard
adb shell input keyevent 66 || true          # Done on an empty field
sleep 1
shot login_error_empty
adb shell input text 'ab' || true
hide_keyboard
tap_id login || true
sleep 1.2
shot login_error_malformed
tap_id toggleKey || true
sleep 0.8
shot login_key_visible

# ------------------------------------------------- 4. airplane mode (real)
# Splash: the app update check can't run -> reason + Retry, never a silent
# continue; back online, Retry goes on to sign in.
airplane enable
stop_app
adb shell am start -n $PKG/.SplashActivity >> "$LOG" 2>&1 || true
if wait_for_id splashRetry 40; then
    sleep 1.5
    shot splash_check_failed_live
    log "splash offline texts: $(texts)"
    log "splash offline focus: $(focus)"
else
    log "WARN: splash Retry not shown offline"
fi
airplane disable
sleep 2
tap_id splashRetry || true
if wait_for_id keyField 60 || wait_for_id gateTitle 5; then log "splash retry: passed"; else log "WARN: splash retry did not continue"; fi
log "splash retry focus: $(focus)"
stop_app
airplane enable
stop_app
adb shell am start -W -n $PKG/.LogAct >> "$LOG" 2>&1 || true
if wait_for_id gateAction 30; then
    sleep 1
    shot gate_check_failed_live
    log "offline gate texts: $(texts)"
fi
airplane disable
tap_id gateAction || true                    # Try again -> passes
wait_gone gateTitle 45 || log "WARN: gate did not pass after Try again"
wait_for_id keyField 10 || true
airplane enable
tap_id userkey || true
adb shell input text 'ANOY-7D-QA7K2' || true
hide_keyboard
tap_id login || true
sleep 1.5
shot login_offline
airplane disable

# --------------------------------------- 5. sign-in states (debug extras)
for r in loading wrong maintenance server vpn success; do
    open_login --es qa_result "$r"
    sleep 1.6
    shot "login_$r"
done
# Success hands off to home (real 400 ms hold, then the home screen).
open_login --es qa_result ok
wait_for_id primaryAction 15 || log "WARN: success did not reach home"
sleep 1.5
shot login_ok_home

# -------------------------------------------- 6. update-gate states (sheet)
for g in checking required downloading downloading_unknown installing done check_failed download_failed; do
    open_gate "$g"
    shot "gate_$g"
done

# ------------------------------------------------------------- 7. home
open_home
shot home
adb shell input swipe 540 1700 540 400 400 || true
sleep 1
shot home_scrolled
open_home --es qa_note "''"
shot home_note_empty
open_home --es qa_note "'SERVER UPDATE TONIGHT. PLAY ON THE NEW VERSION ONLY 🔥'"
shot home_note_custom
open_home --es qa_expiry past
shot home_expired
open_home --es qa_expiry hours
shot home_under_day
# IST: the panel's expiry instant (ISO UTC, as /connect sends it) must read in
# Indian time on this UTC emulator - the same text the website shows.
open_home --es qa_expiry 2026-11-14T17:00:00.000Z
log "expiry_ist texts: $(texts)"
shot home_expiry_ist
open_home --ez qa_loading true
shot home_loading
open_home --es qa_status "'Unsafe - detection active'"
shot home_server_warning
open_home --es qa_action preparing
shot home_preparing
open_home --es qa_action launching
shot home_launching
open_home --es qa_action error
shot home_error
open_home --es qa_action snackbar
sleep 0.5
shot home_snackbar

# Sign out (real): confirm sheet, then back to sign in. "Sign out" keeps the
# saved key (field filled); "Forget this key" removes it (field empty).
key_text() { # " text=<field text> len=<n>" of the licence key field
    dump_ui | python3 -c '
import re, sys
xml = sys.stdin.read()
m = re.search(r"<node[^>]*resource-id=\"com\.xlox:id/userkey\"[^>]*>", xml)
t = re.search(r" text=\"([^\"]*)\"", m.group(0)) if m else None
print(" text=%s len=%d" % (t.group(1), len(t.group(1))) if t else " missing")
'
}
saved_key() { adb shell run-as $PKG cat shared_prefs/settings.xml 2>/dev/null | grep -c 'name="USER"' || true; }
open_settings --es qa_saved_key ANOY-30D-QA7K2
shot home_settings
log "settings texts: $(texts)"
log "settings licence rows: key=$(node_text licenceKeyValue) status=$(node_text licenceStatusValue) expires=$(node_text licenceExpiresValue) library=$(node_text libraryVersionValue)"
log "settings access group: $(has_id stateGroup && echo present || echo gone)"
scroll_settings
shot home_settings_bottom
log "settings bottom texts: $(texts)"
tap_id signOut || true
sleep 1.2
shot home_signout_sheet
log "signout sheet texts: $(texts)"
tap_id signOutConfirm || true
wait_for_id keyField 45 || log "WARN: sign out did not reach sign in"
wait_gone gateTitle 45 || true
hide_keyboard
shot signout_done
log "signout focus: $(focus)"
log "signout key field:$(key_text)"
log "signout saved key: $(saved_key)"
open_settings --es qa_saved_key ANOY-30D-QA7K2
scroll_settings
tap_id signOut || true
sleep 1.2
tap_id signOutForget || true
wait_for_id keyField 45 || log "WARN: forget this key did not reach sign in"
wait_gone gateTitle 45 || true
hide_keyboard
shot forget_key_done
log "forget focus: $(focus)"
log "forget key field:$(key_text)"
log "forget saved key: $(saved_key)"

# Launch result handling (debug qa_launch simulates the container's answer;
# the emulator has no BGMI). The text dump after each must not contain the
# launch error unless the game truly did not start.
launch_case() {
    stop_app
    adb shell am start -W -n $PKG/.MAct --ez qa_preview true --ez qa_freeze_clock true --es qa_launch "$1" >> "$LOG" 2>&1 || log "WARN: am start MAct failed"
    sleep "$2"
}
launch_case started 4
log "launch started target focus: $(focus)"
adb shell input keyevent 4 || true            # back from the stand-in "game"
sleep 2
shot home_launch_started_return
log "launch started texts: $(texts)"
launch_case started_polish_throws 4
adb shell input keyevent 4 || true
sleep 2
shot home_launch_polish_throws_return
log "launch started_polish_throws texts: $(texts)"
launch_case null_running 4
shot home_launch_null_running
log "launch null_running texts: $(texts)"
launch_case null_dead 7
shot home_launch_failed
log "launch null_dead texts: $(texts)"
launch_case throws 7
log "launch throws texts: $(texts)"

# Game closed outside Anoy (qa_game_ms: the stand-in game "closes" 12 s after
# the launch): home shows Stop while it runs, then Launch again - no error.
stop_app
adb logcat -c || true
adb shell am start -W -n $PKG/.MAct --ez qa_preview true --ez qa_freeze_clock true --es qa_launch started --el qa_game_ms 12000 >> "$LOG" 2>&1 || log "WARN: am start MAct failed"
sleep 4
log "game_closed target focus: $(focus)"
adb shell input keyevent 4 || true            # back to home while the game still runs
sleep 2
log "game_closed running texts: $(texts)"
sleep 14
shot home_game_closed
log "game_closed after texts: $(texts)"
log "game_closed log: $(adb logcat -d -v tag -s MAct:V 2>/dev/null | grep -E 'Game:|Launch failed' | tr -d '\r' | tr '\n' '|')"

# Licence refresh on return to home (qa_refresh simulates the panel's answer;
# the refresh itself, the countdown and the card are the real code).
licence_return() { # $1 = qa_refresh, $2 = screenshot
    stop_app
    adb logcat -c || true
    adb shell am start -W -n $PKG/.MAct --ez qa_preview true --ez qa_freeze_clock true --es qa_refresh "$1" >> "$LOG" 2>&1 || log "WARN: am start MAct failed"
    sleep 3
    log "licence_$1 before texts: $(texts)"
    adb shell am start -W -a android.settings.SETTINGS >> "$LOG" 2>&1 || true
    sleep 1.5
    adb shell input keyevent 4 || true
    sleep 1
    case "$(focus)" in
        *com.xlox*) ;;
        *) log "licence_$1: back did not return to Anoy, bringing its task forward"
           adb shell am start -n $PKG/.MAct >> "$LOG" 2>&1 || true ;;
    esac
    sleep 2
    shot "$2"
    log "licence_$1 after texts: $(texts)"
    log "licence_$1 log: $(adb logcat -d -v tag -s MAct:V 2>/dev/null | grep -E 'Licence|No active session' | tr -d '\r' | tr '\n' '|')"
}
licence_return extend home_licence_extended
licence_return soon home_licence_soon
licence_return fail home_licence_offline
licence_return expired home_licence_expired_by_panel



# TalkBack text of the licence card (one sentence).
open_home
log "licence card description: $(dump_ui | grep -o 'resource-id="com.xlox:id/licenceCard"[^>]*' | grep -o 'content-desc="[^"]*"')"

adb logcat -d > ../qa-logs/logcat.txt 2>/dev/null || true

# ------------------------------------------------ 8. animations on (live)
adb shell settings put global window_animation_scale 1.0 || true
adb shell settings put global transition_animation_scale 1.0 || true
adb shell settings put global animator_duration_scale 1.0 || true
stop_app
( adb shell screenrecord --time-limit 6 /sdcard/splash.mp4 >/dev/null 2>&1 || true ) &
REC=$!
sleep 0.5
adb shell am start -n $PKG/.SplashActivity >> "$LOG" 2>&1 || true
sleep 0.5
shot anim_splash_t05
sleep 0.5
shot anim_splash_t10
sleep 0.5
shot anim_splash_t15
sleep 0.5
shot anim_splash_t20
wait $REC 2>/dev/null || true
adb pull /sdcard/splash.mp4 "$OUT/anim_splash.mp4" >/dev/null 2>&1 && log "screenrecord ok" || log "WARN: screenrecord unavailable"
wait_gone gateTitle 45 || true
wait_for_id keyField 10 || true
hide_keyboard
shot anim_login
open_gate checking
shot anim_gate_checking
open_gate done
sleep 0.2
shot anim_gate_done
open_home --ez qa_loading true
shot anim_home_loading
adb shell settings put global window_animation_scale 0.0 || true
adb shell settings put global transition_animation_scale 0.0 || true
adb shell settings put global animator_duration_scale 0.0 || true

# ---------------------------------------------------------- 9. font scale
for fs in 0.85 2.0; do
    tag=$(echo "$fs" | tr -d '.')
    adb shell settings put system font_scale "$fs"
    sleep 1
    open_login
    shot "login_font$tag"
    open_home
    shot "home_font$tag"
    adb shell input swipe 540 1700 540 400 400 || true
    sleep 1
    shot "home_font${tag}_scrolled"
    open_gate required
    shot "gate_font$tag"
done
adb shell settings put system font_scale 1.0

# ------------------------------------------------- 10. 360 dp and tablet
size_pass() {
    label="$1"; size="$2"; density="$3"
    adb shell wm size "$size" || true
    adb shell wm density "$density" || true
    sleep 3
    open_login
    shot "login_$label"
    open_home
    shot "home_$label"
    open_gate required
    shot "gate_$label"
}
size_pass 360 720x1440 320
size_pass tablet 1600x2560 320
adb shell wm size reset || true
adb shell wm density reset || true
sleep 2

# Typical 20:9 phone (411 x 914 dp): the GAME card must clear the pinned Launch bar
# without scrolling. Measured from the view bounds, not eyeballed.
adb shell wm size 1080x2400 || true
adb shell wm density 420 || true
sleep 3
open_home
shot home_typical
dump_ui | python3 -c '
import re, sys
xml = sys.stdin.read()
def box(i):
    m = re.search(r"resource-id=\"com\.xlox:id/%s\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"" % i, xml)
    return tuple(map(int, m.groups())) if m else None
card, bar = box("gameCard"), box("actionBar")
# 2.3.0: the pinned bar has no fade; its top edge (12 dp above Launch) is the limit.
if card and bar:
    clear = card[3] <= bar[1]
    print("game card bottom %d, launch bar top %d: %s" % (card[3], bar[1], "clear" if clear else "covered"))
else:
    print("game card bounds missing")
' | while read -r line; do log "typical phone: $line"; done
adb shell wm size reset || true
adb shell wm density reset || true
sleep 2

# Launcher icon: app drawer, recents and every icon-shape overlay available.
stop_app
adb shell am start -W -n $PKG/.SplashActivity >> "$LOG" 2>&1 || true
sleep 1.5
adb shell input keyevent 187 || true          # recents
sleep 2
adb exec-out screencap -p > "$OUT/icon_recents.png" || true
log "captured icon_recents.png"
adb shell input keyevent 3 || true            # home
sleep 1
for shape in default circle squircle roundedrect teardrop; do
    if [ "$shape" != default ]; then
        adb shell cmd overlay enable-exclusive --category "com.android.theme.icon.$shape" >> "$LOG" 2>&1 \
            || { log "icon shape overlay $shape unavailable"; continue; }
        sleep 3
    fi
    adb shell input keyevent 3 || true
    sleep 1
    adb shell input swipe 540 1900 540 300 300 || true   # open the app drawer
    sleep 2
    adb exec-out screencap -p > "$OUT/icon_drawer_$shape.png" || true
    log "captured icon_drawer_$shape.png"
    log "drawer icon $shape: $(dump_ui | grep -o 'content-desc="Anoy[^"]*"[^>]*bounds="[^"]*"' | head -1)"
    adb shell input keyevent 3 || true
done
for shape in circle squircle roundedrect teardrop; do
    adb shell cmd overlay disable "com.android.theme.icon.$shape" >/dev/null 2>&1 || true
done

adb logcat -d > ../qa-logs/logcat-sizes.txt 2>/dev/null || true

# ------------------------------------------ Game OBB copy before launch
# The real AppManager + container storage. qa_action obb_copy runs the
# copy-then-launch that follows the pre-checks (no activated container here);
# qa_launch=started makes "the game" a stand-in screen (Settings).
# Stock Android 11+ only lets "installer" apps read another app's
# Android/obb folder; Anoy does not ask for that. obb0 checks the honest
# message on such a phone; obb1/obb2 simulate a phone where Android allows the
# read (test-only appops switch, the app itself is unchanged).
#   obb0: folder blocked - clear message, no launch.
#   obb1: first install - must copy main + patch with progress, then launch.
#   obb2: second tap   - truly current: skip the copy, then launch.
#   obb3: no game OBB  - missing-files message, no launch.
GAME=com.pubg.imobile
GOBB=/sdcard/Android/obb/$GAME
obb_log() { adb logcat -d -v tag -s AppManager:V MAct:V 2>/dev/null | grep -E '^./AppManager|^./MAct.*Launch' | grep -v '	at ' | tr -d '\r' | tr '\n' '|'; }
obb_start() {
    adb shell am start -W -n $PKG/.MAct --ez qa_preview true --ez qa_freeze_clock true --es qa_action obb_copy --es qa_launch started >> "$LOG" 2>&1 || log "WARN: am start MAct failed"
}
obb_case() { # $1 = tag
    stop_app
    adb logcat -c || true
    obb_start
    sleep 0.7
    adb exec-out screencap -p > "$OUT/home_$1_mid.png" || true
    sleep 0.3
    adb exec-out screencap -p > "$OUT/home_$1_mid2.png" || true
    log "$1 mid texts: $(texts)"
    sleep 7
    log "$1 focus: $(focus)"
    log "$1 log: $(obb_log)"
}
stop_app
adb shell pm clear $PKG >/dev/null 2>&1 || true   # fresh install: empty container
grant
adb shell rm -rf $GOBB >/dev/null 2>&1 || true
adb shell mkdir -p $GOBB
adb shell dd if=/dev/zero of=$GOBB/main.20.$GAME.obb bs=1048576 count=320 >/dev/null 2>&1 || log "WARN: could not create main OBB"
adb shell dd if=/dev/zero of=$GOBB/main.19.$GAME.obb bs=1048576 count=2 >/dev/null 2>&1 || true   # older main: ignored
adb shell dd if=/dev/zero of=$GOBB/patch.20.$GAME.obb bs=1048576 count=24 >/dev/null 2>&1 || log "WARN: could not create patch OBB"
log "obb source listing: $(adb shell ls -l $GOBB 2>&1 | tr -d '\r' | tr '\n' '|')"
stop_app
adb shell appops set $PKG REQUEST_INSTALL_PACKAGES default >/dev/null 2>&1 || true
adb logcat -c || true
obb_start
sleep 4
log "obb0 focus: $(focus)"
log "obb0 texts: $(texts)"
log "obb0 log: $(obb_log)"
adb shell appops set $PKG REQUEST_INSTALL_PACKAGES allow >> "$LOG" 2>&1 || log "WARN: appops REQUEST_INSTALL_PACKAGES failed"
obb_case obb1
obb_case obb2
stop_app
adb shell rm -rf $GOBB >/dev/null 2>&1 || true
adb logcat -c || true
obb_start
sleep 4
log "obb3 focus: $(focus)"
shot home_obb3_missing
log "obb3 texts: $(texts)"
log "obb3 log: $(obb_log)"
stop_app
grant

# ------------------------------------------- Permissions asked by Launch
# Launch with All files access, Files and Install unknown apps missing must
# ask for each in turn (settings page, runtime dialog, settings page) and,
# once all are granted, carry on into the real OBB copy and launch.
# qa_action permissions_copy = Launch after the activation / library checks
# (the emulator has no activated container); qa_launch=started = stand-in game.
tap_res() { # $1 = resource-id regex (any package)
    xy=$(dump_ui | python3 -c '
import re, sys
xml = sys.stdin.read()
m = re.search(r"resource-id=\"(?:%s)\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"" % sys.argv[1], xml)
if m:
    x1, y1, x2, y2 = map(int, m.groups())
    print((x1 + x2) // 2, (y1 + y2) // 2)
' "$1")
    if [ -z "$xy" ]; then log "WARN: no $1 on screen"; return 1; fi
    # shellcheck disable=SC2086
    adb shell input tap $xy
}
ALLOW_RE='com\.android\.permissioncontroller:id/permission_allow(?:_all|_foreground_only)?_button'
DENY_RE='com\.android\.permissioncontroller:id/permission_deny(?:_and_dont_ask_again)?_button'
perm_reset() { # fresh install state: nothing granted, nothing asked
    stop_app
    adb shell pm clear $PKG >/dev/null 2>&1 || true
    adb shell appops set $PKG MANAGE_EXTERNAL_STORAGE deny >/dev/null 2>&1 || true
    adb shell appops set $PKG REQUEST_INSTALL_PACKAGES deny >/dev/null 2>&1 || true
    adb shell pm revoke $PKG android.permission.READ_EXTERNAL_STORAGE >/dev/null 2>&1 || true
    adb shell pm revoke $PKG android.permission.WRITE_EXTERNAL_STORAGE >/dev/null 2>&1 || true
}
perm_start() {
    adb logcat -c || true
    adb shell am start -W -n $PKG/.MAct --ez qa_preview true --ez qa_freeze_clock true --es qa_action permissions_copy --es qa_launch started >> "$LOG" 2>&1 || log "WARN: am start MAct failed"
    sleep 3
}
perm_explain() { # $1 = name: screenshot + texts of the explanation sheet, then Continue
    sleep 1
    adb exec-out screencap -p > "$OUT/$1.png" || true
    log "$1 focus: $(focus)"
    log "$1 texts: $(texts)"
    tap_id permExplainContinue || true
    sleep 2.5
}
perm_log() { adb logcat -d -v tag -s AppManager:V MAct:V StorageAccess:V 2>/dev/null | grep -E '^./AppManager|^./StorageAccess|^./MAct.*(Launch|Permission)' | grep -v '	at ' | tr -d '\r' | tr '\n' '|'; }

# Allow all three; the container copy is removed first so the copy really runs.
adb shell mkdir -p $GOBB
adb shell dd if=/dev/zero of=$GOBB/main.20.$GAME.obb bs=1048576 count=64 >/dev/null 2>&1 || log "WARN: could not create main OBB"
perm_reset
adb shell rm -rf /sdcard/Android/obb/$PKG/scopedStorage >> "$LOG" 2>&1 || log "WARN: could not clear the container OBB"
perm_start
log "perm_allow pid before: $(adb shell pidof $PKG | tr -d '\r')"
perm_explain perm_explain_all_files
log "perm_allow step1 focus: $(focus)"
adb exec-out screencap -p > "$OUT/perm_all_files.png" || true
adb shell appops set $PKG MANAGE_EXTERNAL_STORAGE allow >/dev/null 2>&1 || true
adb shell input keyevent 4 || true
sleep 3
log "perm_allow step2 focus: $(focus)"
adb exec-out screencap -p > "$OUT/perm_files.png" || true
for i in 1 2 3 4; do
    case "$(focus)" in
        *permissioncontroller*) tap_res "$ALLOW_RE" || true; sleep 2 ;;
        *) break ;;
    esac
done
perm_explain perm_explain_install
log "perm_allow step3 focus: $(focus)"
adb exec-out screencap -p > "$OUT/perm_install.png" || true
adb shell appops set $PKG REQUEST_INSTALL_PACKAGES allow >/dev/null 2>&1 || true
adb shell input keyevent 4 || true
sleep 1.2
adb exec-out screencap -p > "$OUT/home_perm_restarting.png" || true
sleep 8
log "perm_allow final focus: $(focus)"
log "perm_allow pid after: $(adb shell pidof $PKG | tr -d '\r')"
log "perm_allow log: $(perm_log)"
# After the restart (sign in is not possible here) home continues the Launch:
# the remembered Launch runs the copy in the fresh process, then launches.
stop_app
adb logcat -c || true
adb shell am start -W -n $PKG/.MAct --ez qa_preview true --ez qa_freeze_clock true --es qa_launch started >> "$LOG" 2>&1 || log "WARN: am start MAct failed"
sleep 8
log "perm_resume focus: $(focus)"
log "perm_resume log: $(perm_log)"
log "perm_allow granted: $(adb shell dumpsys package $PKG | grep -E 'android.permission.(READ_EXTERNAL_STORAGE|WRITE_EXTERNAL_STORAGE|RECORD_AUDIO|ACCESS_FINE_LOCATION|POST_NOTIFICATIONS|BLUETOOTH_CONNECT): granted=' | tr -d ' \r' | sort -u | tr '\n' ' ')"

# Refuse Files: message, no copy, no launch.
perm_reset
adb shell appops set $PKG MANAGE_EXTERNAL_STORAGE allow >/dev/null 2>&1 || true
perm_start
log "perm_files_deny prompt focus: $(focus)"
for i in 1 2 3 4; do
    case "$(focus)" in
        *permissioncontroller*) tap_res "$DENY_RE" || true; sleep 2 ;;
        *) break ;;
    esac
done
sleep 1
log "perm_files_deny focus: $(focus)"
log "perm_files_deny texts: $(texts)"
log "perm_files_deny log: $(perm_log)"

# Refuse Install unknown apps: message, no copy, no launch.
perm_reset
adb shell appops set $PKG MANAGE_EXTERNAL_STORAGE allow >/dev/null 2>&1 || true
adb shell pm grant $PKG android.permission.READ_EXTERNAL_STORAGE >/dev/null 2>&1 || true
adb shell pm grant $PKG android.permission.WRITE_EXTERNAL_STORAGE >/dev/null 2>&1 || true
perm_start
perm_explain perm_install_deny_explain
log "perm_install_deny prompt focus: $(focus)"
adb shell input keyevent 4 || true
sleep 3
log "perm_install_deny focus: $(focus)"
shot home_perm_install_denied
log "perm_install_deny texts: $(texts)"
log "perm_install_deny log: $(perm_log)"

# Refuse All files access: message, no copy, no launch.
perm_reset
perm_start
perm_explain perm_all_files_deny_explain
log "perm_all_files_deny prompt focus: $(focus)"
adb shell input keyevent 4 || true
sleep 3
log "perm_all_files_deny focus: $(focus)"
log "perm_all_files_deny texts: $(texts)"
log "perm_all_files_deny log: $(perm_log)"

# "Not now" on the explanation: no settings page, the existing message, no copy.
perm_reset
perm_start
sleep 1
log "perm_not_now sheet focus: $(focus)"
tap_id permExplainNotNow || true
sleep 2
log "perm_not_now focus: $(focus)"
shot home_perm_not_now
log "perm_not_now texts: $(texts)"
log "perm_not_now log: $(perm_log)"
stop_app
adb shell rm -rf $GOBB >/dev/null 2>&1 || true
grant

# ------------------------------------ Help and Settings > Licence / Updates
# Help: "?" in the header opens three tips; Copy support info previews what
# it copies (2.3.1: including the licence key, status and IST expiry), the
# first-run tips show once only.
# Settings (2.3.1): the licence key is masked, the eye reveals it, a tap
# copies it ("Copied"); Check for app updates (sample answers); the
# re-download confirmation and the "installed" library sheet.
help_log() { adb logcat -d -v tag -s MAct:V 2>/dev/null | grep -E 'Help:|Access:|Permission|Launch' | grep -v '	at ' | tr -d '\r' | tr '\n' '|'; }
stop_app
adb shell pm clear $PKG >/dev/null 2>&1 || true
grant
adb logcat -c || true
# 2.2.0: the countdown really ticks (the clock is frozen everywhere else in
# QA so uiautomator can reach an idle screen): two frames 2 s apart differ.
stop_app
adb shell am start -W -n $PKG/.MAct --ez qa_preview true >> "$LOG" 2>&1 || true
sleep 2.5
shot home_clock_a
sleep 2
shot home_clock_b
# 2.2.0: Help never opens on its own - only from the "?" button.
open_home
sleep 1.5
log "help_first first: $(has_id helpRoot && echo shown || echo none)"
log "home texts: $(texts)"
open_home
sleep 1.5
log "help_first second: $(has_id helpRoot && echo shown || echo none)"
log "help_first log: $(help_log)"
open_home --es qa_action error
tap_id helpButton || true
sleep 1.2
shot help_sheet
log "help sheet texts: $(texts)"
tap_id helpCopySupport || true
sleep 1.2
shot help_support_info
log "help support info: $(node_text supportInfoText)"
log "help support texts: $(texts)"
tap_id supportInfoCopy || true
sleep 0.4
# 2.3.0: the Copy button reads "Copied" (held 4 s under qa_freeze_clock, ~800 ms
# otherwise), then the sheet closes: read the screen first, screenshot after.
copied_texts=""
for i in 1 2 3; do
    copied_texts=$(texts)
    case "$copied_texts" in *'text="Copied"'*) break ;; esac
    sleep 0.3
done
log "help copied texts: $copied_texts"
shot help_support_copied
log "help log: $(help_log)"
# Settings > Licence: masked key, eye reveals it, a tap copies it.
adb logcat -c || true
open_settings --es qa_saved_key ANOY-30D-QA7K2
log "licence masked: $(node_text licenceKeyValue)"
log "licence settings texts: $(texts)"
shot settings_licence
tap_id licenceKeyEye || true
sleep 0.6
log "licence revealed: $(node_text licenceKeyValue)"
shot settings_key_revealed
tap_id licenceKeyRow || true
sleep 0.4
# "Copied" holds 6 s under qa_freeze_clock (~1.2 s otherwise): read first.
log "licence copied: $(node_text licenceKeyValue)"
shot settings_key_copied
# Check for app updates: up to date / couldn't check / a newer version.
open_settings --es qa_app_check current
tap_id checkUpdateRow || true
# "Up to date" holds 8 s under qa_freeze_clock (3 s otherwise).
sleep 1.4
log "app check current: $(node_text checkUpdateValue)"
shot settings_update_current
open_settings --es qa_app_check failed
tap_id checkUpdateRow || true
sleep 1.6
log "app check failed: $(node_text checkUpdateValue)"
shot settings_update_failed
open_settings --es qa_app_check available
tap_id checkUpdateRow || true
wait_for_id appUpdatePrimary 8 || log "WARN: update screen missing"
sleep 1
log "app check available focus: $(focus)"
log "app check available texts: $(texts)"
shot settings_update_available
tap_id appUpdateLater || true
sleep 1.5
log "app check not now focus: $(focus)"
log "app check not now settings: $(has_id settingsPage && echo shown || echo gone)"
# Re-download library: the confirmation, then Cancel (no download in QA).
open_settings
tap_id redownloadRow || true
sleep 1.2
log "redownload sheet texts: $(texts)"
shot settings_redownload_confirm
tap_id redownloadCancel || true
sleep 0.8
log "redownload cancel: $(has_id redownloadRoot && echo open || echo closed)"
# The library sheet's "installed" state after a re-download (debug preview).
open_gate redownloaded
log "redownload done texts: $(texts)"
shot gate_redownloaded
stop_app

# ------------------------------------- 11. minified release build (real flow)
if [ -f "$RELEASE_APK" ]; then
    adb logcat -c || true
    install_apk "$RELEASE_APK"
    grant
    adb shell am start -W -n $PKG/.SplashActivity >> "$LOG" 2>&1 || log "WARN: release launch failed"
    sleep 3
    if wait_for_id gateAction 30; then
        tap_id gateAction || true
        wait_gone gateTitle 90 || log "WARN: release update did not finish"
    fi
    wait_gone gateTitle 30 || true
    sleep 2
    hide_keyboard
    log "release focus: $(focus)"
    shot release_login
    adb logcat -d > ../qa-logs/release-logcat.txt 2>/dev/null || true
else
    log "WARN: release APK missing at $RELEASE_APK"
fi

log "=== emulator QA finished $(date -u) ==="
