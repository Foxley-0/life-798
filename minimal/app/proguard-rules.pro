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

# CameraX 内部通过反射加载实现类
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**
