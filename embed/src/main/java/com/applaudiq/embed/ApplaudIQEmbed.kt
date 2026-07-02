package com.applaudiq.embed

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.browser.customtabs.CustomTabsIntent
import org.json.JSONObject

/**
 * ApplaudIQEmbed — renders the full Applaud IQ recognition portal in a WebView with auto
 * or manual login. Mirrors the web + iOS SDK bridge protocol.
 *
 * Auto-login: your BACKEND mints a one-time `embedToken`
 * (POST /api/v1/embed/sessions with the secret key); pass it here.
 *
 *   ApplaudIQEmbed.open(
 *       context,
 *       ApplaudIQEmbed.Config(
 *           key = "pk_live_xxx",
 *           token = embedToken,
 *           onReady = { /* signed in */ },
 *           onError = { msg -> /* failed */ },
 *       ),
 *   )
 *
 * SSO can't run inside a WebView (Google/Microsoft block embedded webviews), so an SSO request
 * opens Chrome Custom Tabs (system browser) and the returned one-time code comes back via the
 * `applaudiq://sso-callback?code=` deep link and is redeemed inside the web view.
 */
object ApplaudIQEmbed {

    enum class Mode(val raw: String) { AUTO("auto"), MANUAL("manual") }

    /**
     * @param key        publishable key (`pk_live_…`) — never the `aiq_embed_` secret.
     * @param token      one-time embedToken from your server's mint call (auto-login only).
     * @param mode       AUTO (silent, needs token) or MANUAL (portal login form).
     * @param baseUrl    portal origin. Must be HTTPS (http only for localhost in debug builds).
     * @param onReady    employee is signed in and the portal is shown.
     * @param onAuthPending signed in but awaiting HR approval (new/auto-provisioned employee).
     * @param onError    sign-in failed (message: bad/expired key or token, blocked, network…).
     * @param onClose    the embed was dismissed.
     * @param onSignOut  the user signed out from inside an auto (host-managed) embed.
     */
    data class Config(
        val key: String,
        val token: String? = null,
        val mode: Mode = Mode.AUTO,
        val baseUrl: String = "https://recognize.applaudiq.com",
        val onReady: (() -> Unit)? = null,
        val onAuthPending: (() -> Unit)? = null,
        val onError: ((String) -> Unit)? = null,
        val onClose: (() -> Unit)? = null,
        val onSignOut: (() -> Unit)? = null,
        /**
         * When true (default), the hardware Back button steps back through the embed's in-app
         * history (`webView.goBack()`) until the root, where Back closes the embed. Set false to
         * keep the default (Back closes the embed immediately).
         */
        val backNavigation: Boolean = true,
    )

    private var pending: Config? = null

    /** Present the embed full-screen. */
    fun open(context: Context, config: Config) {
        pending = config
        context.startActivity(Intent(context, ApplaudIQEmbedActivity::class.java))
    }

    internal fun consume(): Config? = pending.also { pending = null }
}

class ApplaudIQEmbedActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private lateinit var config: ApplaudIQEmbed.Config

    private var tokenSent = false
    private var readyFired = false
    private var initErrorFired = false
    private var ssoInFlight = false

    // The app's SSO callback deep link — each app registers its OWN (default `applaudiq://sso-callback`)
    // via the `aiqSsoScheme`/`aiqSsoHost` manifest placeholders, so two Applaud IQ apps on one device
    // don't both claim the same callback (which would pop an Android "Open with" chooser and misroute the
    // one-time code). Read from the merged manifest's meta-data; sent to the backend as `native_redirect`
    // and matched when parsing the returning deep link.
    private val ssoCallback: String by lazy {
        runCatching {
            packageManager.getApplicationInfo(packageName, android.content.pm.PackageManager.GET_META_DATA)
                .metaData?.getString("com.applaudiq.embed.SSO_CALLBACK")
        }.getOrNull()?.takeIf { it.contains("://") } ?: "applaudiq://sso-callback"
    }
    private val ssoScheme: String get() = ssoCallback.substringBefore("://")
    private val ssoHost: String get() = ssoCallback.substringAfter("://").substringBefore("/").substringBefore("?")

    private companion object {
        const val STATE_SSO_IN_FLIGHT = "aiq_sso_in_flight"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = ApplaudIQEmbed.consume() ?: run { finish(); return }
        // Survive an Activity recreation (e.g. config change) during the Custom Tabs login so the
        // returning callback deep link is still redeemed instead of being dropped by the guard below.
        savedInstanceState?.let { ssoInFlight = it.getBoolean(STATE_SSO_IN_FLIGHT, false) }

        // Refuse an insecure portal origin: the one-time token + session cookies must never travel
        // over cleartext. HTTPS required; http tolerated only for a localhost dev portal in debug.
        val debug = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!EmbedInternals.isSecureBaseUrl(config.baseUrl, allowCleartextLocalhost = debug)) {
            config.onError?.invoke("insecure_base_url")
            finish()
            return
        }

        webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(Color.WHITE)
            with(settings) {
                javaScriptEnabled = true            // the portal is a JS app — required
                domStorageEnabled = true            // sessionStorage holds aiq_embed_mode/key — required
                // Lock everything else down.
                allowFileAccess = false
                allowContentAccess = false
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = false
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                mediaPlaybackRequiresUserGesture = true
            }
            // No on-disk debugging in release builds.
            WebView.setWebContentsDebuggingEnabled(debug)
            addJavascriptInterface(Bridge(), "ApplaudIQAndroid")
            webViewClient = EmbedWebViewClient()
        }
        setContentView(webView)

        // Hardware Back steps back through the embed's WebView history; at the root it falls
        // through to the default (finish). Disabled when backNavigation = false.
        onBackPressedDispatcher.addCallback(this) {
            if (config.backNavigation && webView.canGoBack()) {
                webView.goBack()
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }

        webView.loadUrl(
            EmbedInternals.buildEmbedUrl(config.baseUrl, config.mode.raw, config.key, config.token),
        )
        handleDeepLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_SSO_IN_FLIGHT, ssoInFlight)
    }

    override fun onDestroy() {
        // Tear down the WebView cleanly so the in-memory portal session doesn't linger.
        if (this::webView.isInitialized) {
            webView.removeJavascriptInterface("ApplaudIQAndroid")
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        super.onDestroy()
    }

    /** applaudiq://sso-callback?code=… (success) or ?error=… (failure) returns from Custom Tabs. */
    private fun handleDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (!ssoInFlight) return // ignore stray deep links fired by other apps when no SSO is pending
        val uri = data.toString()
        // Only act on OUR callback; an unrelated deep link is left alone (stray-link guard).
        if (!EmbedInternals.isSsoCallback(uri, ssoScheme, ssoHost)) return
        val code = EmbedInternals.parseSsoCode(uri, ssoScheme, ssoHost)
        if (code != null) {
            ssoInFlight = false
            completeSSO(code)
            return
        }
        // Failure / identity-mismatch (?error=… or no code): surface it to the host AND show the failure
        // INSIDE the embed — load the portal's own /sso-callback error page (the same page the web + Capacitor
        // SDKs land on) so the message is visible; its "Return to login" goes back to the embed login.
        ssoInFlight = false
        val message = EmbedInternals.parseSsoError(uri, ssoScheme, ssoHost) ?: "sso_failed"
        config.onError?.invoke(message)
        if (this::webView.isInitialized) {
            // Show the failure on the FRAMEABLE embed page, which renders the "Authentication Failed" card
            // (the portal's /sso-callback page is X-Frame-Options: DENY, so it can't be reused by the Capacitor
            // iframe — all SDKs route SSO errors here for consistency). "Return to login" retries in the embed.
            val errUrl =
                EmbedInternals.buildEmbedUrl(config.baseUrl, config.mode.raw, config.key, config.token) +
                    "&sso_error=" + java.net.URLEncoder.encode(message, "UTF-8")
            webView.loadUrl(errUrl)
        }
    }

    // MARK: navigation confinement — pin the MAIN FRAME to the portal origin; sub-frames (reCAPTCHA,
    // SSO widgets, fonts, analytics) are sandboxed sub-resources and must keep loading; everything
    // else off-origin is handed to the system browser so an open redirect can't move the
    // authenticated session — and the native bridge — onto an attacker-controlled page.
    private inner class EmbedWebViewClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            route(request.url, request.isForMainFrame)

        @Deprecated("Deprecated in Java")
        override fun shouldOverrideUrlLoading(view: WebView, url: String?): Boolean =
            route(url?.let(Uri::parse), true)

        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            // Inject the native bridge + flags as early as possible, ONLY on portal-origin pages.
            if (EmbedInternals.sameOrigin(url, config.baseUrl)) {
                view.evaluateJavascript(EmbedInternals.bridgeInitScript(config.mode.raw), null)
            }
        }

        private fun route(uri: Uri?, isMainFrame: Boolean): Boolean {
            if (uri == null) return false
            if (!isMainFrame) return false // let sub-frames load in place
            val url = uri.toString()
            if (uri.scheme == "about" || EmbedInternals.sameOrigin(url, config.baseUrl)) return false
            // Off-origin top-level navigation → open in the system browser, not the embed WebView.
            if (uri.scheme == "http" || uri.scheme == "https") {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            }
            return true
        }
    }

    // MARK: bridge embed → native
    inner class Bridge {
        @JavascriptInterface
        fun onMessage(json: String) {
            // @JavascriptInterface runs on a binder thread. Hop to the UI thread, then verify the
            // WebView is still on the portal origin before honoring anything — a navigated-away or
            // off-origin page must not be able to spoof the handshake or trigger SSO / close.
            runOnUiThread {
                if (!EmbedInternals.sameOrigin(webView.url, config.baseUrl)) return@runOnUiThread
                dispatch(json)
            }
        }

        /** Reported by completeSSO()'s in-webview fetch when the one-time code exchange fails. */
        @JavascriptInterface
        fun onSsoError() = runOnUiThread { config.onError?.invoke("sso_exchange_failed") }
    }

    private fun dispatch(json: String) {
        val d = try { JSONObject(json) } catch (e: Exception) { return }
        if (d.optString("source") != "applaudiq-embed") return
        when (d.optString("type")) {
            "applaudiq:ready" -> {
                if (config.mode == ApplaudIQEmbed.Mode.AUTO) {
                    val token = config.token
                    if (!token.isNullOrEmpty() && !tokenSent) {
                        tokenSent = true
                        sendToEmbed("applaudiq:init-token", JSONObject().put("token", token))
                    } else if (token.isNullOrEmpty() && !initErrorFired) {
                        // Auto with no token can never sign in — stop the spinner + surface it once.
                        initErrorFired = true
                        sendToEmbed("applaudiq:init-error", JSONObject())
                        config.onError?.invoke("missing_token")
                    }
                } else {
                    fireReady() // manual: the mount handshake is the only ready before /login
                }
            }
            "applaudiq:authenticated" -> fireReady() // auto: the definitive signed-in signal
            "applaudiq:auth-pending" -> config.onAuthPending?.invoke()
            "applaudiq:error" ->
                config.onError?.invoke(d.optJSONObject("payload")?.optString("message").orEmpty().ifEmpty { "error" })
            "applaudiq:close" -> { config.onClose?.invoke(); finish() }
            "applaudiq:signout" -> { config.onSignOut?.invoke(); finish() }
            "applaudiq:resize" -> { /* full-screen Activity: no-op on native */ }
            "applaudiq:sso-request" -> {
                val p = d.optJSONObject("payload")
                val provider = p?.optString("provider").orEmpty().ifEmpty { "google" }
                val clientId = p?.opt("clientId")?.let { if (it == JSONObject.NULL) null else it.toString() }
                val email = p?.optString("email").orEmpty().ifEmpty { null }
                openSSO(provider, clientId, email)
            }
            "applaudiq:open-external" -> {
                // Reward-store downloads / payment / OAuth: open the URL in the system browser.
                val url = d.optJSONObject("payload")?.optString("url").orEmpty()
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                }
            }
        }
    }

    private fun fireReady() {
        if (readyFired) return
        readyFired = true
        config.onReady?.invoke()
    }

    private fun sendToEmbed(type: String, payload: JSONObject) {
        val msg = JSONObject().put("source", "applaudiq-sdk").put("type", type).put("payload", payload)
        webView.evaluateJavascript(
            "window.dispatchEvent(new MessageEvent('message',{data:$msg,origin:location.origin}))",
            null,
        )
    }

    // MARK: SSO via Chrome Custom Tabs (Google/Microsoft block embedded webviews)
    private fun openSSO(provider: String, clientId: String?, email: String?) {
        val url = EmbedInternals.buildSsoUrl(config.baseUrl, provider, clientId, email, ssoCallback)
        ssoInFlight = true
        runCatching {
            val cct = CustomTabsIntent.Builder().build()
            // FLAG_ACTIVITY_NO_HISTORY: Chrome finishes the Custom Tab the moment the SSO redirect
            // (the applaudiq://… callback) navigates away from it, so the browser does NOT linger as a
            // separate card in the Android recents switcher after sign-in — only the app remains.
            // Trade-off: if the user manually leaves the tab mid-login (Home/app-switch, e.g. to grab a
            // 2FA code) the tab is dismissed and they restart sign-in — standard for an auth tab.
            cct.intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
            cct.launchUrl(this, Uri.parse(url))
        }.onFailure { ssoInFlight = false }
    }

    // Redeem the one-time SSO code INSIDE the web view (same-origin fetch) so the session cookies
    // land in the WebView's own cookie store, then reload so the authenticated portal renders.
    private fun completeSSO(code: String) {
        val codeJson = JSONObject.quote(code) // safe JSON-string the code (no script injection)
        val js = """
            (async function(){
              try {
                const r = await fetch('/api/v1/employee/auth/sso/exchange', {
                  method: 'POST', credentials: 'include',
                  headers: { 'Content-Type': 'application/json' },
                  body: JSON.stringify({ code: $codeJson })
                });
                if (!r.ok) throw new Error('sso_exchange_failed');
                window.location.replace('/');
              } catch (e) {
                ApplaudIQAndroid.onSsoError();
              }
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }
}
