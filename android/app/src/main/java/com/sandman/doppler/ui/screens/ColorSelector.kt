package com.sandman.doppler.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sandman.doppler.model.DopplerColor
import com.sandman.doppler.ui.theme.Amber400
import com.sandman.doppler.ui.theme.Cyan400
import com.sandman.doppler.ui.theme.Slate400
import com.sandman.doppler.ui.theme.Slate700
import com.sandman.doppler.ui.theme.Slate800
import com.sandman.doppler.ui.theme.Slate900

/**
 * A row of preset swatches plus a free-form custom colour.
 *
 * Extracted because the same block was duplicated four times on
 * [DisplayLightingScreen] - day display, day buttons, night display, night buttons - and a
 * fix applied to one copy had silently not reached the other three.
 *
 * The custom option is not decoration. The clock accepts any `[r,g,b]`: `PUT
 * hardware/{high,low}-{display,button}-color` was verified on hardware with
 * `{"color":[10,20,30]}`, so the five presets were the only thing limiting what the app
 * could do.
 */
@Composable
fun ColorSelector(
    current: DopplerColor,
    onSelect: (DopplerColor) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Colour"
) {
    var showCustom by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        Text(label, color = Color.White, fontWeight = FontWeight.Medium, fontSize = 14.sp)
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Top
        ) {
            PRESET_SWATCHES.forEach { (name, dopplerColor, composeColor) ->
                ColorSwatch(
                    name = name,
                    composeColor = composeColor,
                    selected = current == dopplerColor,
                    onClick = { onSelect(dopplerColor) }
                )
            }
            // Shows the live colour so a custom pick stays visible after the dialog closes.
            // Without this the row would show nothing selected and the user could not tell
            // what they had set.
            ColorSwatch(
                name = "Custom",
                composeColor = current.toComposeColor(),
                selected = PRESET_SWATCHES.none { it.second == current },
                onClick = { showCustom = true }
            )
        }
    }

    if (showCustom) {
        CustomColorDialog(
            initial = current,
            onConfirm = {
                onSelect(it)
                showCustom = false
            },
            onDismiss = { showCustom = false }
        )
    }
}

@Composable
private fun ColorSwatch(
    name: String,
    composeColor: Color,
    selected: Boolean,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(composeColor)
                .then(
                    if (selected) Modifier.border(3.dp, Color.White, CircleShape)
                    else Modifier
                )
                .clickable(onClick = onClick)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            name,
            color = if (selected) Color.White else Slate400,
            fontSize = 11.sp
        )
    }
}

/**
 * Free-form RGB picker.
 *
 * Sliders rather than a hue wheel because the clock's API is `[r,g,b]` and the three channels
 * map one-to-one onto the sliders - no HSV conversion to explain or get wrong. A hex field
 * sits alongside for anyone who has the value from elsewhere.
 *
 * The hex field rejects bad input instead of guessing: see [DopplerColor.fromHexOrNull] for
 * why the lenient parse is unsafe here.
 */
@Composable
fun CustomColorDialog(
    initial: DopplerColor,
    onConfirm: (DopplerColor) -> Unit,
    onDismiss: () -> Unit
) {
    var red by remember { mutableStateOf(initial.r.toFloat()) }
    var green by remember { mutableStateOf(initial.g.toFloat()) }
    var blue by remember { mutableStateOf(initial.b.toFloat()) }
    var hex by remember { mutableStateOf(initial.toHex()) }

    val fromSliders = DopplerColor(red.toInt(), green.toInt(), blue.toInt())
    val parsedHex = DopplerColor.fromHexOrNull(hex)
    val hexValid = parsedHex != null

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Slate900,
        title = { Text("Custom Colour", color = Color.White, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(fromSliders.toComposeColor())
                            .border(1.dp, Slate700, RoundedCornerShape(8.dp))
                    )
                    Spacer(modifier = Modifier.size(12.dp))
                    Text(
                        fromSliders.toHex(),
                        color = Slate400,
                        fontSize = 13.sp
                    )
                }

                ChannelSlider("R", red, Color.Red) { red = it }
                ChannelSlider("G", green, Color.Green) { green = it }
                ChannelSlider("B", blue, Color.Blue) { blue = it }

                OutlinedTextField(
                    value = hex,
                    onValueChange = { hex = it },
                    singleLine = true,
                    isError = !hexValid,
                    label = { Text("Hex", color = Slate400, fontSize = 12.sp) },
                    supportingText = {
                        if (!hexValid) {
                            Text(
                                "Enter 3 or 6 hex digits, e.g. #22D3EE",
                                color = Amber400,
                                fontSize = 11.sp
                            )
                        }
                    },
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Cyan400,
                        unfocusedBorderColor = Slate700,
                        errorBorderColor = Amber400,
                        cursorColor = Cyan400
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    // A valid hex entry wins over the sliders, since typing six digits is a
                    // more deliberate act than nudging one channel.
                    onConfirm(parsedHex ?: fromSliders)
                },
                colors = ButtonDefaults.buttonColors(containerColor = Cyan400, contentColor = Color.Black)
            ) {
                Text("Apply", fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Slate400)
            }
        }
    )
}

@Composable
private fun ChannelSlider(
    name: String,
    value: Float,
    accent: Color,
    onChange: (Float) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            name,
            color = Slate400,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.size(width = 16.dp, height = 20.dp)
        )
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = 0f..255f,
            // 255 steps across the track; a coarser range would make dark blues unreachable.
            steps = 254,
            colors = SliderDefaults.colors(
                thumbColor = accent,
                activeTrackColor = accent,
                inactiveTrackColor = Slate800
            ),
            modifier = Modifier.weight(1f)
        )
        Text(
            value.toInt().toString(),
            color = Slate400,
            fontSize = 12.sp,
            modifier = Modifier.size(width = 30.dp, height = 20.dp)
        )
    }
}

/** `DopplerColor` as a Compose colour, for swatch backgrounds only. */
internal fun DopplerColor.toComposeColor(): Color = Color(r, g, b)

/**
 * The five swatches that shipped with the app.
 *
 * Names and values match `DopplerColor.CYAN` etc. and the plan's documented palette.
 * Kept as triples so the Compose colour used for the swatch is stated in one place rather
 * than duplicated per screen.
 */
private val PRESET_SWATCHES: List<Triple<String, DopplerColor, Color>> = listOf(
    Triple("Cyan", DopplerColor.CYAN, Cyan400),
    Triple("Amber", DopplerColor.AMBER, Amber400),
    Triple("Red", DopplerColor.DEEP_RED, Color(0xFFFF2828)),
    Triple("Emerald", DopplerColor.EMERALD, Color(0xFF10DC78)),
    Triple("Purple", DopplerColor.PURPLE, Color(0xFFB43CFF))
)