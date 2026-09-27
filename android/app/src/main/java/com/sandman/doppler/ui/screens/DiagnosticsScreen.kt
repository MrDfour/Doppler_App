package com.sandman.doppler.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sandman.doppler.ui.theme.*
import com.sandman.doppler.viewmodel.DopplerViewModel

@Composable
fun DiagnosticsScreen(viewModel: DopplerViewModel) {
    val state by viewModel.deviceState.collectAsState()
    val logs by viewModel.logs.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Slate950)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "Diagnostics & Protocol Monitor",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Slate900),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Connection Mode: DIRECT LOCAL WI-FI", color = Emerald400, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text("Device DSN: ${state?.dsn ?: "N/A"}", color = Slate200, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    Text("IP Endpoint: ${state?.ipAddress ?: "N/A"}:3000", color = Slate400, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    Text("Firmware: ${state?.firmwareVersion ?: "1.4.12"} | Model: ${state?.modelNumber ?: "PAI-DOPPLER-01"}", color = Slate400, fontSize = 12.sp)
                }
            }
        }

        item {
            Text(
                text = "Activity Log (Sanitized)",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        }

        items(logs) { log ->
            Card(
                colors = CardDefaults.cardColors(containerColor = Slate900.copy(alpha = 0.6f)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = log.summary,
                            color = if (log.type == "ERROR") Rose500 else Cyan400,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                        Text(
                            text = log.timestamp,
                            color = Slate400,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    if (log.details.isNotEmpty()) {
                        Text(
                            text = log.details,
                            color = Slate400,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }
    }
}
