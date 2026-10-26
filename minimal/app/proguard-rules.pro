# OkHttp / kotlinx 基本规则
-dontwarn okhttp3.**
-dontwarn okio.**
-keepattributes Signature
-keepattributes *Annotation*

# 本项目网络与模型层（JSON 字段靠反射匹配，需整体保留）
-keep class com.life798.mini.net.** { *; }
-keep class com.life798.mini.core.** { *; }

# ZXing：解码器通过工厂 + 反射构造，R8 会误判为无用代码整包裁掉。
# 不保留的话 release 包扫码会直接 NoClassDefFoundError。
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# zxing-android-embedded：CaptureActivity 由 manifest 引用，
# 内部还会通过反射构造 View 与解码器，整包保留最稳妥。
-keep class com.journeyapps.** { *; }
-dontwarn com.journeyapps.**
