package com.sandman.doppler

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stars
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.sandman.doppler.api.CopilotCloudAuthClient
import com.sandman.doppler.api.DopplerCloudApi
import com.sandman.doppler.api.DopplerLocalApi
import com.sandman.doppler.repository.DopplerRepository
import com.sandman.doppler.security.TokenStore
import com.sandman.doppler.storage.WeatherPlaceStore
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
    private lateinit var weatherPlaceStore: WeatherPlaceStore

    private fun buildApi(store: TokenStore): DopplerLocalApi {
        val dsn = store.savedDsn ?: "Doppler-00000000"
        return if (!store.cloudAccessToken.isNullOrBlank()) {
            DopplerCloudApi(
                dsn = dsn,
                cloudAccessToken = store.cloudAccessToken!!,
                refreshTokenProvider = {
                    val refresh = store.cloudRefreshToken
                    if (refresh.isNullOrBlank()) return@DopplerCloudApi null
                    val result = CopilotCloudAuthClient().refreshAccessToken(refresh)
                    if (result.isSuccess) {
                        val fresh = result.getOrNull()?.accessToken
                        if (!fresh.isNullOrBlank()) store.cloudAccessToken = fresh
                        store.cloudRefreshToken = result.getOrNull()?.refreshToken
                        fresh
                    } else null
                }
            )
        } else {
            DopplerLocalApi(
                host = store.savedIpAddress,
                port = store.savedPort,
                dsn = dsn,
                localKey = store.authToken ?: ""
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tokenStore = TokenStore(applicationContext)
        weatherPlaceStore = WeatherPlaceStore(applicationContext)
        // Prefer the cloud control plane when a Copilot cloud token is available;
        // fall back to the raw LAN daemon otherwise (e.g. manual provisioning).
        localApi = buildApi(tokenStore)
        repository = DopplerRepository(localApi)
        viewModel = DopplerViewModel(repository, placeStore = weatherPlaceStore)

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
                                icon = { Icon(Icons.Default.Dashboard, contentDescription = stringResource(R.string.dashboard)) },
                                label = { Text(stringResource(R.string.dashboard)) }
                            )
                            NavigationBarItem(
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 },
                                icon = { Icon(Icons.Default.Lightbulb, contentDescription = stringResource(R.string.lighting)) },
                                label = { Text(stringResource(R.string.lighting)) }
                            )
                            NavigationBarItem(
                                selected = selectedTab == 2,
                                onClick = { selectedTab = 2 },
                                icon = { Icon(Icons.Default.Alarm, contentDescription = stringResource(R.string.alarms)) },
                                label = { Text(stringResource(R.string.alarms)) }
                            )
                            NavigationBarItem(
                                selected = selectedTab == 3,
                                onClick = { selectedTab = 3 },
                                icon = { Icon(Icons.Default.Stars, contentDescription = stringResource(R.string.lightbar)) },
                                label = { Text(stringResource(R.string.lightbar)) }
                            )
                            NavigationBarItem(
                                selected = selectedTab == 4,
                                onClick = { selectedTab = 4 },
                                icon = { Icon(Icons.Default.Cloud, contentDescription = stringResource(R.string.weather)) },
                                label = { Text(stringResource(R.string.weather)) }
                            )
                            NavigationBarItem(
                                selected = selectedTab == 5,
                                onClick = { selectedTab = 5 },
                                icon = { Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings)) },
                                label = { Text(stringResource(R.string.settings)) }
                            )
                            NavigationBarItem(
                                selected = selectedTab == 6,
                                onClick = { selectedTab = 6 },
                                icon = { Icon(Icons.Default.Info, contentDescription = stringResource(R.string.info)) },
                                label = { Text(stringResource(R.string.info)) }
                            )
                        }
                    }
                ) { innerPadding ->
                    androidx.compose.foundation.layout.Box(modifier = Modifier.padding(innerPadding)) {
                        when (selectedTab) {
                            0 -> DashboardScreen(viewModel)
                            1 -> DisplayLightingScreen(viewModel)
                            2 -> AlarmsScreen(viewModel)
                            3 -> LightBarScreen(viewModel)
                            4 -> WeatherScreen(viewModel)
                            5 -> SettingsScreen(
                                viewModel = viewModel,
                                tokenStore = tokenStore,
                                onReconnect = {
                                    localApi = buildApi(tokenStore)
                                    repository = DopplerRepository(localApi)
                                    viewModel = DopplerViewModel(
                                        repository,
                                        placeStore = weatherPlaceStore
                                    )
                                }
                            )
                            6 -> DiagnosticsScreen(viewModel)
                        }
                    }
                }
            }
        }
    }
}