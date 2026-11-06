package com.water.widget.scan;

/**
 * 与 libwxqr.so 的 JNI 桥。
 *
 * ⚠️ 包名 `com.water.widget` 是**临时**的：现成 so 里编进去的符号是
 * `Java_com_water_widget_scan_WxQr_*`，类必须放在这个包下才能对上。
 * 验证通过后应该改 `_research/wxqr-out/jni/jni.cpp` 里的包名重编成
 * `Java_com_life798_mini_scan_WxQr_*`，再把这个类挪到 com.life798.mini.scan。
 *
 * 全部是 static native —— C++ 侧第二个参数写的是 jclass。
 */
public final class WxQr {

    static {
        System.loadLibrary("wxqr");
    }

    private WxQr() {
    }

    /** 加载四个模型。耗时操作，必须放后台线程。 */
    public static native boolean nativeInit(String detectProto, String detectModel,
                                            String srProto, String srModel);

    /**
     * 解码一帧紧凑 8 位灰度图（长度必须 >= w * h，无行填充）。
     *
     * @param scaleFactor (0,1] 检测前缩放比例；传 1.0 表示原图
     * @return 识别到的文本数组，无结果时为空数组
     */
    public static native String[] nativeDecode(byte[] gray, int w, int h, float scaleFactor);

    public static native void nativeRelease();
}
