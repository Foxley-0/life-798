# 保留 OkHttp 与 kotlinx 序列化所需的基本规则
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class com.life798.mini.net.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
