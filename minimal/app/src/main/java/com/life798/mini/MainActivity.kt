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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.life798.mini.core.AppLog
import com.life798.mini.core.UiPrefs
import com.life798.mini.ui.AboutScreen
import com.life798.mini.ui.AppViewModel
import com.life798.mini.ui.AppearanceScreen
import com.life798.mini.ui.LogScreen
import com.life798.mini.ui.LoginScreen
import com.life798.mini.ui.MiniTheme
import com.life798.mini.ui.ScanScreen
import com.life798.mini.ui.SettingsScreen
import com.life798.mini.ui.StartScreen
import com.life798.mini.ui.THEME_DARK
import com.life798.mini.ui.THEME_LIGHT
import com.life798.mini.ui.UiState
import com.life798.mini.ui.components.BarBackdropContent
import com.life798.mini.ui.components.BarBlurHost
import com.life798.mini.ui.components.barBlurBackground
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
 * 返回键只做一件事：出栈。所有页面都不再自己挂 BackHandler ——
 * 上一版每页各自拦截、根层没人接管，扫码页按返回会直接退出应用。
 */
private enum class Route { TABS, START, LOG, ABOUT, THEME }

private val RouteStackSaver = listSaver<List<String>, String>(
    save = { it },
    restore = { it }
)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val vm: AppViewModel = viewModel()
            val state by vm.ui.collectAsState()
            val prefs = remember { UiPrefs(this) }

            var themeMode by rememberSaveable { mutableIntStateOf(prefs.themeMode) }
            var accentMode by rememberSaveable { mutableIntStateOf(prefs.accentMode) }

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
    var confirmExitStart by remember { mutableStateOf(false) }

    fun push(route: Route) {
        AppLog.i("Nav", "push ${route.name}")
        stack = stack + route.name
    }

    /** 出栈；返回 true 表示这次返回键已被消费。 */
    fun pop(): Boolean {
        if (stack.isEmpty()) return false
        val top = stack.last()
        AppLog.i("Nav", "pop $top")
        stack = stack.dropLast(1)
        // 离开启动页即作废设备编号，必须重新扫码
        if (top == Route.START.name) vm.clearDevice()
        return true
    }

    /** 返回按钮与系统返回键走同一条路：启动页先弹确认，其余直接出栈。 */
    fun requestBack() {
        AppLog.i("Nav", "back stack=$stack tab=$tab")
        if (stack.lastOrNull() == Route.START.name) {
            confirmExitStart = true
        } else {
            pop()
        }
    }

    // 全局唯一的返回入口：先出栈 → 再回扫码 tab → 最后才是退出应用
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

    // 外层 Scaffold 只做一件事：挂载 MiuixPopupHost，让 OverlayDialog 有地方渲染。
    // 布局与 inset 都交给内层 —— 两层都设 0，谁也不偷谁的。
    Scaffold(
        containerColor = MiuixTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { _ ->
        BarBlurHost(bottomBarBlurEnabled = true) {
            MainScaffold(
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

@Composable
private fun MainScaffold(
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
    // 底栏只在「已登录 + 停在 TABS 根」时出现；登录页、加载页、二级页都不该有
    val isTabs = current == null && state.ready && state.loggedIn

    Scaffold(
        containerColor = MiuixTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            // 底栏进 slot：不被父级 padding 限制，miuix NavigationBar 自己会补
            // navigationBars 的底部高度，背景于是铺到屏幕最底（小白条沉浸）。
            AnimatedVisibility(
                visible = isTabs,
                enter = slideInVertically(tween(260)) { it } + fadeIn(tween(180)),
                exit = slideOutVertically(tween(200)) { it } + fadeOut(tween(140))
            ) {
                NavigationBar(
                    modifier = Modifier.barBlurBackground(RectangleShape),
                    color = Color.Transparent,
                    showDivider = true
                ) {
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
    ) { padding ->
        val bottomInset = padding.calculateBottomPadding()

        // 内容层标记为「会被底栏采样」——滚动时内容在栏下虚化，而不是栏自己糊自己
        BarBackdropContent(modifier = Modifier.fillMaxSize()) {
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
                        onTabChange = onTabChange,
                        current = current,
                        bottomInset = bottomInset,
                        onPush = onPush,
                        onBack = onBack,
                        themeMode = themeMode,
                        accentMode = accentMode,
                        onThemeMode = onThemeMode,
                        onAccentMode = onAccentMode
                    )
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
    current: String?,
    bottomInset: Dp,
    onPush: (Route) -> Unit,
    onBack: () -> Unit,
    themeMode: Int,
    accentMode: Int,
    onThemeMode: (Int) -> Unit,
    onAccentMode: (Int) -> Unit
) {
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

            else -> AnimatedContent(
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
                        bottomInset = bottomInset,
                        onOpenAppearance = { onPush(Route.THEME) },
                        onOpenLog = { onPush(Route.LOG) },
                        onOpenAbout = { onPush(Route.ABOUT) },
                        onLogout = vm::logout
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
