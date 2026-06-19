# Testing ApplaudIQEmbed (Android)

## Unit tests (no device/emulator)

Pure-JVM logic (URL building, the bridge-injection script, origin confinement, SSO URL building, deep-link
parsing) lives in `EmbedInternals` and is covered by `embed/src/test/`:

```bash
JAVA_HOME=<jdk17> ANDROID_HOME=<android-sdk> ./gradlew :embed:testDebugUnitTest
```

Build the library AAR:

```bash
./gradlew :embed:assembleRelease   # → embed/build/outputs/aar/embed-release.aar
```

## Manual / instrumented smoke (emulator or device)

Use the runnable example app in
[applaudiq-sdk-example](https://github.com/therewardstore/applaudiq-sdk-example) under
`native-integration/android/`. Point `Config` at your portal (`http://10.0.2.2:3017` for the local stack from
an emulator) and your publishable key, then verify:

1. **Auto-login** — pass a server-minted `embedToken`; the portal signs in silently and shows the feed; no
   visible login. `onReady` fires.
2. **Manual login** — `mode = MANUAL`; the portal shows its own email/SSO login inside the WebView and signs in
   **without a visible reCAPTCHA** (the native nonce path).
3. **SSO** — choosing Google/Microsoft opens **Chrome Custom Tabs** (not the WebView); after auth the
   `applaudiq://sso-callback` deep link returns to the app and the session is established.
4. **HR approval** — a brand-new employee sees a "pending HR approval" screen (`onAuthPending`) until approved.
5. **Security smoke** — firing a stray deep link with no SSO in flight is ignored
   (`adb shell am start -a android.intent.action.VIEW -d "applaudiq://sso-callback?code=evil"`); an off-portal
   link opens in the system browser, not the embed; an expired token shows a graceful error (no blank screen).

## Backend / contract tests

The portal-side `/embed` + backend contract this SDK implements is covered in the main platform repo:
`scripts/tests/embed/embed-android-api.sh` (mint/exchange/validate/captcha-nonce/identify-bypass) and the
Playwright native-simulation spec `frontend/employee-portal/tests/e2e/embed_native.spec.ts`.
