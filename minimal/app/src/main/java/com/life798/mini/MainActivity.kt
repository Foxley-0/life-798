package com.life798.mini

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.life798.mini.ui.AppViewModel
import com.life798.mini.ui.HomeScreen
import com.life798.mini.ui.LogScreen
import com.life798.mini.ui.LoginScreen
import com.life798.mini.ui.MiniTheme
import com.life798.mini.ui.ScanScreen

private enum class Screen { HOME, SCAN, LOG }

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MiniTheme {
                val vm: AppViewModel = viewModel()
                val state by vm.ui.collectAsState()

                var screen by rememberSaveable { mutableStateOf(Screen.HOME.name) }

                // 登录成功后自动回到首页
                LaunchedEffect(state.loggedIn) {
                    if (state.loggedIn) screen = Screen.HOME.name
                }

                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .safeDrawingPadding(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    when {
                        !state.ready -> Loading()

                        !state.loggedIn -> LoginScreen(
                            state = state,
                            onRefreshCaptcha = vm::refreshCaptcha,
                            onSendSms = vm::sendSms,
                            onLogin = vm::login
                        )

                        screen == Screen.SCAN.name -> ScanScreen(
                            state = state,
                            onBack = {
                                // 扫码页的错误提示不要带回首页
                                vm.clearMessage()
                                screen = Screen.HOME.name
                            },
                            onDecoded = { raw ->
                                // 只有解析成功才离开扫码页，失败留在原页并给出提示
                                if (vm.onQrDecoded(raw)) {
                                    screen = Screen.HOME.name
                                }
                            }
                        )

                        screen == Screen.LOG.name -> LogScreen(
                            onBack = { screen = Screen.HOME.name }
                        )

                        else -> HomeScreen(
                            state = state,
                            onScan = { screen = Screen.SCAN.name },
                            onStart = vm::startDevice,
                            onLogout = vm::logout,
                            onClearMessage = vm::clearMessage,
                            onOpenLog = { screen = Screen.LOG.name }
                        )
                    }
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
