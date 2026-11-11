package com.life798.mini.ui.devicecard

import com.life798.mini.core.Platform

/**
 * 设备类型。**只决定图标**，不决定卡片长什么样、不决定去哪一页。
 *
 * 这个枚举故意做得极薄：加一种新设备（比如吹风机）只需要在这里补一项、
 * 在 [DeviceIcons] 里补一个图标，卡片本体一行都不用改。
 */
enum class DeviceKind {
    /** 饮水机 / 直饮水。 */
    DRINK,

    /** 淋浴 / 热水龙头。 */
    SHOWER,

    /** 洗衣机。 */
    WASHER,
}

/**
 * 卡片唯一的数据来源。
 *
 * 三处（正在使用 / 最近 7 天 / 常用设备）拿到的都是它，卡片只认这一份数据，
 * 不认识「这是 798 还是胖乖」「这是从哪个 key 读出来的」。
 *
 * [at] 不参与显示，只用于跨平台混排时排序 —— 最近 7 天和常用设备都要按它倒序，
 * 混排的前提是两边的时间能放在一起比。
 *
 * [startedAt] 非空表示这台设备正在用，副行改成计时，由卡片自己每秒推进。
 */
data class DeviceCardItem(
    val platform: Platform,
    val deviceId: String,
    val kind: DeviceKind,
    /** 已经带平台前缀的显示名，例如 `798-雅三饮水机`。 */
    val title: String,
    /** 副行。正在使用时会被计时覆盖。 */
    val subtitle: String? = null,
    /** 右侧小标签，目前只有常用设备的「在用」。 */
    val badge: String? = null,
    /** 正在使用的起始时刻，用于计时。 */
    val startedAt: Long? = null,
    /** 排序用时间戳。 */
    val at: Long = 0L,
) {
    /** 跨平台去重键：两个平台的设备编号可能撞车，必须带平台。 */
    val key: String get() = "${platform.id}:$deviceId"

    val running: Boolean get() = startedAt != null
}
