package com.life798.mini.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size as GeomSize
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.life798.mini.R
import com.life798.mini.core.DeviceEntry
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Scan
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// ---------------------------------------------------------------------------
// 页头
// ---------------------------------------------------------------------------

/**
 * 页头：大标题 + 副标题。
 *
 * 这里**不放齿轮** —— 挂在页头右上角，用户会以为它是整页设置；
 * 实际上它管的是「常用设备」那几张卡，所以齿轮挪到设备区的标题行里去了。
 */
@Composable
internal fun PageTop() {
    val cs = MiuixTheme.colorScheme
    val dark = cs.background.luminance() < 0.5f
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 20.dp)
    ) {
        Text(
            text = "扫描",
            color = if (dark) cs.onSurface else Color(0xFF121316),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.6).sp
        )
        Spacer(Modifier.height(5.dp))
        Text(
            text = "对准设备上的二维码，即刻开始用水",
            color = cs.onSurfaceVariantSummary,
            fontSize = 13.sp,
            lineHeight = 19.sp
        )
    }
}

// ---------------------------------------------------------------------------
// 扫描卡
// ---------------------------------------------------------------------------

/**
 * 卡片里的一台设备。
 *
 * 只描述「它是谁、从什么时候开始」，不关心它来自哪个平台 ——
 * 出水、洗衣将来各有各的会话，但卡片只负责并列展示，所以这里不需要平台字段。
 * 真要区分时在这里加，卡片的版式不用动。
 */
internal data class HeroDevice(
    val id: String,
    val name: String,
    val startedAt: Long,
)

/** 同时最多展示两台。再多也放不下，而且业务上不会出现。 */
private const val MAX_DEVICES = 2

/**
 * 卡片固定高度。
 *
 * 178dp = insideMargin(24 × 2) + 内容区 130dp，而 130dp 正好是空态的自然高度
 * （取景框 66 + 间距 18 + 按钮 46）。**锁死它，空态外观分毫不变**；
 * 有设备时内容在 130dp 里垂直居中，卡片外框不跟着行数跳。
 */
private val HeroCardHeight = 178.dp

/**
 * 主视觉：一块标准的 miuix [Card]。
 *
 * 不自绘背景 —— 渐变、玻璃、高光边这些都是自己发明材质，一旦和系统其它页面
 * 摆在一起就会露馅。MIUI 的层次靠的是**色阶**（background → surface →
 * surfaceContainer）而不是材质，卡片只要用对色阶，圆角、按压反馈、深浅模式
 * 全部由组件自己处理。
 *
 * 卡里放什么由 [devices] 决定，只有三种形态：
 *
 * | 设备数 | 内容 | 底部按钮 |
 * |---|---|---|
 * | 0 | 取景框 + 时钟 + 扫码 | 有（撑满剩余宽度） |
 * | 1 | 一张设备卡 + 扫码 | 有（自然宽度，居中） |
 * | 2 | 两张设备卡 | **无** —— 到上限了，再放一个点不动的入口只会误导 |
 *
 * 卡片只做「展示 + 跳转」，停水、启动全在设备页里做。
 *
 * **外层这张卡永远在**，设备卡是嵌在它里面的。内层底色直接用 `cs.background`：
 * 大卡是白的（#FFFFFF），设备卡回到页面底色（#F2F3F5），形成「卡里挖了个槽」的凹陷感 ——
 * MIUI 设置页里那种分组输入框就是这个关系。
 *
 * 不用 `cs.surface`：浅色下它是 #F7F7F7（和背景只差 5，等于没有），深色下是纯黑（压得过重）。
 * 也不用 `onSurface` 叠透明度：8% 出来是 #EBEBEB，比页面底色灰一档，看着发闷。
 */
@Composable
internal fun HeroCard(
    devices: List<HeroDevice>,
    enabled: Boolean,
    onStartScan: () -> Unit,
    onOpenRecent: () -> Unit,
    onOpenDevice: (String) -> Unit,
) {
    val cs = MiuixTheme.colorScheme
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(HeroCardHeight)
            .graphicsLayer { alpha = if (enabled) 1f else 0.38f },
        insideMargin = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
        // 和常用设备卡、用量页的卡同一档色阶，三种卡摆在一起不会打架
        colors = CardDefaults.defaultColors(color = cs.surfaceContainer)
    ) {
        if (devices.isEmpty()) {
            EmptyHero(enabled = enabled, onStartScan = onStartScan, onOpenRecent = onOpenRecent)
        } else {
            // 计时器提到这一层：两行共用一次重组，而不是每行各起一个。
            // 卡片被置灰（管理模式）时 enabled=false，ticker 自动停，不空转。
            val now = rememberNowTick(active = enabled)
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                devices.take(MAX_DEVICES).forEachIndexed { index, device ->
                    if (index > 0) Spacer(Modifier.height(8.dp))
                    HeroDeviceCard(
                        device = device,
                        now = now,
                        onClick = { onOpenDevice(device.id) }
                    )
                }
                if (devices.size < MAX_DEVICES) {
                    Spacer(Modifier.height(12.dp))
                    PrimaryButton(enabled = enabled, onClick = onStartScan)
                }
            }
        }
    }
}

/** 一台设备都没有时的样子 —— 就是原来那套取景框 + 时钟 + 大按钮，样式一字不改。 */
@Composable
private fun EmptyHero(
    enabled: Boolean,
    onStartScan: () -> Unit,
    onOpenRecent: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ScanFrame()
        Spacer(Modifier.height(18.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 时钟放左边：右手单手握机时拇指落在右下，右边留给主按钮，
            // 次要入口离拇指远一点，误触概率低
            GhostButton(enabled = enabled, onClick = onOpenRecent)
            PrimaryButton(enabled = enabled, onClick = onStartScan)
        }
    }
}

/**
 * 一张设备卡，嵌在 [HeroCard] 里面，整张可点。
 *
 * 高度 60dp（上下 11dp + 图标 38dp），两张加 8dp 间距共 128dp，
 * 一台时再加 12dp 间距和 46dp 按钮共 118dp —— 都塞得进 130dp 的内容区。
 *
 * 图标必须给够尺寸：26dp 的方块配 14dp 描边水滴，红色叠红色会糊成一团，
 * 看起来像被底色盖住了。这里 38dp 方块 + 20dp 水滴 + 主色，边界才立得住。
 *
 * 计时和箭头都**不参与 weight**，名字再长也挤不走它们；
 * 名字自己 `maxLines = 1` + 省略号，永远不会溢出卡片。
 */
@Composable
private fun HeroDeviceCard(
    device: HeroDevice,
    now: Long,
    onClick: () -> Unit,
) {
    val cs = MiuixTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(cs.background)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(cs.primary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            DropGlyph(size = 20.dp, color = cs.primary, strokeUnits = 2.2f)
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = device.name,
                color = cs.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "正在使用",
                    color = cs.onSurfaceVariantSummary,
                    fontSize = 11.5.sp,
                    maxLines = 1
                )
                Text(
                    text = " · ",
                    color = cs.onSurfaceVariantSummary,
                    fontSize = 11.5.sp,
                    maxLines = 1
                )
                Text(
                    text = elapsedLabel(now - device.startedAt),
                    color = cs.primary,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            imageVector = MiuixIcons.ChevronForward,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = cs.onSurfaceVariantSummary
        )
    }
}

/**
 * 每秒推进一次的「现在」。
 *
 * 只在卡片可见且可交互时跳 —— 置灰、切页、扫码浮层盖住时都不会空转。
 * 用 `produceState` 而不是 `LaunchedEffect` + `mutableStateOf`：
 * 后者要自己管作用域，前者离开组合就自动取消。
 */
@Composable
private fun rememberNowTick(active: Boolean): Long {
    val now by produceState(initialValue = System.currentTimeMillis(), active) {
        while (active) {
            value = System.currentTimeMillis()
            delay(1000L)
        }
    }
    return now
}

/** 已用时长：HH:mm:ss。设备刚启动、时钟有偏差时按 0 处理，不显示负数。 */
private fun elapsedLabel(elapsedMs: Long): String {
    val total = (elapsedMs / 1000L).coerceAtLeast(0L)
    return String.format(
        Locale.US,
        "%02d:%02d:%02d",
        total / 3600,
        total % 3600 / 60,
        total % 60
    )
}

/** 取景框：只保留上面两个角，下面两个角会跟按钮挤在一起。 */
@Composable
private fun ScanFrame() {
    val frameColor = MiuixTheme.colorScheme.onSurface
    Box(
        modifier = Modifier.size(width = 168.dp, height = 66.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(1.dp)) {
            val stroke = 2.dp.toPx()
            val half = stroke / 2f
            val arm = 28.dp.toPx() - half
            val radius = 12.dp.toPx() - half
            val w = size.width
            val color = frameColor.copy(alpha = 0.34f)

            val topLeft = Path().apply {
                moveTo(0f, arm)
                lineTo(0f, radius)
                quadraticBezierTo(0f, 0f, radius, 0f)
                lineTo(arm, 0f)
            }
            val topRight = Path().apply {
                moveTo(w - arm, 0f)
                lineTo(w - radius, 0f)
                quadraticBezierTo(w, 0f, w, radius)
                lineTo(w, arm)
            }
            val style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
            drawPath(topLeft, color, style = style)
            drawPath(topRight, color, style = style)
        }
        Icon(
            painter = painterResource(R.drawable.ic_camera),
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = frameColor.copy(alpha = 0.62f)
        )
    }
}

/** 主按钮：直接用 miuix 的 primary 配色，不自己调色。 */
@Composable
private fun PrimaryButton(enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        minWidth = 0.dp,
        minHeight = 46.dp,
        insideMargin = PaddingValues(horizontal = 22.dp, vertical = 0.dp),
        colors = ButtonDefaults.buttonColorsPrimary()
    ) {
        Icon(
            imageVector = MiuixIcons.Scan,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = Color.White
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = "点击启动扫描",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.2.sp
        )
    }
}

/** 次级入口：贴在玻璃上的浅色圆钮，不能比主按钮抢眼。 */
@Composable
private fun GhostButton(enabled: Boolean, onClick: () -> Unit) {
    val cs = MiuixTheme.colorScheme
    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(cs.onSurface.copy(alpha = 0.07f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        ClockGlyph(size = 20.dp, color = cs.onSurfaceVariantSummary)
    }
}

// ---------------------------------------------------------------------------
// 设备区
// ---------------------------------------------------------------------------

/**
 * 常用设备区。
 *
 * 管理模式下标题行原地变成操作条 —— 不新开页面、不隐藏底栏。
 * 这一块只影响下面那几张卡，没必要全屏接管；卡片多了往下滚就行。
 *
 * 齿轮放在**这一块的标题行**里，而不是页头右上角：
 * 挂页头上，用户会以为它管的是整个扫码页；放这里才看得出它管的是常用设备。
 */
@Composable
internal fun DeviceSection(
    favorites: List<DeviceEntry>,
    activeDeviceId: String?,
    managing: Boolean,
    selected: Set<String>,
    onPick: (String) -> Unit,
    onToggleSelect: (String) -> Unit,
    onSelectAll: () -> Unit,
    onToggleManage: () -> Unit,
    onRename: (DeviceEntry) -> Unit,
    onRemove: () -> Unit,
    onOpenRecent: () -> Unit,
    bottomInset: Dp,
    modifier: Modifier = Modifier,
) {
    val cs = MiuixTheme.colorScheme

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 22.dp, end = 12.dp, top = 22.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (managing) "已选 ${selected.size} 项" else "常用设备",
                color = cs.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.weight(1f))
            if (managing) {
                // 全选 / 取消全选：批量删的时候不用一台台点
                val allSelected = favorites.isNotEmpty() && selected.size == favorites.size
                Text(
                    text = if (allSelected) "取消全选" else "全选",
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = favorites.isNotEmpty(), onClick = onSelectAll)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    color = if (favorites.isEmpty()) {
                        cs.onSurfaceVariantSummary.copy(alpha = 0.45f)
                    } else {
                        cs.primary
                    },
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.width(2.dp))
                Text(
                    text = "删除",
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = selected.isNotEmpty(), onClick = onRemove)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    color = if (selected.isEmpty()) {
                        cs.onSurfaceVariantSummary.copy(alpha = 0.45f)
                    } else {
                        cs.error
                    },
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.width(4.dp))
                TextButton(
                    text = "完成",
                    onClick = onToggleManage,
                    minWidth = 44.dp,
                    minHeight = 36.dp
                )
            } else {
                // 入口常显，不跟「有没有常用设备」绑在一起 ——
                // 一台都没保存的时候恰恰最需要它：扫过的设备只在流水里找得到
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onOpenRecent)
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    Text(
                        text = "最近 7 天",
                        color = cs.primary,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Icon(
                        imageVector = MiuixIcons.ChevronForward,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                        tint = cs.primary
                    )
                }
                Spacer(Modifier.width(2.dp))
                IconButton(
                    onClick = onToggleManage,
                    minWidth = 40.dp,
                    minHeight = 40.dp
                ) {
                    Icon(
                        imageVector = MiuixIcons.Settings,
                        contentDescription = "管理常用设备",
                        modifier = Modifier.size(20.dp),
                        tint = cs.onSurfaceVariantSummary
                    )
                }
            }
        }

        if (favorites.isEmpty()) {
            EmptyDevices()
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                favorites.forEach { entry ->
                    FavoriteCard(
                        entry = entry,
                        active = entry.id == activeDeviceId,
                        managing = managing,
                        checked = entry.id in selected,
                        onClick = {
                            if (managing) onToggleSelect(entry.id) else onPick(entry.id)
                        },
                        onRename = { onRename(entry) }
                    )
                }
                // 底栏是悬浮胶囊，最后一张卡要躲开它
                Spacer(Modifier.height(bottomInset + 12.dp))
            }
        }
    }
}

/**
 * 一台常用设备。管理模式左侧多一个选择圈，右侧的「在用」换成铅笔。
 *
 * 底色用 `surfaceContainer` 而不是 `surface` —— 后者在浅色下是 #F7F7F7，
 * 跟页面背景 #F2F3F5 只差 5，卡片几乎看不出轮廓；深色下又是纯黑，压得过重。
 * surfaceContainer 是 #FFFFFF / #242424，两种模式下都立得住。
 */
@Composable
private fun FavoriteCard(
    entry: DeviceEntry,
    active: Boolean,
    managing: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
) {
    val cs = MiuixTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(cs.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (managing) {
            SelectCircle(checked = checked, color = cs.primary)
            Spacer(Modifier.width(11.dp))
        }
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(cs.primary.copy(alpha = if (active) 0.16f else 0.09f)),
            contentAlignment = Alignment.Center
        ) {
            DropGlyph(size = 19.dp, color = cs.primary, strokeUnits = 2f)
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.displayName,
                color = cs.onSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            entry.subName?.let { sub ->
                Spacer(Modifier.height(3.dp))
                Text(
                    text = sub,
                    color = cs.onSurfaceVariantSummary,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (managing) {
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .clickable(onClick = onRename),
                contentAlignment = Alignment.Center
            ) {
                PencilGlyph(size = 18.dp, color = cs.onSurfaceVariantSummary)
            }
        } else if (active) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = "在用",
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(cs.primary.copy(alpha = 0.11f))
                    .padding(horizontal = 8.dp, vertical = 3.5.dp),
                color = cs.primary,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/** 选择圈：22dp 圆环，选中时填充主色并画对勾。 */
@Composable
private fun SelectCircle(checked: Boolean, color: Color) {
    Canvas(modifier = Modifier.size(22.dp)) {
        val r = size.minDimension / 2f
        val stroke = 1.8.dp.toPx()
        if (checked) {
            drawCircle(color = color, radius = r - stroke / 2f)
            val tick = Path().apply {
                moveTo(size.width * 0.28f, size.height * 0.52f)
                lineTo(size.width * 0.44f, size.height * 0.68f)
                lineTo(size.width * 0.74f, size.height * 0.34f)
            }
            drawPath(
                path = tick,
                color = Color.White,
                style = Stroke(
                    width = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
        } else {
            drawCircle(
                color = color.copy(alpha = 0.35f),
                radius = r - stroke / 2f,
                style = Stroke(width = stroke)
            )
        }
    }
}

/** 一台常用设备都没有时的引导。 */
@Composable
private fun EmptyDevices() {
    val cs = MiuixTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 40.dp, end = 40.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(cs.onSurface.copy(alpha = 0.05f)),
            contentAlignment = Alignment.Center
        ) {
            QrGlyph(size = 24.dp, color = cs.onSurface.copy(alpha = 0.28f))
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = "还没有保存的设备\n扫一次码，就会自动记在这里",
            color = cs.onSurfaceVariantSummary,
            fontSize = 12.5.sp,
            lineHeight = 21.sp,
            textAlign = TextAlign.Center
        )
    }
}

// ---------------------------------------------------------------------------
// 最近 7 天抽屉内容
// ---------------------------------------------------------------------------

/**
 * 抽屉内容，按 miuix 的标准结构写（对照官方 demo `BottomSheetSection.kt`）：
 *
 * - 容器是 `LazyColumn` + `scrollEndHaptic` + `overScrollVertical`，滚动手感才是 MIUI 的
 * - **一个 `Card` 包住所有行**，行与行之间用 `HorizontalDivider` 分隔。
 *   以前是「每行一张独立小圆角卡 + 7dp 间距」，那是 iOS 分组列表的语言，不是 MIUI 的
 * - 卡片色取 `secondaryContainer`（浅 #F0F0F0 / 深 #434343），
 *   抽屉底取 `surfaceContainer`（浅 #FFF / 深 #242424），三级色阶才立得住。
 *   不要硬编码色值 —— 主题一改，硬编码就跟背景糊在一起（这个坑踩过一次）
 * - 左右内边距由抽屉自带的 `insideMargin`（24dp）提供，这里**不能再加**，
 *   否则实际内缩 40dp，行会显得又窄又飘
 *
 * 点整行 = 直接启动这台设备；点右边「+」= 加进常用。
 * 两个动作互不干扰 —— 外层 clickable 只负责启动，IconButton 自己把事件拦掉。
 *
 * **不去重**：这是一条使用流水，同一台设备扫五次就出现五行。
 * 每行的时间精确到分，靠时间区分「同一台设备的不同次使用」。
 *
 * 高度固定（[listHeight]），跟记录条数无关 —— 只有一条记录时下面留白，
 * 有二十条时内部滚动。抽屉高度随内容伸缩会让人每次打开都要重新找位置。
 */
@Composable
internal fun RecentSheetContent(
    recent: List<DeviceEntry>,
    favoriteIds: Set<String>,
    listHeight: Dp,
    onPick: (String) -> Unit,
    onAdd: (String) -> Unit,
) {
    val cs = MiuixTheme.colorScheme
    // 抽屉贴底，最后一行不能被手势条压住
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    if (recent.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(listHeight),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "最近 7 天还没有记录",
                color = cs.onSurfaceVariantSummary,
                fontSize = 13.sp
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .height(listHeight)
            .scrollEndHaptic()
            .overScrollVertical()
    ) {
        item {
            Card(
                modifier = Modifier.padding(bottom = navBottom + 12.dp),
                colors = CardDefaults.defaultColors(color = cs.secondaryContainer)
            ) {
                recent.forEachIndexed { index, entry ->
                    if (index > 0) {
                        HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(entry.id) }
                            .padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = entry.displayName,
                                color = cs.onSurface,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(2.5.dp))
                            Text(
                                text = visitTime(entry.at),
                                color = cs.onSurfaceVariantSummary,
                                fontSize = 11.sp,
                                maxLines = 1
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        if (entry.id in favoriteIds) {
                            Text(
                                text = "已添加",
                                modifier = Modifier.padding(end = 10.dp),
                                color = cs.onSurfaceVariantSummary,
                                fontSize = 12.sp
                            )
                        } else {
                            IconButton(onClick = { onAdd(entry.id) }) {
                                Icon(
                                    imageVector = MiuixIcons.Add,
                                    contentDescription = "添加到常用设备",
                                    tint = cs.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 自绘图标
//
// miuix 图标库里只有 Add / Back / ChevronForward / ListView / Months /
// Scan / Settings 这几个，相机、水滴、时钟、铅笔、二维码一个都没有；
// Material Icons 又全是实心 —— 套进来会变成一块黑疙瘩。
// 设计稿是描边风格，所以统一按 24×24 坐标系自己画。
// ---------------------------------------------------------------------------

/** 水滴。尖头朝上、圆肚朝下。 */
@Composable
private fun DropGlyph(size: Dp, color: Color, strokeUnits: Float = 2f) {
    Canvas(modifier = Modifier.size(size)) {
        val s = this.size.minDimension / 24f
        val style = Stroke(
            width = strokeUnits * s,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
        val cx = 12f * s
        val cy = 14f * s
        val r = 6.2f * s
        val tip = 2.8f * s
        val drop = Path().apply {
            moveTo(cx, tip)
            cubicTo(cx - r * 0.40f, cy - r * 1.05f, cx - r, cy - r * 0.42f, cx - r, cy)
            arcTo(
                rect = Rect(cx - r, cy - r, cx + r, cy + r),
                startAngleDegrees = 180f,
                // 必须是 -180（逆时针），不能是 +180。
                // Compose 的 0° 在 3 点方向、正角度顺时针：从 180°（左）扫 +180
                // 走的是 180 → 270 → 360，也就是**上半圆**，圆肚会鼓到上面去，
                // 和从顶点下来的两条边交叉，画出来是个「∧」加一个三角洞，
                // 根本不是水滴。扫 -180 才是 180 → 90 → 0 的下半圆，圆肚朝下。
                sweepAngleDegrees = -180f,
                forceMoveTo = false
            )
            cubicTo(cx + r, cy - r * 0.42f, cx + r * 0.40f, cy - r * 1.05f, cx, tip)
            close()
        }
        drawPath(drop, color, style = style)
    }
}

/** 时钟。外圈 + 时针分针。 */
@Composable
private fun ClockGlyph(size: Dp, color: Color) {
    Canvas(modifier = Modifier.size(size)) {
        val s = this.size.minDimension / 24f
        val style = Stroke(
            width = 2f * s,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
        drawCircle(
            color = color,
            radius = 8.6f * s,
            center = Offset(12f * s, 12f * s),
            style = style
        )
        val hands = Path().apply {
            moveTo(12f * s, 7.4f * s)
            lineTo(12f * s, 12f * s)
            lineTo(15.1f * s, 14f * s)
        }
        drawPath(hands, color, style = style)
    }
}

/** 铅笔。斜放的笔身 + 笔头分隔线。 */
@Composable
private fun PencilGlyph(size: Dp, color: Color) {
    Canvas(modifier = Modifier.size(size)) {
        val s = this.size.minDimension / 24f
        val style = Stroke(
            width = 1.9f * s,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
        val body = Path().apply {
            moveTo(16.2f * s, 3.4f * s)
            lineTo(20.6f * s, 7.8f * s)
            lineTo(8.4f * s, 20f * s)
            lineTo(3.2f * s, 20.8f * s)
            lineTo(4f * s, 15.6f * s)
            close()
        }
        drawPath(body, color, style = style)
        drawLine(
            color = color,
            start = Offset(13.6f * s, 6f * s),
            end = Offset(18f * s, 10.4f * s),
            strokeWidth = 1.9f * s,
            cap = StrokeCap.Round
        )
    }
}

/** 二维码：三个定位角 + 右下角一小块数据。 */
@Composable
private fun QrGlyph(size: Dp, color: Color) {
    Canvas(modifier = Modifier.size(size)) {
        val s = this.size.minDimension / 24f
        val style = Stroke(
            width = 2f * s,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
        listOf(
            Offset(3f * s, 3f * s),
            Offset(14f * s, 3f * s),
            Offset(3f * s, 14f * s)
        ).forEach { offset ->
            drawRoundRect(
                color = color,
                topLeft = offset,
                size = GeomSize(7f * s, 7f * s),
                cornerRadius = CornerRadius(1.6f * s),
                style = style
            )
        }
        drawRect(
            color = color,
            topLeft = Offset(14f * s, 14f * s),
            size = GeomSize(3f * s, 3f * s),
            style = style
        )
        drawLine(
            color = color,
            start = Offset(20f * s, 14f * s),
            end = Offset(20f * s, 17f * s),
            strokeWidth = 2f * s,
            cap = StrokeCap.Round
        )
        drawLine(
            color = color,
            start = Offset(17f * s, 20f * s),
            end = Offset(21f * s, 20f * s),
            strokeWidth = 2f * s,
            cap = StrokeCap.Round
        )
    }
}

// ---------------------------------------------------------------------------
// 工具
// ---------------------------------------------------------------------------

/** 按比例混合两个颜色，用来从 primary 派生提亮 / 压暗的色标。 */
private fun Color.mix(other: Color, amount: Float): Color = Color(
    red = red + (other.red - red) * amount,
    green = green + (other.green - green) * amount,
    blue = blue + (other.blue - blue) * amount,
    alpha = 1f
)

/**
 * 使用记录的时间：**年月日 + 时分**，不显示「几分钟前」。
 *
 * 流水里同一台设备会出现好几行，只有绝对时间能把它们区分开；
 * 相对时间还会让两行在刷新前后跳来跳去。
 */
private val VISIT_TIME_FMT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("M月d日 HH:mm", Locale.CHINA)

internal fun visitTime(at: Long): String =
    Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(VISIT_TIME_FMT)
