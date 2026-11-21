# Contained game web login — original-Mundo candidate

## Corrected report / scope

The tester confirmed 2.4.3 did not fix the reported Twitter flow. Stock BGMI
opens the web sign-in inside the game. When launched through the loader it opens
outside; successful web sign-in then tries to open the real BGMI installation,
not the running virtual game. Adding a few Android callback filters alone does
not prevent that outbound escape.

This candidate changes the outbound path, using the original Mundo library.
Neither AAR, native licensing/injection contract, game OBB flow, app signer,
brand identity, library-download fixes nor backend is replaced. No live panel
selection changes. The uploaded replacement Mundo remains quarantined.

## Implementation and evidence

- Static examination of the pinned original AAR confirms its activity handler
  covers seven standard launch methods, and `MetaCore.addServiceInterpreter`
  returns a previous `InvocationHandler`. These API/hash facts are checked by
  the regression suite; no SDK class/native binary is executed by local tests.
- Only the virtual game process callback installs `GameWebRouting`. Host loader
  links (including Telegram) are not globally intercepted. Entry matching is
  limited to Twitter/X login, OAuth and web-login URLs; news/store/ordinary
  game web links retain their original behaviour. Both `activity` and
  `activity_task` services are supported, covering legacy/current Android calls.
- `GameWebCallRouter` changes only the outbound authentication HTTPS Intent argument and
  delegates to the original SDK. Binder/resultTo, request code, options, return
  object/type and original exception behaviour are retained. Registration is
  synchronized so a concurrent call cannot race previous-handler assignment.
- Already-explicit virtual-game activities are unchanged. If an implicit or
  outside-browser-targeted authentication URL has a matching virtual game handler, route
  explicitly to that in-game activity instead of retaining Chrome's component.
- Otherwise use a fresh explicit Intent targeting this flavor's non-exported
  `GameWebLoginActivity`. Browser-session extras and destructive task flags are
  not carried into this fallback. It is not an Android default browser or a
  broad web/App-Link receiver.
- The fallback displays the actual HTTPS origin (not the secret-bearing URL).
  System WebView renders the game-supplied page; JavaScript/DOM storage/cookies
  support the ordinary provider flow. A separate host WebView data-directory
  suffix is set before inflation on Android 9+. No cookie/data clearing.
- Strict TLS, no mixed content, no file/content access, no native JS interface,
  no JS injection, no user-agent spoofing. Screenshots are disabled on the login
  surface; release WebView debugging is disabled. Provider credentials, URI,
  query/fragment, Intent contents and exception text are not logged.
- Main-frame custom callback schemes, HTTPS App Links and `intent://` returns
  are resolved dynamically from virtual BGMI's actual manifest. Only enabled,
  exported BGMI handlers in the already-running default virtual user may receive
  a return. Explicit intent components/packages must also belong to BGMI. Fresh
  callback Intents strip grants, selectors, arbitrary parcelables and Android
  control extras; legitimate primitive provider extras may pass without logging.
- A successful dispatch uses `MetaActivityManager.launchIntent`, not Android's
  outside resolver. Unmatched returns are blocked visibly, never opened in real
  BGMI or an outside browser. An intent URI's HTTPS browser fallback can load
  only inside the same WebView, bounded to three fallback loads.
- Back/close foregrounds only the existing virtual game; it does not start a new
  game session or erase the pending state by restarting the game.

## Publication / rollback safety

Version name `2.4.4-webtest`, versionCode **21** deliberately matches 2.4.3 for
manual in-place return to the existing APK with the same signer, without an
uninstall/data clear. The release workflow marks `-webtest`, `-test` and `-rc`
names **prerelease / not latest**; normal stable version publication is unchanged.
The panel's latest-stable helper ignores prereleases. Neither installing this
candidate nor creating its GitHub assets changes live library/APK selections.

## Checks / limits

- 12 Android/source/API/wiring/parser guards.
- 15 existing JVM library/callback groups plus 15 contained-web groups execute
  production policy, service-chain and navigation state machines. Fixtures cover
  explicit outside browser requests, game-owned activity preservation, unknown
  game callback schemes, HTTPS App Links, intent redirects, in-WebView fallback,
  rejecting unhandled/unsafe navigation, original request/result argument
  preservation, unchanged unrelated calls, exception propagation and concurrent
  registration. No real credentials are used.
- CI checks both actual APK manifests for the non-exported/no-filter WebView and
  legacy callback filters, in addition to build/lint/signature/branding/native
  alignment and existing UI/artwork/panel regressions.

**No phone/provider-login success is asserted by these automated tests.** There
is no attached device, game APK/trace or automated Twitter account in this
workspace. Twitter may also impose its own supported-WebView policy; the app
must not spoof/bypass it. If a URL is not a game HTTPS VIEW launch, the new
handler does not silently rewrite unrelated traffic. An unrecognized/absent
virtual callback handler fails closed rather than leaking a return to real BGMI.

## Device acceptance (pending)

1. Install the original-Mundo webtest over the existing loader, without clearing
   app/container data. Start virtual BGMI and select Twitter web login.
2. The page must stay in the game's own embedded view or the loader's private
   **Game web login** surface. Chrome/another outside browser must not open.
3. Finish the official provider flow. Verify the same running virtual game is
   foregrounded and actually completes account login; real BGMI must not open.
4. Cancel/back and retry. Verify an old return cannot restart a stopped game.
   Check Facebook, ordinary loader Telegram links and library updates unchanged.
5. If containment itself fails, report whether **Game web login** appeared, plus
   model/Android/BGMI versions. Result-only log tags `GameWebRouting` and
   `GameWebLogin` contain no URLs/tokens and can identify handler installation
   versus callback resolution. Do not send account passwords, licence keys,
   full OAuth URLs, cookies or unredacted logs. No data clear is required.
