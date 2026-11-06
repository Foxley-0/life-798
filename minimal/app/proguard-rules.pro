# OkHttp / kotlinx 基本规则
-dontwarn okhttp3.**
-dontwarn okio.**
-keepattributes Signature
-keepattributes *Annotation*

# 本项目网络与模型层（JSON 字段靠反射匹配，需整体保留）
-keep class com.life798.mini.net.** { *; }
-keep class com.life798.mini.core.** { *; }

# ── 微信二维码引擎（libwxqr.so）───────────────────────────────
# JNI 方法按符号名绑定（Java_com_water_widget_scan_WxQr_nativeInit 等），
# 类名或方法名被 R8 改掉就是 UnsatisfiedLinkError，且只在 release 包出现。
-keep class com.water.widget.scan.WxQr { *; }

# ── CameraX ────────────────────────────────────────────────
# 相机绑定/解绑走生命周期回调与反射，保留避免 release 下偶发 NoSuchMethodError
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# ── 注意 ──────────────────────────────────────────────────
# ML Kit 那一整套 keep 规则（com.google.mlkit.** 及 gms.internal.mlkit_*）
# 随 1.5.5 换成微信引擎一并删掉了，留着只会误导排查。
