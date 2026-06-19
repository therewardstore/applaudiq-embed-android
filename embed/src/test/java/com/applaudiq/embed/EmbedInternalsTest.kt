package com.applaudiq.embed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM unit tests for the embed SDK logic (no Android runtime / emulator needed). */
class EmbedInternalsTest {

    private val portal = "https://recognize.applaudiq.com"

    // ---- buildEmbedUrl ----
    @Test fun embedUrl_auto_withKey() {
        assertEquals(
            "https://recognize.applaudiq.com/embed?mode=auto&k=pk_live_x",
            EmbedInternals.buildEmbedUrl(portal, "auto", "pk_live_x"),
        )
    }

    @Test fun embedUrl_manual() {
        assertEquals(
            "https://recognize.applaudiq.com/embed?mode=manual&k=pk_live_x",
            EmbedInternals.buildEmbedUrl(portal, "manual", "pk_live_x"),
        )
    }

    @Test fun embedUrl_unknownMode_fallsBackToAuto() {
        assertTrue(EmbedInternals.buildEmbedUrl(portal, "bogus", "pk_live_x").contains("mode=auto"))
    }

    @Test fun embedUrl_encodesReservedChars() {
        val url = EmbedInternals.buildEmbedUrl(portal, "auto", "pk_live_a/b+c d")
        assertTrue(url.contains("k=pk_live_a%2Fb%2Bc+d") || url.contains("k=pk_live_a%2Fb%2Bc%20d"))
    }

    @Test fun embedUrl_trimsTrailingSlash() {
        assertEquals(
            "https://recognize.applaudiq.com/embed?mode=auto&k=pk_live_x",
            EmbedInternals.buildEmbedUrl("https://recognize.applaudiq.com/", "auto", "pk_live_x"),
        )
    }

    @Test fun embedUrl_tokenOnlyInAuto() {
        assertTrue(EmbedInternals.buildEmbedUrl(portal, "auto", "pk_live_x", "tok123").contains("&token=tok123"))
        assertFalse(EmbedInternals.buildEmbedUrl(portal, "manual", "pk_live_x", "tok123").contains("token"))
    }

    // ---- buildSsoUrl ----
    @Test fun ssoUrl_native1_withClientAndHint() {
        assertEquals(
            "https://recognize.applaudiq.com/api/v1/auth/sso/google/employee/authorize?native=1&client_id=100001&login_hint=a%40b.com",
            EmbedInternals.buildSsoUrl(portal, "google", "100001", "a@b.com"),
        )
    }

    @Test fun ssoUrl_unknownProvider_fallsBackToGoogle() {
        assertTrue(EmbedInternals.buildSsoUrl(portal, "evilcorp", null, null).contains("/sso/google/"))
    }

    @Test fun ssoUrl_microsoftAllowed() {
        assertTrue(EmbedInternals.buildSsoUrl(portal, "microsoft", null, null).contains("/sso/microsoft/"))
    }

    @Test fun ssoUrl_omitsNullClientAndEmail() {
        val url = EmbedInternals.buildSsoUrl(portal, "google", null, null)
        assertFalse(url.contains("client_id"))
        assertFalse(url.contains("login_hint"))
        assertTrue(url.endsWith("native=1"))
    }

    @Test fun ssoUrl_treatsStringNullClientAsAbsent() {
        assertFalse(EmbedInternals.buildSsoUrl(portal, "google", "null", null).contains("client_id"))
    }

    @Test fun ssoUrl_appendsNativeRedirect_encoded() {
        val url = EmbedInternals.buildSsoUrl(portal, "google", "100001", "a@b.com", "aiqexample://sso-callback")
        assertTrue(url.contains("&native_redirect=aiqexample%3A%2F%2Fsso-callback"))
    }

    @Test fun ssoUrl_omitsNativeRedirectWhenNull() {
        assertFalse(EmbedInternals.buildSsoUrl(portal, "google", "100001", "a@b.com").contains("native_redirect"))
    }

    // ---- bridgeInitScript ----
    @Test fun bridge_setsNativeFlagAndMode_auto() {
        val s = EmbedInternals.bridgeInitScript("auto")
        assertTrue(s.contains("""__APPLAUDIQ_EMBED__ = { mode: "auto", native: true }"""))
        assertTrue(s.contains("ReactNativeWebView"))
        assertTrue(s.contains("ApplaudIQAndroid.onMessage"))
    }

    @Test fun bridge_mode_manual() {
        assertTrue(EmbedInternals.bridgeInitScript("manual").contains(""""manual""""))
    }

    @Test fun bridge_mode_allowlisted_noInjection() {
        // A crafted mode can't break out of the JS string literal.
        assertTrue(EmbedInternals.bridgeInitScript("\"};alert(1);//").contains(""""auto""""))
    }

    // ---- sameOrigin / originOf ----
    @Test fun sameOrigin_portalPaths_true() {
        assertTrue(EmbedInternals.sameOrigin("$portal/embed?mode=auto", portal))
        assertTrue(EmbedInternals.sameOrigin("$portal/login", portal))
        assertTrue(EmbedInternals.sameOrigin("$portal/", portal))
    }

    @Test fun sameOrigin_evilHost_false() {
        assertFalse(EmbedInternals.sameOrigin("https://evil.com/embed", portal))
        assertFalse(EmbedInternals.sameOrigin("https://recognize.applaudiq.com.evil.com/x", portal))
    }

    @Test fun sameOrigin_schemeMismatch_false() {
        assertFalse(EmbedInternals.sameOrigin("http://recognize.applaudiq.com/x", portal))
    }

    @Test fun sameOrigin_nullOrBlank_false() {
        assertFalse(EmbedInternals.sameOrigin(null, portal))
        assertFalse(EmbedInternals.sameOrigin("", portal))
    }

    @Test fun sameOrigin_localhostPort() {
        assertTrue(EmbedInternals.sameOrigin("http://10.0.2.2:3017/login", "http://10.0.2.2:3017"))
        assertFalse(EmbedInternals.sameOrigin("http://10.0.2.2:3018/login", "http://10.0.2.2:3017"))
    }

    // ---- isSecureBaseUrl ----
    @Test fun secure_https_true() {
        assertTrue(EmbedInternals.isSecureBaseUrl(portal, allowCleartextLocalhost = false))
    }

    @Test fun secure_httpRejected_inProd() {
        assertFalse(EmbedInternals.isSecureBaseUrl("http://recognize.applaudiq.com", allowCleartextLocalhost = false))
        assertFalse(EmbedInternals.isSecureBaseUrl("http://10.0.2.2:3017", allowCleartextLocalhost = false))
    }

    @Test fun secure_httpLocalhost_allowedInDebug() {
        assertTrue(EmbedInternals.isSecureBaseUrl("http://localhost:3017", allowCleartextLocalhost = true))
        assertTrue(EmbedInternals.isSecureBaseUrl("http://10.0.2.2:3017", allowCleartextLocalhost = true))
        assertFalse(EmbedInternals.isSecureBaseUrl("http://example.com", allowCleartextLocalhost = true))
    }

    // ---- parseSsoCode ----
    @Test fun ssoCode_happy() {
        assertEquals("abc123", EmbedInternals.parseSsoCode("applaudiq://sso-callback?code=abc123"))
    }

    @Test fun ssoCode_wrongHost_null() {
        assertNull(EmbedInternals.parseSsoCode("applaudiq://other?code=abc"))
    }

    @Test fun ssoCode_wrongScheme_null() {
        assertNull(EmbedInternals.parseSsoCode("https://x/sso-callback?code=abc"))
    }

    @Test fun ssoCode_missingCode_null() {
        assertNull(EmbedInternals.parseSsoCode("applaudiq://sso-callback?state=xyz"))
        assertNull(EmbedInternals.parseSsoCode("applaudiq://sso-callback?code="))
    }

    @Test fun ssoCode_nullInput_null() {
        assertNull(EmbedInternals.parseSsoCode(null))
        assertNull(EmbedInternals.parseSsoCode(""))
    }

    // ---- parseSsoError / isSsoCallback (failure path) ----
    @Test fun ssoError_happy_decoded() {
        assertEquals(
            "This login was started for a@b.com.",
            EmbedInternals.parseSsoError("applaudiq://sso-callback?error=This%20login%20was%20started%20for%20a%40b.com."),
        )
    }

    @Test fun ssoError_customScheme() {
        assertEquals(
            "nope",
            EmbedInternals.parseSsoError("aiqexample://sso-callback?error=nope", "aiqexample", "sso-callback"),
        )
    }

    @Test fun ssoError_absentOnSuccessCallback_null() {
        assertNull(EmbedInternals.parseSsoError("applaudiq://sso-callback?code=abc123"))
    }

    @Test fun ssoError_wrongHostOrScheme_null() {
        assertNull(EmbedInternals.parseSsoError("applaudiq://other?error=x"))
        assertNull(EmbedInternals.parseSsoError("https://x/sso-callback?error=x"))
    }

    @Test fun isSsoCallback_matchesSchemeHost() {
        assertTrue(EmbedInternals.isSsoCallback("applaudiq://sso-callback?error=x"))
        assertTrue(EmbedInternals.isSsoCallback("applaudiq://sso-callback?code=abc"))
        assertTrue(EmbedInternals.isSsoCallback("aiqexample://sso-callback", "aiqexample", "sso-callback"))
    }

    @Test fun isSsoCallback_rejectsOthers() {
        assertFalse(EmbedInternals.isSsoCallback("applaudiq://other?code=abc"))
        assertFalse(EmbedInternals.isSsoCallback("https://recognize.applaudiq.com/x"))
        assertFalse(EmbedInternals.isSsoCallback(null))
        assertFalse(EmbedInternals.isSsoCallback(""))
    }
}
