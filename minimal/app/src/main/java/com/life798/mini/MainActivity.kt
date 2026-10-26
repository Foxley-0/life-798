package com.life798.mini

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.life798.mini.ui.AppViewModel
import com.life798.mini.ui.LogScreen
import com.life798.mini.ui.LoginScreen
import com.life798.mini.ui.MiniTheme
import com.life798.mini.ui.ScanScreen
import com.life798.mini.ui.SettingsScreen
import com.life798.mini.ui.StartScreen
import com.life798.mini.ui.UiState
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Scan
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme

private enum class Tab { SCAN, SETTINGS }

/** 页面层级：TABS 是底部导航，START / LOG 是二级页。 */
private enum class Layer { TABS, START, LOG }

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MiniTheme {
                val vm: AppViewModel = viewModel()
                val state by vm.ui.collectAsState()

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .safeDrawingPadding()
                        .background(MiuixTheme.colorScheme.background)
                ) {
                    AnimatedContent(
                        targetState = when {
                            !state.ready -> Phase.LOADING
                            !state.loggedIn -> Phase.LOGIN
                            else -> Phase.MAIN
                        },
                        transitionSpec = {
                            // 登录态切换：淡入 + 轻微上浮，接近 MIUI 的「内容浮现」感
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
                            Phase.MAIN -> MainContent(state = state, vm = vm)
                        }
                    }
                }
            }
        }
    }
}

private enum class Phase { LOADING, LOGIN, MAIN }

@Composable
private fun MainContent(state: UiState, vm: AppViewModel) {
    var tab by rememberSaveable { mutableStateOf(Tab.SCAN.name) }
    var layer by rememberSaveable { mutableStateOf(Layer.TABS.name) }

    AnimatedContent(
        targetState = layer,
        transitionSpec = {
            // 二级页：进入从右滑入、返回向右滑出，旧页轻微位移做层次感
            val entering = targetState != Layer.TABS.name
            if (entering) {
                (slideInHorizontally(tween(280)) { it } + fadeIn(tween(180))) togetherWith
                    (slideOutHorizontally(tween(280)) { -it / 5 } + fadeOut(tween(180)))
            } else {
                (slideInHorizontally(tween(280)) { -it / 5 } + fadeIn(tween(180))) togetherWith
                    (slideOutHorizontally(tween(280)) { it } + fadeOut(tween(180)))
            }
        },
        label = "layer"
    ) { current ->
        when (current) {
            Layer.START.name -> StartScreen(
                state = state,
                onBack = {
                    vm.clearDevice()
                    layer = Layer.TABS.name
                },
                onStart = vm::startDevice
            )

            Layer.LOG.name -> LogScreen(onBack = { layer = Layer.TABS.name })

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
                                onDecoded = vm::onQrDecoded,
                                onScanned = { layer = Layer.START.name }
                            )

                            else -> SettingsScreen(
                                state = state,
                                onOpenLog = { layer = Layer.LOG.name },
                                onLogout = vm::logout
                            )
                        }
                    }
                }

                NavigationBar {
                    NavigationBarItem(
                        selected = tab == Tab.SCAN.name,
                        onClick = { tab = Tab.SCAN.name },
                        icon = MiuixIcons.Scan,
                        label = "扫描"
                    )
                    NavigationBarItem(
                        selected = tab == Tab.SETTINGS.name,
                        onClick = { tab = Tab.SETTINGS.name },
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
