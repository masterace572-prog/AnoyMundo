#!/usr/bin/env python3
"""SDK-free structural guards; Android behavior is tested separately by Robolectric."""
import hashlib
from pathlib import Path
import struct
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / 'app/src/main/res'
JAVA = ROOT / 'app/src/main/java/com/xlox'
A = '{http://schemas.android.com/apk/res/android}'


def ids(layout):
    return {e.get(A + 'id'): e for e in ET.parse(RES / 'layout' / layout).getroot().iter()
            if e.get(A + 'id')}


class NavigationWiring(unittest.TestCase):
    def test_home_and_settings_are_sibling_pages_above_a_persistent_capsule(self):
        root = ET.parse(RES / 'layout/activity_main.xml').getroot()
        self.assertEqual(root.tag, 'LinearLayout')
        self.assertEqual(root[0].tag, 'com.xlox.ui.HomeSettingsPager')
        self.assertEqual(root[0].get(A + 'layout_height'), '0dp')
        self.assertEqual(root[0].get(A + 'layout_weight'), '1')
        self.assertEqual([e.get('layout') for e in root[0]], ['@layout/page_home', '@layout/page_settings'])
        self.assertEqual(root[1].get(A + 'id'), '@+id/bottomNavigationHost')
        self.assertEqual(root[1].get(A + 'tag'), 'snackbar_anchor')
        nav = ids('activity_main.xml')
        self.assertEqual(nav['@+id/navigationHome'].get(A + 'text'), '@string/navigation_home')
        self.assertEqual(nav['@+id/navigationSettings'].get(A + 'text'), '@string/settings')
        # The decoration must not dictate a wrap_content capsule's intrinsic height.
        self.assertEqual(nav['@+id/navigationIndicator'].get(A + 'layout_height'), '0dp')

    def test_pages_are_opaque_and_keep_launch_actions_inside_home(self):
        for layout in ('page_home.xml', 'page_settings.xml'):
            root = ET.parse(RES / 'layout' / layout).getroot()
            self.assertEqual(root.get(A + 'background'), '@color/black')
            self.assertNotIn(root.get(A + 'visibility'), ('gone', 'invisible'))
        home = ids('page_home.xml')
        settings = ids('page_settings.xml')
        for key in ('primaryAction', 'airPrimaryAction', 'gameCard', 'airGameCard'):
            self.assertIn('@+id/' + key, home)
            self.assertNotIn('@+id/' + key, settings)
        self.assertNotIn('@+id/helpButton', home)
        self.assertNotIn('@+id/settingsButton', home)
        self.assertIn('@+id/helpButton', settings)

    def test_sign_out_keeps_its_confirmation_handler_and_is_centered_red(self):
        sign_out = ids('page_settings.xml')['@+id/signOut']
        self.assertEqual(sign_out.tag, 'com.google.android.material.button.MaterialButton')
        self.assertEqual(sign_out.get(A + 'gravity'), 'center')
        self.assertEqual(sign_out.get(A + 'textColor'), '@color/danger_text')
        main = (JAVA / 'MAct.java').read_text()
        self.assertIn('findViewById(R.id.signOut).setOnClickListener', main)
        self.assertIn('confirmSignOut();', main)
        self.assertIn('new Prefs(this).remove(PREF_USER)', main)
        self.assertIn('findViewById(R.id.helpButton).setOnClickListener', main)

    def test_native_pager_retains_two_pages_without_fade_transformers(self):
        pager = (JAVA / 'ui/HomeSettingsPager.java').read_text()
        self.assertIn('extends ViewPager', pager)
        self.assertIn('R.layout.page_home', pager)
        self.assertIn('R.layout.page_settings', pager)
        self.assertIn('setOffscreenPageLimit(1)', pager)
        self.assertNotIn('setPageTransformer(', pager)
        self.assertNotIn('.setAlpha(', pager)
        nav = (JAVA / 'ui/HomeSettingsNavigation.java').read_text()
        self.assertIn('pager.setCurrentItem(page, animate && !Ui.reducedMotion', nav)
        self.assertIn('updateProgress(position + offset)', nav)
        self.assertIn('capsule.setProgress(progress)', nav)
        self.assertIn('View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS', nav)

    def test_back_countdown_and_key_masking_follow_page_state_not_visibility(self):
        main = (JAVA / 'MAct.java').read_text()
        self.assertIn('settingsBack.setEnabled(settings)', main)
        self.assertIn('navigation.homeIsSettled()', main)
        self.assertIn('if (moving) maskLicenceKey()', main)
        self.assertIn('navigation.select(HomeSettingsPager.HOME, true)', main)
        self.assertIn('outState.putInt(STATE_PAGE, navigation.currentPage())', main)
        self.assertIn('savedInstanceState.getInt(STATE_PAGE, HomeSettingsPager.HOME)', main)
        self.assertNotIn('settingsPage.getVisibility()', main)
        self.assertNotIn('actionBarView.setVisibility(', main)
        stop = main.split('protected void onStop()', 1)[1].split('super.onStop()', 1)[0]
        self.assertIn('homeStarted = false', stop)
        self.assertIn('maskLicenceKey()', stop)

    def test_navigation_insets_are_not_added_twice_to_the_pages(self):
        main = (JAVA / 'MAct.java').read_text()
        self.assertIn('Ui.padBottom(bottomNavigation, bars.bottom)', main)
        self.assertNotIn('Ui.padBottom(settingsContent, bars.bottom)', main)
        self.assertNotIn('Ui.padBottom(actionPanel, bars.bottom)', main)
        self.assertNotIn('R.id.actionBar', main)
        self.assertNotIn('R.id.actionPanel', main)
        self.assertNotIn('addOnLayoutChangeListener', main.split('private void setupInsets()', 1)[1].split('private void confirmSignOut()', 1)[0])

    def test_game_controls_and_feedback_are_inside_their_own_scrolling_cards(self):
        home = ids('page_home.xml')
        for card, children in (
                ('gameCard', ('primaryAction', 'gameStateValue', 'statusMessage', 'taskProgress')),
                ('airGameCard', ('airPrimaryAction', 'airGameStateValue', 'airStatusMessage', 'airTaskProgress'))):
            descendants = {e.get(A + 'id') for e in home['@+id/' + card].iter()}
            for child in children:
                self.assertIn('@+id/' + child, descendants)
            self.assertNotIn('@+id/serverValue', descendants)
            self.assertNotIn('@+id/statusValue', descendants)
            self.assertIn(home['@+id/' + card], list(home['@+id/mainScroll'].iter()))
        self.assertNotIn('@+id/actionBar', home)
        self.assertNotIn('@+id/actionPanel', home)
        self.assertNotIn('@+id/airComingSoonDetail', home)
        self.assertNotIn('@+id/libraryValue', home)
        self.assertNotIn('@+id/airLibraryValue', home)
        strings = {e.get('name'): e.text for e in ET.parse(RES / 'values/strings.xml').getroot()}
        self.assertEqual(strings['home_title'], 'Welcome back')
        self.assertEqual(strings['game_air_coming_soon_action'], 'Coming soon')
        self.assertNotIn('game_air_coming_soon_detail', strings)
        self.assertEqual(home['@+id/airGameStateValue'].get(A + 'visibility'), 'gone')

    def test_expiry_and_global_status_are_in_header_and_active_segment_is_blue(self):
        home = ids('page_home.xml')
        header_ids = {e.get(A + 'id') for e in home['@+id/mainHeader'].iter()}
        for name in ('licenceSummary', 'timeLeftValue', 'statusValue', 'serverValue'):
            self.assertIn('@+id/' + name, header_ids)
        self.assertNotIn('@+id/licenceCard', home)
        self.assertNotIn('@+id/expiresValue', home)
        self.assertIn('@+id/licenceExpiresValue', ids('page_settings.xml'))
        indicator = ET.parse(RES / 'drawable/bg_nav_indicator.xml').getroot()
        self.assertEqual(indicator.find('solid').get(A + 'color'), '@color/accent')
        self.assertIn('R.color.on_accent', (JAVA / 'ui/HomeSettingsNavigation.java').read_text())

    def test_swipes_arbitrate_actual_events_before_child_scroll_interception(self):
        pager = (JAVA / 'ui/HomeSettingsPager.java').read_text()
        for required in ('dispatchTouchEvent(MotionEvent event)', 'onInterceptTouchEvent(MotionEvent event)',
                         'requestDisallowInterceptTouchEvent(boolean disallowIntercept)',
                         'direction = dx > dy ? HORIZONTAL : VERTICAL',
                         'direction != HORIZONTAL', 'direction == VERTICAL',
                         'MotionEvent.ACTION_CANCEL', 'super.onTouchEvent(event)'):
            self.assertIn(required, pager)
        self.assertNotIn('beginFakeDrag(', pager)
        self.assertNotIn('setOnTouchListener(', pager)
        host = (ROOT / 'app/src/test/java/com/xlox/ui/HomeSettingsNavigationTest.java').read_text()
        self.assertIn('root.dispatchTouchEvent(event)', host)
        self.assertIn('diagonalSwipeFromScrolledSettingsContentReachesHome', host)
        self.assertIn('diagonalSwipeStartingOnLaunchCancelsTheButtonAndTapStillWorks', host)
        self.assertIn('verticalSwipeStartingOnGameButtonKeepsItsScrollAndNeverLaunches', host)

    def test_static_dashboard_fonts_are_licensed_pinned_and_not_variable(self):
        fonts = {
            'regular': (400, '2602c71d1f36c96da0022706f15da7d51102d19d10510b77b5a39bf861122807'),
            'medium': (500, '729037f5dc28daa949c5ce8e69d945bf14faf2649db737e6eb085c120c778dd0'),
            'semibold': (600, '2b2ab8c077256d2849465199657f042e36588a7b88d345acd2ab4e5807ebef99'),
        }
        for style, (weight, digest) in fonts.items():
            data = (RES / 'font' / ('manrope_' + style + '.ttf')).read_bytes()
            self.assertEqual(hashlib.sha256(data).hexdigest(), digest)
            self.assertEqual(data[:4], b'\x00\x01\x00\x00')
            count = struct.unpack_from('>H', data, 4)[0]
            tables = {}
            for i in range(count):
                tag, _, offset, size = struct.unpack_from('>4sIII', data, 12 + i * 16)
                self.assertLessEqual(offset + size, len(data))
                tables[tag] = (offset, size)
            self.assertNotIn(b'fvar', tables)  # Android 21 cannot use variable fonts.
            self.assertEqual(struct.unpack_from('>H', data, tables[b'OS/2'][0] + 4)[0], weight)
            for required in (b'glyf', b'cmap', b'name', b'hmtx'):
                self.assertIn(required, tables)
        licence = (ROOT / 'app/src/main/assets/licenses/OFL-Manrope.txt').read_text()
        self.assertIn('SIL OPEN FONT LICENSE Version 1.1', licence)
        styles = (RES / 'values/styles.xml').read_text()
        self.assertIn('<style name="Text.Dashboard" parent="Text">', styles)
        self.assertIn('@font/manrope_regular', styles)
        self.assertIn('@font/manrope_medium', styles)
        self.assertIn('@font/manrope_semibold', styles)

    def test_source_version_and_compatible_runtime_pins(self):
        gradle = (ROOT / 'app/build.gradle').read_text()
        for value in ("versionName '2.4.3'", 'versionCode 21', 'minSdk 21', 'targetSdk 28',
                      'compileSdk 36', "androidx.viewpager:viewpager:1.0.0", "namespace 'com.xlox'"):
            self.assertIn(value, gradle)
        self.assertIn("testImplementation 'org.robolectric:robolectric:4.14.1'", gradle)
        self.assertIn('unitTests.includeAndroidResources = true', gradle)


if __name__ == '__main__':
    unittest.main(verbosity=2)
