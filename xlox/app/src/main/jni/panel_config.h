/*
 * Anoy panel integration for the XLOX loader.
 *
 * These are the ONLY endpoints the app talks to. Change ANOY_PANEL_BASE to
 * point the APK at another deployment, then rebuild. The panel must expose:
 *   GET  /api/lib/version   -> plain-text version, one line
 *   GET  /api/lib/download  -> ZIP containing libbgmi.so (application/zip)
 *   POST /connect           -> licence check JSON (status / data / reason)
 */
#ifndef ANOY_PANEL_CONFIG_H
#define ANOY_PANEL_CONFIG_H

#define ANOY_PANEL_BASE   "https://anoymundo-panel.vercel.app"
#define ANOY_VERSION_URL  "https://anoymundo-panel.vercel.app/api/lib/version"
#define ANOY_DOWNLOAD_URL "https://anoymundo-panel.vercel.app/api/lib/download"
#define ANOY_CONNECT_URL  "https://anoymundo-panel.vercel.app/connect"
#define ANOY_TELEGRAM_URL "https://t.me/libAkAudioVisiual"

/* Must match STATIC_WORDS on the panel (real = game-user_key-serial-WORDS). */
#define ANOY_STATIC_WORDS "Vm8Lk7Uj2JmsjCPVPVjrLa7zgfx3uz9E"

#define ANOY_USER_AGENT   "AnoyXLOX/1.0"

#endif
