# Keep the public SDK API + the JS bridge entry points (called by the WebView via @JavascriptInterface).
-keep class com.applaudiq.embed.ApplaudIQEmbed { *; }
-keep class com.applaudiq.embed.ApplaudIQEmbed$* { *; }
-keep class com.applaudiq.embed.AIQEmbed { *; }
-keep class com.applaudiq.embed.AIQEmbed$* { *; }
-keepclassmembers class com.applaudiq.embed.ApplaudIQEmbedActivity$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}
