package com.applaudiq.embed

import java.net.URI
import java.net.URLEncoder

/**
 * Pure (Android-free) logic for the embed SDK so it can be unit-tested on the plain JVM
 * without Robolectric or an emulator. The Activity wires these helpers to the WebView /
 * Custom Tabs / Intent APIs. Mirrors the iOS SDK's URL building, origin confinement,
 * bridge-injection script, and message dispatch.
 */
internal object EmbedInternals {

    /** Whitelisted SSO providers — never interpolate an arbitrary, embed-supplied value into the authorize path. */
    val SSO_PROVIDERS = setOf("google", "microsoft")

    private fun enc(v: String): String = URLEncoder.encode(v, "UTF-8")

    private fun normalizeMode(mode: String): String = if (mode == "manual") "manual" else "auto"

    /** `<baseUrl>/embed?mode={auto|manual}&k={url-encoded key}` (+ optional `&token=` in auto). */
    fun buildEmbedUrl(baseUrl: String, mode: String, key: String, token: String? = null): String {
        val base = baseUrl.trimEnd('/')
        val m = normalizeMode(mode)
        val sb = StringBuilder("$base/embed?mode=$m")
        if (key.isNotEmpty()) sb.append("&k=").append(enc(key))
        if (m == "auto" && !token.isNullOrEmpty()) sb.append("&token=").append(enc(token))
        return sb.toString()
    }

    /**
     * `<baseUrl>/api/v1/auth/sso/{provider}/employee/authorize?native=1[&client_id=][&login_hint=][&native_redirect=]`.
     * `native=1` makes the backend hand the session back as a one-time code on the app's callback deep link
     * (instead of a web cookie redirect). `native_redirect` tells the backend WHICH scheme to hand the code to —
     * each app registers its own (default `applaudiq://sso-callback`) so two Applaud IQ apps on one device don't
     * collide on the callback. Provider is allowlisted.
     */
    fun buildSsoUrl(
        baseUrl: String,
        provider: String,
        clientId: String?,
        email: String?,
        nativeRedirect: String? = null,
    ): String {
        val base = baseUrl.trimEnd('/')
        val p = provider.lowercase().let { if (it in SSO_PROVIDERS) it else "google" }
        val sb = StringBuilder("$base/api/v1/auth/sso/$p/employee/authorize?native=1")
        if (!clientId.isNullOrEmpty() && clientId != "null") sb.append("&client_id=").append(enc(clientId))
        if (!email.isNullOrEmpty()) sb.append("&login_hint=").append(enc(email))
        if (!nativeRedirect.isNullOrEmpty()) sb.append("&native_redirect=").append(enc(nativeRedirect))
        return sb.toString()
    }

    /**
     * documentStart bridge: exposes `window.ReactNativeWebView.postMessage` (the native path the
     * portal's postToHost() uses) forwarding to the `ApplaudIQAndroid` interface, and
     * `window.__APPLAUDIQ_EMBED__ = { mode, native: true }` so the portal's isNativeEmbed() detects
     * an auto/manual native embed on EVERY main-frame load (survives /embed → /login → /). `mode`
     * comes from the {auto,manual} allowlist, never raw config, so it can't break out of the string.
     */
    fun bridgeInitScript(mode: String): String {
        val m = normalizeMode(mode)
        return """
            (function(){
              window.ReactNativeWebView = window.ReactNativeWebView || {
                postMessage: function(s){ try { ApplaudIQAndroid.onMessage(s); } catch(e){} }
              };
              window.__APPLAUDIQ_EMBED__ = { mode: "$m", native: true };
            })();
        """.trimIndent()
    }

    /** The portal origin (`scheme://host[:port]`) we may load and stay on. Null on a malformed base. */
    fun originOf(url: String): String? {
        return try {
            val u = URI(url)
            val host = u.host ?: return null
            val scheme = u.scheme ?: return null
            val port = if (u.port != -1) ":${u.port}" else ""
            "$scheme://$host$port"
        } catch (e: Exception) {
            null
        }
    }

    /** True when `url` is the same origin as `baseUrl` (the trusted portal). */
    fun sameOrigin(url: String?, baseUrl: String): Boolean {
        if (url.isNullOrEmpty()) return false
        val a = originOf(url) ?: return false
        val b = originOf(baseUrl) ?: return false
        return a.equals(b, ignoreCase = true)
    }

    /**
     * HTTPS is required so the one-time token + session cookies never travel cleartext.
     * Plain http is tolerated only for a localhost dev portal when [allowCleartextLocalhost] is true
     * (the Activity passes BuildConfig.DEBUG).
     */
    fun isSecureBaseUrl(baseUrl: String, allowCleartextLocalhost: Boolean): Boolean {
        val u = try { URI(baseUrl) } catch (e: Exception) { return false }
        val host = u.host ?: return false
        if (u.scheme == "https") return true
        if (allowCleartextLocalhost && u.scheme == "http" && (host == "localhost" || host == "127.0.0.1" || host == "10.0.2.2")) {
            return true
        }
        return false
    }

    /**
     * Extract the one-time `code` from the app's `<scheme>://<host>?code=…` callback deep link; null otherwise.
     * The scheme/host are the app's configured SSO callback (default `applaudiq`/`sso-callback`) so the parser
     * matches whatever the app registered + sent as `native_redirect`.
     */
    fun parseSsoCode(uriString: String?, scheme: String = "applaudiq", host: String = "sso-callback"): String? =
        ssoCallbackParam(uriString, scheme, host, "code")

    /**
     * Extract the `error` message from the app's SSO callback deep link (the backend sends
     * `<scheme>://<host>?error=…` on a failed/identity-mismatch sign-in); null if absent.
     */
    fun parseSsoError(uriString: String?, scheme: String = "applaudiq", host: String = "sso-callback"): String? =
        ssoCallbackParam(uriString, scheme, host, "error")

    /** True when the deep link is THIS app's SSO callback (scheme + host match), regardless of query. */
    fun isSsoCallback(uriString: String?, scheme: String = "applaudiq", host: String = "sso-callback"): Boolean {
        if (uriString.isNullOrEmpty()) return false
        val u = try { URI(uriString) } catch (e: Exception) { return false }
        return u.scheme == scheme && u.host == host
    }

    /** Shared parser: pull a single decoded query param from the app's SSO callback deep link; null otherwise. */
    private fun ssoCallbackParam(uriString: String?, scheme: String, host: String, key: String): String? {
        if (uriString.isNullOrEmpty()) return null
        val u = try { URI(uriString) } catch (e: Exception) { return null }
        if (u.scheme != scheme || u.host != host) return null
        val query = u.query ?: return null
        for (pair in query.split("&")) {
            val idx = pair.indexOf('=')
            if (idx > 0 && pair.substring(0, idx) == key) {
                val raw = pair.substring(idx + 1)
                if (raw.isEmpty()) return null
                return try { java.net.URLDecoder.decode(raw, "UTF-8") } catch (e: Exception) { raw }
            }
        }
        return null
    }
}
