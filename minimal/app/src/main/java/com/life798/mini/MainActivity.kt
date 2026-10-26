package com.life798.mini

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.life798.mini.ui.AppViewModel
import com.life798.mini.ui.LogScreen
import com.life798.mini.ui.LoginScreen
import com.life798.mini.ui.MiniTheme
import com.life798.mini.ui.ScanScreen
import com.life798.mini.ui.SettingsScreen
import com.life798.mini.ui.UiState
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.theme.MiuixTheme

private enum class Tab { SCAN, SETTINGS }

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
                    when {
                        !state.ready -> Loading()

                        !state.loggedIn -> LoginScreen(
                            state = state,
                            onRefreshCaptcha = vm::refreshCaptcha,
                            onSendSms = vm::sendSms,
                            onLogin = vm::login
                        )

                        else -> MainTabs(state = state, vm = vm)
                    }
                }
            }
        }
    }
}

@Composable
private fun MainTabs(state: UiState, vm: AppViewModel) {
    var tab by rememberSaveable { mutableStateOf(Tab.SCAN.name) }
    var showLog by rememberSaveable { mutableStateOf(false) }

    if (showLog) {
        LogScreen(onBack = { showLog = false })
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            when (tab) {
                Tab.SCAN.name -> ScanScreen(
                    state = state,
                    onDecoded = vm::onQrDecoded,
                    onStart = vm::startDevice
                )

                else -> SettingsScreen(
                    state = state,
                    onOpenLog = { showLog = true },
                    onLogout = vm::logout
                )
            }
        }

        NavigationBar {
            NavigationBarItem(
                selected = tab == Tab.SCAN.name,
                onClick = { tab = Tab.SCAN.name },
                icon = ImageVector.vectorResource(R.drawable.ic_scan),
                label = "扫描"
            )
            NavigationBarItem(
                selected = tab == Tab.SETTINGS.name,
                onClick = { tab = Tab.SETTINGS.name },
                icon = ImageVector.vectorResource(R.drawable.ic_settings),
                label = "设置"
            )
        }
    }
}

@Composable
private fun Loading() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}
