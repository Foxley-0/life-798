package com.life798.mini.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.life798.mini.BuildConfig
import com.life798.mini.R
import com.life798.mini.ui.effect.BgEffectBackground
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val HERO_HEIGHT = 240.dp
private val TOP_BAR_HEIGHT = 56.dp
private const val REPO_URL = "https://github.com/Foxley-0/life-798"

/**
 * 关于页（二级页）。
 *
 * 结构参考 miuix 官方 AboutPage / SonyPods：
 * - HyperOS 流动背景（原来挂在设置页，现在归这里）
 * - 固定 hero：图标 / 名称 / 版本，随滚动依次淡出
 * - 下方卡片：运行日志、项目仓库、开源许可
 */
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    onOpenLog: () -> Unit
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val heroPx = with(LocalDensity.current) { HERO_HEIGHT.toPx() }

    val progress by remember(heroPx) {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) {
                1f
            } else {
                (listState.firstVisibleItemScrollOffset / heroPx).coerceIn(0f, 1f)
            }
        }
    }

    var showLicenses by remember { mutableStateOf(false) }

    // 动效要铺到状态栏下才叫沉浸，所以 inset 只作用在内容上，不作用在背景上
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    // 导航条高度单独算：加在根布局上会把背景也 padding 掉，小白条区域就露系统色
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(
        modifier = Modifier
            .fillMaxSize()
    ) {
        BgEffectBackground(
            dynamicBackground = true,
            isFullSize = true,
            modifier = Modifier.fillMaxSize(),
            alpha = { 1f - progress * 0.55f }
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = statusBarTop + HERO_HEIGHT + TOP_BAR_HEIGHT,
                    start = 16.dp,
                    end = 16.dp,
                    bottom = 28.dp + navBottom
                )
            ) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        ArrowPreference(
                            title = "运行日志",
                            summary = "查看与导出本次运行记录",
                            onClick = onOpenLog
                        )
                        ArrowPreference(
                            title = "项目仓库",
                            endActions = {
                                Text(
                                    text = "GitHub",
                                    fontSize = 13.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantActions
                                )
                            },
                            onClick = { openLink(context, REPO_URL) }
                        )
                        ArrowPreference(
                            title = "开源许可",
                            summary = "miuix / ZXing / OkHttp",
                            onClick = { showLicenses = true }
                        )
                    }

                    Spacer(Modifier.height(20.dp))
                    Text(
                        text = "非官方客户端，与服务提供方无关。\n仅供本人对自己有权使用的设备进行操作。",
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // 固定 hero：版本先淡、名称次之、图标最后
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(top = statusBarTop + TOP_BAR_HEIGHT),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(R.drawable.ic_logo),
                contentDescription = null,
                modifier = Modifier
                    .size(84.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .graphicsLayer {
                        val p = ((progress - 0.35f) / 0.35f).coerceIn(0f, 1f)
                        alpha = 1f - p
                        scaleX = 1f - p * 0.06f
                        scaleY = 1f - p * 0.06f
                    }
            )

            Spacer(Modifier.height(14.dp))

            Text(
                text = "798 小助手",
                fontSize = 21.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier.graphicsLayer {
                    val p = ((progress - 0.15f) / 0.35f).coerceIn(0f, 1f)
                    alpha = 1f - p
                }
            )

            Spacer(Modifier.height(5.dp))

            Text(
                text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.graphicsLayer {
                    val p = (progress / 0.3f).coerceIn(0f, 1f)
                    alpha = 1f - p
                }
            )
        }

        // 顶栏始终在，返回按钮不能跟着 hero 一起消失
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(top = statusBarTop)
                .height(TOP_BAR_HEIGHT)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = MiuixIcons.Back,
                    contentDescription = "返回",
                    tint = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier.size(22.dp)
                )
            }
            Text(
                text = "关于",
                fontSize = 17.sp,
                color = MiuixTheme.colorScheme.onSurface.copy(
                    alpha = (progress * 1.4f).coerceIn(0f, 1f)
                )
            )
        }

        if (showLicenses) {
        OverlayDialog(
            show = true,
            title = "开源许可",
            summary = null,
            onDismissRequest = { showLicenses = false }
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                LicenseLine("miuix", "Apache-2.0 · MIUI / HyperOS 设计系统")
                LicenseLine("ZXing", "Apache-2.0 · 二维码解码")
                LicenseLine("OkHttp", "Apache-2.0 · 网络请求")
                LicenseLine("Jetpack Compose", "Apache-2.0 · 声明式 UI")
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "完整许可证文本见各项目仓库。",
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
        }
    }
    }
}

@Composable
private fun LicenseLine(name: String, desc: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = name, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurface)
        Text(
            text = desc,
            fontSize = 11.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
    }
}

private fun openLink(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure {
        Toast.makeText(context, "没有可用的浏览器", Toast.LENGTH_SHORT).show()
    }
}
