package com.life798.mini.ui.devicecard

import com.life798.mini.core.DeviceEntry
import com.life798.mini.core.DevicePrefs
import com.life798.mini.core.DeviceSession
import com.life798.mini.core.Platform
import com.life798.mini.core.laundry.LaundrySession
import com.life798.mini.ui.water.PgWaterState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把三处「设备来源」翻译成 [DeviceCardItem]。
 *
 * 这里是整个卡片系统唯一知道「有 798、有胖乖」的地方；卡片本体和图标都不需要知道。
 *
 * **存储完全没动**：仍然读 `visits_<手机号>` / `pangguai-visits_<手机号>` 那套老 key，
 * 没有新增字段、没有合并 key。平台归属由「这条记录是从哪个 [DevicePrefs] 读出来的」决定，
 * 类型则在读取时按 id 形状 + 名字关键词当场推导 —— 所以老数据一条都不用迁移。
 *
 * 唯一需要接受的代价：设备名字还没回来（异步请求中）时，类型只能保守判成淋浴；
 * 等 `updateName` 把名字补上，下一次读取会自动变回正确图标。因为推导不落盘，错了也不会留下痕迹。
 */
class DeviceSource(
    private val hui798: DevicePrefs,
    private val pangguai: DevicePrefs,
    /**
     * 胖乖设备分类查询：`(编号, 名字) -> categoryCode`。
     *
     * 流水里只存了编号和名字，**没有类型**；而名字经常是 `静4-4热`、`40402` 这种
     * 看不出是饮水还是淋浴的串，靠关键词猜一定会错（实测：`静4-4热` 其实是
     * `category=04` 的饮水机，却因为名字里没有「饮水」二字被判成淋浴）。
     *
     * 真正的分类存在 `pangguai-water-devices` 里（扫码时服务端就返回了），
     * 所以这里注入一个查询函数，按编号去查服务端给的分类 —— 存储格式一个字节都没动。
     */
    private val pgCategoryOf: (id: String, name: String) -> String? = { _, _ -> null },
) {

    /**
     * 最近 7 天。
     *
     * **两个平台合并成一个列表，按时间倒序混排** —— 不分组、不加标题、不排序两次。
     * 这是「一视同仁」的硬指标：用户看到的应该是一条连续的使用流水。
     */
    fun recent(): List<DeviceCardItem> =
        (hui798.recentVisits().toCards(Platform.HUI798) { visitTime(it.at) } +
            pangguai.recentVisits().toCards(Platform.PANGGUAI) { visitTime(it.at) })
            .sortedByDescending { it.at }

    /** 常用设备。同样合并、同样混排，同样不分组。副行只在改过名时才有（显示服务端原名）。 */
    fun favorites(): List<DeviceCardItem> =
        (hui798.favorites().toCards(Platform.HUI798) { it.subName } +
            pangguai.favorites().toCards(Platform.PANGGUAI) { it.subName })
            .sortedByDescending { it.at }

    /**
     * 正在使用。
     *
     * 三个来源并列，谁在用就出谁，顺序按开始时间升序（先开的排前面）。
     * 洗衣允许同时洗多台，所以这里是一个真正的列表，不是「最多一条」。
     */
    fun active(
        hui798Session: DeviceSession?,
        pgWater: PgWaterState?,
        pgLaundry: List<LaundrySession>,
    ): List<DeviceCardItem> = buildList {
        hui798Session?.let { s ->
            add(
                DeviceCardItem(
                    platform = Platform.HUI798,
                    deviceId = s.deviceId,
                    kind = inferKind(Platform.HUI798, s.deviceId, s.deviceName.orEmpty()),
                    title = title(Platform.HUI798, s.deviceName.orEmpty(), s.deviceId),
                    startedAt = s.startedAt,
                    at = s.startedAt,
                )
            )
        }

        // 胖乖水：只有真正出水/正在启动时才进卡片。扫完还没点启动（pending）不算「使用中」，
        // 那种状态由扫码页自己的确认卡负责，不要在这里冒充一张「正在使用」。
        pgWater?.device?.takeIf { pgWater.running }?.let { d ->
            add(
                DeviceCardItem(
                    platform = Platform.PANGGUAI,
                    deviceId = d.deviceId.ifBlank { d.sn },
                    kind = kindOfCategory(d.categoryCode),
                    title = title(Platform.PANGGUAI, d.name, d.deviceId.ifBlank { d.sn }),
                    startedAt = pgWater.startedAt,
                    at = pgWater.startedAt ?: 0L,
                )
            )
        }

        pgLaundry.filter { it.running }.forEach { s ->
            add(
                DeviceCardItem(
                    platform = Platform.PANGGUAI,
                    // 点卡片要能重放扫码，所以优先用 NQT，而不是服务端 goodsId
                    deviceId = s.nqt.ifBlank { s.deviceId },
                    kind = DeviceKind.WASHER,
                    title = title(Platform.PANGGUAI, s.deviceName, s.deviceId),
                    // 洗衣机没有「已用时长」的通用口径，用剩余时间更有意义
                    startedAt = s.startedAtMillis,
                    at = s.startedAtMillis,
                )
            )
        }
    }.sortedBy { it.startedAt ?: 0L }

    // ------------------------------------------------------------------

    private fun List<DeviceEntry>.toCards(
        platform: Platform,
        subtitleOf: (DeviceEntry) -> String?,
    ): List<DeviceCardItem> = map { e ->
        DeviceCardItem(
            platform = platform,
            deviceId = e.id,
            kind = inferKind(platform, e.id, e.name),
            title = title(platform, e.displayName, e.id),
            subtitle = subtitleOf(e),
            at = e.at,
        )
    }

    /**
     * 类型推导：**服务端分类 → id 形状 → 名字关键词**，顺序不能反。
     *
     * 1. 胖乖水设备表里有 `categoryCode` 就以它为准（`04` 饮水 / `08` 淋浴）——
     *    这是唯一来自服务端的权威字段，名字猜不出来的设备全靠它
     * 2. 胖乖 + UUID 形状 = 洗衣机的 NQT 码，这是三种设备里唯一能靠 id 形态确定的
     * 3. 名字含「洗衣」= 洗衣机（兜住 NQT 不规范的情况）
     * 4. 名字含「饮水 / 直饮」= 饮水机
     * 5. 其余一律按淋浴 —— 798 账号目前全部是「水表/淋浴器设备…热水龙头」，
     *    这是覆盖最多的一类，猜错的代价只是图标不对，不影响任何功能
     */
    private fun inferKind(platform: Platform, id: String, name: String): DeviceKind {
        if (platform == Platform.PANGGUAI) {
            pgCategoryOf(id, name)?.let { return kindOfCategory(it) }
            if (NQT.matches(id)) return DeviceKind.WASHER
        }
        return when {
            name.contains("洗衣") -> DeviceKind.WASHER
            name.contains("饮水") || name.contains("直饮") -> DeviceKind.DRINK
            else -> DeviceKind.SHOWER
        }
    }

    /** 胖乖水的 `categoryCode`：`04` 饮水机、`08` 淋浴。这是抓包里确定的字段。 */
    private fun kindOfCategory(code: String): DeviceKind =
        if (code == "04") DeviceKind.DRINK else DeviceKind.SHOWER

    /**
     * 名字前面加平台短前缀：`798-雅三饮水机` / `胖乖-3号楼洗衣机01`。
     *
     * 前缀只解决「这是哪个平台的设备」，**不用于分组**。已经带前缀的不重复加，
     * 避免用户自己把设备改名成 `798-xxx` 之后变成 `798-798-xxx`。
     */
    private fun title(platform: Platform, name: String, id: String): String {
        val short = if (platform == Platform.PANGGUAI) "胖乖" else "798"
        val base = name.ifBlank { id }
        return if (base.startsWith("$short-")) base else "$short-$base"
    }

    private companion object {
        /** 胖乖洗衣机的 NQT 是标准 UUID。 */
        val NQT = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    }
}

/** 最近 7 天用的时间格式：`11-11 21:03`。 */
internal fun visitTime(at: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(at))
