package com.sandman.doppler.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import com.sandman.doppler.ui.rememberDragCommitGate
import com.sandman.doppler.ui.theme.*
import com.sandman.doppler.viewmodel.DopplerViewModel
import kotlin.math.roundToInt

@Composable
fun DisplayLightingScreen(viewModel: DopplerViewModel) {
    val state by viewModel.deviceState.collectAsState()

    val presetColors = listOf(
        "Cyan" to (DopplerColor.CYAN to Cyan400),
        "Amber" to (DopplerColor.AMBER to Amber400),
        "Red" to (DopplerColor.DEEP_RED to Rose500),
        "Emerald" to (DopplerColor.EMERALD to Emerald400),
        "Purple" to (DopplerColor.PURPLE to Purple400)
    )

    // Current state values with defaults
    val dayDisplayColor = state?.dayDisplayColor ?: DopplerColor.CYAN
    val nightDisplayColor = state?.nightDisplayColor ?: DopplerColor.DEEP_RED
    val dayButtonColor = state?.dayButtonColor ?: DopplerColor.CYAN
    val nightButtonColor = state?.nightButtonColor ?: DopplerColor.DEEP_RED

    // Sliders preview locally while dragging, but each DragCommitGate throttles the
    // writes so a drag costs one command per settle window instead of one per frame.
    val dayDisplayGate = rememberDragCommitGate { viewModel.setDayBrightness(it) }
    val nightDisplayGate = rememberDragCommitGate { viewModel.setNightBrightness(it) }
    val dayButtonGate = rememberDragCommitGate { viewModel.setDayButtonBrightness(it) }
    val nightButtonGate = rememberDragCommitGate { viewModel.setNightButtonBrightness(it) }
    val dayToNightGate = rememberDragCommitGate { viewModel.setDayToNightThreshold(it) }
    val nightToDayGate = rememberDragCommitGate { viewModel.setNightToDayThreshold(it) }

    val confirmedBrightness = mapOf(
        "dayDisplay" to (state?.dayDisplayBrightness ?: 85),
        "nightDisplay" to (state?.nightDisplayBrightness ?: 25),
        "dayButton" to (state?.dayButtonBrightness ?: 80),
        "nightButton" to (state?.nightButtonBrightness ?: 20),
        "dayToNightThresh" to (state?.dayToNightThreshold ?: 35),
        "nightToDayThresh" to (state?.nightToDayThreshold ?: 45)
    )

    // Previews the repository has already echoed back (optimistic update) can be dropped.
    val drafts = remember { mutableStateMapOf<String, Int>() }
    LaunchedEffect(confirmedBrightness, drafts.keys.toList()) {
        confirmedBrightness.forEach { (key, value) ->
            if (drafts[key] == value) drafts.remove(key)
        }
    }

    val dayDisplayBrightness = drafts["dayDisplay"] ?: (state?.dayDisplayBrightness ?: 85)
    val nightDisplayBrightness = drafts["nightDisplay"] ?: (state?.nightDisplayBrightness ?: 25)
    val dayButtonBrightness = drafts["dayButton"] ?: (state?.dayButtonBrightness ?: 80)
    val nightButtonBrightness = drafts["nightButton"] ?: (state?.nightButtonBrightness ?: 20)

    val syncButtonDisplayBrightness = state?.syncButtonDisplayBrightness ?: true
    val syncHighLowColor = state?.syncHighLowColor ?: false
    val syncButtonDisplayColor = state?.syncButtonDisplayColor ?: true

    val dayToNightThresh = drafts["dayToNightThresh"] ?: (state?.dayToNightThreshold ?: 35)
    val nightToDayThresh = drafts["nightToDayThresh"] ?: (state?.nightToDayThreshold ?: 45)
    val currentLux = state?.ambientLightSensorLux ?: 100
    val isNightMode = state?.isNightMode ?: false

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Slate950)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        item {
            Column {
                Text(
                    text = "Display & Button Lighting",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp
                )
                Text(
                    text = "Configure independent display and button colors, brightness levels, sync options, and auto-dimming thresholds.",
                    color = Slate400,
                    fontSize = 13.sp
                )
            }
        }

        // Section: Sync Synchronization Toggles
        item {
            Text(
                text = "Lighting Synchronization",
                color = Cyan400,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
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
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Sync Button & Display Brightness
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Sync Button & Screen Brightness", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text("Lock button LEDs to mirror the main display brightness level", color = Slate400, fontSize = 12.sp)
                        }
                        Switch(
                            checked = syncButtonDisplayBrightness,
                            onCheckedChange = { viewModel.setSyncButtonDisplayBrightness(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Slate950,
                                checkedTrackColor = Cyan400,
                                uncheckedThumbColor = Slate400,
                                uncheckedTrackColor = Slate800
                            )
                        )
                    }

                    HorizontalDivider(color = Slate800)

                    // Sync Button & Display Color
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Sync Button & Screen Color", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text("Lock button LEDs to mirror display color", color = Slate400, fontSize = 12.sp)
                        }
                        Switch(
                            checked = syncButtonDisplayColor,
                            onCheckedChange = { viewModel.setSyncButtonDisplayColor(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Slate950,
                                checkedTrackColor = Cyan400,
                                uncheckedThumbColor = Slate400,
                                uncheckedTrackColor = Slate800
                            )
                        )
                    }

                    HorizontalDivider(color = Slate800)

                    // Sync Day & Night Color
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Sync Day & Night Color", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text("Use the same color in both day and night modes", color = Slate400, fontSize = 12.sp)
                        }
                        Switch(
                            checked = syncHighLowColor,
                            onCheckedChange = { viewModel.setSyncHighLowColor(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Slate950,
                                checkedTrackColor = Cyan400,
                                uncheckedThumbColor = Slate400,
                                uncheckedTrackColor = Slate800
                            )
                        )
                    }
                }
            }
        }

        // Section: Day Mode Colors
        item {
            Text(
                text = "Day Mode Colors (Sunlight / Ambient > ${nightToDayThresh} Lux)",
                color = Amber400,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
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
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Day Display Color
                    Text("Display Digits Color", color = Color.White, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        presetColors.forEach { (name, pair) ->
                            val (dopplerColor, composeColor) = pair
                            val isSelected = dayDisplayColor.r == dopplerColor.r &&
                                    dayDisplayColor.g == dopplerColor.g &&
                                    dayDisplayColor.b == dopplerColor.b

                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(
                                    modifier = Modifier
                                        .size(46.dp)
                                        .clip(CircleShape)
                                        .background(composeColor)
                                        .then(
                                            if (isSelected) Modifier.border(3.dp, Color.White, CircleShape)
                                            else Modifier
                                        )
                                        .clickable { viewModel.setDayColor(dopplerColor) }
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(name, color = if (isSelected) Color.White else Slate400, fontSize = 11.sp)
                            }
                        }
                    }

                    if (!syncButtonDisplayColor) {
                        HorizontalDivider(color = Slate800)
                        // Day Button Color
                        Text("Physical Buttons Color", color = Color.White, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            presetColors.forEach { (name, pair) ->
                                val (dopplerColor, composeColor) = pair
                                val isSelected = dayButtonColor.r == dopplerColor.r &&
                                        dayButtonColor.g == dopplerColor.g &&
                                        dayButtonColor.b == dopplerColor.b

                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(
                                        modifier = Modifier
                                            .size(46.dp)
                                            .clip(CircleShape)
                                            .background(composeColor)
                                            .then(
                                                if (isSelected) Modifier.border(3.dp, Color.White, CircleShape)
                                                else Modifier
                                            )
                                            .clickable { viewModel.setDayButtonColor(dopplerColor) }
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(name, color = if (isSelected) Color.White else Slate400, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Section: Night Mode Colors
        if (!syncHighLowColor) {
            item {
                Text(
                    text = "Night Mode Colors (Ambient < ${dayToNightThresh} Lux)",
                    color = Purple400,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
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
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Night Display Color
                        Text("Display Digits Color", color = Color.White, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            presetColors.forEach { (name, pair) ->
                                val (dopplerColor, composeColor) = pair
                                val isSelected = nightDisplayColor.r == dopplerColor.r &&
                                        nightDisplayColor.g == dopplerColor.g &&
                                        nightDisplayColor.b == dopplerColor.b

                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(
                                        modifier = Modifier
                                            .size(46.dp)
                                            .clip(CircleShape)
                                            .background(composeColor)
                                            .then(
                                                if (isSelected) Modifier.border(3.dp, Color.White, CircleShape)
                                                else Modifier
                                            )
                                            .clickable { viewModel.setNightColor(dopplerColor) }
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(name, color = if (isSelected) Color.White else Slate400, fontSize = 11.sp)
                                }
                            }
                        }

                        if (!syncButtonDisplayColor) {
                            HorizontalDivider(color = Slate800)
                            // Night Button Color
                            Text("Physical Buttons Color", color = Color.White, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                presetColors.forEach { (name, pair) ->
                                    val (dopplerColor, composeColor) = pair
                                    val isSelected = nightButtonColor.r == dopplerColor.r &&
                                            nightButtonColor.g == dopplerColor.g &&
                                            nightButtonColor.b == dopplerColor.b

                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Box(
                                            modifier = Modifier
                                                .size(46.dp)
                                                .clip(CircleShape)
                                                .background(composeColor)
                                            .then(
                                                if (isSelected) Modifier.border(3.dp, Color.White, CircleShape)
                                                else Modifier
                                            )
                                            .clickable { viewModel.setNightButtonColor(dopplerColor) }
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(name, color = if (isSelected) Color.White else Slate400, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Section: Brightness Controls
        item {
            Text(
                text = "Brightness Levels",
                color = Cyan400,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
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
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Day Display Brightness
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Day Display Brightness", color = Color.White, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                            Text("$dayDisplayBrightness%", color = Amber400, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                        Slider(
                            value = dayDisplayBrightness.toFloat(),
                            onValueChange = {
                                drafts["dayDisplay"] = it.roundToInt()
                                dayDisplayGate.submit(it.roundToInt())
                            },
                            valueRange = 0f..100f,
                            colors = SliderDefaults.colors(
                                thumbColor = Amber400,
                                activeTrackColor = Amber400,
                                inactiveTrackColor = Slate800
                            )
                        )
                    }

                    // Night Display Brightness
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Night Display Brightness", color = Color.White, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                            Text("$nightDisplayBrightness%", color = Purple400, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                        Slider(
                            value = nightDisplayBrightness.toFloat(),
                            onValueChange = {
                                drafts["nightDisplay"] = it.roundToInt()
                                nightDisplayGate.submit(it.roundToInt())
                            },
                            valueRange = 0f..100f,
                            colors = SliderDefaults.colors(
                                thumbColor = Purple400,
                                activeTrackColor = Purple400,
                                inactiveTrackColor = Slate800
                            )
                        )
                    }

                    if (!syncButtonDisplayBrightness) {
                        HorizontalDivider(color = Slate800)

                        // Day Button Brightness
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Day Buttons Brightness", color = Color.White, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                Text("$dayButtonBrightness%", color = Cyan400, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                            Slider(
                                value = dayButtonBrightness.toFloat(),
                                onValueChange = {
                                    drafts["dayButton"] = it.roundToInt()
                                    dayButtonGate.submit(it.roundToInt())
                                },
                                valueRange = 0f..100f,
                                colors = SliderDefaults.colors(
                                    thumbColor = Cyan400,
                                    activeTrackColor = Cyan400,
                                    inactiveTrackColor = Slate800
                                )
                            )
                        }

                        // Night Button Brightness
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Night Buttons Brightness", color = Color.White, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                Text("$nightButtonBrightness%", color = Rose500, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                            Slider(
                                value = nightButtonBrightness.toFloat(),
                                onValueChange = {
                                    drafts["nightButton"] = it.roundToInt()
                                    nightButtonGate.submit(it.roundToInt())
                                },
                                valueRange = 0f..100f,
                                colors = SliderDefaults.colors(
                                    thumbColor = Rose500,
                                    activeTrackColor = Rose500,
                                    inactiveTrackColor = Slate800
                                )
                            )
                        }
                    }
                }
            }
        }

        // Section: Auto-Dimming Ambient Thresholds
        item {
            Text(
                text = "Auto-Dimming Lux Thresholds",
                color = Emerald400,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
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
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Current Ambient Reading", color = Color.White, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                            Text("Top hardware light sensor", color = Slate400, fontSize = 12.sp)
                        }
                        Surface(
                            color = Slate800,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = if (isNightMode) Icons.Default.NightsStay else Icons.Default.WbSunny,
                                    contentDescription = null,
                                    tint = if (isNightMode) Purple400 else Amber400,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "$currentLux Lux (${if (isNightMode) "Night" else "Day"})",
                                    color = if (isNightMode) Purple400 else Amber400,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = Slate800)

                    // Day -> Night threshold
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Day ➔ Night Threshold", color = Color.White, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                            Text("$dayToNightThresh Lux", color = Purple400, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                        Text("Switches to night mode when ambient light drops below this value", color = Slate400, fontSize = 12.sp)
                        Slider(
                            value = dayToNightThresh.toFloat(),
                            onValueChange = {
                                drafts["dayToNightThresh"] = it.roundToInt()
                                dayToNightGate.submit(it.roundToInt())
                            },
                            valueRange = 0f..255f,
                            colors = SliderDefaults.colors(
                                thumbColor = Purple400,
                                activeTrackColor = Purple400,
                                inactiveTrackColor = Slate800
                            )
                        )
                    }

                    // Night -> Day threshold
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Night ➔ Day Threshold", color = Color.White, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                            Text("$nightToDayThresh Lux", color = Amber400, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                        Text("Switches back to day mode when ambient light rises above this value", color = Slate400, fontSize = 12.sp)
                        Slider(
                            value = nightToDayThresh.toFloat(),
                            onValueChange = {
                                drafts["nightToDayThresh"] = it.roundToInt()
                                nightToDayGate.submit(it.roundToInt())
                            },
                            valueRange = 0f..255f,
                            colors = SliderDefaults.colors(
                                thumbColor = Amber400,
                                activeTrackColor = Amber400,
                                inactiveTrackColor = Slate800
                            )
                        )
                    }
                }
            }
        }
    }
}

