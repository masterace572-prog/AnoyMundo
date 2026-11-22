#!/usr/bin/env python3
"""Credential-free UI contract, preservation and accessibility source checks.

Run with: python3 -m unittest discover -s tests -p 'test_ui_*.py' -v
No Android SDK, network, live account, or original xlox working tree is needed.
These are source checks, not a substitute for APK/emulator/physical-device QA.
"""
from __future__ import annotations

import contextlib
import hashlib
import io
import json
from pathlib import Path
import re
import sys
import unittest
from unittest.mock import Mock, patch
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res"
ANDROID = "{http://schemas.android.com/apk/res/android}"
APP = "{http://schemas.android.com/apk/res-auto}"
sys.path.insert(0, str(ROOT / "qa"))
from ui_contract import controller_fingerprint  # noqa: E402

INVENTORY = json.loads((ROOT / "qa/source-baseline.json").read_text())["sha256"]
CONTRACT = json.loads((ROOT / "qa/ui-contract-baseline.json").read_text())

# Only these pre-existing files implement the approved presentation changes.
# Everything else in the 281-file source inventory must remain byte-identical.
UI_FILES = {
    "app/src/main/java/com/xlox/MAct.java", "app/src/main/java/com/xlox/LogAct.java",
    "app/src/main/res/layout/activity_main.xml", "app/src/main/res/layout/activity_login.xml",
    "app/src/main/res/layout/sheet_sign_out.xml", "app/src/main/res/layout/sheet_redownload.xml",
    "app/src/main/res/layout/sheet_support_info.xml",
    "app/src/main/res/values/colors.xml", "app/src/main/res/values/dimens.xml",
    "app/src/main/res/values/styles.xml", "app/src/main/res/values/strings.xml",
    "app/src/main/res/drawable/bg_icon_press.xml", "app/src/main/res/drawable/bg_row.xml",
    "app/src/main/res/drawable/bg_row_danger.xml", "app/src/main/res/drawable/ic_megaphone.xml",
    "app/src/main/res/drawable/ic_play.xml", "app/src/main/res/drawable/ic_stop.xml",
    "app/src/main/res/drawable/ic_triangle_alert.xml",
    "scripts/ui_quality_gate.py", "scripts/check_branding.py",
}
SETUP_FILES = {".gitignore", "app/build.gradle"}


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def load_values(name: str, tag: str) -> dict[str, str]:
    return {e.attrib["name"]: (e.text or "").strip()
            for e in ET.parse(RES / "values" / name).getroot().findall(tag)}


def layout(name: str) -> ET.Element:
    return ET.parse(RES / "layout" / name).getroot()


def by_id(root: ET.Element, name: str) -> ET.Element:
    return next(e for e in root.iter() if e.get(ANDROID + "id") == "@+id/" + name)


STYLES = {e.attrib["name"]: e for e in ET.parse(RES / "values/styles.xml").getroot().findall("style")}
DIMENS = load_values("dimens.xml", "dimen")
COLORS = load_values("colors.xml", "color")


def style_attrs(name: str) -> dict[str, str]:
    el = STYLES.get(name.removeprefix("@style/"))
    if el is None:
        return {}
    name = el.attrib["name"]
    parent = el.get("parent", name.rsplit(".", 1)[0] if "." in name else "")
    attrs = style_attrs(parent) if parent else {}
    attrs.update({e.attrib["name"]: e.text or "" for e in el.findall("item")})
    return attrs


def attr(el: ET.Element, name: str) -> str:
    namespace, key = name.split(":", 1) if ":" in name else ("", name)
    expanded = (ANDROID if namespace == "android" else APP if namespace == "app" else "") + key
    return el.get(expanded, style_attrs(el.get("style", "")).get(name, ""))


def dp(value: str) -> float:
    if value.startswith("@dimen/"):
        value = DIMENS[value.split("/", 1)[1]]
    assert value.endswith("dp"), value
    return float(value[:-2])


def rgb(value: str) -> tuple[float, float, float]:
    value = value.lstrip("#")[-6:]
    return tuple(int(value[i:i + 2], 16) / 255 for i in (0, 2, 4))


def luminance(value: str) -> float:
    return sum((v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4) * w
               for v, w in zip(rgb(value), (0.2126, 0.7152, 0.0722)))


def contrast(a: str, b: str) -> float:
    x, y = sorted((luminance(a), luminance(b)))
    return (y + 0.05) / (x + 0.05)


class Preservation(unittest.TestCase):
    def test_non_ui_source_and_dependencies_are_byte_identical(self):
        for file, expected in INVENTORY.items():
            if file in UI_FILES | SETUP_FILES:
                continue
            with self.subTest(file=file):
                self.assertEqual(sha((ROOT / file).read_bytes()), expected)

    def test_only_setup_signing_path_changed_in_gradle(self):
        source = (ROOT / "app/build.gradle").read_text().replace("../../xlox/app/xlox.jks", "xlox.jks")
        self.assertEqual(sha(source.encode()), INVENTORY["app/build.gradle"])
        self.assertFalse((ROOT / "app/xlox.jks").exists(), "Never duplicate the original signing key")

    def test_original_ignore_rules_are_preserved(self):
        source = (ROOT / ".gitignore").read_text().split("\n# This isolated copy", 1)[0]
        self.assertEqual(sha(source.encode()), INVENTORY[".gitignore"])

    def test_controller_logic_outside_presentation_is_unchanged(self):
        for file, policy in CONTRACT["controllers"].items():
            with self.subTest(file=file):
                self.assertEqual(controller_fingerprint((ROOT / file).read_text(), policy),
                                 policy["protected_fingerprint"])

    def test_every_existing_id_is_retained_without_duplicates(self):
        for file, expected in CONTRACT["layout_ids"].items():
            ids = re.findall(r"@\+id/(\w+)", (ROOT / file).read_text())
            with self.subTest(file=file):
                self.assertTrue(set(expected) <= set(ids))
                self.assertEqual(len(ids), len(set(ids)))
                self.assertEqual(set(ids) - set(expected),
                                 {"licenceKeyCopy"} if file.endswith("activity_main.xml") else set())

    def test_both_brand_contacts_are_preserved(self):
        main = load_values("strings.xml", "string")
        kenny = {e.attrib["name"]: e.text for e in ET.parse(ROOT / "app/src/kenny/res/values/strings.xml").getroot().findall("string")}
        self.assertEqual(main["telegram_url"], "https://t.me/libAkAudioVisiual")
        self.assertEqual(kenny["telegram_url"], "https://t.me/crimecell")
        self.assertEqual(main["brand_name"], "Anoy Hax")
        self.assertEqual(kenny["brand_name"], "KennyPvt Hax")

    def test_settings_actions_keep_their_real_handlers(self):
        source = (ROOT / "app/src/main/java/com/xlox/MAct.java").read_text()
        handlers = {"licenceKeyRow": "copyLicenceKey();", "licenceKeyCopy": "copyLicenceKey();",
                    "checkUpdateRow": "checkAppUpdate();", "redownloadRow": "confirmRedownload();",
                    "settingsBack": "closeSettings();", "settingsTelegram": "Ui.openUrl(this, getString(R.string.telegram_url));",
                    "settingsCopySupport": "showSupportInfo();", "signOut": "confirmSignOut();",
                    "helpButton": "showHelp();", "settingsButton": "openSettings();"}
        for view, handler in handlers.items():
            with self.subTest(view=view):
                match = re.search(r"findViewById\(R.id." + view + r"\).setOnClickListener\(v -> \{(.*?)\}\);", source, re.S)
                self.assertIsNotNone(match)
                self.assertIn(handler, match[1])
        self.assertIn("primaryAction.setOnClickListener(v -> onPrimaryAction());", source)

    def test_update_check_status_spinner_and_confirmation_ids_remain(self):
        screen = layout("activity_main.xml")
        for name in ("checkUpdateRow", "checkUpdateCaption", "checkUpdateSpinner", "checkUpdateIcon", "checkUpdateValue", "checkUpdateGlyph"):
            self.assertIsNotNone(by_id(screen, name))
        self.assertEqual(by_id(screen, "checkUpdateValue").get("style"), "@style/Text.Caption.Medium")
        for file in ("sheet_sign_out.xml", "sheet_redownload.xml", "sheet_support_info.xml"):
            self.assertEqual(layout(file).tag, "androidx.core.widget.NestedScrollView")


class Presentation(unittest.TestCase):
    def test_requested_palette_is_exact(self):
        for name, expected in {"background": "000000", "surface_1": "151619", "surface_2": "1C1D21",
                               "accent": "2585F5", "text_primary": "F5F5F5", "text_secondary": "92949B",
                               "success": "30C96B", "danger": "F04444"}.items():
            self.assertEqual(COLORS[name][-6:], expected)

    def test_small_text_contrast_is_at_least_four_point_five(self):
        for foreground in ("text_primary", "text_secondary", "text_tertiary"):
            for background in ("background", "surface_1", "surface_2", "surface_3"):
                with self.subTest(foreground=foreground, background=background):
                    self.assertGreaterEqual(contrast(COLORS[foreground], COLORS[background]), 4.5)
        for foreground, background in (("on_accent", "accent"), ("on_accent", "accent_pressed"),
                                       ("danger", "surface_2"), ("danger", "danger_tint")):
            self.assertGreaterEqual(contrast(COLORS[foreground], COLORS[background]), 4.5)

    def test_top_level_icons_share_one_surface_and_no_text_glyph(self):
        screen = layout("activity_main.xml")
        for name in ("helpButton", "settingsButton", "settingsBack"):
            node = by_id(screen, name)
            self.assertEqual(node.tag, "ImageButton")
            self.assertEqual(node.get("style"), "@style/IconButton.Surface")
            self.assertIsNone(node.get(ANDROID + "text"))
        self.assertEqual(by_id(screen, "helpButton").get(APP + "srcCompat"), "@drawable/ic_help")

    def test_icon_controls_have_labels_and_48dp_targets(self):
        for filename in ("activity_main.xml", "activity_login.xml"):
            for node in layout(filename).iter("ImageButton"):
                with self.subTest(view=node.get(ANDROID + "id")):
                    self.assertGreaterEqual(dp(attr(node, "android:layout_width")), 48)
                    self.assertGreaterEqual(dp(attr(node, "android:layout_height")), 48)
                    self.assertTrue(node.get(ANDROID + "contentDescription", "").startswith("@string/"))
                    self.assertEqual(attr(node, "android:focusable"), "true")

    def test_button_and_row_minimum_touch_heights(self):
        for name in ("Widget.Anoy.Button", "Widget.Anoy.Button.Text.Purchase", "Row", "Row.Danger"):
            self.assertGreaterEqual(dp(style_attrs(name)["android:minHeight"]), 48)

    def test_vectors_use_one_rounded_stroke_family(self):
        for path in sorted((RES / "drawable").glob("ic_*.xml")):
            if path.name.startswith("ic_launcher"):
                continue
            root = ET.parse(path).getroot()
            with self.subTest(icon=path.name):
                self.assertEqual(root.get(ANDROID + "viewportWidth"), "24")
                self.assertEqual(root.get(ANDROID + "viewportHeight"), "24")
                for node in root.findall("path"):
                    self.assertEqual(node.get(ANDROID + "strokeWidth"), "1.75")
                    self.assertEqual(node.get(ANDROID + "strokeLineCap"), "round")
                    self.assertEqual(node.get(ANDROID + "strokeLineJoin"), "round")

    def test_vector_numeric_tokens_are_safe_for_older_android_parsers(self):
        number = r"[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?"
        for file in (RES / "drawable").glob("ic_*.xml"):
            if file.name.startswith("ic_launcher"):
                continue
            for path in ET.parse(file).getroot().iter("path"):
                for token in re.findall(number, path.get(ANDROID + "pathData", "")):
                    with self.subTest(icon=file.name, number=token):
                        self.assertIsNone(re.match(r"^[+-]?\.", token),
                                          "Expand SVG shorthand decimals for Android 21 compatibility")

    def test_telegram_and_clipboard_are_recognizable_vector_controls(self):
        screen = layout("activity_login.xml")
        paste = by_id(screen, "pasteKey")
        self.assertEqual(paste.tag, "ImageButton")
        self.assertEqual(paste.get(APP + "srcCompat"), "@drawable/ic_clipboard_paste")
        self.assertIsNone(paste.get(ANDROID + "text"))
        buy = by_id(screen, "getKey")
        self.assertEqual(buy.get(APP + "icon"), "@drawable/ic_telegram")
        self.assertEqual(load_values("strings.xml", "string")["get_key"], "Buy now")
        self.assertTrue((ROOT / "app/src/main/assets/licenses/TABLER-MIT.txt").is_file())

    def test_input_grows_and_long_settings_values_can_wrap(self):
        screen = layout("activity_login.xml")
        self.assertEqual(by_id(screen, "keyField").get(ANDROID + "layout_height"), "wrap_content")
        self.assertEqual(by_id(screen, "userkey").get(ANDROID + "layout_height"), "wrap_content")
        self.assertEqual(by_id(screen, "userkey").get(ANDROID + "singleLine"), "true")
        for name in ("modelValue", "androidValue", "licenceExpiresValue", "appVersionValue", "libraryVersionValue"):
            node = by_id(layout("activity_main.xml"), name)
            self.assertNotEqual(attr(node, "android:maxLines"), "1")
            self.assertNotEqual(attr(node, "android:singleLine"), "true")
            self.assertEqual(attr(node, "android:ellipsize"), "")
        self.assertNotIn("android:maxLines", style_attrs("Text.Countdown"))

    def test_keyboard_insets_and_tablet_action_width_policy(self):
        source = (ROOT / "app/src/main/java/com/xlox/LogAct.java").read_text()
        self.assertNotIn("Ui.maxWidth(findViewById(R.id.loginActions)", source)
        self.assertIn("Ui.maxWidth(findViewById(R.id.loginContent), 440);", source)
        self.assertIn("Ui.maxWidth(footer, 440);", source)
        self.assertIn("footer.setVisibility(ime ? View.GONE : View.VISIBLE)", source)
        self.assertIn("button.requestRectangleOnScreen", source)
        self.assertLessEqual(dp(DIMENS["login_top_space"]), 40)
        self.assertLess(dp(DIMENS["login_top_space_ime"]), dp(DIMENS["login_top_space"]))

    def test_focus_states_and_settings_accessibility_are_visible(self):
        for file in ("bg_icon_press.xml", "bg_icon_surface.xml", "bg_row.xml", "bg_row_danger.xml"):
            source = (RES / "drawable" / file).read_text()
            self.assertIn('android:state_focused="true"', source)
            self.assertIn("@color/accent", source)
        source = (ROOT / "app/src/main/java/com/xlox/MAct.java").read_text()
        self.assertIn("ViewCompat.setAccessibilityPaneTitle", source)
        self.assertIn("View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS", source)
        self.assertIn("View.IMPORTANT_FOR_ACCESSIBILITY_AUTO", source)

    def test_theme_keeps_dark_only_no_glass_gradients_or_shadows(self):
        self.assertFalse((RES / "values-night").exists())
        self.assertEqual(STYLES["AppTheme"].get("parent"), "Theme.Material3.Dark.NoActionBar")
        for name in ("Widget.Anoy.Card", "Widget.Anoy.Group"):
            self.assertEqual(style_attrs(name)["cardElevation"], "0dp")
        self.assertEqual(style_attrs("Widget.Anoy.Button")["android:elevation"], "0dp")
        for file in (RES / "drawable").glob("*.xml"):
            self.assertNotIn("<gradient", file.read_text())

    def test_build_workflow_is_isolated_and_cannot_publish(self):
        workflow = (ROOT.parent / ".github/workflows/build-gptsuggestion.yml").read_text()
        self.assertIn("contents: read", workflow)
        self.assertNotRegex(workflow, r"(?:contents|deployments|packages|id-token):\s*write")
        self.assertNotRegex(workflow, r"gh\s+release|publish-release\.mjs|vercel\s|supabase\s|git\s+push")
        self.assertIn("working-directory: gptsuggestion", workflow)
        self.assertIn(":app:assembleAnoyRelease", workflow)
        self.assertIn(":app:assembleKennyRelease", workflow)
        self.assertIn("gptsuggestion/qa-output/", workflow)


class SmokeHarness(unittest.TestCase):
    @staticmethod
    def runner():
        sys.path.insert(0, str(ROOT / "scripts"))
        from ui_refinement_smoke import UiSmoke
        return UiSmoke.__new__(UiSmoke)

    def test_diagnostics_fit_runner_annotation_limits_without_data_loss(self):
        payload = "A" * 10001
        stream = io.StringIO()
        with contextlib.redirect_stdout(stream):
            self.runner().diagnostic_chunks("Test", "PAYLOAD:", payload)
        lines = stream.getvalue().splitlines()
        self.assertEqual(len(lines), 3)
        self.assertTrue(all(len(line) < 4096 for line in lines))
        self.assertEqual("".join(line.split("PAYLOAD:", 1)[1] for line in lines), payload)

    def test_back_is_sent_once_only_from_login_with_ime_hidden_footer(self):
        runner = self.runner()
        runner.invalidate = Mock()
        runner.maybe_node = Mock(side_effect=[None, ET.Element("node")])
        runner.key = Mock()
        with patch("ui_refinement_smoke.time.sleep"):
            runner.hide_keyboard()
        runner.key.assert_called_once_with(4)

    def test_visible_footer_cannot_send_back_and_close_the_app(self):
        runner = self.runner()
        runner.invalidate = Mock()
        runner.maybe_node = Mock(return_value=ET.Element("node"))
        runner.key = Mock()
        runner.hide_keyboard()
        runner.key.assert_not_called()

    def test_unknown_screen_cannot_receive_a_keyboard_back_press(self):
        runner = self.runner()
        runner.invalidate = Mock()
        runner.maybe_node = Mock(return_value=None)
        runner.key = Mock()
        runner.hide_keyboard()
        runner.key.assert_not_called()

    def test_partial_bounds_are_scrolled_before_touch_target_measurement(self):
        runner = self.runner()
        runner.width, runner.height, runner.scale = 720, 1520, 2
        partial = ET.Element("node", {"bounds": "[400,1320][700,1370]"})
        full = ET.Element("node", {"bounds": "[400,1100][700,1196]"})
        runner.node = Mock(side_effect=[partial, full])
        runner.shell, runner.invalidate = Mock(), Mock()
        self.assertIs(runner.full_control("getKey"), full)
        runner.shell.assert_called_once()
        runner.invalidate.assert_called_once()

    def test_genuinely_small_control_never_passes_after_scrolling(self):
        runner = self.runner()
        runner.width, runner.height, runner.scale = 720, 1520, 2
        runner.node = Mock(return_value=ET.Element("node", {"bounds": "[400,1100][700,1180]"}))
        runner.shell, runner.invalidate = Mock(), Mock()
        with self.assertRaisesRegex(AssertionError, "cannot expose a full 48dp target"):
            runner.full_control("tooSmall")


if __name__ == "__main__":
    unittest.main(verbosity=2)
