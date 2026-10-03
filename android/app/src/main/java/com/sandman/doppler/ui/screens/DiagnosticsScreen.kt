package com.sandman.doppler.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sandman.doppler.model.OverrideProbeResult
import com.sandman.doppler.model.OverrideVerdict
import com.sandman.doppler.ui.theme.*
import com.sandman.doppler.viewmodel.DopplerViewModel

private fun OverrideVerdict.color(): Color = when (this) {
    OverrideVerdict.SUPPORTED -> Emerald400
    // Amber, not emerald: accepted by the relay but not proven to have rendered.
    OverrideVerdict.ACCEPTED_UNVERIFIED -> Amber400
    OverrideVerdict.REJECTED -> Amber400
    OverrideVerdict.NOT_SUPPORTED -> Rose500
    OverrideVerdict.UNKNOWN -> Slate400
}

@Composable
fun DiagnosticsScreen(viewModel: DopplerViewModel) {
    val state by viewModel.deviceState.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val probeResults by viewModel.overrideProbeResults.collectAsState()
    val isProbing by viewModel.isProbingOverrides.collectAsState()
    val isCloud = viewModel.isCloudControlPlane
    val connectionModeLabel = viewModel.connectionModeLabel

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
                    // Report the path actually in use. These two fail in completely
                    // different ways, so a wrong label here costs real debugging time.
                    Text(
                        text = "Connection Mode: $connectionModeLabel",
                        color = if (isCloud) Amber400 else Emerald400,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                    Text("Device DSN: ${state?.dsn ?: "N/A"}", color = Slate200, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    if (isCloud) {
                        Text(
                            "Endpoint: control.sandmandoppler.com (cloud relay)",
                            color = Slate400, fontSize = 12.sp, fontFamily = FontFamily.Monospace
                        )
                    } else {
                        Text(
                            "IP Endpoint: ${state?.ipAddress ?: "N/A"}:${state?.port ?: "N/A"}",
                            color = Slate400, fontSize = 12.sp, fontFamily = FontFamily.Monospace
                        )
                    }
                    Text(
                        "Firmware: ${state?.firmwareVersion ?: "Unknown"} | Model: ${state?.modelNumber ?: "Unknown"}",
                        color = Slate400, fontSize = 12.sp
                    )
                    Text(
                        text = if (state?.online == true) "Link: UP" else "Link: DOWN",
                        color = if (state?.online == true) Emerald400 else Rose500,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Slate900),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Override Support Probe",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Text(
                        "Sends both custom-display overrides once and reports the HTTP status. " +
                            "NOT SUPPORTED means your firmware has no route for it; REJECTED means the route exists " +
                            "but refused the payload. ACCEPTED UNVERIFIED means the relay took it - these two " +
                            "overrides have no read-back endpoint, so only the clock face can confirm they rendered.",
                        color = Slate400,
                        fontSize = 12.sp
                    )
                    Button(
                        onClick = { viewModel.probeOverrideSupport() },
                        enabled = !isProbing,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Cyan400,
                            disabledContainerColor = Slate800
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.HealthAndSafety, contentDescription = null, tint = Slate950)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isProbing) "Probing..." else "Probe Override Support",
                            color = Slate950,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    probeResults?.forEach { result ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = result.label,
                                    color = Color.White,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = result.verdict.name,
                                    color = result.verdict.color(),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                            Text(
                                text = result.httpCode?.let { "HTTP $it" } ?: "no HTTP response",
                                color = result.verdict.color(),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = result.detail,
                                color = Slate400,
                                fontSize = 11.sp
                            )
                        }
                    }
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
