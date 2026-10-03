package com.sandman.doppler.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sandman.doppler.model.DopplerColor
import com.sandman.doppler.model.DopplerDisplayDots
import com.sandman.doppler.ui.theme.*
import com.sandman.doppler.viewmodel.DopplerViewModel

@Composable
fun DashboardScreen(viewModel: DopplerViewModel) {
    val state by viewModel.deviceState.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val lastError by viewModel.lastError.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Slate950)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Status & Connectivity Card
        Card(
            colors = CardDefaults.cardColors(containerColor = Slate900),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = state?.name ?: "Sandman Doppler",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                    Text(
                        text = "${state?.ipAddress ?: "192.168.1.100"}:${state?.port ?: 5443} • DSN: ${state?.dsn ?: "Unknown"}",
                        color = Slate400,
                        fontSize = 12.sp
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (state?.online == true) Emerald400 else Rose500)
                    )
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh",
                            tint = if (isRefreshing) Cyan400 else Slate400
                        )
                    }
                }
            }
        }

        // Live Wi-Fi & Ambient Status Badges
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Wi-Fi Health Badge
            Surface(
                color = Slate900,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Wifi,
                        contentDescription = "Wi-Fi",
                        tint = Cyan400,
                        modifier = Modifier.size(20.dp)
                    )
                    Column {
                        Text(
                            text = state?.wifiSsid?.ifBlank { "Doppler LAN" } ?: "Doppler LAN",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                        Text(
                            text = "Signal: ${state?.wifiRssi ?: 0}%",
                            color = Slate400,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            // Day / Night & Ambient Lux Meter Badge
            val isNight = state?.isNightMode ?: false
            Surface(
                color = Slate900,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = if (isNight) Icons.Default.Nightlight else Icons.Default.WbSunny,
                        contentDescription = "Mode",
                        tint = if (isNight) Rose500 else Amber400,
                        modifier = Modifier.size(20.dp)
                    )
                    Column {
                        Text(
                            text = if (isNight) "Night Mode" else "Day Mode",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                        Text(
                            text = "Ambient: ${state?.ambientLightSensorLux ?: 0} lux",
                            color = Slate400,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }

        // Hardware Doppler Clock Face Simulation
        Card(
            colors = CardDefaults.cardColors(containerColor = Color.Black),
            shape = RoundedCornerShape(24.dp),
            border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(Slate800)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 29-LED Light Bar on top
                val activeDisplayColor = if (state?.isNightMode == true) {
                    Color(
                        (state?.nightDisplayColor?.r ?: 255),
                        (state?.nightDisplayColor?.g ?: 40),
                        (state?.nightDisplayColor?.b ?: 40)
                    )
                } else {
                    Color(
                        (state?.dayDisplayColor?.r ?: 0),
                        (state?.dayDisplayColor?.g ?: 220),
                        (state?.dayDisplayColor?.b ?: 255)
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Slate950)
                        .padding(6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    repeat(29) { index ->
                        Box(
                            modifier = Modifier
                                .size(width = 6.dp, height = 10.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(activeDisplayColor.copy(alpha = 0.85f))
                        )
                    }
                }

                // Digital Clock Digits reflecting UTC hour/min or format
                val hour = state?.currentUtcHour ?: 12
                val min = state?.currentUtcMin ?: 0
                val formattedTime = String.format("%02d:%02d", hour, min)

                Text(
                    text = formattedTime,
                    color = activeDisplayColor,
                    fontSize = 58.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 4.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = state?.timezone?.ifBlank { "UTC Time" } ?: "UTC Time",
                        color = Slate400,
                        fontSize = 12.sp
                    )

                    Surface(
                        color = Slate900,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.padding(4.dp)
                    ) {
                        Text(
                            text = "${state?.masterVolume ?: 75}% Vol",
                            color = activeDisplayColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }

        // Master Volume Control
        Card(
            colors = CardDefaults.cardColors(containerColor = Slate900),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Master Volume",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    Text(
                        text = "${state?.masterVolume ?: 75}%",
                        color = Cyan400,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }

                Slider(
                    value = (state?.masterVolume ?: 75).toFloat(),
                    onValueChange = { viewModel.setVolume(it.toInt()) },
                    valueRange = 0f..100f,
                    colors = SliderDefaults.colors(
                        thumbColor = Cyan400,
                        activeTrackColor = Cyan400,
                        inactiveTrackColor = Slate800
                    )
                )
            }
        }

        // Quick Action Buttons
        Text(
            text = "Quick Actions",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = { viewModel.pressButton("snooze") },
                colors = ButtonDefaults.buttonColors(containerColor = Slate900),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Snooze, contentDescription = "Snooze", tint = Amber400, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Snooze", color = Color.White, fontSize = 13.sp)
            }

            Button(
                onClick = { viewModel.pressButton("stop") },
                colors = ButtonDefaults.buttonColors(containerColor = Slate900),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Stop, contentDescription = "Stop Alarm", tint = Rose500, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Stop", color = Color.White, fontSize = 13.sp)
            }

            Button(
                onClick = {
                    viewModel.triggerLightBar(
                        DopplerDisplayDots(
                            colors = listOf(listOf(0, 0, 0)),
                            duration = 0,
                            attributes = mapOf("display" to "off")
                        )
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = Slate900),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.BrightnessLow, contentDescription = "Blackout", tint = Slate400, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Blackout", color = Color.White, fontSize = 13.sp)
            }
        }
    }
}
