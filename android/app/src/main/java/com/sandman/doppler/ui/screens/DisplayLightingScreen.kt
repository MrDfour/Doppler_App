package com.sandman.doppler.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sandman.doppler.model.DopplerColor
import com.sandman.doppler.model.LightBarEffect
import com.sandman.doppler.ui.theme.*
import com.sandman.doppler.viewmodel.DopplerViewModel

@Composable
fun DisplayLightingScreen(viewModel: DopplerViewModel) {
    val state by viewModel.deviceState.collectAsState()

    val presetColors = listOf(
        DopplerColor.CYAN to Cyan400,
        DopplerColor.AMBER to Amber400,
        DopplerColor.DEEP_RED to Rose500,
        DopplerColor.EMERALD to Emerald400,
        DopplerColor.PURPLE to Purple400
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Slate950)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "Day Mode Display Color",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Slate900),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    presetColors.forEach { (dopplerColor, composeColor) ->
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(composeColor)
                                .clickable { viewModel.setDayColor(dopplerColor) }
                        )
                    }
                }
            }
        }

        item {
            Text(
                text = "Night Mode Display Color",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Slate900),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    presetColors.forEach { (dopplerColor, composeColor) ->
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(composeColor)
                                .clickable { viewModel.setNightColor(dopplerColor) }
                        )
                    }
                }
            }
        }

        item {
            Text(
                text = "29-LED Lightbar Effects",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Slate900),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                viewModel.triggerLightBar(
                                    LightBarEffect(mode = "pulse", duration = 15, speed = 50, color = DopplerColor.CYAN)
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Slate800),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Pulse Cyan", color = Cyan400)
                        }

                        Button(
                            onClick = {
                                viewModel.triggerLightBar(
                                    LightBarEffect(mode = "comet", duration = 15, speed = 40, color = DopplerColor.AMBER, direction = "right")
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Slate800),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Comet Amber", color = Amber400)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                viewModel.triggerLightBar(
                                    LightBarEffect(mode = "pulse", duration = 20, rainbow = true)
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Slate800),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Rainbow Wave", color = Emerald400)
                        }

                        Button(
                            onClick = { viewModel.stopLightBar() },
                            colors = ButtonDefaults.buttonColors(containerColor = Slate800),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Turn Off", color = Rose500)
                        }
                    }
                }
            }
        }
    }
}
