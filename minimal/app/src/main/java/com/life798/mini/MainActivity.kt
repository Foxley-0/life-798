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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.zIndex
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
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem
import top.yukonga.miuix.kmp.basic.FloatingToolbarDefaults
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
 * 返回键只做一件事：出栈。所有页面都不再自己挂 BackHandler。
 */
private enum class Route { TABS, START, LOG, ABOUT, THEME }

private val RouteStackSaver = listSaver<List<String>, String>(
    save = { it },
    restore = { it }
)

private const val MAX_STACK_DEPTH = 5

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
            var bottomBarStyle by rememberSaveable { mutableIntStateOf(prefs.bottomBarStyle) }

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
                    bottomBarStyle = bottomBarStyle,
                    onThemeMode = {
                        themeMode = it
                        prefs.themeMode = it
                    },
                    onAccentMode = {
                        accentMode = it
                        prefs.accentMode = it
                    },
                    onBottomBarStyle = {
                        bottomBarStyle = it
                        prefs.bottomBarStyle = it
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
    bottomBarStyle: Int,
    onThemeMode: (Int) -> Unit,
    onAccentMode: (Int) -> Unit,
    onBottomBarStyle: (Int) -> Unit
) {
    val context = LocalContext.current

    var tab by rememberSaveable { mutableStateOf(Tab.SCAN.name) }
    var stack by rememberSaveable(stateSaver = RouteStackSaver) {
        mutableStateOf(emptyList<String>())
    }
    var lastBackAt by remember { mutableLongStateOf(0L) }
    var confirmExitStart by remember { mutableStateOf(false) }

    // 登录态失效（主动登出 / token 过期）时把导航整体归零。
    // 否则重新登录会直接落在上一次停留的页面，甚至卡在一个没有设备号的启动页。
    LaunchedEffect(state.loggedIn) {
        if (!state.loggedIn) {
            if (stack.isNotEmpty() || tab != Tab.SCAN.name) {
                AppLog.i("Nav", "登录态失效，重置导航 stack=$stack tab=$tab")
            }
            stack = emptyList()
            tab = Tab.SCAN.name
        }
    }

    // 栈里留着 START 但没有设备号（进程重建后设备号不落盘）→ 自动退回扫码页
    LaunchedEffect(stack, state.deviceId, state.loggedIn) {
        if (state.loggedIn &&
            stack.lastOrNull() == Route.START.name &&
            state.deviceId.isBlank()
        ) {
            AppLog.i("Nav", "启动页缺少设备号，自动出栈")
            stack = stack.dropLast(1)
        }
    }

    fun push(route: Route) {
        if (stack.lastOrNull() == route.name) {
            AppLog.i("Nav", "忽略重复入栈 ${route.name}")
            return
        }
        if (stack.size >= MAX_STACK_DEPTH) {
            AppLog.w("Nav", "栈深已达上限，忽略 ${route.name}")
            return
        }
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
                bottomBarStyle = bottomBarStyle,
                onThemeMode = onThemeMode,
                onAccentMode = onAccentMode,
                onBottomBarStyle = onBottomBarStyle
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
    bottomBarStyle: Int,
    onThemeMode: (Int) -> Unit,
    onAccentMode: (Int) -> Unit,
    onBottomBarStyle: (Int) -> Unit
) {
    val current = stack.lastOrNull()
    // 底栏只在「已登录 + 停在 TABS 根」时出现；登录页、加载页、二级页都不该有
    val isTabs = current == null && state.ready && state.loggedIn

    Scaffold(
        containerColor = MiuixTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            AnimatedVisibility(
                visible = isTabs,
                enter = slideInVertically(tween(260)) { it } + fadeIn(tween(180)),
                exit = slideOutVertically(tween(200)) { it } + fadeOut(tween(140))
            ) {
                BottomBar(
                    tab = tab,
                    onTabChange = onTabChange,
                    style = bottomBarStyle
                )
            }
        }
    ) { padding ->
        val bottomInset = padding.calculateBottomPadding()

        // 内容层标记为「会被底栏采样」——滚动时内容在栏下虚化
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
                        bottomBarStyle = bottomBarStyle,
                        onThemeMode = onThemeMode,
                        onAccentMode = onAccentMode,
                        onBottomBarStyle = onBottomBarStyle
                    )
                }
            }
        }
    }
}

/**
 * 底栏。两种形态共用同一套毛玻璃采样：
 * - 胶囊：`FloatingNavigationBar`，宽度 wrap content + 居中，圆角取 `FloatingToolbarDefaults.CornerRadius`
 * - 长条：`NavigationBar`，全宽 + 顶部细线
 *
 * 关键：`FloatingNavigationBar` 的 modifier 最终作用在内部 Row 上（不是外层 Column），
 * 所以模糊层的 shape 必须是圆角矩形，否则会把胶囊盖成方的。
 */
@Composable
private fun BottomBar(
    tab: String,
    onTabChange: (String) -> Unit,
    style: Int
) {
    if (style == UiPrefs.BOTTOM_BAR_PILL) {
        FloatingNavigationBar(
            modifier = Modifier
                .barBlurBackground(RoundedCornerShape(FloatingToolbarDefaults.CornerRadius))
                .zIndex(2f),
            color = Color.Transparent
        ) {
            FloatingNavigationBarItem(
                selected = tab == Tab.SCAN.name,
                onClick = { onTabChange(Tab.SCAN.name) },
                icon = MiuixIcons.Scan,
                label = "扫描"
            )
            FloatingNavigationBarItem(
                selected = tab == Tab.SETTINGS.name,
                onClick = { onTabChange(Tab.SETTINGS.name) },
                icon = MiuixIcons.Settings,
                label = "设置"
            )
        }
    } else {
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
    bottomBarStyle: Int,
    onThemeMode: (Int) -> Unit,
    onAccentMode: (Int) -> Unit,
    onBottomBarStyle: (Int) -> Unit
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
                bottomBarStyle = bottomBarStyle,
                onThemeMode = onThemeMode,
                onAccentMode = onAccentMode,
                onBottomBarStyle = onBottomBarStyle,
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
