package com.life798.mini.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * 一本待导出 / 待导入的账。
 *
 * [phone] 是这本账在导出时所属的手机号。导入前必须拿它跟当前登录账号比对 ——
 * 这是「别把 A 的用水量导进 B 的账本」唯一的保险，不靠文件名、也不靠用户自觉。
 */
data class BillBook(
    val platform: Platform,
    val phone: String,
    val bills: List<BillRecord>,
)

/** 导入时每一本账的判定结果。 */
sealed interface BookCheck {
    val book: BillBook

    /** 可以导入：手机号对得上，或这本账本来就是空的。 */
    data class Importable(override val book: BillBook) : BookCheck

    /** 文件属于另一个手机号 —— 这一本不能导。 */
    data class WrongPhone(override val book: BillBook, val expected: String) : BookCheck

    /** 当前没登录这个平台，先跳过（登录后可以再导一次）。 */
    data class NoAccount(override val book: BillBook) : BookCheck
}

/**
 * 账单文件的编解码。
 *
 * ## 格式
 *
 * ```json
 * {
 *   "tag": "life798-bills",          // 认文件用的，防止随便一个 json 被当成账本
 *   "phone": "17772892831",          // 手机号 tag，放在最外层最前面
 *   "version": 1,
 *   "exportedAt": 1764139200000,
 *   "books": [
 *     { "platform": "hui798",   "phone": "17772892831", "count": 170, "bills": [...] },
 *     { "platform": "pangguai", "phone": "17772892831", "count": 13,  "bills": [...] }
 *   ]
 * }
 * ```
 *
 * `bills` 里的每一条就是账本落盘形状（[BillStore.encodeBill]），不另造字段：
 * 导出后能原样导回，也能被以后任何一版直接读。
 *
 * 顶层 `phone` 只是「这份文件是谁的」的一句话说明（取第一个非空手机号），
 * **真正拦截导入的是每本账自己的 `phone`** —— 两个平台可以用不同手机号登录。
 */
object BillTransfer {

    const val TAG = "life798-bills"
    const val VERSION = 1

    /** 文件选择器用的 MIME。部分文件管理器不认识 application/json，导入端会放宽。 */
    const val MIME = "application/json"

    /** 导出文件的默认名：`一水通账单-17772892831-20261126.json`。 */
    fun defaultFileName(phone: String, now: Long = System.currentTimeMillis()): String {
        val day = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
            .format(java.util.Date(now))
        val who = phone.ifBlank { "未登录" }
        return "一水通账单-$who-$day.json"
    }

    /** 把若干本账编成文件内容。 */
    fun encode(books: List<BillBook>, exportedAt: Long = System.currentTimeMillis()): String {
        val array = JSONArray()
        books.forEach { book ->
            val bills = JSONArray()
            book.bills.forEach { bills.put(BillStore.encodeBill(it)) }
            array.put(
                JSONObject()
                    .put("platform", book.platform.id)
                    .put("phone", book.phone)
                    .put("count", book.bills.size)
                    .put("bills", bills)
            )
        }
        return JSONObject()
            .put("tag", TAG)
            // 手机号 tag 放在最外层、紧跟 tag：任何人打开文件第一眼就能看到是谁的账
            .put("phone", books.firstOrNull { it.phone.isNotBlank() }?.phone.orEmpty())
            .put("version", VERSION)
            .put("exportedAt", exportedAt)
            .put("books", array)
            .toString(2)
    }

    sealed interface ParseResult {
        data class Ok(val phone: String, val books: List<BillBook>) : ParseResult
        data class Invalid(val reason: String) : ParseResult
    }

    /**
     * 解析文件内容。**不碰账号**，只负责把结构读出来；手机号比对在 [check] 里做。
     *
     * 结构不对就明确说原因，不要抛异常给界面 —— 用户选错文件是常事。
     */
    fun parse(raw: String): ParseResult {
        val text = raw.trim().removePrefix("\uFEFF")
        if (text.isBlank()) return ParseResult.Invalid("文件是空的")

        val root = runCatching { JSONObject(text) }.getOrNull()
            ?: return ParseResult.Invalid("不是有效的账单文件（内容不是 JSON 对象）")

        if (root.optString("tag") != TAG) {
            return ParseResult.Invalid("不是一水通导出的账单文件")
        }
        val version = root.optInt("version", 0)
        if (version <= 0 || version > VERSION) {
            return ParseResult.Invalid("文件版本 $version 不受支持，请升级到最新版本再导入")
        }

        val array = root.optJSONArray("books")
            ?: return ParseResult.Invalid("文件里没有账单数据")

        val books = buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val platform = Platform.entries.firstOrNull {
                    it.id == item.optString("platform")
                } ?: continue
                val billsRaw = item.optJSONArray("bills") ?: continue
                add(
                    BillBook(
                        platform = platform,
                        phone = item.optString("phone"),
                        bills = BillStore.parseJson(billsRaw.toString())
                    )
                )
            }
        }
        if (books.isEmpty()) return ParseResult.Invalid("文件里没有可识别的账本")

        val phone = root.optString("phone").ifBlank {
            books.firstOrNull { it.phone.isNotBlank() }?.phone.orEmpty()
        }
        return ParseResult.Ok(phone, books)
    }

    /**
     * 逐本比对当前账号。
     *
     * @param current 各平台当前登录手机号（未登录传空串）
     */
    fun check(books: List<BillBook>, current: Map<Platform, String>): List<BookCheck> =
        books.map { book ->
            val phone = current[book.platform].orEmpty()
            when {
                book.bills.isEmpty() -> BookCheck.Importable(book)
                book.phone.isBlank() -> BookCheck.WrongPhone(book, phone)
                phone.isBlank() -> BookCheck.NoAccount(book)
                book.phone != phone -> BookCheck.WrongPhone(book, phone)
                else -> BookCheck.Importable(book)
            }
        }

    /**
     * 整份文件能不能导。
     *
     * 只要有一本手机号对不上就整份拒绝：部分导入会留下一个「一半是别人账」的账本，
     * 比不导更难收拾。
     */
    fun rejection(checks: List<BookCheck>): String? =
        checks.filterIsInstance<BookCheck.WrongPhone>().firstOrNull()?.let { wrong ->
            "这份文件属于 ${mask(wrong.book.phone)}，当前 ${wrong.book.platform.label} " +
                "登录的是 ${mask(wrong.expected)}，已阻止导入。"
        }

    /** 手机号中间四位打码，弹窗里不要把完整号码摊开。 */
    fun mask(phone: String): String =
        if (phone.length == 11) "${phone.take(3)}****${phone.takeLast(4)}" else phone.ifBlank { "未登录" }
}
