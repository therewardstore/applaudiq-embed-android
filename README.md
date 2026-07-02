# ApplaudIQEmbed — Android SDK

Embed the **Applaud IQ** recognition portal inside a native Android app. The SDK hosts the portal in a
`WebView` and handles the token bridge + SSO; you pass a **publishable key** (and, for auto-login, a
one-time token), open the embed, and handle a few callbacks.

- **Android 6.0+ (API 23+)** · Kotlin **and** Java · only AndroidX (`activity`, `browser`, `webkit`)
- Install via **Gradle** (Maven Central) or **manually** (source module)

---

## Build integration

### 1. Install

**Gradle (Maven Central)** ([central.sonatype.com](https://central.sonatype.com/artifact/com.applaudiq/embed)) —
ensure `mavenCentral()` is in your `settings.gradle` repositories, then in your module `build.gradle`:

```groovy
implementation 'com.applaudiq:embed:1.2.0'
```

**Manual (source module)** — clone this repo (or add it as a git submodule) next to your app and include it:

```groovy
// settings.gradle
include ':embed'
project(':embed').projectDir = new File(rootProject.projectDir, '../applaudiq-embed-android/embed')
// app/build.gradle
implementation project(':embed')
```

### 1b. Set your SSO callback scheme

SSO opens in the system browser and returns to your app via a deep link. Pick a scheme **unique to your app**
(not the brand-wide `applaudiq`, which other Applaud IQ apps may claim) and set it in `app/build.gradle`. The SDK
auto-registers the `ApplaudIQEmbedActivity` intent-filter from these and sends them to the backend as
`native_redirect`, so the callback returns to **exactly your app** — no Android "Open with" chooser when two
Applaud IQ apps are installed on one device:

```groovy
// app/build.gradle → android { defaultConfig { … } }
manifestPlaceholders = [aiqSsoScheme: 'myapp', aiqSsoHost: 'sso-callback']
```

> The library deliberately does **not** default these — a library default bakes into the AAR and can't be
> overridden by the consumer (the AppAuth redirect-scheme contract), so each app must declare its own.

SSO runs in **Chrome Custom Tabs**; on success the one-time code returns on your `<scheme>://sso-callback` deep
link and is exchanged inside the WebView (`onReady`). On failure (e.g. wrong account) the SDK fires
`onError(message)` and reloads the login so the user can retry; the browser tab is finished automatically and
does **not** linger in the recents switcher.

### 2. Import

```kotlin
// Kotlin
import com.applaudiq.embed.ApplaudIQEmbed
```

```java
// Java
import com.applaudiq.embed.AIQEmbed;
```

### 3. Get your keys

- **Publishable key** (`pk_live_…`) — from **HR portal → Settings → Embed SDK Keys**. Safe to ship in the app;
  **required in both login modes**.
- **Auto-login only:** your server mints a one-time `embedToken` (`POST <api>/api/v1/embed/sessions` with the
  server secret) — the secret never goes in the app. Manual login needs neither.

### 4. Open the embed

**Manual login** — the portal shows its own email / SSO login; just the publishable key:

```kotlin
// Kotlin
ApplaudIQEmbed.open(
    context,
    ApplaudIQEmbed.Config(key = "pk_live_…", mode = ApplaudIQEmbed.Mode.MANUAL),
)
```

```java
// Java
AIQEmbed.open(context, "pk_live_…", null, AIQEmbed.Mode.MANUAL, null, null);
```

**Auto-login** — silent sign-in with a token your server minted:

```kotlin
// Kotlin
ApplaudIQEmbed.open(
    context,
    ApplaudIQEmbed.Config(
        key = "pk_live_…",          // baseUrl defaults to https://recognize.applaudiq.com
        token = embedToken,
        mode = ApplaudIQEmbed.Mode.AUTO,
        onReady = { /* signed in, feed shown */ },
        onAuthPending = { /* signed in, awaiting HR approval */ },
        onError = { message -> /* sign-in failed */ },
        onClose = { /* embed dismissed */ },
        onSignOut = { /* user signed out of an auto embed — tear down your session */ },
    ),
)
```

```java
// Java
AIQEmbed.open(context, "pk_live_…", null, AIQEmbed.Mode.AUTO, embedToken, new AIQEmbed.Listener() {
    @Override public void onReady() { /* signed in, feed shown */ }
    @Override public void onAuthPending() { /* signed in, awaiting HR approval */ }
    @Override public void onError(String message) { /* sign-in failed */ }
    @Override public void onClose() { /* embed dismissed */ }
    @Override public void onSignOut() { /* user signed out of an auto embed */ }
});
```

### 5. Handle callbacks

`onReady` (signed in & shown) · `onAuthPending` (signed in, awaiting HR approval — show a pending state) ·
`onError(message)` (bad/expired key or token, blocked load, **or a failed SSO sign-in** — offer a retry) · `onClose` (embed dismissed) ·
`onSignOut` (the user signed out of an **auto** / host-managed embed — tear down your app's session).

### Config options

- **`backNavigation`** — default **`true`**: the hardware **Back** button steps back through the embed's WebView
  history, closing the embed only at the root. Set `false` to make Back close the embed immediately:
  ```kotlin
  ApplaudIQEmbed.Config(key = "pk_live_…", backNavigation = false)
  ```

---

## Downloads & external links

When the portal (or the reward store nested inside it) needs to open a URL outside the WebView —
a file download, a payment page, or an OAuth handoff — it sends the `applaudiq:open-external` bridge
message with payload `{ url }`. The SDK opens `http(s)` URLs in the **system browser**
(`Intent.ACTION_VIEW`). No app code is required.

---

## Test integration

- Run on an emulator/device. **Manual login works with just the publishable key** — no server needed.
- For auto-login, point your app at a backend that mints a token, or test with a token minted via curl.
- A brand-new employee signs in but sees a **pending HR approval** screen until an HR admin approves them
  (`onAuthPending` fires).
- A runnable example lives in
  [applaudiq-sdk-example](https://github.com/therewardstore/applaudiq-sdk-example/tree/master/native-integration/android) under
  `native-integration/android/`.

## Go-live checklist

- Use a `pk_live_…` key and your production `baseUrl`. **`baseUrl` must be HTTPS** — a non-secure origin is
  refused at load with `onError("insecure_base_url")` (plain `http` is allowed only for `localhost`/`10.0.2.2`
  in **debuggable** builds).
- Auto-login: a real server-side mint endpoint (never embed the `aiq_embed_…` secret in the app).
- SSO returns via your **per-app `<scheme>://sso-callback`** deep link (set with `manifestPlaceholders`, opened in
  **Chrome Custom Tabs**); on failure it fires `onError` and reloads the login, and the tab never lingers in recents.

---

## API

| Language   | Entry point                                                                                                                                  |
| ---------- | ------------------------------------------------------------------------------------------------------------------------------------------- |
| **Kotlin** | `ApplaudIQEmbed.open(context, Config(key, token, mode, baseUrl, onReady, onAuthPending, onError, onClose, onSignOut, backNavigation))` |
| **Java**   | `AIQEmbed.open(context, key, baseUrl, AIQEmbed.Mode, token, AIQEmbed.Listener)` — `Listener` has `onReady`/`onAuthPending`/`onError`/`onClose`/`onSignOut` (default no-ops) |

`Mode` is `AUTO` (uses `token`) or `MANUAL` (no token). The publishable `key` is required in both modes.

## Changelog

Latest: **v1.2.0 (LTS)**. See [CHANGELOG.md](./CHANGELOG.md) for the full release history (also shown on the Maven Central page).

## License

[MIT](./LICENSE) © Applaud IQ
