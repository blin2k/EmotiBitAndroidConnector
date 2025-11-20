package com.example.emotibitconnector.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.emotibitconnector.EmotiBitUiState
import com.example.emotibitconnector.EmotiBitViewModel
import com.example.emotibitconnector.Logx
import com.example.emotibitconnector.RecordExportTarget
import com.example.emotibitconnector.RecordFileInfo
import com.example.emotibitconnector.UiDiscovered
import com.example.emotibitconnector.UiLogEntry
import com.example.emotibitconnector.network.EmotiBitProto
import com.example.emotibitconnector.ui.theme.EmotiBitConnectorTheme
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun EmotiBitConnectorApp() {
    val viewModel: EmotiBitViewModel = viewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    EmotiBitScreen(
        state = state,
        onDeviceIpChange = viewModel::updateDeviceIp,
        onDpChange = viewModel::updateDp,
        onCpChange = viewModel::updateCp,
        onEcIntervalChange = viewModel::updateEcInterval,
        onUserIdChange = viewModel::updateUserId,
        onStartClick = viewModel::startSession,
        onStopClick = viewModel::stopSession,
        onScanClick = viewModel::scanDevices,
        onUseDiscovered = viewModel::applyDiscovered,
        onStartRecording = viewModel::startRecording,
        onStopRecording = viewModel::stopRecording,
        onSaveAs = viewModel::setRecordUri,
        onExportRecording = viewModel::exportRecording,
        onUploadRecording = viewModel::uploadRecording,
        onDeleteRecording = viewModel::deleteRecording,
        onRefreshRecordings = viewModel::refreshRecordingList,
        onDismissRecordFileStatus = viewModel::clearRecordFileStatus,
        onSendPn = viewModel::sendPn,
        onSendPo = viewModel::sendPo,
        onSendHe = viewModel::sendHe,
        onDismissError = viewModel::clearError,
        onToggleOptionalUi = viewModel::toggleOptionalUi,
        onDismissRecordingBusyPrompt = viewModel::dismissRecordingBusyPrompt,
        onDismissStopSessionBusyPrompt = viewModel::dismissStopSessionBusyPrompt
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmotiBitScreen(
    state: EmotiBitUiState,
    onDeviceIpChange: (String) -> Unit,
    onDpChange: (String) -> Unit,
    onCpChange: (String) -> Unit,
    onEcIntervalChange: (String) -> Unit,
    onUserIdChange: (String) -> Unit,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    onScanClick: () -> Unit,
    onUseDiscovered: (String) -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onSaveAs: (Uri?) -> Unit,
    onExportRecording: (String, RecordExportTarget) -> Unit,
    onUploadRecording: (String) -> Unit,
    onDeleteRecording: (String) -> Unit,
    onRefreshRecordings: () -> Unit,
    onDismissRecordFileStatus: () -> Unit,
    onSendPn: () -> Unit,
    onSendPo: () -> Unit,
    onSendHe: () -> Unit,
    onDismissError: () -> Unit,
    onToggleOptionalUi: () -> Unit,
    onDismissRecordingBusyPrompt: () -> Unit,
    onDismissStopSessionBusyPrompt: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("EmotiBit Connector") }
            )
        }
    ) { innerPadding ->
        val scrollState = rememberScrollState()
        val clipboardManager = LocalClipboardManager.current
        val context = LocalContext.current
        val diagnostics = remember(state) { buildDiagnostics(state) }
        val suggestedName = remember(state.recordResolvedName, state.recordFileStem) {
            ensureCsvExtension(
                when {
                    state.recordResolvedName.isNotBlank() -> state.recordResolvedName
                    state.recordFileStem.isNotBlank() -> state.recordFileStem
                    else -> "EmotiBit-${System.currentTimeMillis()}"
                }
            )
        }
        val saveAsLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("text/csv"),
            onResult = onSaveAs
        )
        val notificationPermissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) {
            onStartClick()
        }

        val handleStartClick = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val granted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
                if (!granted) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    onStartClick()
                }
            } else {
                onStartClick()
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onToggleOptionalUi) {
                    Text(if (state.showOptionalUi) "Hide optional UI" else "Show optional UI")
                }
            }
            if (state.showOptionalUi) {
                Text(
                    text = "Set the EmotiBit device IP (from AP client list) then press Start to open UDP/TCP session.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = state.deviceIpText,
                    onValueChange = onDeviceIpChange,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("Device IP") },
                    placeholder = { Text("e.g. 192.168.50.101") }
                )
                Button(
                    onClick = onScanClick,
                    enabled = !state.isScanning,
                    modifier = Modifier.align(Alignment.CenterVertically)
                ) {
                    Text(if (state.isScanning) "Scanning…" else "Scan")
                }
            }
            if (state.isScanning) {
                Text(
                    text = "Scanning current subnet for EmotiBit…",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (state.discoveredDevices.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Discovered devices",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    state.discoveredDevices.forEach { device ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = buildString {
                                    append(device.ip)
                                    append(" · ")
                                    append(device.deviceId ?: "Unknown")
                                },
                                style = MaterialTheme.typography.bodySmall
                            )
                            TextButton(onClick = { onUseDiscovered(device.ip) }) {
                                Text("Use")
                            }
                        }
                        Divider()
                    }
                }
            }
            if (state.showOptionalUi) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = state.dpText,
                        onValueChange = onDpChange,
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("UDP DP") },
                        placeholder = { Text(EmotiBitProto.DEFAULT_DATA_PORT.toString()) }
                    )
                    OutlinedTextField(
                        value = state.cpText,
                        onValueChange = onCpChange,
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("TCP CP") },
                        placeholder = { Text((EmotiBitProto.DEFAULT_DATA_PORT + 1).toString()) }
                    )
                }
                OutlinedTextField(
                    value = state.ecIntervalText,
                    onValueChange = onEcIntervalChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("EC interval (ms)") },
                    placeholder = { Text("1000") }
                )
                ReadOnlyInfoField("Local Wi-Fi IPv4", state.localWifiIp ?: "<unknown>")
                ReadOnlyInfoField("Broadcast IPv4", state.broadcastIp ?: "<unknown>")
                ReadOnlyInfoField("Subnet Prefix", state.localWifiPrefix?.let { "/$it" } ?: "<unknown>")
                Divider()
                Text(
                    text = "Connection",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            val startStopLabel = when {
                state.isStreaming && state.isStopSessionInProgress -> "Stopping…"
                state.isStreaming -> "Stop listening"
                else -> "Start listening & connect"
            }
            val startStopEnabled = when {
                state.isStreaming -> !state.isStopSessionInProgress
                else -> state.deviceIpText.isNotBlank()
            }
            val startStopAction = if (state.isStreaming) onStopClick else handleStartClick
            Button(
                onClick = startStopAction,
                enabled = startStopEnabled,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(startStopLabel)
            }
            if (state.showOptionalUi) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(onClick = onSendPn, enabled = state.isStreaming) {
                        Text("Send PN")
                    }
                    OutlinedButton(onClick = onSendPo, enabled = state.isStreaming) {
                        Text("Send PO")
                    }
                    OutlinedButton(onClick = onSendHe, enabled = state.deviceIpText.isNotBlank()) {
                        Text("Send HE")
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    TextButton(onClick = {
                        val intent = android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        runCatching { context.startActivity(intent) }
                    }) {
                        Text("Battery optimization tips")
                    }
                }
                Divider()
            } else {
                Divider()
            }
            Text(
                text = "Recording",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            OutlinedTextField(
                value = state.userIdText,
                onValueChange = onUserIdChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("User ID") },
                placeholder = { Text("e.g. PT01") }
            )
            OutlinedTextField(
                value = state.recordResolvedName,
                onValueChange = {},
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Resolved filename") },
                readOnly = true,
                enabled = false,
                colors = TextFieldDefaults.colors(
                    disabledTextColor = MaterialTheme.colorScheme.onSurface,
                    disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    disabledLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
            val recordingLabel = when {
                state.isRecordingCsv && state.isRecordingStopInProgress -> "Stopping recording…"
                state.isRecordingCsv -> "Stop Recording"
                else -> "Start Recording"
            }
            val recordingEnabled = when {
                state.isRecordingCsv -> !state.isRecordingStopInProgress
                else -> true
            }
            val recordingAction = if (state.isRecordingCsv) onStopRecording else onStartRecording
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = recordingAction,
                    enabled = recordingEnabled,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(recordingLabel)
                }
                TextButton(onClick = { saveAsLauncher.launch(suggestedName) }) {
                    Text("Save As…")
                }
            }
            RecordingStatus(state)
            SavedRecordingsSection(
                recordings = state.recordings,
                isBusy = state.isRecordFileOperationRunning,
                inProgressFile = state.recordFileInProgress,
                onExport = onExportRecording,
                onUpload = onUploadRecording,
                onDelete = onDeleteRecording,
                onRefresh = onRefreshRecordings
            )
            state.recordFileStatusMessage?.let { status ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onDismissRecordFileStatus) {
                        Text("Dismiss")
                    }
                }
            }
            state.recordFileErrorMessage?.let { error ->
                ErrorBanner(message = error, onDismiss = onDismissRecordFileStatus)
            }
            state.recordError?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (state.showRecordingBusyDialog) {
                AlertDialog(
                    onDismissRequest = onDismissRecordingBusyPrompt,
                    confirmButton = {
                        TextButton(onClick = onDismissRecordingBusyPrompt) {
                            Text("OK")
                        }
                    },
                    title = { Text("Finishing recording") },
                    text = { Text("Stop Recording is still processing. Please wait…") }
                )
            }
            if (state.showStopSessionBusyDialog) {
                AlertDialog(
                    onDismissRequest = onDismissStopSessionBusyPrompt,
                    confirmButton = {
                        TextButton(onClick = onDismissStopSessionBusyPrompt) {
                            Text("OK")
                        }
                    },
                    title = { Text("Stopping session") },
                    text = { Text("Stop listening is already in progress. Please wait…") }
                )
            }
            state.errorMessage?.let { message ->
                ErrorBanner(message = message, onDismiss = onDismissError)
            }
            if (state.showOptionalUi) {
                Divider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Session log",
                        style = MaterialTheme.typography.titleMedium
                    )
                    OutlinedButton(onClick = {
                        clipboardManager.setText(AnnotatedString(diagnostics))
                        Logx.i("Diagnostics copied to clipboard (logCount=${state.logEntries.size})")
                    }) {
                        Text("Copy diagnostics")
                    }
                }
                val logListState = rememberLazyListState()
                LaunchedEffect(state.logEntries.size) {
                    if (state.logEntries.isNotEmpty()) {
                        logListState.animateScrollToItem(state.logEntries.lastIndex)
                    }
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp, max = 320.dp),
                    state = logListState
                ) {
                    items(state.logEntries, key = { it.id }) { entry ->
                        LogRow(entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordingStatus(state: EmotiBitUiState) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "Recording: ${if (state.isRecordingCsv) "Yes" else "No"}",
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = "Rows: ${state.recordRows}",
            style = MaterialTheme.typography.bodySmall
        )
        state.recordTarget?.let { target ->
            Text(
                text = "Target: $target",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun SavedRecordingsSection(
    recordings: List<RecordFileInfo>,
    isBusy: Boolean,
    inProgressFile: String?,
    onExport: (String, RecordExportTarget) -> Unit,
    onUpload: (String) -> Unit,
    onDelete: (String) -> Unit,
    onRefresh: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Saved recordings (private storage)",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            TextButton(onClick = onRefresh) {
                Text("Refresh")
            }
        }
        if (recordings.isEmpty()) {
            Text(
                text = "No saved recordings yet. Start recording to create a CSV.",
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                recordings.forEachIndexed { index, file ->
                    RecordingFileRow(
                        info = file,
                        isBusy = isBusy && inProgressFile == file.name,
                        buttonsEnabled = !isBusy,
                        onExport = onExport,
                        onUpload = onUpload,
                        onDelete = onDelete
                    )
                    if (index != recordings.lastIndex) {
                        Divider()
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordingFileRow(
    info: RecordFileInfo,
    isBusy: Boolean,
    buttonsEnabled: Boolean,
    onExport: (String, RecordExportTarget) -> Unit,
    onUpload: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    val context = LocalContext.current
    val sizeText = remember(info.sizeBytes) { Formatter.formatShortFileSize(context, info.sizeBytes) }
    val dateFormatter = remember { SimpleDateFormat("MMM d, HH:mm", Locale.US) }
    val dateText = remember(info.lastModifiedMs) { dateFormatter.format(Date(info.lastModifiedMs)) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = info.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "$sizeText • $dateText",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (isBusy) {
                Text(
                    text = "Working…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Button(
                onClick = { onUpload(info.name) },
                enabled = buttonsEnabled && !isBusy
            ) {
                Text("Upload")
            }
            OutlinedButton(
                onClick = { onExport(info.name, RecordExportTarget.Downloads) },
                enabled = buttonsEnabled && !isBusy
            ) {
                Text("Downloads")
            }
            OutlinedButton(
                onClick = { onExport(info.name, RecordExportTarget.Documents) },
                enabled = buttonsEnabled && !isBusy
            ) {
                Text("Documents")
            }
            TextButton(
                onClick = { onDelete(info.name) },
                enabled = buttonsEnabled && !isBusy,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Delete")
            }
        }
    }
}

@Composable
private fun ReadOnlyInfoField(label: String, value: String) {
    OutlinedTextField(
        value = value,
        onValueChange = {},
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(label) },
        readOnly = true,
        enabled = false,
        colors = TextFieldDefaults.colors(
            disabledTextColor = MaterialTheme.colorScheme.onSurface,
            disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            disabledLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    )
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium
        )
        TextButton(onClick = onDismiss) {
            Text("Dismiss", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun LogRow(entry: UiLogEntry) {
    val formatter = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    val time = formatter.format(Date(entry.timestamp))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Text(
            text = "$time • ${entry.message}",
            style = MaterialTheme.typography.bodySmall
        )
        Divider(modifier = Modifier.padding(top = 8.dp))
    }
}

private fun buildDiagnostics(state: EmotiBitUiState): String {
    val builder = StringBuilder()
    builder.appendLine("=== EmotiBit Diagnostics Snapshot ===")
    builder.appendLine("deviceIp=${state.deviceIpText.ifBlank { "<unset>" }} dp=${state.dpText} cp=${state.cpText} interval=${state.ecIntervalText}")
    builder.appendLine(
        "localWiFiIp=${state.localWifiIp ?: "<unknown>"} broadcast=${state.broadcastIp ?: "<unknown>"} prefix=${state.localWifiPrefix?.let { "/$it" } ?: "<unknown>"}"
    )
    builder.appendLine("discoveries=${state.discoveredDevices.size} scanning=${state.isScanning}")
    builder.appendLine("packets=${state.packetsRx} lastSender=${state.lastSender ?: "<none>"}")
    builder.appendLine("lastPayload=${state.lastPayloadPreview ?: "<none>"}")
    builder.appendLine("recording=${state.isRecordingCsv} rows=${state.recordRows} target=${state.recordTarget ?: "<none>"}")
    builder.appendLine("logs (last ${state.logEntries.takeLast(20).size} lines)")
    state.logEntries.takeLast(20).forEach { entry ->
        builder.appendLine("${entry.timestamp}: ${entry.message}")
    }
    return builder.toString()
}

private fun ensureCsvExtension(name: String): String {
    if (name.isBlank()) return "EmotiBit-${System.currentTimeMillis()}.csv"
    return if (name.lowercase(Locale.US).endsWith(".csv")) name else "$name.csv"
}

@Preview(showBackground = true)
@Composable
private fun EmotiBitScreenPreview() {
    EmotiBitConnectorTheme {
        EmotiBitScreen(
            state = EmotiBitUiState(
                deviceIpText = "192.168.50.10",
                dpText = "3132",
                cpText = "3133",
                ecIntervalText = "1000",
                userIdText = "PT01",
                localWifiIp = "192.168.50.5",
                broadcastIp = "192.168.50.255",
                localWifiPrefix = 24,
                isStreaming = true,
                isScanning = false,
                packetsRx = 42,
                lastSender = "192.168.50.20:40000",
                lastPayloadPreview = "1761288846,77,4,EC,1,100,CP,3133,DP,3132",
                logEntries = listOf(
                    UiLogEntry(1, "Session established dp=3132 cp=3133", System.currentTimeMillis()),
                    UiLogEntry(2, "UDP packet received", System.currentTimeMillis())
                ),
                discoveredDevices = listOf(UiDiscovered("192.168.50.36", "MD-V5-0000241")),
                recordFileStem = "PT01-20250101",
                recordResolvedName = "PT01-20250101.csv",
                isRecordingCsv = true,
                recordRows = 1200,
                recordTarget = ".../files/EmotiBit/EmotiBit-20250101-000000.csv",
                isRecordFileOperationRunning = false,
                recordFileStatusMessage = "Exported EmotiBit-20250101-000000.csv to Downloads",
                recordings = listOf(
                    RecordFileInfo(
                        name = "EmotiBit-20250101-000000.csv",
                        absolutePath = "/data/user/0/com.example/emotibit/EmotiBit-20250101-000000.csv",
                        sizeBytes = 120_000,
                        lastModifiedMs = System.currentTimeMillis()
                    ),
                    RecordFileInfo(
                        name = "EmotiBit-20250102-010101.csv",
                        absolutePath = "/data/user/0/com.example/emotibit/EmotiBit-20250102-010101.csv",
                        sizeBytes = 42_000,
                        lastModifiedMs = System.currentTimeMillis() - 86_400_000
                    )
                )
            ),
            onDeviceIpChange = {},
            onDpChange = {},
            onCpChange = {},
            onEcIntervalChange = {},
            onUserIdChange = {},
            onStartClick = {},
            onStopClick = {},
            onScanClick = {},
            onUseDiscovered = {},
            onStartRecording = {},
            onStopRecording = {},
            onSaveAs = {},
            onExportRecording = { _, _ -> },
            onUploadRecording = {},
            onDeleteRecording = {},
            onRefreshRecordings = {},
            onDismissRecordFileStatus = {},
            onSendPn = {},
            onSendPo = {},
            onSendHe = {},
            onDismissError = {},
            onToggleOptionalUi = {},
            onDismissRecordingBusyPrompt = {},
            onDismissStopSessionBusyPrompt = {}
        )
    }
}
