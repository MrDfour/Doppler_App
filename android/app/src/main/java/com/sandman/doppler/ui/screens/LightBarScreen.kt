package com.sandman.doppler.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sandman.doppler.model.DopplerColor
import com.sandman.doppler.model.DopplerDisplayDots
import com.sandman.doppler.ui.theme.*
import com.sandman.doppler.viewmodel.DopplerViewModel
import kotlin.math.roundToInt

private data class LightBarMode(
    val key: String,
    val label: String,
    val description: String,
    val supportsColor: Boolean = true,
    val supportsSpeed: Boolean = true
)

private val LIGHTBAR_MODES = listOf(
    LightBarMode("set", "Static", "Fill all LEDs with a solid color", supportsSpeed = false),
    LightBarMode("blink", "Blink", "Blink all LEDs on and off at the set speed"),
    LightBarMode("pulse", "Pulse", "Smooth breathing fade in/out"),
    LightBarMode("comet", "Comet", "A comet tail trails across the bar"),
    LightBarMode("sweep", "Sweep", "Wipe color from edge to edge"),
    LightBarMode("rainbow", "Rainbow", "Full spectrum wave animation", supportsColor = false)
)

private val COLOR_OPTIONS = listOf(
    "Cyan" to (DopplerColor.CYAN to Cyan400),
    "Amber" to (DopplerColor.AMBER to Amber400),
    "Red" to (DopplerColor.DEEP_RED to Rose500),
    "Emerald" to (DopplerColor.EMERALD to Emerald400),
    "Purple" to (DopplerColor.PURPLE to Purple400)
)

@Composable
fun LightBarScreen(viewModel: DopplerViewModel) {
    var selectedModeKey by remember { mutableStateOf("pulse") }
    var selectedColor by remember { mutableStateOf(DopplerColor.CYAN) }
    var duration by remember { mutableStateOf(15) }
    var speed by remember { mutableStateOf(50) }
    var scrollText by remember { mutableStateOf("") }
    var scrollTextColor by remember { mutableStateOf(DopplerColor.CYAN) }
    var scrollTextDuration by remember { mutableStateOf(10) }
    var scrollTextSpeed by remember { mutableStateOf(50) }
    var miniDigitValue by remember { mutableStateOf(0) }
    var miniDigitDuration by remember { mutableStateOf(15) }
    var miniDigitColor by remember { mutableStateOf(DopplerColor.AMBER) }

    val currentMode = LIGHTBAR_MODES.first { it.key == selectedModeKey }

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
                    text = "29-LED Lightbar",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp
                )
                Text(
                    text = "Control the horizontal lightbar, push scrolling text, or override the mini display.",
                    color = Slate400,
                    fontSize = 13.sp
                )
            }
        }

        // Live LED strip preview (visualizer)
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Slate900),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Preview Strip", color = Slate400, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    // 29 LED dots
                    val dotColor = if (currentMode.supportsColor) {
                        Color(selectedColor.r / 255f, selectedColor.g / 255f, selectedColor.b / 255f)
                    } else null
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        repeat(29) { index ->
                            val lit = when (selectedModeKey) {
                                "set" -> true
                                "blink" -> true
                                "comet" -> index >= 24 // comet at end
                                "sweep" -> index < 20
                                "rainbow" -> true
                                "pulse" -> true
                                else -> true
                            }
                            val ledColor = when {
                                !lit -> Slate800
                                selectedModeKey == "rainbow" -> Color(
                                    android.graphics.Color.HSVToColor(
                                        floatArrayOf(index * (360f / 29), 1f, 1f)
                                    )
                                )
                                dotColor != null -> dotColor.copy(
                                    alpha = if (selectedModeKey == "pulse") {
                                        0.4f + 0.6f * (0.5f + 0.5f * kotlin.math.sin(index * 0.5f))
                                    } else 1f
                                )
                                else -> Slate800
                            }
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(ledColor)
                            )
                        }
                    }
                }
            }
        }

        // Section: Mode selector
        item {
            Text("Animation Mode", color = Cyan400, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }

        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(LIGHTBAR_MODES) { mode ->
                    val selected = mode.key == selectedModeKey
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (selected) Cyan400.copy(alpha = 0.15f) else Slate900
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .border(
                                width = if (selected) 1.5.dp else 0.dp,
                                color = if (selected) Cyan400 else Color.Transparent,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable { selectedModeKey = mode.key }
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(mode.label, color = if (selected) Cyan400 else Color.White,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, fontSize = 14.sp)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(currentMode.description, color = Slate400, fontSize = 12.sp)
        }

        // Section: Color (if applicable)
        if (currentMode.supportsColor) {
            item {
                Text("Color", color = Cyan400, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Slate900),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        COLOR_OPTIONS.forEach { (name, pair) ->
                            val (dc, cc) = pair
                            val isSelected = dc.r == selectedColor.r && dc.g == selectedColor.g && dc.b == selectedColor.b
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(CircleShape)
                                        .background(cc)
                                        .then(
                                            if (isSelected) Modifier.border(3.dp, Color.White, CircleShape) else Modifier
                                        )
                                        .clickable { selectedColor = dc }
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(name, color = if (isSelected) Color.White else Slate400, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }

        // Section: Duration & Speed controls
        item {
            Text("Timing", color = Cyan400, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Slate900),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Duration", color = Color.White, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                            Text("${duration}s", color = Cyan400, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                        Slider(
                            value = duration.toFloat(),
                            onValueChange = { duration = it.roundToInt() },
                            valueRange = 1f..60f,
                            colors = SliderDefaults.colors(thumbColor = Cyan400, activeTrackColor = Cyan400, inactiveTrackColor = Slate800)
                        )
                    }

                    if (currentMode.supportsSpeed) {
                        Column {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Speed", color = Color.White, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                Text("$speed", color = Emerald400, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                            Slider(
                                value = speed.toFloat(),
                                onValueChange = { speed = it.roundToInt() },
                                valueRange = 1f..100f,
                                colors = SliderDefaults.colors(thumbColor = Emerald400, activeTrackColor = Emerald400, inactiveTrackColor = Slate800)
                            )
                        }
                    }
                }
            }
        }

        // Send lightbar effect button
        item {
            Button(
                onClick = {
                    val colors = if (currentMode.supportsColor) {
                        listOf(selectedColor.toList())
                    } else null
                    viewModel.triggerLightBar(
                        DopplerDisplayDots(
                            colors = colors,
                            duration = duration,
                            speed = if (currentMode.supportsSpeed) speed else 50,
                            attributes = mapOf("display" to selectedModeKey)
                        )
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = Cyan400),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.LightMode, contentDescription = null, tint = Slate950)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "Send to Lightbar (${currentMode.label})",
                    color = Slate950,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        item {
            OutlinedButton(
                onClick = {
                    viewModel.triggerLightBar(
                        DopplerDisplayDots(
                            colors = listOf(listOf(0, 0, 0)),
                            duration = 0,
                            attributes = mapOf("display" to "off")
                        )
                    )
                },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Slate400),
                border = androidx.compose.foundation.BorderStroke(1.dp, Slate700),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.VisibilityOff, contentDescription = null, tint = Slate400)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Turn Off Lightbar", color = Slate400)
            }
        }

        // Divider
        item { HorizontalDivider(color = Slate800) }

        // Section: Scrolling Text
        item {
            Text("Scrolling Text Override", color = Amber400, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Slate900),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Push a message to scroll across the main 7-segment digits.",
                        color = Slate400,
                        fontSize = 12.sp
                    )

                    OutlinedTextField(
                        value = scrollText,
                        onValueChange = { if (it.length <= 40) scrollText = it },
                        label = { Text("Message to display") },
                        placeholder = { Text("e.g. SANDMAN", color = Slate700) },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.TextFields, contentDescription = null) },
                        trailingIcon = {
                            if (scrollText.isNotEmpty()) {
                                IconButton(onClick = { scrollText = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear", tint = Slate400)
                                }
                            }
                        },
                        supportingText = { Text("${scrollText.length}/40", color = Slate500) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Amber400,
                            unfocusedBorderColor = Slate700,
                            focusedLabelColor = Amber400,
                            unfocusedLabelColor = Slate400,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            cursorColor = Amber400
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Text color selector
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Color:", color = Color.White, fontSize = 13.sp)
                        COLOR_OPTIONS.forEach { (_, pair) ->
                            val (dc, cc) = pair
                            val isSelected = dc.r == scrollTextColor.r && dc.g == scrollTextColor.g && dc.b == scrollTextColor.b
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(cc)
                                    .then(if (isSelected) Modifier.border(2.dp, Color.White, CircleShape) else Modifier)
                                    .clickable { scrollTextColor = dc }
                            )
                        }
                    }

                    // Duration & Speed for text
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Duration: ${scrollTextDuration}s", color = Slate400, fontSize = 12.sp)
                            Slider(
                                value = scrollTextDuration.toFloat(),
                                onValueChange = { scrollTextDuration = it.roundToInt() },
                                valueRange = 5f..60f,
                                colors = SliderDefaults.colors(thumbColor = Amber400, activeTrackColor = Amber400, inactiveTrackColor = Slate800)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Speed: $scrollTextSpeed", color = Slate400, fontSize = 12.sp)
                            Slider(
                                value = scrollTextSpeed.toFloat(),
                                onValueChange = { scrollTextSpeed = it.roundToInt() },
                                valueRange = 10f..100f,
                                colors = SliderDefaults.colors(thumbColor = Amber400, activeTrackColor = Amber400, inactiveTrackColor = Slate800)
                            )
                        }
                    }

                    Button(
                        onClick = {
                            if (scrollText.isNotBlank()) {
                                viewModel.setDisplayText(scrollText.uppercase(), scrollTextDuration, scrollTextSpeed, scrollTextColor)
                            }
                        },
                        enabled = scrollText.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = Amber400),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Send, contentDescription = null, tint = Slate950)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Send Text to Display", color = Slate950, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Divider
        item { HorizontalDivider(color = Slate800) }

        // Section: Mini Display Digits
        item {
            Text("Mini Display Override (-199 to 199)", color = Emerald400, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Slate900),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Push a custom number to the small secondary 7-segment digits.",
                        color = Slate400,
                        fontSize = 12.sp
                    )

                    // Number picker
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { if (miniDigitValue > -199) miniDigitValue-- },
                            modifier = Modifier
                                .size(44.dp)
                                .background(Slate800, CircleShape)
                        ) {
                            Icon(Icons.Default.Remove, contentDescription = "Decrease", tint = Emerald400)
                        }

                        Surface(color = Slate800, shape = RoundedCornerShape(12.dp)) {
                            Text(
                                text = miniDigitValue.toString(),
                                color = Emerald400,
                                fontSize = 40.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                            )
                        }

                        IconButton(
                            onClick = { if (miniDigitValue < 199) miniDigitValue++ },
                            modifier = Modifier
                                .size(44.dp)
                                .background(Slate800, CircleShape)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Increase", tint = Emerald400)
                        }
                    }

                    // Mini digit color
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Color:", color = Color.White, fontSize = 13.sp)
                        COLOR_OPTIONS.forEach { (_, pair) ->
                            val (dc, cc) = pair
                            val isSelected = dc.r == miniDigitColor.r && dc.g == miniDigitColor.g && dc.b == miniDigitColor.b
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(cc)
                                    .then(if (isSelected) Modifier.border(2.dp, Color.White, CircleShape) else Modifier)
                                    .clickable { miniDigitColor = dc }
                            )
                        }
                    }

                    // Duration
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Duration", color = Color.White, fontSize = 13.sp)
                        Text("${miniDigitDuration}s", color = Emerald400, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                    Slider(
                        value = miniDigitDuration.toFloat(),
                        onValueChange = { miniDigitDuration = it.roundToInt() },
                        valueRange = 5f..60f,
                        colors = SliderDefaults.colors(thumbColor = Emerald400, activeTrackColor = Emerald400, inactiveTrackColor = Slate800)
                    )

                    Button(
                        onClick = {
                            viewModel.setSmallDisplayDigits(miniDigitValue, miniDigitDuration, miniDigitColor)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Emerald400),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Pin, contentDescription = null, tint = Slate950)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Push Number to Mini Display", color = Slate950, fontWeight = FontWeight.Bold)
                    }

                    TextButton(
                        onClick = { miniDigitValue = 0 },
                        colors = ButtonDefaults.textButtonColors(contentColor = Slate400),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Reset to 0")
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}
