# Keep okhttp / JSON reflection-free code paths intact.
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class com.icloudalbum.app.data.** { *; }
