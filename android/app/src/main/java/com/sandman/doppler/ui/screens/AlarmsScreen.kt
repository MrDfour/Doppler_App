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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.sandman.doppler.R
import com.sandman.doppler.model.DopplerAlarm
import com.sandman.doppler.model.DopplerColor
import com.sandman.doppler.ui.theme.*
import com.sandman.doppler.viewmodel.DopplerViewModel
import kotlin.math.roundToInt

private data class AlarmDraft(
    val id: Int,
    val name: String = "",
    val hour: Int = 7,
    val minute: Int = 0,
    val repeatDays: Set<String> = emptySet(),
    val sound: String = "Gentle.mp3",
    val volume: Int = 80,
    val colorPreset: DopplerColor = DopplerColor.CYAN,
    // Real hardware status values, not 1/0. See DopplerAlarm.STATUS_*.
    // Defaults to ARMED, not UNARMED: this is the status a newly added alarm is created with,
    // and an alarm created unarmed never rings.
    val status: Int = DopplerAlarm.STATUS_ARMED
)

private val DAYS_OF_WEEK = listOf("Su", "Mo", "Tu", "We", "Th", "Fr", "Sa")

@Composable
fun AlarmsScreen(viewModel: DopplerViewModel) {
    val state by viewModel.deviceState.collectAsState()
    val alarms = state?.alarms ?: emptyList()
    val availableSounds = state?.availableSounds.takeIf { it?.isNotEmpty() == true }
        ?: listOf(
            "Gentle.mp3", "Chime.mp3", "Bell.mp3", "Beep.mp3", "Birds.mp3",
            "Digital.mp3", "Buzz.mp3", "Alarm.mp3", "Piano.mp3", "Harp.mp3",
            "Ocean.mp3", "Rain.mp3", "Forest.mp3", "Sunrise.mp3", "Classic.mp3",
            "Electronic.mp3", "Soft.mp3", "Loud.mp3", "Ring.mp3", "Tune.mp3"
        )
    val is24Hour = state?.time24Hour ?: false

    var showDialog by remember { mutableStateOf(false) }
    var editingAlarm by remember { mutableStateOf<AlarmDraft?>(null) }

    Scaffold(
        containerColor = Slate950,
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    // Find next available user alarm ID (1..255)
                    val usedIds = alarms.map { it.id }.toSet()
                    val nextId = (1..255).firstOrNull { it !in usedIds } ?: 1
                    editingAlarm = AlarmDraft(id = nextId)
                    showDialog = true
                },
                containerColor = Cyan400,
                contentColor = Slate950
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_alarm))
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(Slate950)
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Column {
                    Text(
                        text = stringResource(R.string.alarms),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp
                    )
                    Text(
                        text = stringResource(R.string.alarm_manage_hint),
                        color = Slate400,
                        fontSize = 13.sp
                    )
                }
            }

            if (alarms.isEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Slate900),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.Alarm,
                                contentDescription = null,
                                tint = Slate700,
                                modifier = Modifier.size(56.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(stringResource(R.string.no_alarms_configured), color = Slate400, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            Text(stringResource(R.string.tap_to_add_alarm), color = Slate700, fontSize = 13.sp)
                        }
                    }
                }
            } else {
                items(alarms) { alarm ->
                    AlarmCard(
                        alarm = alarm,
                        is24Hour = is24Hour,
                        onToggle = { viewModel.toggleAlarm(alarm) },
                        onEdit = {
                            editingAlarm = AlarmDraft(
                                id = alarm.id,
                                name = alarm.name,
                                hour = alarm.time_hr,
                                minute = alarm.time_min,
                                repeatDays = alarm.repeatDaysList.toSet(),
                                sound = alarm.sound,
                                volume = alarm.volume,
                                colorPreset = alarm.color?.toDopplerColor() ?: DopplerColor.CYAN,
                                status = alarm.status
                            )
                            showDialog = true
                        },
                        onDelete = { if (!alarm.isSystemAlarm) viewModel.deleteAlarm(alarm.id) },
                        onPreview = { viewModel.playAlarmSound(alarm.sound) }
                    )
                }
            }

            // Bottom spacing for FAB
            item { Spacer(modifier = Modifier.height(72.dp)) }
        }
    }

    if (showDialog && editingAlarm != null) {
        AlarmEditDialog(
            draft = editingAlarm!!,
            is24Hour = is24Hour,
            availableSounds = availableSounds,
            onDismiss = {
                showDialog = false
                editingAlarm = null
            },
            onSave = { draft ->
                val repeatStr = DAYS_OF_WEEK.filter { it in draft.repeatDays }.joinToString("")
                val alarm = DopplerAlarm(
                    id = draft.id,
                    name = draft.name,
                    time_hr = draft.hour,
                    time_min = draft.minute,
                    repeat = repeatStr,
                    color = com.sandman.doppler.model.DopplerColorObject(
                        red = draft.colorPreset.r,
                        green = draft.colorPreset.g,
                        blue = draft.colorPreset.b
                    ),
                    volume = draft.volume,
                    status = draft.status,
                    sound = draft.sound,
                    src = 1
                )
                viewModel.saveAlarm(alarm)
                showDialog = false
                editingAlarm = null
            },
            onPreview = { sound -> viewModel.playAlarmSound(sound) }
        )
    }
}

@Composable
private fun AlarmCard(
    alarm: DopplerAlarm,
    is24Hour: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onPreview: () -> Unit
) {
    val timeText = if (is24Hour) {
        String.format("%02d:%02d", alarm.time_hr, alarm.time_min)
    } else {
        val h = if (alarm.time_hr % 12 == 0) 12 else alarm.time_hr % 12
        val ampm = if (alarm.time_hr < 12) "AM" else "PM"
        String.format("%d:%02d %s", h, alarm.time_min, ampm)
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (alarm.isEnabled) Slate900 else Slate900.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { if (!alarm.isSystemAlarm) onEdit() }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = timeText,
                        color = if (alarm.isEnabled) Color.White else Slate400,
                        fontSize = 36.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    val label = when {
                        alarm.isSystemAlarm -> stringResource(R.string.system_alarm)
                        alarm.name.isNotBlank() -> alarm.name
                        else -> "${stringResource(R.string.alarm)} #${alarm.id}"
                    }
                    Text(text = label, color = Slate400, fontSize = 13.sp)
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(onClick = onPreview) {
                        Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.preview), tint = Cyan400)
                    }
                    Switch(
                        checked = alarm.isEnabled,
                        onCheckedChange = { onToggle() },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Slate950,
                            checkedTrackColor = Cyan400,
                            uncheckedThumbColor = Slate400,
                            uncheckedTrackColor = Slate800
                        )
                    )
                    if (!alarm.isSystemAlarm) {
                        IconButton(onClick = onDelete) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete), tint = Slate400)
                        }
                    }
                }
            }

            // Repeat chips row
            val repeatDays = alarm.repeatDaysList
            if (repeatDays.isNotEmpty() || !alarm.isSystemAlarm) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (repeatDays.isEmpty()) {
                        Text(stringResource(R.string.once), color = Slate500, fontSize = 12.sp)
                    } else {
                        repeatDays.forEach { day ->
                            Surface(
                                color = Cyan400.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = day,
                                    color = Cyan400,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Text("🔔 ${alarm.sound.removeSuffix(".mp3")}", color = Slate500, fontSize = 12.sp)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlarmEditDialog(
    draft: AlarmDraft,
    is24Hour: Boolean,
    availableSounds: List<String>,
    onDismiss: () -> Unit,
    onSave: (AlarmDraft) -> Unit,
    onPreview: (String) -> Unit
) {
    var hour by remember { mutableStateOf(draft.hour) }
    var minute by remember { mutableStateOf(draft.minute) }
    var name by remember { mutableStateOf(draft.name) }
    var repeatDays by remember { mutableStateOf(draft.repeatDays) }
    var selectedSound by remember { mutableStateOf(draft.sound) }
    var volume by remember { mutableStateOf(draft.volume) }
    var selectedColor by remember { mutableStateOf(draft.colorPreset) }
    var status by remember { mutableStateOf(draft.status) }
    var showSoundPicker by remember { mutableStateOf(false) }

    val colorOptions = listOf(
        DopplerColor.CYAN to Cyan400,
        DopplerColor.AMBER to Amber400,
        DopplerColor.DEEP_RED to Rose500,
        DopplerColor.EMERALD to Emerald400,
        DopplerColor.PURPLE to Purple400
    )

    val textFieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Cyan400,
        unfocusedBorderColor = Slate700,
        focusedLabelColor = Cyan400,
        unfocusedLabelColor = Slate400,
        focusedTextColor = Color.White,
        unfocusedTextColor = Color.White,
        cursorColor = Cyan400
    )

    Dialog(onDismissRequest = onDismiss) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Slate900),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Title
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (draft.id == 0) stringResource(R.string.system_alarm) else if (draft.name.isBlank()) stringResource(R.string.new_alarm) else draft.name,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close), tint = Slate400)
                        }
                    }
                }

                // Time picker - hour/minute numeric
                item {
                    Text(stringResource(R.string.time), color = Cyan400, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Hour
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            IconButton(onClick = { hour = (hour + 1) % (if (is24Hour) 24 else 12).also { if (!is24Hour && hour == 0) hour = 11 } }) {
                                Icon(Icons.Default.KeyboardArrowUp, contentDescription = stringResource(R.string.hour_plus), tint = Cyan400)
                            }
                            Surface(color = Slate800, shape = RoundedCornerShape(12.dp)) {
                                Text(
                                    text = String.format(if (is24Hour) "%02d" else "%d", if (is24Hour) hour else (if (hour % 12 == 0) 12 else hour % 12)),
                                    color = Color.White,
                                    fontSize = 40.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                                )
                            }
                            IconButton(onClick = { hour = (hour - 1 + (if (is24Hour) 24 else 12)) % (if (is24Hour) 24 else 12) }) {
                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.hour_minus), tint = Cyan400)
                            }
                        }

                        Text(":", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp))

                        // Minute
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            IconButton(onClick = { minute = (minute + 1) % 60 }) {
                                Icon(Icons.Default.KeyboardArrowUp, contentDescription = stringResource(R.string.min_plus), tint = Cyan400)
                            }
                            Surface(color = Slate800, shape = RoundedCornerShape(12.dp)) {
                                Text(
                                    text = String.format("%02d", minute),
                                    color = Color.White,
                                    fontSize = 40.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                                )
                            }
                            IconButton(onClick = { minute = (minute - 1 + 60) % 60 }) {
                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.min_minus), tint = Cyan400)
                            }
                        }

                        // AM/PM if 12h
                        if (!is24Hour) {
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                val isAm = hour < 12
                                OutlinedButton(
                                    onClick = { hour = if (hour < 12) hour + 12 else hour - 12 },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Cyan400),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Cyan400)
                                ) {
                                    Text(if (isAm) "AM" else "PM", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                // Alarm Name
                item {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.alarm_name_optional)) },
                        placeholder = { Text(stringResource(R.string.wake_up_example), color = Slate700) },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Label, contentDescription = null) },
                        colors = textFieldColors,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Repeat Days
                item {
                    Text(stringResource(R.string.repeat), color = Cyan400, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(DAYS_OF_WEEK) { day ->
                            val selected = day in repeatDays
                            Surface(
                                color = if (selected) Cyan400 else Slate800,
                                shape = CircleShape,
                                modifier = Modifier
                                    .size(44.dp)
                                    .clickable {
                                        repeatDays = if (selected) repeatDays - day else repeatDays + day
                                    }
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                    Text(
                                        text = day,
                                        color = if (selected) Slate950 else Slate400,
                                        fontSize = 12.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                        }
                    }
                    if (repeatDays.isEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(stringResource(R.string.no_repeat_alarm_fires_once), color = Slate500, fontSize = 12.sp)
                    }
                }

                // Sound selector
                item {
                    Text(stringResource(R.string.sound), color = Cyan400, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showSoundPicker = !showSoundPicker },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Cyan400),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Slate700),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.MusicNote, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = selectedSound.removeSuffix(".mp3"),
                                maxLines = 1,
                                color = Color.White
                            )
                        }
                        FilledIconButton(
                            onClick = { onPreview(selectedSound) },
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = Slate800)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.preview), tint = Cyan400)
                        }
                    }

                    if (showSoundPicker) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Slate800),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                availableSounds.chunked(2).forEach { row ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        row.forEach { sound ->
                                            val selected = sound == selectedSound
                                            TextButton(
                                                onClick = { selectedSound = sound; showSoundPicker = false },
                                                modifier = Modifier.weight(1f),
                                                colors = ButtonDefaults.textButtonColors(
                                                    contentColor = if (selected) Cyan400 else Slate400
                                                )
                                            ) {
                                                if (selected) {
                                                    Icon(Icons.Default.CheckCircle, contentDescription = null,
                                                        tint = Cyan400, modifier = Modifier.size(14.dp))
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                }
                                                Text(sound.removeSuffix(".mp3"), fontSize = 12.sp, maxLines = 1)
                                            }
                                        }
                                        if (row.size == 1) Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }

                // Volume
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.alarm_volume), color = Cyan400, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text("$volume%", color = Cyan400, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                    Slider(
                        value = volume.toFloat(),
                        onValueChange = { volume = it.roundToInt() },
                        valueRange = 0f..100f,
                        colors = SliderDefaults.colors(
                            thumbColor = Cyan400,
                            activeTrackColor = Cyan400,
                            inactiveTrackColor = Slate800
                        )
                    )
                }

                // Alarm Color
                item {
                    Text(stringResource(R.string.alarm_led_color), color = Cyan400, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        colorOptions.forEach { (dc, composeColor) ->
                            val isSelected = dc.r == selectedColor.r && dc.g == selectedColor.g && dc.b == selectedColor.b
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(composeColor)
                                    .then(
                                        if (isSelected) Modifier.border(3.dp, Color.White, CircleShape) else Modifier
                                    )
                                    .clickable { selectedColor = dc }
                            )
                        }
                    }
                }

                // Enabled toggle
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.enable_alarm), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Switch(
                            checked = status != DopplerAlarm.STATUS_UNARMED &&
                                status != DopplerAlarm.STATUS_DISABLED,
                            onCheckedChange = {
                                // Arm with 1 and disarm with 10, the two values proven on
                                // hardware. Writing 10 to switch an alarm ON produced an alarm
                                // that showed as enabled in the app and never rang.
                                status = if (it) {
                                    DopplerAlarm.STATUS_ARMED
                                } else {
                                    DopplerAlarm.STATUS_UNARMED
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Slate950,
                                checkedTrackColor = Cyan400,
                                uncheckedThumbColor = Slate400,
                                uncheckedTrackColor = Slate800
                            )
                        )
                    }
                }

                // Save / Cancel
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onDismiss,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Slate400),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Slate700),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.cancel))
                        }
                        Button(
                            onClick = {
                                onSave(
                                    AlarmDraft(
                                        id = draft.id,
                                        name = name,
                                        hour = hour,
                                        minute = minute,
                                        repeatDays = repeatDays,
                                        sound = selectedSound,
                                        volume = volume,
                                        colorPreset = selectedColor,
                                        status = status
                                    )
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Cyan400),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null, tint = Slate950)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.save_alarm), color = Slate950, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}