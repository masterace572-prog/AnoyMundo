#!/usr/bin/env python3
"""SDK-free structural guards; Android behavior is tested separately by Robolectric."""
from pathlib import Path
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
        for key in ('primaryAction', 'airPrimaryAction', 'actionBar', 'gameCard', 'airGameCard'):
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
        self.assertIn('int needed = (b - t) + Ui.dp(this, 8)', main)

    def test_source_version_and_compatible_runtime_pins(self):
        gradle = (ROOT / 'app/build.gradle').read_text()
        for value in ("versionName '2.4.3'", 'versionCode 21', 'minSdk 21', 'targetSdk 28',
                      'compileSdk 36', "androidx.viewpager:viewpager:1.0.0", "namespace 'com.xlox'"):
            self.assertIn(value, gradle)
        self.assertIn("testImplementation 'org.robolectric:robolectric:4.14.1'", gradle)
        self.assertIn('unitTests.includeAndroidResources = true', gradle)


if __name__ == '__main__':
    unittest.main(verbosity=2)
