package com.sandman.doppler.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sandman.doppler.model.DopplerAlarm
import com.sandman.doppler.ui.theme.*
import com.sandman.doppler.viewmodel.DopplerViewModel

@Composable
fun AlarmsScreen(viewModel: DopplerViewModel) {
    val state by viewModel.deviceState.collectAsState()
    val alarms = state?.alarms ?: emptyList()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Slate950)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "Alarms",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )
        }

        if (alarms.isEmpty()) {
            item {
                Text(
                    text = "No alarms configured on this Doppler.",
                    color = Slate400,
                    fontSize = 14.sp
                )
            }
        } else {
            items(alarms) { alarm ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (alarm.status == 1) Slate900 else Slate900.copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(16.dp),
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
                                text = alarm.timeFormatted,
                                color = if (alarm.isEnabled) Color.White else Slate400,
                                fontSize = 32.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                            val title = if (alarm.isSystemAlarm) "Doppler System Alarm" else alarm.name.ifBlank { "User Alarm #${alarm.id}" }
                            Text(
                                text = "$title • ${alarm.sound} • ${alarm.repeat.ifBlank { "Once" }}",
                                color = Slate400,
                                fontSize = 12.sp
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            IconButton(onClick = { viewModel.playAlarmSound(alarm.sound) }) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "Preview Sound",
                                    tint = Cyan400
                                )
                            }

                            Switch(
                                checked = alarm.isEnabled,
                                onCheckedChange = { viewModel.toggleAlarm(alarm) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Slate950,
                                    checkedTrackColor = Cyan400,
                                    uncheckedThumbColor = Slate400,
                                    uncheckedTrackColor = Slate800
                                )
                            )

                            if (!alarm.isSystemAlarm) {
                                IconButton(onClick = { viewModel.deleteAlarm(alarm.id) }) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete Alarm",
                                        tint = Slate400
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
