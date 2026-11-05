package com.life798.mini.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一台设备在本地的记录。
 *
 * [id] 是唯一标识，**只存不显示** —— 15 位数字没人记得住，界面上永远显示名字。
 *
 * [alias] 是用户自己起的名字，[name] 是服务端返回的名字。
 * 改过名的设备两行显示（别名 + 原名），没改名的只显示一行。
 */
data class DeviceEntry(
    val id: String,
    val name: String = "",
    val alias: String = "",
    val at: Long = 0L,
) {
    /** 第一行：用户起的名字优先，其次服务端名字，最后才退化到编号。 */
    val displayName: String
        get() = alias.ifBlank { name.ifBlank { id } }

    /**
     * 第二行：只有改过名、且服务端名字存在时才显示，用来跟别名对照。
     *
     * 没改名时返回 null —— 界面就只占一行，名字横向空间更宽裕，
     * 不会出现「名字被挤得显示不全」。
     */
    val subName: String?
        get() = name.takeIf { alias.isNotBlank() && it.isNotBlank() }
}

/**
 * 设备记录存储：798 常用设备 + 最近 7 天用过的设备。
 *
 * 为什么用 SharedPreferences 而不是 DataStore：
 * 跟 [UiPrefs] 一个理由 —— 同步读，浮层第一帧就有数据。
 * DataStore 是异步 Flow，会让列表先闪一下空白再填上。
 * 数据量小（常用 ≤20、流水 ≤200），JSON 字符串完全够。
 *
 * **按账号隔离**：key 是 `favorites_<owner>` / `visits_<owner>`，owner 是当前手机号。
 * 跟 [BillStore] 各管各的 —— 两边只是 owner 来源相同，不共享任何代码，
 * 以后重做扫描页也不会碰到账本。
 * [owner] 为空（未登录 / 刚登出）时整个仓库短路，不读也不写。
 */
class DevicePrefs(context: Context, private val owner: String) {

    private val sp = context.applicationContext
        .getSharedPreferences("mini_device", Context.MODE_PRIVATE)

    private val keyFavorites = "favorites_$owner"
    private val keyVisits = "visits_$owner"

    /** 没登录就没有设备记录 —— 防止登出后还看到上一个账号的常用设备。 */
    private val enabled: Boolean get() = owner.isNotBlank()

    /** 常用设备，按最近使用时间倒序。 */
    fun favorites(): List<DeviceEntry> =
        read(keyFavorites).sortedByDescending { it.at }

    /** 最近 7 天用过的设备，按时间倒序。过期的不返回，也不需要定时清理。 */
    fun recentVisits(): List<DeviceEntry> {
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        return read(keyVisits).filter { it.at >= cutoff }.sortedByDescending { it.at }
    }

    /**
     * 扫码成功后记一笔。
     *
     * **刻意不去重**：同一台设备扫几次就记几条，列表按时间倒序就是一条
     * 使用流水。这里要回答的是「最近 7 天什么时候用过哪台设备」，
     * 不是「我有几台设备」—— 去重会把时间线压平，反而看不出使用频次。
     *
     * [name] 允许为空 —— 设备名是异步请求回来的，那会儿还没有。
     * 等 [updateName] 拿到名字会补上。
     */
    fun recordVisit(id: String, name: String) {
        if (id.isBlank()) return
        val list = read(keyVisits).toMutableList()
        list += DeviceEntry(id = id, name = name, at = System.currentTimeMillis())
        write(keyVisits, trim(list, MAX_VISITS))
    }

    /** 设备名异步回来之后，把两个列表里同一台设备的名字都补上。 */
    fun updateName(id: String, name: String) {
        if (id.isBlank() || name.isBlank()) return
        for (key in listOf(keyVisits, keyFavorites)) {
            val list = read(key)
            if (list.none { it.id == id && it.name != name }) continue
            write(key, list.map { if (it.id == id) it.copy(name = name) else it })
        }
    }

    /** 从最近记录里挑一台加入常用。名字从记录里带过来，不重复请求。 */
    fun addFavorite(id: String) {
        if (id.isBlank()) return
        val favs = read(keyFavorites)
        if (favs.any { it.id == id }) return
        // 流水里同一台设备可能有多条，取最近那条的名字
        val name = read(keyVisits)
            .filter { it.id == id }
            .maxByOrNull { it.at }
            ?.name
            .orEmpty()
        val entry = DeviceEntry(id = id, name = name, at = System.currentTimeMillis())
        write(keyFavorites, trim(favs + entry, MAX_FAVORITES))
    }

    /** 改名。传空字符串等于恢复成服务端原名。 */
    fun renameFavorite(id: String, alias: String) {
        val list = read(keyFavorites)
        if (list.none { it.id == id }) return
        val trimmed = alias.trim()
        write(keyFavorites, list.map { if (it.id == id) it.copy(alias = trimmed) else it })
    }

    fun removeFavorite(id: String) {
        val list = read(keyFavorites)
        if (list.none { it.id == id }) return
        write(keyFavorites, list.filterNot { it.id == id })
    }

    // ---------------- 内部 ----------------

    private fun read(key: String): List<DeviceEntry> {
        if (!enabled) return emptyList()
        val raw = sp.getString(key, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id")
                    if (id.isBlank()) continue
                    add(
                        DeviceEntry(
                            id = id,
                            name = o.optString("name"),
                            alias = o.optString("alias"),
                            at = o.optLong("at"),
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun write(key: String, list: List<DeviceEntry>) {
        if (!enabled) return
        val arr = JSONArray()
        for (e in list) {
            arr.put(
                JSONObject()
                    .put("id", e.id)
                    .put("name", e.name)
                    .put("alias", e.alias)
                    .put("at", e.at)
            )
        }
        sp.edit().putString(key, arr.toString()).apply()
    }

    /** 超上限时丢最久没用的。 */
    private fun trim(list: List<DeviceEntry>, max: Int): List<DeviceEntry> =
        if (list.size <= max) list else list.sortedByDescending { it.at }.take(max)

    companion object {
        private const val MAX_FAVORITES = 20
        // 不去重之后条数会涨，7 天流水给到 200 条足够（一条约 80 字节）
        private const val MAX_VISITS = 200
        private const val RETENTION_MS = 7L * 24 * 60 * 60 * 1000

        /**
         * 删掉「按账号隔离」之前的单份记录。
         *
         * 那时候所有账号共用 `favorites` / `visits` 两个 key，数据已经分不清是谁的，
         * 启动时清一次，之后永远走 `favorites_<owner>` / `visits_<owner>`。
         */
        fun clearLegacy(context: Context) {
            context.applicationContext
                .getSharedPreferences("mini_device", Context.MODE_PRIVATE)
                .edit()
                .remove("favorites")
                .remove("visits")
                .apply()
        }
    }
}

