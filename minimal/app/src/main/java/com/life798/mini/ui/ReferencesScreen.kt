package com.life798.mini.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.life798.mini.R
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * 开源许可页。
 *
 * 财小通那版用 AboutLibraries 从 Gradle 依赖自动生成，但那要额外加一个
 * 插件和运行时库。这里直接硬编码项目列表 —— 依赖就这几个，不会经常变。
 */
@Composable
fun ReferencesScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxSize()) {
        SmallTopAppBar(
            title = "开源许可",
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = MiuixIcons.Back,
                        contentDescription = "返回",
                        tint = MiuixTheme.colorScheme.onSurface,
                    )
                }
            }
        )

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .overScrollVertical(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "本应用建立在以下开源项目之上，感谢它们的作者。",
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                        fontSize = MiuixTheme.textStyles.body2.fontSize,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }

            item {
                SectionTitle("开源项目")
                Card(modifier = Modifier.fillMaxWidth()) {
                    REFERENCE_PROJECTS.forEach { project ->
                        BasicComponent(
                            title = project.name,
                            summary = project.desc,
                            onClick = { context.openLink(project.url) },
                        )
                    }
                }
            }
        }
    }
}

private data class ReferenceProject(val name: String, val desc: String, val url: String)

private val REFERENCE_PROJECTS = listOf(
    ReferenceProject(
        name = "Jetpack Compose",
        desc = "UI 框架 · Apache-2.0",
        url = "https://developer.android.com/compose",
    ),
    ReferenceProject(
        name = "miuix",
        desc = "MIUI 风格组件库 · Apache-2.0",
        url = "https://github.com/miuix-kotlin-multiplatform/miuix",
    ),
    ReferenceProject(
        name = "CameraX",
        desc = "相机预览 · Apache-2.0",
        url = "https://developer.android.com/media/camera/camerax",
    ),
    ReferenceProject(
        name = "ML Kit Barcode Scanning",
        desc = "二维码识别 · Google SDK",
        url = "https://developers.google.com/ml-kit/vision/barcode-scanning",
    ),
    ReferenceProject(
        name = "OkHttp",
        desc = "网络请求 · Apache-2.0",
        url = "https://square.github.io/okhttp/",
    ),
    ReferenceProject(
        name = "Kotlin Coroutines",
        desc = "异步 · Apache-2.0",
        url = "https://kotlinlang.org/docs/coroutines-overview.html",
    ),
)

@Composable
private fun SectionTitle(title: String) {
    SmallTitle(
        text = title,
        modifier = Modifier.padding(top = 4.dp),
        insideMargin = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
    )
}

private fun Context.openLink(url: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, R.string.link_open_failed, Toast.LENGTH_SHORT).show()
    }
}
