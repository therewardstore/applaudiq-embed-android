# Changelog

All notable changes to ApplaudIQEmbed (Android) are documented here. This project follows
[Semantic Versioning](https://semver.org/).

## [1.0.0]

First production release of the native Android SDK — parity with the iOS + Web SDKs.

- **Auto + manual login** in a `WebView`, mirroring the web/iOS bridge protocol: the portal's
  `postToHost()` is delivered via the injected `window.ReactNativeWebView.postMessage` shim, and
  `window.__APPLAUDIQ_EMBED__ = { mode, native: true }` is set at document start on every main-frame load
  so the portal reliably detects an auto/manual native embed (across `/embed → /login → /`).
- **Lifecycle callbacks:** `onReady` / `onAuthPending` / `onError` / `onClose` / `onSignOut` (Kotlin `Config`
  and the Java `AIQEmbed.Listener`).
- **`backNavigation`** (Kotlin `Config`, default `true`): the hardware Back button steps back through the embed's
  WebView history, closing the embed only at the root. Set `false` to make Back close the embed immediately.
- **SSO via Chrome Custom Tabs:** an `applaudiq:sso-request` opens
  `…/auth/sso/{provider}/employee/authorize?native=1&client_id=&login_hint=` (provider allowlisted to
  google/microsoft); the returned one-time code comes back on the `applaudiq://sso-callback` deep link and is
  redeemed by an in-WebView same-origin fetch to `/api/v1/employee/auth/sso/exchange`, then the portal reloads.
- **Security:** the JS bridge is honored only while the WebView is on the portal origin; main-frame navigation
  is pinned to the portal origin (sub-frames — reCAPTCHA, IdP widgets — still load; off-origin top-level links go
  to the system browser); a stray `applaudiq://` deep link is ignored unless an SSO flow is in-flight; HTTPS is
  enforced (`onError("insecure_base_url")` otherwise; cleartext only for `localhost`/`10.0.2.2` in debuggable
  builds); file access is disabled; mixed content blocked; tokens/codes are never logged; the `aiq_embed_` secret
  is never accepted.
- **API surface:** `ApplaudIQEmbed.open(context, Config(...))` (Kotlin) and `AIQEmbed.open(...)` with a
  `Listener` (Java). Published as `com.applaudiq:embed`.
