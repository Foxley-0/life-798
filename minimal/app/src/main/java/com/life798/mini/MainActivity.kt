package com.life798.mini

import android.app.Activity
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.life798.mini.core.UiPrefs
import com.life798.mini.ui.ACCENT_MIUI
import com.life798.mini.ui.AppearanceScreen
import com.life798.mini.ui.AppViewModel
import com.life798.mini.ui.AboutScreen
import com.life798.mini.ui.LogScreen
import com.life798.mini.ui.LoginScreen
import com.life798.mini.ui.MiniTheme
import com.life798.mini.ui.ScanScreen
import com.life798.mini.ui.SettingsScreen
import com.life798.mini.ui.StartScreen
import com.life798.mini.ui.THEME_LIGHT
import com.life798.mini.ui.THEME_SYSTEM
import com.life798.mini.ui.THEME_DARK
import com.life798.mini.ui.UiState
import com.life798.mini.ui.isDarkTheme
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Scan
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

private enum class Tab { SCAN, SETTINGS }

/**
 * 路由。TABS 是底部导航的根，其余都是叠在它上面的二级页。
 *
 * 用「显式栈」而不是一堆布尔量：返回键永远只做一件事——出栈。
 * 之前每页各自 `BackHandler` 的做法，根层没人接管，扫码页按返回会直接退出应用。
 */
private enum class Route { TABS, START, LOG, ABOUT, THEME }

/** 栈里存名字，方便 rememberSaveable 直接序列化。 */
private val RouteStackSaver = listSaver<List<String>, String>(
    save = { it },
    restore = { it }
)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 先铺一次，避免首帧状态栏是系统默认色
        enableEdgeToEdge()

        setContent {
            val vm: AppViewModel = viewModel()
            val state by vm.ui.collectAsState()
            val prefs = remember { UiPrefs(this) }

            var themeMode by rememberSaveable { mutableIntStateOf(prefs.themeMode) }
            var accentMode by rememberSaveable { mutableIntStateOf(prefs.accentMode) }

            // 状态栏 / 导航栏图标颜色跟着「实际」深浅走，而不是系统的
            val dark = isDarkTheme(themeMode)
            LaunchedEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(
                        AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT
                    ) { dark },
                    navigationBarStyle = SystemBarStyle.auto(
                        AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT
                    ) { dark }
                )
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    window.isNavigationBarContrastEnforced = false
                }
            }

            MiniTheme(themeMode = themeMode, accentMode = accentMode) {
                MiniApp(
                    state = state,
                    vm = vm,
                    themeMode = themeMode,
                    accentMode = accentMode,
                    onThemeMode = {
                        themeMode = it
                        prefs.themeMode = it
                    },
                    onAccentMode = {
                        accentMode = it
                        prefs.accentMode = it
                    }
                )
            }
        }
    }
}

private enum class Phase { LOADING, LOGIN, MAIN }

@Composable
private fun MiniApp(
    state: UiState,
    vm: AppViewModel,
    themeMode: Int,
    accentMode: Int,
    onThemeMode: (Int) -> Unit,
    onAccentMode: (Int) -> Unit
) {
    val context = LocalContext.current

    var tab by rememberSaveable { mutableStateOf(Tab.SCAN.name) }
    var stack by rememberSaveable(stateSaver = RouteStackSaver) {
        mutableStateOf(emptyList<String>())
    }
    var lastBackAt by remember { mutableLongStateOf(0L) }
    // 离开启动页要清设备号，属于不可撤销动作，先确认
    var confirmExitStart by remember { mutableStateOf(false) }

    fun push(route: Route) {
        stack = stack + route.name
    }

    /** 出栈；返回 true 表示这次返回键已被消费。 */
    fun pop(): Boolean {
        if (stack.isEmpty()) return false
        val top = stack.last()
        stack = stack.dropLast(1)
        // 离开启动页即作废设备编号，必须重新扫码 —— 统一在这里处理，
        // 不再散落在各个回调里
        if (top == Route.START.name) vm.clearDevice()
        return true
    }

    /** 返回按钮与系统返回键走同一条路：启动页先弹确认，其余直接出栈。 */
    fun requestBack() {
        if (stack.lastOrNull() == Route.START.name) {
            confirmExitStart = true
        } else {
            pop()
        }
    }

    // 全局唯一的返回入口。顺序：先出栈 → 再回扫码 tab → 最后才是退出应用
    BackHandler {
        when {
            stack.isNotEmpty() -> requestBack()
            tab != Tab.SCAN.name -> tab = Tab.SCAN.name
            else -> {
                val now = System.currentTimeMillis()
                if (now - lastBackAt < 2000L) {
                    (context as? Activity)?.finish()
                } else {
                    lastBackAt = now
                    Toast.makeText(context, "再按一次退出", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Scaffold 不只是布局：它同时挂载 MiuixPopupHost，OverlayDialog 才有地方渲染。
    // 之前没有 Scaffold，所有确认弹窗都被静默丢弃 —— 点返回/退出登录毫无反应。
    Scaffold(
        containerColor = MiuixTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { _ ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
        ) {
            AnimatedContent(
                targetState = when {
                    !state.ready -> Phase.LOADING
                    !state.loggedIn -> Phase.LOGIN
                    else -> Phase.MAIN
                },
                transitionSpec = {
                    (fadeIn(tween(260)) + slideInVertically(tween(260)) { it / 28 }) togetherWith
                        fadeOut(tween(180))
                },
                label = "phase"
            ) { phase ->
                when (phase) {
                    Phase.LOADING -> Loading()
                    Phase.LOGIN -> LoginScreen(
                        state = state,
                        onRefreshCaptcha = vm::refreshCaptcha,
                        onSendSms = vm::sendSms,
                        onLogin = vm::login
                    )
                    Phase.MAIN -> MainContent(
                        state = state,
                        vm = vm,
                        tab = tab,
                        onTabChange = { tab = it },
                        stack = stack,
                        onPush = ::push,
                        onBack = { requestBack() },
                        themeMode = themeMode,
                        accentMode = accentMode,
                        onThemeMode = onThemeMode,
                        onAccentMode = onAccentMode
                    )
                }
            }

            if (confirmExitStart) {
                OverlayDialog(
                    show = true,
                    title = "确定返回吗？",
                    summary = "返回后设备编号会被清除，需要重新扫描二维码。",
                    onDismissRequest = { confirmExitStart = false }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        TextButton(
                            text = "取消",
                            onClick = { confirmExitStart = false },
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                confirmExitStart = false
                                pop()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("确定")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MainContent(
    state: UiState,
    vm: AppViewModel,
    tab: String,
    onTabChange: (String) -> Unit,
    stack: List<String>,
    onPush: (Route) -> Unit,
    onBack: () -> Unit,
    themeMode: Int,
    accentMode: Int,
    onThemeMode: (Int) -> Unit,
    onAccentMode: (Int) -> Unit
) {
    val current = stack.lastOrNull()

    AnimatedContent(
        targetState = current ?: Route.TABS.name,
        transitionSpec = {
            val entering = targetState != Route.TABS.name
            if (entering) {
                (slideInHorizontally(tween(280)) { it } + fadeIn(tween(180))) togetherWith
                    (slideOutHorizontally(tween(280)) { -it / 5 } + fadeOut(tween(180)))
            } else {
                (slideInHorizontally(tween(280)) { -it / 5 } + fadeIn(tween(180))) togetherWith
                    (slideOutHorizontally(tween(280)) { it } + fadeOut(tween(180)))
            }
        },
        label = "layer"
    ) { route ->
        when (route) {
            Route.START.name -> StartScreen(
                state = state,
                onBack = onBack,
                onStart = vm::startDevice
            )

            Route.LOG.name -> LogScreen(onBack = onBack)

            Route.ABOUT.name -> AboutScreen(
                onBack = onBack,
                onOpenLog = { onPush(Route.LOG) }
            )

            Route.THEME.name -> AppearanceScreen(
                themeMode = themeMode,
                accentMode = accentMode,
                onThemeMode = onThemeMode,
                onAccentMode = onAccentMode,
                onBack = onBack
            )

            else -> Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f)) {
                    AnimatedContent(
                        targetState = tab,
                        transitionSpec = {
                            fadeIn(tween(200)) togetherWith fadeOut(tween(150))
                        },
                        label = "tab"
                    ) { currentTab ->
                        when (currentTab) {
                            Tab.SCAN.name -> ScanScreen(
                                state = state,
                                onEnter = vm::clearMessage,
                                onDecoded = vm::onQrDecoded,
                                onScanned = { onPush(Route.START) }
                            )

                            else -> SettingsScreen(
                                state = state,
                                onOpenAppearance = { onPush(Route.THEME) },
                                onOpenLog = { onPush(Route.LOG) },
                                onOpenAbout = { onPush(Route.ABOUT) },
                                onLogout = vm::logout
                            )
                        }
                    }
                }

                NavigationBar {
                    NavigationBarItem(
                        selected = tab == Tab.SCAN.name,
                        onClick = { onTabChange(Tab.SCAN.name) },
                        icon = MiuixIcons.Scan,
                        label = "扫描"
                    )
                    NavigationBarItem(
                        selected = tab == Tab.SETTINGS.name,
                        onClick = { onTabChange(Tab.SETTINGS.name) },
                        icon = MiuixIcons.Settings,
                        label = "设置"
                    )
                }
            }
        }
    }
}

@Composable
private fun Loading() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}
