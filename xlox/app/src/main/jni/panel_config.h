/*
 * One panel, two fixed release channels. RELEASE_CHANNEL is passed by the
 * Gradle flavor to Android.mk; the native URLs AND Java fallbacks agree.
 * /connect, the salt, JNI class names and the container contract stay shared.
 */
#ifndef ANOY_PANEL_CONFIG_H
#define ANOY_PANEL_CONFIG_H

#define ANOY_PANEL_BASE   "https://anoymundo-panel.vercel.app"
#if defined(LOADER_KENNY)
#define LOADER_BRAND      "KennyPvt"
#define LOADER_CHANNEL    "kenny"
#define ANOY_TELEGRAM_URL "https://t.me/crimecell"
#define ANOY_VERSION_URL  "https://anoymundo-panel.vercel.app/api/lib/version?channel=kenny"
#define ANOY_DOWNLOAD_URL "https://anoymundo-panel.vercel.app/api/lib/download?channel=kenny"
#else
#define LOADER_BRAND      "Anoy"
#define LOADER_CHANNEL    "anoy"
#define ANOY_TELEGRAM_URL "https://t.me/libAkAudioVisiual"
#define ANOY_VERSION_URL  "https://anoymundo-panel.vercel.app/api/lib/version?channel=anoy"
#define ANOY_DOWNLOAD_URL "https://anoymundo-panel.vercel.app/api/lib/download?channel=anoy"
#endif
#define ANOY_CONNECT_URL  "https://anoymundo-panel.vercel.app/connect"

/* Must match STATIC_WORDS on the panel (real = game-user_key-serial-WORDS). */
#define ANOY_STATIC_WORDS "Vm8Lk7Uj2JmsjCPVPVjrLa7zgfx3uz9E"

#define ANOY_USER_AGENT LOADER_BRAND "/2.4"

#endif
