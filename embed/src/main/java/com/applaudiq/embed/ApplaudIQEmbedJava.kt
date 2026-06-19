package com.applaudiq.embed

import android.content.Context

/**
 * Java-friendly facade over the Kotlin [ApplaudIQEmbed] API. The Kotlin `Config` uses function-type
 * callbacks which are awkward from Java, so this exposes the same capability with a SAM-style
 * [Listener] (every method has a default no-op, so Java callers override only what they need):
 *
 *   AIQEmbed.open(context, "pk_live_xxx", null, AIQEmbed.Mode.AUTO, embedToken, new AIQEmbed.Listener() {
 *       @Override public void onReady() { /* signed in */ }
 *       @Override public void onError(String message) { /* failed */ }
 *   });
 *
 * Kotlin callers should keep using [ApplaudIQEmbed.open] with the `Config` lambdas directly.
 */
object AIQEmbed {

    enum class Mode { AUTO, MANUAL }

    interface Listener {
        fun onReady() {}
        fun onAuthPending() {}
        fun onError(message: String) {}
        fun onClose() {}
        fun onSignOut() {}
    }

    /** Present the embed for Java callers. [baseUrl] may be null to use the production portal. */
    @JvmStatic
    @JvmOverloads
    fun open(
        context: Context,
        key: String,
        baseUrl: String? = null,
        mode: Mode = Mode.AUTO,
        token: String? = null,
        listener: Listener? = null,
    ) {
        val config = ApplaudIQEmbed.Config(
            key = key,
            token = token,
            mode = if (mode == Mode.MANUAL) ApplaudIQEmbed.Mode.MANUAL else ApplaudIQEmbed.Mode.AUTO,
            baseUrl = baseUrl ?: "https://recognize.applaudiq.com",
            onReady = listener?.let { l -> { l.onReady() } },
            onAuthPending = listener?.let { l -> { l.onAuthPending() } },
            onError = listener?.let { l -> { msg -> l.onError(msg) } },
            onClose = listener?.let { l -> { l.onClose() } },
            onSignOut = listener?.let { l -> { l.onSignOut() } },
        )
        ApplaudIQEmbed.open(context, config)
    }
}
