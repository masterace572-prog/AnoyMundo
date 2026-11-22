#!/usr/bin/env python3
"""Offline native UI smoke checks on a FRESH, DISPOSABLE CI emulator only.

Uses existing debug-only preview extras, not changes to production logic.
The ARM64 debug APK is stripped/re-signed ONLY as an x86 UI fixture; that file
is never a phone deliverable. No real licence, provider login, BGMI data, panel
publication or live download is used. Real ARM64/device tests remain required.
"""
from __future__ import annotations

import argparse
import base64
from io import BytesIO
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import time
import traceback
import xml.etree.ElementTree as ET
from zipfile import ZipFile

PKG = "com.xlox"
GAME = "com.pubg.imobile"
SAMPLE_KEY = "ANOY-30D-QA7K2"  # Deliberately non-live, emulator-only fixture.


def bounds(node: ET.Element) -> tuple[int, int, int, int]:
    values = tuple(map(int, re.findall(r"-?\d+", node.get("bounds", ""))))
    assert len(values) == 4, "Missing node bounds"
    return values


class UiSmoke:
    def __init__(self, apk: Path, output: Path, emit_review_previews: bool = False):
        self.apk = apk.resolve()
        self.emit_review_previews = emit_review_previews
        self.out = output.resolve()
        self.out.mkdir(parents=True, exist_ok=True)
        self.fixture = self.out.parent / "fixture"
        self.fixture.mkdir(parents=True, exist_ok=True)
        self.checks: list[dict] = []
        self.captures: list[str] = []
        self.cache = None
        self.width, self.height, self.scale = 720, 1520, 2.0
        self.sdk = Path(os.environ.get("ANDROID_SDK_ROOT") or os.environ["ANDROID_HOME"])
        self.tools = self.sdk / "build-tools/34.0.0"

    @staticmethod
    def run(*args: str, timeout: int = 45, check: bool = True, binary: bool = False):
        result = subprocess.run(list(map(str, args)), capture_output=True, text=not binary, timeout=timeout)
        if check and result.returncode:
            error = result.stderr if not binary else result.stderr.decode(errors="replace")
            raise RuntimeError(f"Command failed: {args[0]}: {error[-1200:]}")
        return result.stdout

    def adb(self, *args: str, **kwargs):
        return self.run("adb", *args, **kwargs)

    def shell(self, *args: str, **kwargs):
        # adb shell joins arguments again on the remote side; quote data such
        # as announcements so spaces/ampersands cannot become shell syntax.
        return self.adb("shell", shlex.join(map(str, args)), **kwargs)

    def check(self, condition: bool, message: str):
        self.checks.append({"check": message, "passed": bool(condition)})
        print(("PASS " if condition else "FAIL ") + message, flush=True)
        if not condition:
            raise AssertionError(message)

    def invalidate(self):
        self.cache = None

    def tree(self, fresh: bool = False) -> ET.Element:
        if self.cache is not None and not fresh:
            return self.cache
        for _ in range(2):
            self.shell("rm", "-f", "/sdcard/gptsuggestion-ui.xml", check=False)
            self.shell("uiautomator", "dump", "--compressed", "/sdcard/gptsuggestion-ui.xml", timeout=35, check=False)
            raw = self.shell("cat", "/sdcard/gptsuggestion-ui.xml", check=False)
            try:
                self.cache = ET.fromstring(raw[raw.index("<?xml"):])
                return self.cache
            except (ValueError, ET.ParseError):
                time.sleep(0.3)
        raise AssertionError("Cannot obtain a fresh UI hierarchy (no stale dumps accepted)")

    def maybe_node(self, name: str):
        for node in self.tree().iter("node"):
            if node.get("resource-id") == PKG + ":id/" + name:
                x1, y1, x2, y2 = bounds(node)
                if x2 > x1 and y2 > y1:
                    return node
        return None

    def node(self, name: str, scroll: bool = False) -> ET.Element:
        for attempt in range(8 if scroll else 1):
            node = self.maybe_node(name)
            if node is not None:
                return node
            if attempt < 7 and scroll:
                self.shell("input", "swipe", str(self.width // 2), str(int(self.height * .78)),
                           str(self.width // 2), str(int(self.height * .30)), "220")
                self.invalidate()
        raise AssertionError(f"Visible control missing: {name}")

    def tap(self, name: str, scroll: bool = False):
        x1, y1, x2, y2 = bounds(self.node(name, scroll))
        self.shell("input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
        self.invalidate()
        time.sleep(.25)

    def key(self, keycode: int):
        self.shell("input", "keyevent", str(keycode))
        self.invalidate()
        time.sleep(.25)

    def wait_text(self, name: str, expected: str, seconds: float = 12):
        until = time.monotonic() + seconds
        while time.monotonic() < until:
            self.invalidate()
            node = self.maybe_node(name)
            if node is not None and node.get("text") == expected:
                self.check(True, f"{name} shows {expected}")
                return
            time.sleep(.3)
        raise AssertionError(f"{name} never showed {expected}")

    def start(self, activity: str, **extras):
        self.shell("am", "force-stop", PKG)
        args = ["am", "start", "-W", "-n", f"{PKG}/com.xlox.{activity}"]
        for name, value in extras.items():
            args.extend(["--ez" if isinstance(value, bool) else "--es", name,
                         str(value).lower() if isinstance(value, bool) else str(value)])
        self.shell(*args, timeout=50)
        self.invalidate()
        time.sleep(.7)

    def home(self, settings: bool = False, **extras):
        data = dict(qa_preview=True, qa_freeze_clock=True, qa_saved_key=SAMPLE_KEY,
                    qa_note="Library news and maintenance updates appear here.", qa_settings=settings)
        data.update(extras)
        self.start("MAct", **data)
        self.node("settingsBack" if settings else "primaryAction")

    def login(self, **extras):
        self.start("LogAct", qa_gate="skip", **extras)  # Existing qaPreview default calls onReady.
        self.node("userkey")

    def shot(self, name: str, hierarchy: bool = True):
        from PIL import Image
        image = self.adb("exec-out", "screencap", "-p", binary=True)
        decoded = Image.open(BytesIO(image))
        decoded.verify()
        self.check(len(image) > 3500, name + " is a nonblank PNG")
        (self.out / (name + ".png")).write_bytes(image)
        self.captures.append(name)
        if hierarchy:
            (self.out / (name + ".xml")).write_bytes(ET.tostring(self.tree(fresh=True), encoding="utf-8", xml_declaration=True))
        if self.emit_review_previews and name in ("home", "settings", "login"):
            # Compact, genuine-render diagnostics remain readable via GitHub's
            # Checks API when artifact-storage downloads are unavailable.
            # The full-resolution PNG/XML remains in the normal QA artifact.
            # This emulator contains only the synthetic offline fixture key.
            preview = Image.open(BytesIO(image)).convert("RGB")
            preview.thumbnail((360, 900))
            stream = BytesIO()
            preview.save(stream, format="JPEG", quality=76, optimize=True)
            payload = base64.b64encode(stream.getvalue()).decode("ascii")
            if len(payload) < 60000:
                print(f"::notice title=UI review {name}::UI_REVIEW_JPEG_BASE64:{payload}", flush=True)
        return image

    def configure(self, width: int = 360, height: int = 760, font: float = 1):
        self.shell("am", "force-stop", PKG)
        self.width, self.height = width * 2, height * 2
        self.shell("wm", "size", f"{self.width}x{self.height}")
        self.shell("wm", "density", "320")
        self.shell("settings", "put", "system", "font_scale", str(font))
        self.invalidate()
        time.sleep(.8)

    def touch_targets(self, *names: str):
        for name in names:
            x1, y1, x2, y2 = bounds(self.node(name))
            self.check(x2 - x1 >= 47 * self.scale and y2 - y1 >= 47 * self.scale,
                       name + " retains a 48dp touch target")

    def prepare(self):
        self.adb("wait-for-device", timeout=90)
        self.check(self.shell("getprop", "ro.kernel.qemu").strip() == "1",
                   "Refuse physical devices: disposable emulator confirmed")
        for package in (PKG, GAME):
            self.check("package:" not in self.shell("pm", "path", package, check=False),
                       "Fresh emulator has no pre-existing " + package + " data")
        key = self.fixture / "ui-only-debug.keystore"
        self.run("keytool", "-genkeypair", "-keystore", str(key), "-alias", "androiddebugkey",
                 "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-storepass", "android",
                 "-keypass", "android", "-dname", "CN=Disposable UI fixture", check=True)
        stripped = self.fixture / "ui-unsigned.apk"
        with ZipFile(self.apk) as original, ZipFile(stripped, "w") as target:
            for info in original.infolist():
                if info.filename.startswith("lib/") or info.filename.upper().startswith("META-INF/"):
                    continue
                target.writestr(info, original.read(info.filename))
        aligned = self.fixture / "ui-aligned.apk"
        self.run(str(self.tools / "zipalign"), "-f", "4", str(stripped), str(aligned))
        signed = self.fixture / "ui-emulator-only.apk"
        self.sign(aligned, signed, key)
        self.adb("install", "-r", "-g", str(signed), timeout=120)
        # A package-only fixture makes the real PackageManager/version UI render
        # BGMI metadata. No real game, activity, OBB, download or credentials.
        manifest = self.fixture / "AndroidManifest.xml"
        manifest.write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
                            'package="com.pubg.imobile" android:versionCode="1" android:versionName="4.1.0">'
                            '<uses-sdk android:minSdkVersion="21" android:targetSdkVersion="28"/>'
                            '<application android:hasCode="false" android:label="Disposable UI fixture"/></manifest>')
        game = self.fixture / "game-unsigned.apk"
        self.run(str(self.tools / "aapt2"), "link", "-I", str(self.sdk / "platforms/android-36/android.jar"),
                 "--manifest", str(manifest), "-o", str(game))
        game_signed = self.fixture / "game-ui-only.apk"
        self.sign(game, game_signed, key)
        self.adb("install", "-r", str(game_signed), timeout=90)
        for permission in ("READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE"):
            self.shell("pm", "grant", PKG, "android.permission." + permission, check=False)
        for appop in ("MANAGE_EXTERNAL_STORAGE", "REQUEST_INSTALL_PACKAGES"):
            self.shell("appops", "set", PKG, appop, "allow", check=False)
        self.shell("settings", "put", "secure", "show_ime_with_hard_keyboard", "1")
        self.shell("svc", "wifi", "disable", check=False)
        self.shell("svc", "data", "disable", check=False)
        self.shell("cmd", "connectivity", "airplane-mode", "enable", check=False)
        self.shell("settings", "put", "global", "airplane_mode_on", "1", check=False)
        self.shell("am", "broadcast", "-a", "android.intent.action.AIRPLANE_MODE", "--ez", "state", "true", check=False)
        self.adb("logcat", "-c")
        self.configure()

    def sign(self, source: Path, target: Path, key: Path):
        self.run(str(self.tools / "apksigner"), "sign", "--ks", str(key), "--ks-pass", "pass:android",
                 "--key-pass", "pass:android", "--out", str(target), str(source))

    def saved_key_exists(self) -> bool:
        # Inspect presence only; never print preferences or a licence value.
        value = self.shell("run-as", PKG, "cat", "shared_prefs/settings.xml", check=False)
        return 'name="USER"' in value

    def home_and_settings(self):
        self.home()
        self.shot("home")
        self.touch_targets("helpButton", "settingsButton", "primaryAction")
        left, right = bounds(self.node("helpButton")), bounds(self.node("settingsButton"))
        self.check(left[2] < right[0], "Header help/settings controls do not overlap")
        self.check(self.node("gameVersion").get("text") == "Game 4.1.0", "Game version binding remains visible")
        self.tap("helpButton")
        self.node("helpTitle")
        self.shot("help_sheet")
        self.key(4)
        self.tap("settingsButton")
        self.shot("settings")
        self.check(self.maybe_node("helpButton") is None, "Covered Home leaves the accessibility tree")
        self.touch_targets("settingsBack", "licenceKeyEye", "licenceKeyCopy")
        self.check(SAMPLE_KEY not in self.node("licenceKeyValue").get("text", ""), "Licence initially masked")
        self.tap("licenceKeyEye")
        self.check(self.node("licenceKeyValue").get("text") == SAMPLE_KEY, "Eye reveals the existing saved key")
        self.shot("settings_key_revealed")
        self.tap("licenceKeyEye")
        self.tap("licenceKeyCopy")
        self.wait_text("licenceKeyValue", "Copied")
        self.shot("settings_key_copied")
        self.tap("licenceKeyRow")
        self.wait_text("licenceKeyValue", "Copied")
        self.tap("settingsBack")
        self.node("helpButton")
        self.shot("home_back")
        # Existing preview exercises the real Stop button branch, not a game run.
        self.home(qa_launch="null_running", qa_note="")
        self.wait_text("primaryAction", "Stop")
        self.shot("home_stop")
        self.tap("primaryAction")
        self.check(bool(self.node("statusMessage").get("text")),
                   "Stop tap reaches the existing feedback handler; native outcome is device-only")
        self.shot("home_stop_feedback")
        self.home(qa_expiry="past")
        self.wait_text("primaryAction", "Sign in again")
        self.shot("home_expired")
        self.home(qa_expiry="hours", qa_status="maintenance")
        self.shot("home_under_day")
        self.home(qa_note="")
        self.check(self.maybe_node("panelNoteCard") is None, "Empty announcement remains hidden")
        self.shot("home_no_announcement")

    def login_actions(self):
        # Clipboard contains the fixture copied using Settings' real handler.
        self.login()
        self.tap("userkey")
        self.shell("input", "keyevent", "123", *(["67"] * 64))
        self.invalidate()
        self.key(111)
        self.tap("pasteKey")
        self.tap("toggleKey")
        self.check(self.node("userkey").get("text") == SAMPLE_KEY, "Clipboard icon pastes the exact copied key")
        self.check(self.node("toggleKey").get("content-desc") == "Hide licence key", "Eye label switches to Hide")
        self.shot("login_pasted_visible")
        self.tap("toggleKey")
        self.check(self.node("toggleKey").get("content-desc") == "Show licence key", "Eye label switches back to Show")
        self.shot("login")
        self.touch_targets("toggleKey", "pasteKey", "login", "getKey")
        self.tap("userkey")
        time.sleep(.8)
        self.shot("login_keyboard")
        self.check(self.maybe_node("versionFooter") is None, "Keyboard hides the footer instead of overlapping it")
        self.node("login", scroll=True)
        self.check(bounds(self.node("login"))[3] <= self.height, "Sign in remains within the resized viewport")
        self.key(111)
        self.login(qa_result="empty")
        self.node("keyError")
        self.shot("login_empty_error")
        self.login(qa_result="malformed")
        self.node("keyError")
        self.shot("login_format_error")
        self.login()
        self.tap("login")
        self.node("loginNoticeRetry", scroll=True)
        self.check("offline" in self.node("loginNoticeText").get("text", "").lower(), "Real offline guard shows a readable retry notice")
        self.shot("login_offline")
        self.tap("loginNoticeRetry")
        self.node("loginNoticeText")
        self.login(qa_result="loading")
        self.check(self.node("userkey").get("enabled") == "false", "Loading disables key input")
        self.check(self.node("pasteKey").get("enabled") == "false", "Loading disables clipboard action")
        self.check(self.node("login").get("clickable") == "false", "Loading suppresses duplicate submissions")
        for _ in range(3):
            self.tap("login")
        logs = self.adb("logcat", "-d", "-s", "LogAct:V", "*:S")
        self.check("Native licence check unavailable" not in logs, "Busy taps never enter native authentication")
        self.shot("login_loading")
        self.login(qa_result="success")
        self.wait_text("login", "Signed in")
        self.shot("login_success")
        # Inspect only the public VIEW destination, not browser/provider content.
        self.login()
        self.adb("logcat", "-c")
        self.tap("getKey", scroll=True)
        launch_log = self.adb("logcat", "-d", "-s", "ActivityTaskManager:I", "ActivityManager:I", "Ui:W", "*:S")
        self.check("https://t.me/libAkAudioVisiual" in launch_log, "Buy now keeps the original Telegram VIEW destination")
        (self.out / "telegram-destination.json").write_text(json.dumps({"destination_verified": True, "url": "https://t.me/libAkAudioVisiual", "browser_authentication_tested": False}, indent=2))

    def settings_actions(self):
        for state, expected in (("current", "Up to date"), ("failed", "Couldn't check")):
            self.home(settings=True, qa_app_check=state)
            self.tap("checkUpdateRow", scroll=True)
            self.wait_text("checkUpdateValue", expected)
            self.shot("settings_update_" + state)
        self.home(settings=True, qa_app_check="available")
        self.tap("checkUpdateRow", scroll=True)
        self.node("appUpdatePrimary")
        self.shot("settings_update_available")
        self.home(settings=True)
        self.tap("redownloadRow", scroll=True)
        self.node("redownloadConfirm")
        self.shot("settings_redownload_confirmation")
        self.tap("redownloadCancel", scroll=True)
        self.node("settingsBack")
        self.tap("settingsCopySupport", scroll=True)
        self.node("supportInfoText")
        self.shot("settings_support_preview")
        self.tap("supportInfoCopy", scroll=True)
        self.wait_text("supportInfoCopy", "Copied")
        self.shot("settings_support_copied")
        self.home(settings=True)
        self.tap("signOut", scroll=True)
        self.node("signOutConfirm")
        self.node("signOutForget", scroll=True)
        self.shot("settings_sign_out_confirmation")
        self.tap("signOutCancel", scroll=True)
        self.node("settingsScroll")
        # Keeping/forgetting affects only the fresh emulator's synthetic key.
        for action, present in (("signOutConfirm", True), ("signOutForget", False)):
            self.home(settings=True)
            self.tap("signOut", scroll=True)
            self.tap(action, scroll=True)
            time.sleep(.5)
            self.check(self.saved_key_exists() == present, action + " preserves the existing keep/forget choice")

    def responsive(self):
        for name, width, height, font in (("compact", 320, 568, 1), ("large_type", 360, 760, 2),
                                          ("small_type", 360, 760, .85), ("tablet", 800, 1100, 1),
                                          ("tablet_large_type", 800, 1100, 2)):
            self.configure(width, height, font)
            self.home()
            self.shot(name + "_home")
            self.touch_targets("helpButton", "settingsButton")
            self.home(settings=True, qa_app_check="current")
            self.shot(name + "_settings")
            self.tap("checkUpdateRow", scroll=True)
            self.wait_text("checkUpdateValue", "Up to date")
            self.shot(name + "_settings_update")
            self.tap("signOut", scroll=True)
            self.node("signOutForget", scroll=True)
            self.tap("signOutCancel", scroll=True)
            self.login()
            self.key(111)
            self.node("login", scroll=True)
            x1, _, x2, _ = bounds(self.node("login"))
            minimum = 360 if width >= 600 else width - 56
            self.check((x2 - x1) / self.scale >= minimum, name + " Sign in is not double-constrained")
            self.shot(name + "_login")

    def motion_and_theme(self):
        from PIL import Image, ImageChops
        self.configure()
        for setting in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
            self.shell("settings", "put", "global", setting, "1")
        self.login(qa_result="loading")
        rect = bounds(self.node("login"))
        first = self.shot("login_motion_a", hierarchy=False)
        time.sleep(.25)
        second = self.shot("login_motion_b", hierarchy=False)
        a, b = (Image.open(BytesIO(data)).convert("RGB").crop(rect) for data in (first, second))
        self.check(ImageChops.difference(a, b).getbbox() is not None, "In-button loading indicator animates without changing button width")
        for setting in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
            self.shell("settings", "put", "global", setting, "0")
        for mode in ("no", "yes"):
            self.shell("cmd", "uimode", "night", mode, check=False)
            self.home()
            data = self.shot("system_night_" + mode)
            pixel = Image.open(BytesIO(data)).convert("RGB").getpixel((0, self.height // 2))
            self.check(max(pixel) <= 2, "AMOLED canvas stays black with system night mode " + mode)

    def finish(self, error: str | None):
        try:
            log = self.adb("logcat", "-d", "-s", "AndroidRuntime:E", "*:S", check=False)
            (self.out / "android-runtime.log").write_text(log)
            if "FATAL EXCEPTION" in log:
                self.checks.append({"check": "No Android runtime crash", "passed": False})
                error = error or "Android runtime crash"
        except Exception:
            pass
        report = {"emulator_api": self.shell("getprop", "ro.build.version.sdk", check=False).strip(),
                  "result": "failed" if error else "passed", "failure": error,
                  "checks": self.checks, "captures": self.captures,
                  "limitations": ["x86 UI fixture only, not the delivered ARM64 APK",
                                  "No live licence/provider authentication or real BGMI/OBB execution",
                                  "Real-device signing/upgrade/OEM permission and performance checks remain required"]}
        (self.out / "results.json").write_text(json.dumps(report, indent=2) + "\n")
        compact = json.dumps(report, separators=(",", ":")).replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
        print(f"::{'error' if error else 'notice'} title=Offline UI smoke report::{compact}", flush=True)
        if error:
            raise AssertionError(error)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--emit-review-previews", action="store_true",
                        help="Expose three small synthetic-fixture screen previews in Checks diagnostics")
    args = parser.parse_args()
    runner = UiSmoke(args.apk, args.out, args.emit_review_previews)
    error = None
    try:
        runner.prepare()
        runner.home_and_settings()
        runner.login_actions()
        runner.settings_actions()
        runner.responsive()
        runner.motion_and_theme()
    except Exception:
        error = traceback.format_exc()
        (runner.out / "failure.log").write_text(error)
        try:
            runner.shot("failure", hierarchy=False)
        except Exception:
            pass
    finally:
        runner.finish(error)


if __name__ == "__main__":
    main()
