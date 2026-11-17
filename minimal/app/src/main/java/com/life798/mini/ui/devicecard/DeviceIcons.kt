package com.life798.mini.ui.devicecard

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 设备卡片自己的图标集，**面性（实心）**风格。
 *
 * 为什么不用 MiuixIcons：库里的 157 个图标全是通用件（Add / Scan / Settings / Store…），
 * 杯子、花洒、洗衣机、二维码一个都没有，所以只能自己画。
 *
 * 形状**照着惠生活 798 官方 App 首页那排分类图标改的**（洗烘 / 饮水 / 淋浴），
 * 保留它们各自的识别特征，再把立体的彩色质感压成单色面性，和 MIUI 的图标语言对齐：
 *
 * - 饮水：直筒马克杯 + C 形把手 + 两滴水（官方那个杯子，不是饮水机）
 * - 淋浴：小号半圆伞顶喷头 + 拱形软管 + 三颗水珠（喷头只占旧版的六成宽，水珠放大）
 * - 洗衣：机身 + 顶部控制条（圆点 + 短横）+ 双圈圆舱门
 *
 * 画法照 MiuixIcons 的源码结构：`ImageVector.Builder` + 24dp 默认尺寸 + `fill` 实心路径，
 * 首次访问构建一次后由 `by lazy` 缓存（库里用的是 `private var _x: ImageVector?`，
 * 效果一样，Kotlin 这边 `lazy` 更短且线程安全）。
 *
 * **填充一律用 [PathFillType.EvenOdd]**：这些图标都是「外轮廓里挖洞」的结构，
 * EvenOdd 靠嵌套层数的奇偶决定填不填，**跟路径方向无关**；
 * NonZero 要求内腔必须逆时针、外轮廓顺时针，手写路径一旦方向写反，
 * 挖空就会变成一坨实心，而且编译期完全看不出来。这里选容错高的那个。
 *
 * 所有坐标都在 24×24 的网格里，四周留 2.5dp 以上的安全边距 —— 图标会被放在
 * 38dp 的圆角色块里，缩到 19~20dp 显示，边距不够会顶到色块边缘。
 */
object DeviceIcons {

    /** 饮水：直筒杯 + C 形把手 + 两滴水。 */
    val Drink: ImageVector by lazy {
        icon("Drink") {
            // 杯身
            moveTo(8f, 8.6f)
            lineTo(12.4f, 8.6f)
            quadTo(15f, 8.6f, 15f, 11.2f)
            lineTo(15f, 18.2f)
            quadTo(15f, 20.8f, 12.4f, 20.8f)
            lineTo(8f, 20.8f)
            quadTo(5.4f, 20.8f, 5.4f, 18.2f)
            lineTo(5.4f, 11.2f)
            quadTo(5.4f, 8.6f, 8f, 8.6f)
            close()

            // 杯口薄壁：挖一条细缝，杯沿就有了内壁
            moveTo(7.2f, 9.9f)
            lineTo(13.2f, 9.9f)
            quadTo(13.8f, 9.9f, 13.8f, 10.5f)
            lineTo(13.8f, 10.5f)
            quadTo(13.8f, 11.1f, 13.2f, 11.1f)
            lineTo(7.2f, 11.1f)
            quadTo(6.6f, 11.1f, 6.6f, 10.5f)
            lineTo(6.6f, 10.5f)
            quadTo(6.6f, 9.9f, 7.2f, 9.9f)
            close()

            // 把手：贴住杯身右侧的 C 形环，中间是空的
            moveTo(15f, 11.9f)
            curveTo(19.8f, 11.9f, 19.8f, 17.9f, 15f, 17.9f)
            lineTo(15f, 16.3f)
            curveTo(18.2f, 16.3f, 18.2f, 13.5f, 15f, 13.5f)
            close()

            // 两滴水
            moveTo(8.8f, 4.7f)
            curveTo(9.65f, 5.935f, 9.8f, 6.95f, 8.8f, 7.6f)
            curveTo(7.8f, 6.95f, 7.95f, 5.935f, 8.8f, 4.7f)
            close()

            moveTo(12f, 3.1f)
            curveTo(12.68f, 4.335f, 12.8f, 5.28f, 12f, 5.8f)
            curveTo(11.2f, 5.28f, 11.32f, 4.335f, 12f, 3.1f)
            close()
        }
    }

    /**
     * 淋浴：小号伞形喷头 + 拱形软管 + 三颗大水珠。
     *
     * **三个部件必须是三条独立路径**。早先把喷头和软管塞进同一条路径靠 EvenOdd 挖内腔，
     * 而软管起点落在喷头轮廓内部，整根管子被当成「内层」挖空，弯角处就断开了。
     * 拆成三条路径后各自闭合，重叠区直接融合，不会再互相掏空。
     *
     * 软管的弯用四分之一圆弧（k = 0.5523 的贝塞尔近似）而不是折线：
     * 外弯 R = 3.175、内弯 R = 1.625，两者之差正好是管宽 1.55，
     * 所以整根管子从起点到尾端壁厚恒定，转弯处平滑不断。
     */
    val Shower: ImageVector by lazy {
        icon(
            "Shower",
            // 喷头：平底 + 半椭圆伞顶（rx 3.1 / ry 2.95，比旧版小一半）
            {
                moveTo(3.8f, 10.4f)
                lineTo(10f, 10.4f)
                curveTo(10f, 8.77f, 8.61f, 7.45f, 6.9f, 7.45f)
                curveTo(5.19f, 7.45f, 3.8f, 8.77f, 3.8f, 10.4f)
                close()
            },
            // 软管：左腿自喷头内部升起 → 拱过顶 → 右腿垂到 19.6，末端圆头
            {
                moveTo(6.125f, 8.9f)
                lineTo(6.125f, 6.6f)
                curveTo(6.125f, 4.846f, 7.546f, 3.425f, 9.3f, 3.425f)
                lineTo(13.2f, 3.425f)
                curveTo(14.954f, 3.425f, 16.375f, 4.846f, 16.375f, 6.6f)
                lineTo(16.375f, 19.6f)
                curveTo(16.375f, 20.028f, 16.028f, 20.375f, 15.6f, 20.375f)
                curveTo(15.172f, 20.375f, 14.825f, 20.028f, 14.825f, 19.6f)
                lineTo(14.825f, 6.6f)
                curveTo(14.825f, 5.703f, 14.097f, 4.975f, 13.2f, 4.975f)
                lineTo(9.3f, 4.975f)
                curveTo(8.403f, 4.975f, 7.675f, 5.703f, 7.675f, 6.6f)
                lineTo(7.675f, 8.9f)
                close()
            },
            // 三颗水珠：错落斜落，与喷头之间留出空隙
            {
                moveTo(5.3f, 12.4f)
                curveTo(6.762f, 13.635f, 7.02f, 14.902f, 5.3f, 16.02f)
                curveTo(3.58f, 14.902f, 3.838f, 13.635f, 5.3f, 12.4f)
                close()

                moveTo(8.9f, 14.6f)
                curveTo(10.243f, 15.835f, 10.48f, 17.053f, 8.9f, 18.08f)
                curveTo(7.32f, 17.053f, 7.557f, 15.835f, 8.9f, 14.6f)
                close()

                moveTo(5.9f, 17.4f)
                curveTo(7.022f, 18.635f, 7.22f, 19.762f, 5.9f, 20.62f)
                curveTo(4.58f, 19.762f, 4.778f, 18.635f, 5.9f, 17.4f)
                close()
            }
        )
    }

    /** 洗衣机：机身 + 顶部控制条 + 双圈圆舱门。 */
    val Washer: ImageVector by lazy {
        icon("Washer") {
            // 机身
            moveTo(6.2f, 2.8f)
            lineTo(17.8f, 2.8f)
            quadTo(20.6f, 2.8f, 20.6f, 5.6f)
            lineTo(20.6f, 18.8f)
            quadTo(20.6f, 21.6f, 17.8f, 21.6f)
            lineTo(6.2f, 21.6f)
            quadTo(3.4f, 21.6f, 3.4f, 18.8f)
            lineTo(3.4f, 5.6f)
            quadTo(3.4f, 2.8f, 6.2f, 2.8f)
            close()

            // 顶部控制条：整条挖空，里面放旋钮和显示屏
            moveTo(6.8f, 5.2f)
            lineTo(17.2f, 5.2f)
            quadTo(18.4f, 5.2f, 18.4f, 6.4f)
            lineTo(18.4f, 6.6f)
            quadTo(18.4f, 7.8f, 17.2f, 7.8f)
            lineTo(6.8f, 7.8f)
            quadTo(5.6f, 7.8f, 5.6f, 6.6f)
            lineTo(5.6f, 6.4f)
            quadTo(5.6f, 5.2f, 6.8f, 5.2f)
            close()

            // 旋钮
            moveTo(6.88f, 6.5f)
            curveTo(6.88f, 6.158f, 7.158f, 5.88f, 7.5f, 5.88f)
            curveTo(7.842f, 5.88f, 8.12f, 6.158f, 8.12f, 6.5f)
            curveTo(8.12f, 6.842f, 7.842f, 7.12f, 7.5f, 7.12f)
            curveTo(7.158f, 7.12f, 6.88f, 6.842f, 6.88f, 6.5f)
            close()

            // 显示屏：控制条右侧一条短横
            moveTo(12.65f, 6.1f)
            lineTo(16.75f, 6.1f)
            quadTo(17.2f, 6.1f, 17.2f, 6.55f)
            lineTo(17.2f, 6.55f)
            quadTo(17.2f, 7f, 16.75f, 7f)
            lineTo(12.65f, 7f)
            quadTo(12.2f, 7f, 12.2f, 6.55f)
            lineTo(12.2f, 6.55f)
            quadTo(12.2f, 6.1f, 12.65f, 6.1f)
            close()

            // 舱门：外圈挖空 → 内圈实心 → 中心再挖，形成双圈玻璃门
            moveTo(6.4f, 15.2f)
            curveTo(6.4f, 12.107f, 8.907f, 9.6f, 12f, 9.6f)
            curveTo(15.093f, 9.6f, 17.6f, 12.107f, 17.6f, 15.2f)
            curveTo(17.6f, 18.293f, 15.093f, 20.8f, 12f, 20.8f)
            curveTo(8.907f, 20.8f, 6.4f, 18.293f, 6.4f, 15.2f)
            close()

            moveTo(7.25f, 15.2f)
            curveTo(7.25f, 12.577f, 9.377f, 10.45f, 12f, 10.45f)
            curveTo(14.623f, 10.45f, 16.75f, 12.577f, 16.75f, 15.2f)
            curveTo(16.75f, 17.823f, 14.623f, 19.95f, 12f, 19.95f)
            curveTo(9.377f, 19.95f, 7.25f, 17.823f, 7.25f, 15.2f)
            close()

            moveTo(9.9f, 15.2f)
            curveTo(9.9f, 14.04f, 10.84f, 13.1f, 12f, 13.1f)
            curveTo(13.16f, 13.1f, 14.1f, 14.04f, 14.1f, 15.2f)
            curveTo(14.1f, 16.36f, 13.16f, 17.3f, 12f, 17.3f)
            curveTo(10.84f, 17.3f, 9.9f, 16.36f, 9.9f, 15.2f)
            close()
        }
    }

    /** 水滴。原来的 DropGlyph 是描边，这里是同形状的面性版本。 */
    val Drop: ImageVector by lazy {
        icon("Drop") {
            moveTo(12f, 2.6f)
            curveTo(12f, 2.6f, 18.4f, 10.1f, 18.4f, 14.7f)
            curveTo(18.4f, 18.2f, 15.5f, 21f, 12f, 21f)
            curveTo(8.5f, 21f, 5.6f, 18.2f, 5.6f, 14.7f)
            curveTo(5.6f, 10.1f, 12f, 2.6f, 12f, 2.6f)
            close()
        }
    }

    /** 时钟：表盘挖空成环 + L 形指针。 */
    val Clock: ImageVector by lazy {
        icon("Clock") {
            circle(cx = 12f, cy = 12f, r = 9.2f)
            circle(cx = 12f, cy = 12f, r = 7.4f)
            // 指针：竖 6.8→12，横 12→16.2
            moveTo(11.2f, 6.8f)
            lineTo(12.8f, 6.8f)
            lineTo(12.8f, 12f)
            lineTo(16.2f, 12f)
            lineTo(16.2f, 13.6f)
            lineTo(11.2f, 13.6f)
            close()
        }
    }

    /** 铅笔：斜放笔身，笔尖处挖一条斜缝区分笔尖和笔杆。 */
    val Pencil: ImageVector by lazy {
        icon("Pencil") {
            moveTo(3.2f, 20.8f)
            lineTo(4.2f, 17.2f)
            lineTo(16.6f, 4.8f)
            lineTo(19.2f, 7.4f)
            lineTo(6.8f, 19.8f)
            close()

            // 笔尖与笔杆之间的缝：挖空一条斜条，面性图标靠它才看得出「笔尖」
            moveTo(4.9f, 17.4f)
            lineTo(6.6f, 15.7f)
            lineTo(8.3f, 17.4f)
            lineTo(6.6f, 19.1f)
            close()
        }
    }

    /** 二维码：三个定位角 + 右下两块数据。 */
    val Qr: ImageVector by lazy {
        icon("Qr") {
            qrCorner(x = 3.6f, y = 3.6f)
            qrCorner(x = 14.0f, y = 3.6f)
            qrCorner(x = 3.6f, y = 14.0f)

            // 右下：一大一小两块，模仿二维码的数据区
            moveTo(14.0f, 14.0f)
            lineTo(16.6f, 14.0f)
            lineTo(16.6f, 16.6f)
            lineTo(14.0f, 16.6f)
            close()

            moveTo(17.6f, 17.6f)
            lineTo(20.4f, 17.6f)
            lineTo(20.4f, 20.4f)
            lineTo(17.6f, 20.4f)
            close()
        }
    }

    /**
     * 感叹号：圆角三角 + 竖条 + 圆点。
     *
     * 只用在「洗衣已完成，请取衣」那张卡上，所以形状取最通用的警示三角，
     * 不做动画、不加描边 —— 放在 38dp 的圆角色块里，21dp 显示。
     */
    val Warning: ImageVector by lazy {
        icon("Warning") {
            // 圆角三角：三个角各用一段 quadTo 绕过去，半径约 3
            moveTo(13.417f, 5.846f)
            lineTo(19.583f, 17.354f)
            quadTo(21f, 20f, 18f, 20f)
            lineTo(6f, 20f)
            quadTo(3f, 20f, 4.417f, 17.354f)
            lineTo(10.583f, 5.846f)
            quadTo(12f, 3.2f, 13.417f, 5.846f)
            close()

            // 竖条：上下端各收成半圆，避免尖角
            moveTo(11.05f, 9.75f)
            quadTo(11.05f, 8.8f, 12f, 8.8f)
            quadTo(12.95f, 8.8f, 12.95f, 9.75f)
            lineTo(12.95f, 13.95f)
            quadTo(12.95f, 14.9f, 12f, 14.9f)
            quadTo(11.05f, 14.9f, 11.05f, 13.95f)
            close()

            // 圆点
            circle(cx = 12f, cy = 17.35f, r = 1.15f)
        }
    }

    // ------------------------------------------------------------------
    // 构建工具
    // ------------------------------------------------------------------

    /**
     * 统一的构建入口：24dp 默认尺寸、24×24 视口、EvenOdd 填充。
     *
     * 接受**多个** path：每个 block 生成一条独立路径，填充互不影响。
     * 这是必需的 —— 喷头和软管这类必须相交的部件如果塞进同一条路径，
     * EvenOdd 会把相交区判成内层直接挖空，接头处就断了。
     *
     * 填充色给黑色即可 —— 真正上屏时 Miuix 的 Icon 会通过 tint 覆盖，颜色由调用方决定。
     */
    private fun icon(
        name: String,
        block: PathBuilder.() -> Unit
    ): ImageVector = buildIcon(name, arrayOf(block))

    private fun icon(
        name: String,
        vararg blocks: PathBuilder.() -> Unit
    ): ImageVector = buildIcon(name, blocks)

    private fun buildIcon(
        name: String,
        blocks: Array<out PathBuilder.() -> Unit>
    ): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        blocks.forEach { block ->
            path(
                fill = SolidColor(Color.Black),
                pathFillType = PathFillType.EvenOdd,
                pathBuilder = block,
            )
        }
    }.build()

    /**
     * 圆：用四段三次贝塞尔近似，控制点系数 0.5523 是 90° 圆弧的标准近似。
     * 直接画 arc 要算角度和方向，写错一次就是一个歪掉的圆，不如贝塞尔稳。
     */
    private fun PathBuilder.circle(
        cx: Float,
        cy: Float,
        r: Float,
    ) {
        val k = 0.5523f * r
        moveTo(cx - r, cy)
        curveTo(cx - r, cy - k, cx - k, cy - r, cx, cy - r)
        curveTo(cx + k, cy - r, cx + r, cy - k, cx + r, cy)
        curveTo(cx + r, cy + k, cx + k, cy + r, cx, cy + r)
        curveTo(cx - k, cy + r, cx - r, cy + k, cx - r, cy)
        close()
    }

    /** 二维码的一个定位角：6.4 的实心方块，中间挖 2.4 的洞。 */
    private fun PathBuilder.qrCorner(x: Float, y: Float) {
        moveTo(x, y)
        lineTo(x + 6.4f, y)
        lineTo(x + 6.4f, y + 6.4f)
        lineTo(x, y + 6.4f)
        close()

        moveTo(x + 2f, y + 2f)
        lineTo(x + 4.4f, y + 2f)
        lineTo(x + 4.4f, y + 4.4f)
        lineTo(x + 2f, y + 4.4f)
        close()
    }
}
