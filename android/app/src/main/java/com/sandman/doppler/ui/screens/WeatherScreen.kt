package com.sandman.doppler.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sandman.doppler.R
import com.sandman.doppler.model.ClockTime
import com.sandman.doppler.model.PlaceCandidate
import com.sandman.doppler.model.WeatherMode
import com.sandman.doppler.model.WeatherProvider
import com.sandman.doppler.ui.theme.*
import com.sandman.doppler.viewmodel.DopplerViewModel

@Composable
fun WeatherScreen(viewModel: DopplerViewModel) {
    val state by viewModel.deviceState.collectAsState()
    val results by viewModel.placeResults.collectAsState()
    val searching by viewModel.isSearchingPlaces.collectAsState()
    val message by viewModel.placeSearchMessage.collectAsState()
    val savedLabel by viewModel.savedPlaceLabel.collectAsState()

    var query by remember { mutableStateOf("") }
    var modesExpanded by remember { mutableStateOf(false) }

    val enabled = state?.weatherEnabled ?: false
    val location = state?.weatherLocation.orEmpty()
    val mode = state?.weatherMode ?: WeatherMode.PREFERRED_DEFAULT.wireValue
    val wakeup = ClockTime.parseOrNull(state?.weatherWakeupTime.orEmpty())
    val timezone = state?.clockTimezone

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Slate950),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                stringResource(R.string.weather),
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )
        }

        item {
            WeatherCard(title = stringResource(R.string.show_weather_on_clock)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (enabled) stringResource(R.string.on) else stringResource(R.string.off),
                        color = if (enabled) Emerald400 else Slate400,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Switch(
                        checked = enabled,
                        onCheckedChange = viewModel::setWeatherEnabled,
                        modifier = Modifier.heightIn(min = 56.dp)
                    )
                }
            }
        }

        item {
            WeatherCard(title = stringResource(R.string.where_is_clock_looking)) {
                if (savedLabel != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Place, contentDescription = null, tint = Cyan400)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            savedLabel!!,
                            color = Color.White,
                            fontSize = 19.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                }

                if (location.isNotBlank() && savedLabel == null) {
                    Text(
                        stringResource(R.string.clock_set_to_unknown_location, location),
                        color = Amber400,
                        fontSize = 14.sp
                    )
                    Spacer(Modifier.height(8.dp))
                }

                Text(
                    stringResource(R.string.type_town_or_postal_code),
                    color = Slate400,
                    fontSize = 15.sp
                )
                Spacer(Modifier.height(10.dp))

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !searching,
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 18.sp),
                    label = { Text(stringResource(R.string.town_or_postal_code), fontSize = 15.sp) },
                    placeholder = { Text(stringResource(R.string.chihuahua), fontSize = 18.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotBlank()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.clear))
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Search
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Cyan400,
                        unfocusedBorderColor = Slate700
                    )
                )
                Spacer(Modifier.height(12.dp))

                Button(
                    onClick = { viewModel.searchPlaces(query) },
                    enabled = !searching && query.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Search, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (searching) stringResource(R.string.searching) else stringResource(R.string.search),
                        fontSize = 18.sp
                    )
                }

                if (message != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(message!!, color = Amber400, fontSize = 15.sp)
                }
            }
        }

        if (results.isNotEmpty()) {
            item {
                WeatherCard(title = stringResource(R.string.choose_your_place)) {
                    Text(
                        if (results.size == 1) stringResource(R.string.one_match)
                        else stringResource(R.string.places_match, results.size),
                        color = Slate400,
                        fontSize = 15.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    results.forEach { place ->
                        PlaceRow(place) {
                            viewModel.applyPlace(place)
                            viewModel.clearPlaceResults()
                            query = ""
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    TextButton(
                        onClick = { viewModel.clearPlaceResults() },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text(stringResource(R.string.cancel), fontSize = 16.sp, color = Slate400)
                    }
                }
            }
        }

        item {
            WeatherCard(title = stringResource(R.string.what_should_clock_show)) {
                ModeRow(
                    selectedMode = mode,
                    expanded = modesExpanded,
                    onToggleExpanded = { modesExpanded = !modesExpanded },
                    onSelectMode = viewModel::setWeatherMode
                )
            }
        }

        item {
            WeatherCard(title = stringResource(R.string.when_should_clock_say_forecast)) {
                Text(
                    stringResource(R.string.forecast_announcement_description),
                    color = Slate400,
                    fontSize = 15.sp
                )
                Spacer(Modifier.height(14.dp))
                if (wakeup != null) {
                    TimeStepper(
                        time = wakeup,
                        onChange = viewModel::setWeatherWakeupTime
                    )
                } else {
                    Text(
                        stringResource(R.string.clock_reported_invalid_time, state?.weatherWakeupTime ?: ""),
                        color = Amber400,
                        fontSize = 15.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { viewModel.setWeatherWakeupTime("07:00") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(stringResource(R.string.set_to_0700), fontSize = 18.sp)
                    }
                }
            }
        }

        item {
            WeatherCard(title = stringResource(R.string.clock_time_zone)) {
                Text(
                    stringResource(R.string.which_time_zone_clock_believes),
                    color = Slate400,
                    fontSize = 15.sp
                )
                Spacer(Modifier.height(10.dp))
                if (timezone != null) {
                    Text(
                        timezone,
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                } else {
                    Text(
                        stringResource(R.string.not_reported_by_clock),
                        color = Amber400,
                        fontSize = 15.sp
                    )
                }
                if (savedLabel != null && timezone == null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringResource(R.string.choosing_place_sets_timezone),
                        color = Slate400,
                        fontSize = 14.sp
                    )
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun WeatherCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Slate800),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                title,
                color = Color.White,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun PlaceRow(place: PlaceCandidate, onPick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Slate700)
            .clickable(onClick = onPick)
            .padding(16.dp)
            .heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                place.name,
                color = Color.White,
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold
            )
            if (place.regionLine.isNotBlank()) {
                Text(place.regionLine, color = Slate400, fontSize = 15.sp)
            }
        }
        Icon(Icons.Default.CheckCircle, contentDescription = stringResource(R.string.use_this_place), tint = Cyan400)
    }
}

@Composable
private fun ModeRow(
    selectedMode: Int,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onSelectMode: (Int) -> Unit
) {
    val selected = WeatherMode.fromWire(selectedMode)
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onToggleExpanded)
                .padding(vertical = 14.dp)
                .heightIn(min = 56.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    selected?.label ?: WeatherMode.labelFor(selectedMode),
                    color = Color.White,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold
                )
                if (selected != null) {
                    Text(selected.description, color = Slate400, fontSize = 14.sp)
                }
            }
            Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) stringResource(R.string.hide_list) else stringResource(R.string.show_list),
                tint = Slate400
            )
        }

        if (expanded) {
            Spacer(Modifier.height(8.dp))
            WeatherProvider.entries.forEach { provider ->
                val modes = WeatherMode.selectable.filter { it.provider == provider }
                if (modes.isNotEmpty()) {
                    Text(
                        provider.label.uppercase(),
                        color = if (provider.coversUnitedStatesOnly) Amber400 else Cyan400,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp)
                    )
                    modes.forEach { mode ->
                        ModeOption(
                            mode = mode,
                            selected = mode.wireValue == selectedMode,
                            onSelect = {
                                onSelectMode(mode.wireValue)
                                onToggleExpanded()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeOption(mode: WeatherMode, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onSelect)
            .background(if (selected) Cyan400.copy(alpha = 0.16f) else Color.Transparent)
            .padding(vertical = 10.dp, horizontal = 8.dp)
            .heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(
                mode.label,
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium
            )
            Text(mode.description, color = Slate400, fontSize = 13.sp)
        }
    }
}

@Composable
private fun TimeStepper(time: ClockTime, onChange: (String) -> Unit) {
    Column {
        Text(
            time.format(),
            color = Color.White,
            fontSize = 44.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StepperColumn(
                label = stringResource(R.string.hour),
                value = time.hour,
                onDecrement = { onChange(time.plusMinutes(-60).format()) },
                onIncrement = { onChange(time.plusMinutes(60).format()) }
            )
            StepperColumn(
                label = stringResource(R.string.minute),
                value = time.minute,
                onDecrement = { onChange(time.plusMinutes(-5).format()) },
                onIncrement = { onChange(time.plusMinutes(5).format()) }
            )
        }
    }
}

@Composable
private fun RowScope.StepperColumn(
    label: String,
    value: Int,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit
) {
    Column(
        modifier = Modifier.weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, color = Slate400, fontSize = 15.sp)
        Spacer(Modifier.height(6.dp))
        StepperButton(Icons.Default.KeyboardArrowUp, stringResource(R.string.increase_label, label), onIncrement)
        Text(
            String.format(java.util.Locale.US, "%02d", value),
            color = Color.White,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold
        )
        StepperButton(Icons.Default.KeyboardArrowDown, stringResource(R.string.decrease_label, label), onDecrement)
    }
}

@Composable
private fun StepperButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Slate700)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = Color.White, modifier = Modifier.size(30.dp))
    }
}