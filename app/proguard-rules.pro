# Keep Retrofit / OkHttp / Gson model classes if minify is enabled later.
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.behi.zoha.drive.** { *; }
-dontwarn okhttp3.**
-dontwarn retrofit2.**
