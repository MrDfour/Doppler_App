package com.sandman.doppler.ui.screens

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sandman.doppler.api.CopilotCloudAuthClient
import com.sandman.doppler.api.CloudThingItem
import com.sandman.doppler.api.DiscoveredDoppler
import com.sandman.doppler.api.DopplerDiscovery
import com.sandman.doppler.security.TokenStore
import com.sandman.doppler.ui.theme.*
import com.sandman.doppler.viewmodel.DopplerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Input validation helpers
private val IPV4_REGEX = Regex(
    "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$"
)
private val DSN_REGEX = Regex("^[A-Za-z0-9][A-Za-z0-9\\-]{2,}$")

private fun isValidIpAddress(ip: String): Boolean = IPV4_REGEX.matches(ip.trim())
private fun isValidPort(portStr: String): Boolean {
    val port = portStr.toIntOrNull() ?: return false
    return port in 1..65535
}
private fun isValidDsn(dsn: String): Boolean = dsn.isNotBlank() && DSN_REGEX.matches(dsn.trim())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: DopplerViewModel, tokenStore: TokenStore, onReconnect: () -> Unit = {}) {
    var selectedTab by remember { mutableStateOf(0) } // 0: Cloud Login, 1: Manual Key, 2: LAN Scan

    // Cloud Login state
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var cloudLoading by remember { mutableStateOf(false) }
    var cloudError by remember { mutableStateOf<String?>(null) }
    var cloudSuccess by remember { mutableStateOf<String?>(null) }
    var fetchedThings by remember { mutableStateOf<List<CloudThingItem>>(emptyList()) }
    var selectedThing by remember { mutableStateOf<CloudThingItem?>(null) }

    // Manual Key state
    var manualIp by remember { mutableStateOf(tokenStore.savedIpAddress) }
    var manualPort by remember { mutableStateOf(tokenStore.savedPort.toString()) }
    var manualDsn by remember { mutableStateOf(tokenStore.savedDsn ?: "") }
    var manualKey by remember { mutableStateOf(tokenStore.authToken ?: "") }
    var manualName by remember { mutableStateOf(tokenStore.deviceFriendlyName ?: "") }
    var manualSavedSuccess by remember { mutableStateOf(false) }
    var manualValidationError by remember { mutableStateOf<String?>(null) }
    var testingConnection by remember { mutableStateOf(false) }
    var testConnectionResult by remember { mutableStateOf<String?>(null) }

    // LAN Scan state
    val context = LocalContext.current
    val discovery = remember { DopplerDiscovery(context) }
    val discoveredDevices by discovery.discoveredDevices.collectAsState()
    val scanProgress by discovery.scanProgress.collectAsState()
    val isScanActive by discovery.isScanning.collectAsState()

    val coroutineScope = rememberCoroutineScope()

    // Dark-themed text field colors
    val textFieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Cyan400,
        unfocusedBorderColor = Slate700,
        focusedLabelColor = Cyan400,
        unfocusedLabelColor = Slate400,
        focusedTextColor = Color.White,
        unfocusedTextColor = Color.White,
        cursorColor = Cyan400,
        focusedLeadingIconColor = Cyan400,
        unfocusedLeadingIconColor = Slate400,
        focusedTrailingIconColor = Cyan400,
        unfocusedTrailingIconColor = Slate400,
        errorBorderColor = Rose500,
        errorLabelColor = Rose500,
        errorLeadingIconColor = Rose500
    )

    val state by viewModel.deviceState.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Slate900)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        item {
            Text(
                text = "Doppler Onboarding & Settings",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }

        // Current status card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Slate800),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Router, contentDescription = null, tint = Cyan400)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "Active Configuration", color = Cyan400, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.weight(1f))
                        val statusColor = if (tokenStore.isConfigured) Emerald400 else Amber400
                        val statusText = if (tokenStore.isConfigured) "Ready" else "Not Configured"
                        Surface(
                            color = statusColor.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = statusText,
                                color = statusColor,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Name: ${tokenStore.deviceFriendlyName ?: "Not Set"}",
                        color = Color.LightGray, fontSize = 14.sp
                    )
                    Text(
                        text = "DSN: ${tokenStore.savedDsn ?: "Not Configured"}",
                        color = Color.LightGray, fontSize = 14.sp
                    )
                    Text(
                        text = "Host: ${tokenStore.savedIpAddress}:${tokenStore.savedPort} (HTTPS Port 5443)",
                        color = Color.LightGray, fontSize = 14.sp
                    )
                    Text(
                        text = "Local Key: ${if (!tokenStore.authToken.isNullOrEmpty()) "Configured (Secure)" else "Missing"}",
                        color = Color.LightGray, fontSize = 14.sp
                    )
                }
            }
        }

        // Tab Row
        item {
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Slate800,
                contentColor = Cyan400
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Cloud Login") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Manual Entry") }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("LAN Scan") }
                )
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }

        // Tab Content - embedded in a fixed-size Box so LazyColumn doesn't conflict with inner LazyColumns
        item {
            Box(modifier = Modifier.fillMaxWidth().height(560.dp)) {
                when (selectedTab) {
                    0 -> CloudLoginTab(
                        email = email,
                        onEmailChange = { email = it },
                        password = password,
                        onPasswordChange = { password = it },
                        cloudLoading = cloudLoading,
                        cloudError = cloudError,
                        cloudSuccess = cloudSuccess,
                        fetchedThings = fetchedThings,
                        textFieldColors = textFieldColors,
                        tokenStore = tokenStore,
                        onReconnect = onReconnect,
                        coroutineScope = coroutineScope,
                        onStateUpdate = { loading, error, success, things ->
                            cloudLoading = loading
                            cloudError = error
                            cloudSuccess = success
                            fetchedThings = things
                        }
                    )
                    1 -> ManualEntryTab(
                        manualIp = manualIp,
                        onIpChange = { manualIp = it; manualValidationError = null },
                        manualPort = manualPort,
                        onPortChange = { manualPort = it; manualValidationError = null },
                        manualDsn = manualDsn,
                        onDsnChange = { manualDsn = it; manualValidationError = null },
                        manualKey = manualKey,
                        onKeyChange = { manualKey = it; manualValidationError = null },
                        manualName = manualName,
                        onNameChange = { manualName = it },
                        manualSavedSuccess = manualSavedSuccess,
                        manualValidationError = manualValidationError,
                        testingConnection = testingConnection,
                        testConnectionResult = testConnectionResult,
                        textFieldColors = textFieldColors,
                        context = context,
                        tokenStore = tokenStore,
                        onReconnect = onReconnect,
                        coroutineScope = coroutineScope,
                        onSave = { saved, error, testing, testResult ->
                            manualSavedSuccess = saved
                            manualValidationError = error
                            testingConnection = testing
                            testConnectionResult = testResult
                        }
                    )
                    2 -> LanScanTab(
                        discoveredDevices = discoveredDevices,
                        scanProgress = scanProgress,
                        isScanActive = isScanActive,
                        discovery = discovery,
                        tokenStore = tokenStore,
                        onReconnect = onReconnect,
                        coroutineScope = coroutineScope
                    )
                }
            }
        }

        // Divider before Clock Settings
        item { HorizontalDivider(color = Slate800, modifier = Modifier.padding(vertical = 8.dp)) }

        // ── Clock Behavior Settings ──────────────────────────────────────
        item {
            Text(
                text = "Clock Behavior",
                color = Cyan400,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Slate800),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    // 24-Hour Mode
                    ClockToggleRow(
                        title = "24-Hour Mode",
                        description = "Display time in 24-hour format instead of 12h AM/PM",
                        checked = state?.time24Hour ?: false,
                        onCheckedChange = { viewModel.setTime24Hour(it) }
                    )
                    HorizontalDivider(color = Slate700)
                    // Colon Visible
                    ClockToggleRow(
                        title = "Show Colon",
                        description = "Display the colon separator between hours and minutes",
                        checked = state?.colonVisible ?: true,
                        onCheckedChange = { viewModel.setUseColon(it) }
                    )
                    HorizontalDivider(color = Slate700)
                    // Colon Blink
                    ClockToggleRow(
                        title = "Colon Blink",
                        description = "Blink the colon separator each second",
                        checked = state?.colonBlink ?: true,
                        onCheckedChange = { viewModel.setColonBlink(it) }
                    )
                    HorizontalDivider(color = Slate700)
                    // Leading Zero
                    ClockToggleRow(
                        title = "Leading Zero (24h)",
                        description = "Show leading zero in 24h mode (e.g. 08:00 vs 8:00)",
                        checked = state?.leadingZero24Hour ?: false,
                        onCheckedChange = { viewModel.setLeadingZero(it) }
                    )
                    HorizontalDivider(color = Slate700)
                    // Fade Time
                    ClockToggleRow(
                        title = "Fade Transition",
                        description = "Fade digit segments during minute transitions",
                        checked = state?.fadeTimeMode ?: true,
                        onCheckedChange = { viewModel.setFadeTime(it) }
                    )
                    HorizontalDivider(color = Slate700)
                    // Display Seconds on Mini
                    ClockToggleRow(
                        title = "Show Seconds on Mini Display",
                        description = "Use the secondary 7-segment display to show current seconds",
                        checked = state?.displaySecondsOnMini ?: false,
                        onCheckedChange = { viewModel.setDisplaySeconds(it) }
                    )
                }
            }
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

@Composable
private fun ClockToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(description, color = Slate400, fontSize = 12.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Slate950,
                checkedTrackColor = Cyan400,
                uncheckedThumbColor = Slate400,
                uncheckedTrackColor = Slate700
            )
        )
    }
}



@Composable
private fun CloudLoginTab(
    email: String,
    onEmailChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    cloudLoading: Boolean,
    cloudError: String?,
    cloudSuccess: String?,
    fetchedThings: List<CloudThingItem>,
    textFieldColors: TextFieldColors,
    tokenStore: TokenStore,
    onReconnect: () -> Unit,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    onStateUpdate: (loading: Boolean, error: String?, success: String?, things: List<CloudThingItem>) -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Text(
                "Log into your Sandman Doppler account to automatically provision your device Local Key and DSN directly from the cloud.",
                color = Slate400, fontSize = 13.sp
            )
            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = email,
                onValueChange = onEmailChange,
                label = { Text("Email Address") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Email, contentDescription = null) },
                colors = textFieldColors,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = password,
                onValueChange = onPasswordChange,
                label = { Text("Password") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                visualTransformation = PasswordVisualTransformation(),
                colors = textFieldColors,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    onStateUpdate(true, null, null, emptyList())
                    coroutineScope.launch {
                        val client = CopilotCloudAuthClient()
                        val loginResult = client.login(email, password)
                        if (loginResult.isSuccess) {
                            val token = loginResult.getOrNull()!!
                            val thingsResult = client.fetchThings(token)
                            if (thingsResult.isSuccess) {
                                val things = thingsResult.getOrNull()!!
                                if (things.isEmpty()) {
                                    onStateUpdate(false, "No Sandman Doppler devices found on this account.", null, emptyList())
                                } else {
                                    onStateUpdate(false, null, null, things)
                                }
                            } else {
                                onStateUpdate(false, thingsResult.exceptionOrNull()?.localizedMessage ?: "Failed to fetch things", null, emptyList())
                            }
                        } else {
                            onStateUpdate(false, loginResult.exceptionOrNull()?.localizedMessage ?: "Cloud login failed", null, emptyList())
                        }
                    }
                },
                enabled = email.isNotBlank() && password.isNotBlank() && !cloudLoading,
                colors = ButtonDefaults.buttonColors(containerColor = Cyan400),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (cloudLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Slate900)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Authenticating...", color = Slate900)
                } else {
                    Icon(Icons.Default.Cloud, contentDescription = null, tint = Slate900)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Fetch My Dopplers", color = Slate900, fontWeight = FontWeight.Bold)
                }
            }

            if (cloudError != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = Rose500.copy(alpha = 0.15f)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Error, contentDescription = null, tint = Rose500, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = cloudError, color = Rose500, fontSize = 13.sp)
                    }
                }
            }

            if (cloudSuccess != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = Emerald400.copy(alpha = 0.15f)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Emerald400, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = cloudSuccess, color = Emerald400, fontSize = 13.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        items(fetchedThings) { thing ->
            Card(
                colors = CardDefaults.cardColors(containerColor = Slate800),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                onClick = {
                    coroutineScope.launch {
                        onStateUpdate(true, null, null, fetchedThings)
                        val client = CopilotCloudAuthClient()
                        val loginRes = client.login(email, password)
                        if (loginRes.isSuccess) {
                            val token = loginRes.getOrNull()!!
                            val keyRes = client.fetchLocalKey(thing.dsn, token)
                            if (keyRes.isSuccess) {
                                val keyData = keyRes.getOrNull()!!
                                tokenStore.savedDsn = thing.dsn
                                tokenStore.authToken = keyData.localKey
                                if (!keyData.ipAddie.isNullOrEmpty()) {
                                    tokenStore.savedIpAddress = keyData.ipAddie
                                }
                                // Real local port comes from the cloud localkey response (default 5443)
                                tokenStore.savedPort = keyData.port ?: 5443
                                tokenStore.deviceFriendlyName = thing.name ?: "Sandman Doppler"
                                onStateUpdate(false, null, "Successfully provisioned ${thing.dsn}!", fetchedThings)
                                onReconnect()
                            } else {
                                onStateUpdate(false, "Failed to retrieve localKey for ${thing.dsn}", null, fetchedThings)
                            }
                        } else {
                            onStateUpdate(false, "Re-authentication failed", null, fetchedThings)
                        }
                    }
                }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = thing.name ?: "Sandman Doppler", color = Cyan400, fontWeight = FontWeight.Bold)
                        Text(text = "DSN: ${thing.dsn}", color = Color.White, fontSize = 13.sp)
                        if (!thing.ipAddie.isNullOrEmpty()) {
                            Text(text = "IP: ${thing.ipAddie}", color = Slate400, fontSize = 12.sp)
                        }
                    }
                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Slate400)
                }
            }
        }
    }
}

@Composable
private fun ManualEntryTab(
    manualIp: String,
    onIpChange: (String) -> Unit,
    manualPort: String,
    onPortChange: (String) -> Unit,
    manualDsn: String,
    onDsnChange: (String) -> Unit,
    manualKey: String,
    onKeyChange: (String) -> Unit,
    manualName: String,
    onNameChange: (String) -> Unit,
    manualSavedSuccess: Boolean,
    manualValidationError: String?,
    testingConnection: Boolean,
    testConnectionResult: String?,
    textFieldColors: TextFieldColors,
    context: Context,
    tokenStore: TokenStore,
    onReconnect: () -> Unit,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    onSave: (saved: Boolean, error: String?, testing: Boolean, testResult: String?) -> Unit
) {
    val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Text(
                "Directly enter your Sandman Doppler local IP, DSN, and Local Key for 100% air-gapped / offline operation.",
                color = Slate400, fontSize = 13.sp
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Device Name
            OutlinedTextField(
                value = manualName,
                onValueChange = onNameChange,
                label = { Text("Device Name (optional)") },
                placeholder = { Text("e.g. Bedroom Doppler", color = Slate700) },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Label, contentDescription = null) },
                colors = textFieldColors,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))

            // IP Address
            OutlinedTextField(
                value = manualIp,
                onValueChange = onIpChange,
                label = { Text("Doppler IP Address") },
                placeholder = { Text("e.g. 192.168.1.142", color = Slate700) },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Wifi, contentDescription = null) },
                isError = manualIp.isNotEmpty() && !isValidIpAddress(manualIp),
                supportingText = if (manualIp.isNotEmpty() && !isValidIpAddress(manualIp)) {
                    { Text("Invalid IPv4 address format", color = Rose500, fontSize = 11.sp) }
                } else null,
                colors = textFieldColors,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))

            // Port
            OutlinedTextField(
                value = manualPort,
                onValueChange = onPortChange,
                label = { Text("Port (Default: 5443)") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.SettingsEthernet, contentDescription = null) },
                isError = manualPort.isNotEmpty() && !isValidPort(manualPort),
                supportingText = if (manualPort.isNotEmpty() && !isValidPort(manualPort)) {
                    { Text("Port must be 1–65535", color = Rose500, fontSize = 11.sp) }
                } else null,
                colors = textFieldColors,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))

            // DSN
            OutlinedTextField(
                value = manualDsn,
                onValueChange = onDsnChange,
                label = { Text("Device DSN") },
                placeholder = { Text("e.g. Doppler-12345678", color = Slate700) },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Fingerprint, contentDescription = null) },
                isError = manualDsn.isNotEmpty() && !isValidDsn(manualDsn),
                supportingText = if (manualDsn.isNotEmpty() && !isValidDsn(manualDsn)) {
                    { Text("DSN must be alphanumeric (3+ chars, hyphens allowed)", color = Rose500, fontSize = 11.sp) }
                } else null,
                colors = textFieldColors,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))

            // Local Key with paste button
            OutlinedTextField(
                value = manualKey,
                onValueChange = onKeyChange,
                label = { Text("Local Cryptographic Key (localKey)") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
                trailingIcon = {
                    IconButton(onClick = {
                        val clip = clipboardManager.primaryClip
                        if (clip != null && clip.itemCount > 0) {
                            val pastedText = clip.getItemAt(0).text?.toString() ?: ""
                            onKeyChange(pastedText)
                        }
                    }) {
                        Icon(Icons.Default.ContentPaste, contentDescription = "Paste from clipboard")
                    }
                },
                colors = textFieldColors,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(16.dp))

            // Validation error
            if (manualValidationError != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Rose500.copy(alpha = 0.15f)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Error, contentDescription = null, tint = Rose500, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = manualValidationError, color = Rose500, fontSize = 13.sp)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Test Connection button
            OutlinedButton(
                onClick = {
                    // Validate inputs first
                    if (!isValidIpAddress(manualIp)) {
                        onSave(false, "Invalid IP address format", false, null)
                        return@OutlinedButton
                    }
                    if (!isValidPort(manualPort)) {
                        onSave(false, "Port must be between 1 and 65535", false, null)
                        return@OutlinedButton
                    }

                    onSave(false, null, true, null)
                    coroutineScope.launch {
                        try {
                            val testApi = com.sandman.doppler.api.DopplerLocalApi(
                                host = manualIp.trim(),
                                port = manualPort.trim().toIntOrNull() ?: 5443,
                                dsn = manualDsn.trim().ifBlank { "test" },
                                localKey = manualKey.trim()
                            )
                            val info = testApi.getDeviceInfo()
                            val resultMsg = "Connected! Device: ${info.mfgrName ?: "Sandman"} — ${info.serialNum ?: "Unknown DSN"}"
                            onSave(false, null, false, resultMsg)
                        } catch (e: Exception) {
                            onSave(false, null, false, "Connection failed: ${e.message?.take(80) ?: "Unknown error"}")
                        }
                    }
                },
                enabled = !testingConnection,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Cyan400),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (testingConnection) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Cyan400, strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Testing Connection...")
                } else {
                    Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Test Connection")
                }
            }

            // Test connection result
            if (testConnectionResult != null) {
                Spacer(modifier = Modifier.height(8.dp))
                val isSuccess = testConnectionResult.startsWith("Connected")
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSuccess) Emerald400.copy(alpha = 0.15f) else Rose500.copy(alpha = 0.15f)
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (isSuccess) Icons.Default.CheckCircle else Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (isSuccess) Emerald400 else Rose500,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = testConnectionResult,
                            color = if (isSuccess) Emerald400 else Rose500,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Save & Connect button
            Button(
                onClick = {
                    // Validate all inputs
                    if (!isValidIpAddress(manualIp)) {
                        onSave(false, "Invalid IP address format (e.g. 192.168.1.142)", false, testConnectionResult)
                        return@Button
                    }
                    if (!isValidPort(manualPort)) {
                        onSave(false, "Port must be between 1 and 65535", false, testConnectionResult)
                        return@Button
                    }
                    if (manualDsn.isNotBlank() && !isValidDsn(manualDsn)) {
                        onSave(false, "Invalid DSN format", false, testConnectionResult)
                        return@Button
                    }
                    if (manualKey.isBlank()) {
                        onSave(false, "Local Key is required for authentication", false, testConnectionResult)
                        return@Button
                    }

                    tokenStore.savedIpAddress = manualIp.trim()
                    tokenStore.savedPort = manualPort.trim().toIntOrNull() ?: 5443
                    tokenStore.savedDsn = manualDsn.trim()
                    tokenStore.authToken = manualKey.trim()
                    if (manualName.isNotBlank()) {
                        tokenStore.deviceFriendlyName = manualName.trim()
                    }
                    onSave(true, null, false, testConnectionResult)
                    onReconnect()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Cyan400),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Save, contentDescription = null, tint = Slate900)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Save & Connect", color = Slate900, fontWeight = FontWeight.Bold)
            }

            if (manualSavedSuccess) {
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = Emerald400.copy(alpha = 0.15f)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Emerald400, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Configuration saved successfully!", color = Emerald400, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanScanTab(
    discoveredDevices: List<DiscoveredDoppler>,
    scanProgress: Float,
    isScanActive: Boolean,
    discovery: DopplerDiscovery,
    tokenStore: TokenStore,
    onReconnect: () -> Unit,
    coroutineScope: kotlinx.coroutines.CoroutineScope
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            "Scan your local Wi-Fi network for Sandman Doppler clocks on HTTPS port 5443.",
            color = Slate400, fontSize = 13.sp
        )
        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = {
                if (isScanActive) {
                    discovery.cancelScan()
                } else {
                    coroutineScope.launch {
                        discovery.clearDiscoveredDevices()
                        discovery.startMdnsDiscovery()
                        withContext(Dispatchers.IO) {
                            discovery.probeSubnet(port = 5443, concurrency = 16)
                        }
                    }
                }
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isScanActive) Rose500 else Cyan400
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isScanActive) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Cancel Scan", color = Color.White)
            } else {
                Icon(Icons.Default.Radar, contentDescription = null, tint = Slate900)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Start LAN Scan", color = Slate900, fontWeight = FontWeight.Bold)
            }
        }

        // Progress bar during scan
        if (isScanActive) {
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { scanProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = Cyan400,
                trackColor = Slate700
            )
            Text(
                text = "Scanning subnet... ${(scanProgress * 100).toInt()}%",
                color = Slate400,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (discoveredDevices.isEmpty() && !isScanActive) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Slate800),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.SearchOff, contentDescription = null, tint = Slate400, modifier = Modifier.size(48.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("No devices found yet", color = Slate400, fontSize = 14.sp)
                    Text("Tap 'Start LAN Scan' to search your network", color = Slate700, fontSize = 12.sp)
                }
            }
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(discoveredDevices) { device ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = Slate800),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    onClick = {
                        tokenStore.savedIpAddress = device.host
                        tokenStore.savedPort = device.port
                        if (device.dsn != null) {
                            tokenStore.savedDsn = device.dsn
                        }
                        tokenStore.deviceFriendlyName = device.name
                        onReconnect()
                    }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = device.name, color = Cyan400, fontWeight = FontWeight.Bold)
                            Text(text = "Host: ${device.host}:${device.port}", color = Color.White, fontSize = 13.sp)
                            if (device.dsn != null) {
                                Text(text = "DSN: ${device.dsn}", color = Slate400, fontSize = 12.sp)
                            }
                        }
                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Slate400)
                    }
                }
            }
        }
    }
}
