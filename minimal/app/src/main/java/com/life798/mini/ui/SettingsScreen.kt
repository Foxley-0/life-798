package com.life798.mini.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.life798.mini.core.Platform
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * 设置页：两个平台的账号 + 通用入口。
 *
 * 账号区是两张并列的卡片，各自独立显示状态、独立操作：
 * - 已登录 → 退出登录
 * - 未登录 / 已失效 → 登录（进入添加账号页，不回首登页）
 */
@Composable
fun SettingsScreen(
    state: UiState,
    bottomInset: Dp,
    onOpenAppearance: () -> Unit,
    onOpenAbout: () -> Unit,
    onAddAccount: (Platform) -> Unit,
    onLogout: (Platform) -> Unit,
) {
    var logoutTarget by remember { mutableStateOf<Platform?>(null) }
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = "设置",
            largeTitle = "设置",
            color = Color.Transparent,
            scrollBehavior = scrollBehavior
        )

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 4.dp,
                bottom = bottomInset + 20.dp
            )
        ) {
            item {
                SmallTitle(text = "账号", modifier = Modifier.fillMaxWidth())
                Card(modifier = Modifier.fillMaxWidth()) {
                    AccountCard(
                        account = state.account(Platform.HUI798),
                        onLogin = { onAddAccount(Platform.HUI798) },
                        onLogout = { logoutTarget = Platform.HUI798 },
                    )
                    HorizontalDivider()
                    AccountCard(
                        account = state.account(Platform.PANGGUAI),
                        onLogin = { onAddAccount(Platform.PANGGUAI) },
                        onLogout = { logoutTarget = Platform.PANGGUAI },
                    )
                }

                Spacer(Modifier.height(6.dp))
                Text(
                    text = "两个账号独立登录、独立存储，互不影响。",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary,
                )

                Spacer(Modifier.height(16.dp))

                SmallTitle(text = "通用", modifier = Modifier.fillMaxWidth())
                Card(modifier = Modifier.fillMaxWidth()) {
                    ArrowPreference(
                        title = "外观",
                        summary = "深浅模式、配色与底栏样式",
                        onClick = onOpenAppearance
                    )
                    ArrowPreference(
                        title = "关于",
                        summary = "版本信息与开源许可",
                        onClick = onOpenAbout
                    )
                }
            }
        }

        // 退出确认：只退当前选中的平台，文案里带平台名，避免误以为两个都退
        val target = logoutTarget
        if (target != null) {
            OverlayDialog(
                show = true,
                title = "确定退出${target.label}吗？",
                summary = if (target == Platform.HUI798) {
                    "退出后 798 的设备编号会被清除，需要重新登录；另一个平台的账号不受影响。"
                } else {
                    "只退出胖乖生活，798 的登录状态与数据不受影响。"
                },
                onDismissRequest = { logoutTarget = null }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TextButton(
                        text = "取消",
                        onClick = { logoutTarget = null },
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            logoutTarget = null
                            onLogout(target)
                        },
                        colors = ButtonDefaults.buttonColors(
                            color = MiuixTheme.colorScheme.error,
                            contentColor = MiuixTheme.colorScheme.onError
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("确定")
                    }
                }
            }
        }
    }
}
