/*
 * ===================== IGNIS SETTINGS - EDIT ONLY THIS FILE =====================
 *
 * 1. CONNECT_URL   your own panel's licence check (the /connect address).
 * 2. VERSION_URL   version.txt in your GitHub release (lib version, one line).
 * 3. DOWNLOAD_URL  lib.zip in the same GitHub release.
 *
 * apk.txt (app updates) is read from the SAME place as version.txt:
 * just put apk.txt next to version.txt in the release.
 *
 * Replace YOUR-NAME / YOUR-REPO / your-panel.example.com, then build the APK.
 * ===============================================================================
 */
#ifndef IGNIS_PANEL_CONFIG_H
#define IGNIS_PANEL_CONFIG_H

#define IGNIS_CONNECT_URL  "https://your-panel.example.com/connect"
#define IGNIS_VERSION_URL  "https://github.com/YOUR-NAME/YOUR-REPO/releases/download/update/version.txt"
#define IGNIS_DOWNLOAD_URL "https://github.com/YOUR-NAME/YOUR-REPO/releases/download/update/lib.zip"

/* Help button (Telegram). */
#define IGNIS_TELEGRAM_URL "https://t.me/libAkAudioVisiual"

/* Must be the same as STATIC_WORDS on your panel. */
#define IGNIS_STATIC_WORDS "Vm8Lk7Uj2JmsjCPVPVjrLa7zgfx3uz9E"

#define IGNIS_USER_AGENT   "Ignis/1.0"

#endif
