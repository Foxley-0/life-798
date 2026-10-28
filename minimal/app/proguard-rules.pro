# OkHttp / kotlinx 基本规则
-dontwarn okhttp3.**
-dontwarn okio.**
-keepattributes Signature
-keepattributes *Annotation*

# 本项目网络与模型层（JSON 字段靠反射匹配，需整体保留）
-keep class com.life798.mini.net.** { *; }
-keep class com.life798.mini.core.** { *; }

# ── ML Kit 条码识别（bundled 版）─────────────────────────────
# 崩溃实证（release 包登录后 NPE，用 mapping.txt 反混淆得出）：
#   com.google.android.gms.internal.mlkit_vision_common.zzih
#   读取 com.google.mlkit.vision.barcode.internal.zzg.a 时为空
#
# 这些 internal 类由 native 层和反射访问，R8 看不到引用就会裁字段/改名，
# 表现为「debug 正常、release 一点就崩」。必须整包保留。
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_common.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_barcode.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_barcode_bundled.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.internal.mlkit_**

# ── CameraX ────────────────────────────────────────────────
# 相机绑定/解绑走生命周期回调与反射，保留避免 release 下偶发 NoSuchMethodError
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# ── 注意 ──────────────────────────────────────────────────
# 原来这里有一整套 ZXing / zxing-android-embedded 的 keep 规则，
# 但 v1.1.0 已经把扫码换成 CameraX + ML Kit，那些规则既无用又会误导排查。
