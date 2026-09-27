package com.sandman.doppler

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.sandman.doppler.api.DopplerLocalApi
import com.sandman.doppler.repository.DopplerRepository
import com.sandman.doppler.security.TokenStore
import com.sandman.doppler.ui.screens.*
import com.sandman.doppler.ui.theme.Cyan400
import com.sandman.doppler.ui.theme.SandmanDopplerTheme
import com.sandman.doppler.ui.theme.Slate800
import com.sandman.doppler.ui.theme.Slate900
import com.sandman.doppler.viewmodel.DopplerViewModel

class MainActivity : ComponentActivity() {

    private lateinit var tokenStore: TokenStore
    private lateinit var localApi: DopplerLocalApi
    private lateinit var repository: DopplerRepository
    private lateinit var viewModel: DopplerViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tokenStore = TokenStore(applicationContext)
        localApi = DopplerLocalApi(
            host = tokenStore.savedIpAddress,
            port = tokenStore.savedPort,
            dsn = tokenStore.savedDsn ?: "Doppler-00000000",
            localKey = tokenStore.authToken ?: ""
        )
        repository = DopplerRepository(localApi)
        viewModel = DopplerViewModel(repository)

        setContent {
            SandmanDopplerTheme {
                var selectedTab by remember { mutableStateOf(0) }

                Scaffold(
                    bottomBar = {
                        NavigationBar(
                            containerColor = Slate900,
                            contentColor = Cyan400
                        ) {
                            NavigationBarItem(
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 },
                                icon = { Icon(Icons.Default.Dashboard, contentDescription = "Dashboard") },
                                label = { Text("Dashboard") }
                            )
                            NavigationBarItem(
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 },
                                icon = { Icon(Icons.Default.Lightbulb, contentDescription = "Lighting") },
                                label = { Text("Lighting") }
                            )
                            NavigationBarItem(
                                selected = selectedTab == 2,
                                onClick = { selectedTab = 2 },
                                icon = { Icon(Icons.Default.Alarm, contentDescription = "Alarms") },
                                label = { Text("Alarms") }
                            )
                            NavigationBarItem(
                                selected = selectedTab == 3,
                                onClick = { selectedTab = 3 },
                                icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                                label = { Text("Settings") }
                            )
                            NavigationBarItem(
                                selected = selectedTab == 4,
                                onClick = { selectedTab = 4 },
                                icon = { Icon(Icons.Default.Info, contentDescription = "Diagnostics") },
                                label = { Text("Diagnostics") }
                            )
                        }
                    }
                ) { innerPadding ->
                    androidx.compose.foundation.layout.Box(modifier = Modifier.padding(innerPadding)) {
                        when (selectedTab) {
                            0 -> DashboardScreen(viewModel)
                            1 -> DisplayLightingScreen(viewModel)
                            2 -> AlarmsScreen(viewModel)
                            3 -> SettingsScreen(
                                viewModel = viewModel,
                                tokenStore = tokenStore,
                                onReconnect = {
                                    localApi = DopplerLocalApi(
                                        host = tokenStore.savedIpAddress,
                                        port = tokenStore.savedPort,
                                        dsn = tokenStore.savedDsn ?: "Doppler-00000000",
                                        localKey = tokenStore.authToken ?: ""
                                    )
                                    repository = DopplerRepository(localApi)
                                    viewModel = DopplerViewModel(repository)
                                }
                            )
                            4 -> DiagnosticsScreen(viewModel)
                        }
                    }
                }
            }
        }
    }
}
