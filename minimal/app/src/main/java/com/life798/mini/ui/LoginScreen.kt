package com.life798.mini.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.life798.mini.core.Platform
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextFieldDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * 验证码那两行右侧的固定宽度（验证码图 / 获取验证码 共用，保证右边缘对齐）。
 *
 * 360dp 屏（内容区 312dp）上：右列 156dp、输入框 148dp，两边基本等宽。
 */
private val CAPTCHA_COLUMN_WIDTH = 156.dp

enum class LoginMode {
    /** 首登：两个平台都能选。 */
    FIRST,

    /** 从设置页添加账号：平台已锁定，带返回。 */
    ADD,
}

/**
 * 登录页。两个平台共用一套版式，差异由 [Platform] 自己声明：
 * - [Platform.needsGraphCaptcha] 决定要不要渲染图形验证码
 * - 表单值、验证码、倒计时全部从 `state.form(platform)` 取，互不干扰
 *
 * @param platform 当前选中的平台
 * @param mode 首登 / 添加账号
 * @param onBack 仅 [LoginMode.ADD] 使用
 */
@Composable
fun LoginScreen(
    state: UiState,
    platform: Platform,
    mode: LoginMode = LoginMode.FIRST,
    onSelectPlatform: (Platform) -> Unit = {},
    onBack: (() -> Unit)? = null,
    onRefreshCaptcha: (Platform) -> Unit,
    onSendSms: (Platform, phone: String, graphCode: String) -> Unit,
    onLogin: (Platform, phone: String, smsCode: String) -> Unit,
    onFormChange: (
        platform: Platform,
        phone: String?,
        graphCode: String?,
        smsCode: String?,
    ) -> Unit,
) {
    val form = state.form(platform)
    val configured = if (platform == Platform.HUI798) state.configured else state.pangguaiConfigured

    val captchaBitmap = remember(form.captchaImage) {
        form.captchaImage?.let { bytes ->
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // 背景交给 miuix 的 background：浅色 #F2F3F5、暗色 #121212 自动切换。
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .overScrollVertical()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
    ) {
        Spacer(Modifier.height(if (mode == LoginMode.ADD) 24.dp else 96.dp))

        if (mode == LoginMode.ADD && onBack != null) {
            TextButton(
                text = "← 返回",
                onClick = onBack,
                insideMargin = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                colors = ButtonDefaults.textButtonColors(
                    color = androidx.compose.ui.graphics.Color.Transparent,
                    textColor = MiuixTheme.colorScheme.primary,
                ),
            )
            Spacer(Modifier.height(12.dp))
        }

        // 大标题当视觉重心，不用 logo
        Text(
            text = if (platform == Platform.HUI798) "登录 798 小助手" else "登录胖乖生活",
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
            color = MiuixTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (platform == Platform.HUI798) {
                "扫码启动用水设备"
            } else {
                "本期仅接入登录，设备功能后续开放"
            },
            fontSize = 15.sp,
            color = MiuixTheme.colorScheme.onSurfaceSecondary
        )
        Spacer(Modifier.height(28.dp))

        // 首登时两个平台可选；添加账号时平台已锁定，不显示切换
        if (mode == LoginMode.FIRST) {
            PlatformSwitcher(
                selected = platform,
                onSelect = onSelectPlatform,
            )
            Spacer(Modifier.height(20.dp))
        }

        // 网关没配就直接挡在门口，别让人白试
        if (!configured) {
            Text(
                text = if (platform == Platform.HUI798) {
                    "未配置 API 网关，无法登录。请检查 secrets.properties 或仓库 Secrets。"
                } else {
                    "未配置胖乖接口地址，无法登录。"
                },
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.error
            )
            Spacer(Modifier.height(16.dp))
        }

        TextField(
            value = form.phone,
            onValueChange = {
                onFormChange(platform, it.filter(Char::isDigit).take(11), null, null)
            },
            label = "手机号",
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            cornerRadius = 16.dp,
            colors = TextFieldDefaults.textFieldColors(
                backgroundColor = MiuixTheme.colorScheme.surfaceContainer
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        )

        // 798 才有的图形验证码
        if (platform.needsGraphCaptcha) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = form.graphCode,
                    onValueChange = {
                        if (it.length <= 6) onFormChange(platform, null, it, null)
                    },
                    label = "图形验证码",
                    singleLine = true,
                    cornerRadius = 16.dp,
                    colors = TextFieldDefaults.textFieldColors(
                        backgroundColor = MiuixTheme.colorScheme.surfaceContainer
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp)
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .width(CAPTCHA_COLUMN_WIDTH)
                        .height(56.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MiuixTheme.colorScheme.surfaceContainer)
                        .clickable { onRefreshCaptcha(platform) },
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        form.captchaLoading -> CircularProgressIndicator(
                            modifier = Modifier.size(18.dp)
                        )

                        captchaBitmap != null -> Image(
                            bitmap = captchaBitmap.asImageBitmap(),
                            contentDescription = "图形验证码，点击刷新",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )

                        else -> Text(
                            text = if (form.captchaError != null) "加载失败\n点此重试" else "点此加载",
                            fontSize = 12.sp,
                            lineHeight = 15.sp,
                            color = MiuixTheme.colorScheme.primary,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = form.smsCode,
                onValueChange = {
                    if (it.length <= 8) {
                        onFormChange(platform, null, null, it.filter(Char::isDigit))
                    }
                },
                label = "短信验证码",
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                cornerRadius = 16.dp,
                colors = TextFieldDefaults.textFieldColors(
                    backgroundColor = MiuixTheme.colorScheme.surfaceContainer
                ),
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
            )
            Spacer(Modifier.width(8.dp))
            TextButton(
                text = if (form.countdown > 0) "${form.countdown}s" else "获取验证码",
                onClick = { onSendSms(platform, form.phone, form.graphCode) },
                enabled = form.countdown == 0 && !form.busy && configured,
                cornerRadius = 16.dp,
                insideMargin = PaddingValues(horizontal = 8.dp),
                colors = ButtonDefaults.textButtonColors(
                    color = MiuixTheme.colorScheme.surfaceContainer,
                    // 倒计时期间只灰字、底色不变，避免整块按钮忽明忽暗
                    disabledColor = MiuixTheme.colorScheme.surfaceContainer,
                    textColor = MiuixTheme.colorScheme.primary,
                ),
                modifier = Modifier
                    .width(CAPTCHA_COLUMN_WIDTH)
                    .height(56.dp)
            )
        }

        Spacer(Modifier.height(20.dp))

        Button(
            onClick = { onLogin(platform, form.phone, form.smsCode) },
            enabled = !form.busy && configured,
            cornerRadius = 26.dp,
            colors = ButtonDefaults.buttonColorsPrimary(),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            if (form.busy) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp))
            } else {
                Text("登 录", fontSize = 17.sp)
            }
        }

        if (form.message != null) {
            Spacer(Modifier.height(14.dp))
            Text(
                text = form.message,
                fontSize = 13.sp,
                color = if (form.messageIsError) {
                    MiuixTheme.colorScheme.error
                } else {
                    MiuixTheme.colorScheme.onSurfaceSecondary
                },
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * 平台切换。首登时两个平台都能点；添加账号时整块不渲染。
 *
 * 选中态用主题色实底，未选中用容器色 —— 两种配色都来自 MiuixTheme，
 * 深浅色模式自动跟随，不写死颜色。
 */
@Composable
private fun PlatformSwitcher(
    selected: Platform,
    onSelect: (Platform) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MiuixTheme.colorScheme.surfaceContainer)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Platform.entries.forEach { platform ->
            val active = platform == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (active) MiuixTheme.colorScheme.primary
                        else androidx.compose.ui.graphics.Color.Transparent
                    )
                    .clickable { onSelect(platform) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = platform.label,
                    fontSize = 15.sp,
                    fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                    color = if (active) {
                        MiuixTheme.colorScheme.onPrimary
                    } else {
                        MiuixTheme.colorScheme.onSurfaceSecondary
                    }
                )
            }
        }
    }
}
