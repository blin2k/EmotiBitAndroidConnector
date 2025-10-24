package com.example.emotibitconnector.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.emotibitconnector.CommandMode
import com.example.emotibitconnector.EmotiBitUiNonOscPacket
import com.example.emotibitconnector.EmotiBitUiPacket
import com.example.emotibitconnector.EmotiBitUiState
import com.example.emotibitconnector.EmotiBitViewModel
import com.example.emotibitconnector.ConnectionState
import com.example.emotibitconnector.LogDirection
import com.example.emotibitconnector.UiLogEntry
import com.example.emotibitconnector.network.EmotiBitProto
import com.example.emotibitconnector.network.UdpChannel
import com.example.emotibitconnector.ui.theme.EmotiBitConnectorTheme

@Composable
fun EmotiBitConnectorApp() {
    val viewModel: EmotiBitViewModel = viewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    EmotiBitScreen(
        state = state,
        onDeviceAddressChange = viewModel::updateDeviceAddress,
        onListenPortChange = viewModel::updateListenPort,
        onAdvertisePortChange = viewModel::updateAdvertisePort,
        onControlPortChange = viewModel::updateControlPort,
        onCommandModeChange = viewModel::updateCommandMode,
        onScanClick = viewModel::scan,
        onStartClick = viewModel::startListening,
        onSendStartClick = viewModel::sendStart,
        onStopClick = viewModel::stopAll,
        onClearLogClick = viewModel::clearLog,
        onDismissError = viewModel::clearError
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmotiBitScreen(
    state: EmotiBitUiState,
    onDeviceAddressChange: (String) -> Unit,
    onListenPortChange: (String) -> Unit,
    onAdvertisePortChange: (String) -> Unit,
    onControlPortChange: (String) -> Unit,
    onCommandModeChange: (CommandMode) -> Unit,
    onScanClick: () -> Unit,
    onStartClick: () -> Unit,
    onSendStartClick: () -> Unit,
    onStopClick: () -> Unit,
    onClearLogClick: () -> Unit,
    onDismissError: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("EmotiBit Connector") }
            )
        }
    ) { innerPadding ->
        val scrollState = rememberScrollState()
        val isDiscovering = state.connectionState is ConnectionState.Discovering
        val isConnecting = state.connectionState is ConnectionState.Connecting
        val isStreaming = state.connectionState is ConnectionState.Streaming
        val isListening = isConnecting || isStreaming
        val canSendStart = isListening && state.commandMode != CommandMode.PASSIVE

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Ensure EmotiBit and this phone join Device A's Wi-Fi access point before streaming.",
                style = MaterialTheme.typography.bodyMedium
            )
            state.localWifiIp?.let { localIp ->
                Text(
                    text = "Local Wi-Fi IPv4: $localIp",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            state.broadcastAddress?.let { broadcast ->
                Text(
                    text = "Broadcast IPv4: $broadcast",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            ConnectionStatusSummary(state.connectionState)
            if (state.discoveredDeviceId != null || state.discoveredFirmware != null) {
                val info = buildString {
                    append("Last discovery → ")
                    state.discoveredDeviceId?.let { append("ID $it ") }
                    state.deviceIpInput.takeIf { it.isNotBlank() }?.let { append("IP ${state.deviceIpInput} ") }
                    state.discoveredFirmware?.let { append("FW $it") }
                }
                Text(info.trim(), style = MaterialTheme.typography.bodySmall)
            }
            CommandModeSelector(
                selectedMode = state.commandMode,
                onSelectionChange = onCommandModeChange
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = state.listenPortInput,
                    onValueChange = onListenPortChange,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("Listen port") },
                    placeholder = { Text(EmotiBitProto.DEFAULT_DATA_PORT_HINT.toString()) }
                )
                OutlinedTextField(
                    value = state.advertisePortInput,
                    onValueChange = onAdvertisePortChange,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("Advertise port") },
                    placeholder = { Text(EmotiBitProto.DEFAULT_ADVERTISE_PORT.toString()) }
                )
            }
            OutlinedTextField(
                value = state.controlPortInput,
                onValueChange = onControlPortChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Control port") },
                placeholder = { Text(EmotiBitProto.DEFAULT_CONTROL_PORT.toString()) }
            )
            if (state.commandMode == CommandMode.UNICAST_CMD) {
                OutlinedTextField(
                    value = state.deviceIpInput,
                    onValueChange = onDeviceAddressChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("EmotiBit IPv4 (UNICAST)") },
                    placeholder = { Text("e.g. 192.168.50.101") }
                )
            }
            HintCard()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onScanClick,
                    enabled = !isDiscovering
                ) {
                    Text(if (isDiscovering) "Scanning…" else "Scan")
                }
                Button(
                    onClick = onStartClick,
                    enabled = !isListening
                ) {
                    Text("Start listening")
                }
                OutlinedButton(
                    onClick = onStopClick,
                    enabled = isListening
                ) {
                    Text("Stop")
                }
            }
            OutlinedButton(
                onClick = onSendStartClick,
                enabled = canSendStart
            ) {
                Text("Send start command")
            }
            state.errorMessage?.let { message ->
                ErrorBanner(message = message, onDismiss = onDismissError)
            }
            state.infoMessage?.let { message ->
                InfoBanner(message = message)
            }
            Divider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Network log",
                    style = MaterialTheme.typography.titleMedium
                )
                OutlinedButton(onClick = onClearLogClick, enabled = state.logEntries.isNotEmpty()) {
                    Text("Clear log")
                }
            }
            val logListState = rememberLazyListState()
            LaunchedEffect(state.logEntries.size) {
                if (state.logEntries.isNotEmpty()) {
                    logListState.animateScrollToItem(state.logEntries.lastIndex)
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 160.dp, max = 320.dp)
            ) {
                if (state.logEntries.isEmpty()) {
                    EmptyState(modifier = Modifier.fillMaxSize())
                } else {
                    LazyColumn(
                        state = logListState,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(state.logEntries, key = { it.id }) { entry ->
                            LogEntryRow(entry)
                        }
                    }
                }
            }
            Divider()
            Text(
                text = "Recent OSC packets",
                style = MaterialTheme.typography.titleMedium
            )
            val oscListState = rememberLazyListState()
            LaunchedEffect(state.packetLog.size) {
                if (state.packetLog.isNotEmpty()) {
                    oscListState.animateScrollToItem(state.packetLog.lastIndex)
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 160.dp, max = 360.dp)
            ) {
                if (state.packetLog.isEmpty()) {
                    EmptyState(modifier = Modifier.fillMaxSize())
                } else {
                    LazyColumn(
                        state = oscListState,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(state.packetLog, key = { it.id }) { packet ->
                            PacketRow(packet)
                        }
                    }
                }
            }
            Divider()
            Text(
                text = "Non-OSC packets",
                style = MaterialTheme.typography.titleMedium
            )
            val nonOscListState = rememberLazyListState()
            LaunchedEffect(state.nonOscPacketLog.size) {
                if (state.nonOscPacketLog.isNotEmpty()) {
                    nonOscListState.animateScrollToItem(state.nonOscPacketLog.lastIndex)
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 280.dp)
            ) {
                if (state.nonOscPacketLog.isEmpty()) {
                    NonOscEmptyState()
                } else {
                    LazyColumn(
                        state = nonOscListState,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(state.nonOscPacketLog, key = { it.id }) { packet ->
                            NonOscPacketRow(packet)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommandModeSelector(
    selectedMode: CommandMode,
    onSelectionChange: (CommandMode) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "Command strategy",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        val modes = remember { CommandMode.values().toList() }
        modes.forEach { mode ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RadioButton(
                    selected = selectedMode == mode,
                    onClick = { onSelectionChange(mode) },
                    colors = RadioButtonDefaults.colors()
                )
                Column {
                    Text(mode.toUserFacingLabel(), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = mode.toDescription(),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

private fun CommandMode.toUserFacingLabel(): String = when (this) {
    CommandMode.PASSIVE -> "Passive"
    CommandMode.BROADCAST_CMD -> "Broadcast command"
    CommandMode.UNICAST_CMD -> "Unicast command"
}

private fun CommandMode.toDescription(): String = when (this) {
    CommandMode.PASSIVE -> "Listen only; show data if the EmotiBit broadcasts telemetry."
    CommandMode.BROADCAST_CMD -> "Send a start message to the Wi-Fi subnet broadcast address."
    CommandMode.UNICAST_CMD -> "Send a start message directly to a known EmotiBit IP."
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
private fun InfoBanner(message: String) {
    Text(
        text = message,
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.bodyMedium
    )
}

@Composable
private fun HintCard() {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = "Hint: If Device A (AP) enables client isolation, broadcast commands may be blocked. Use UNICAST_CMD with the EmotiBit IP from the AP's client list.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(12.dp)
        )
    }
}

@Composable
private fun LogEntryRow(entry: UiLogEntry) {
    val color = when (entry.direction) {
        LogDirection.OUTBOUND -> MaterialTheme.colorScheme.primary
        LogDirection.INBOUND -> MaterialTheme.colorScheme.secondary
        LogDirection.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
        LogDirection.ERROR -> MaterialTheme.colorScheme.error
    }
    val directionLabel = when (entry.direction) {
        LogDirection.OUTBOUND -> "→ OUT"
        LogDirection.INBOUND -> "← IN"
        LogDirection.INFO -> "INFO"
        LogDirection.ERROR -> "ERROR"
    }
    val channelLabel = entry.channel?.name ?: ""
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        val headline = buildString {
            append(entry.prettyTime)
            append(" • ")
            append(directionLabel)
            if (channelLabel.isNotBlank()) {
                append(" [")
                append(channelLabel)
                append(']')
            }
            if (entry.endpoint.isNotBlank()) {
                append(' ')
                append(entry.endpoint)
            }
        }
        Text(
            text = headline,
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
        if (entry.address.isNotBlank()) {
            Text(
                text = entry.address,
                style = MaterialTheme.typography.titleSmall
            )
        }
        if (entry.typeTags.isNotBlank()) {
            Text(
                text = "typetags: ${entry.typeTags}",
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (entry.payloadSummary.isNotBlank()) {
            Text(
                text = entry.payloadSummary,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Divider(modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "No data yet. Start listening then wait for OSC packets, or send a command if needed.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun NonOscEmptyState() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "No non-OSC datagrams observed yet.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun PacketRow(packet: EmotiBitUiPacket) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Text(
            text = "${packet.prettyTime} • ${packet.source}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = packet.address,
            style = MaterialTheme.typography.titleSmall
        )
        Text(
            text = "typetags: ${packet.typeTags}",
            style = MaterialTheme.typography.bodySmall
        )
        if (packet.values.isNotEmpty()) {
            Text(
                text = packet.values,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Divider(modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun NonOscPacketRow(packet: EmotiBitUiNonOscPacket) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Text(
            text = "${packet.prettyTime} • ${packet.source}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "${packet.sizeBytes} bytes",
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            text = packet.preview,
            style = MaterialTheme.typography.bodySmall
        )
        Divider(modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun ConnectionStatusSummary(status: ConnectionState) {
    val text = when (status) {
        ConnectionState.Idle -> "Status: Idle"
        ConnectionState.Discovering -> "Status: Discovering EmotiBits"
        is ConnectionState.Connecting -> "Status: Listening on port ${status.listenPort}"
        is ConnectionState.Streaming -> "Status: Streaming on port ${status.listenPort}"
        ConnectionState.Stopped -> "Status: Stopped"
    }
    Text(text = text, style = MaterialTheme.typography.labelLarge)
}

@Preview(showBackground = true)
@Composable
private fun EmotiBitScreenPreview() {
    val packets = listOf(
        EmotiBitUiPacket(
            id = 1L,
            timestampMillis = System.currentTimeMillis(),
            source = "192.168.43.120",
            address = "/EmotiBit/heartRate",
            typeTags = ",f",
            values = "f=72.4",
            prettyTime = "12:00:00.000"
        )
    )
    val nonOscPackets = listOf(
        EmotiBitUiNonOscPacket(
            id = 2L,
            timestampMillis = System.currentTimeMillis(),
            source = "192.168.43.121",
            sizeBytes = 12,
            preview = "FF AA 00 12",
            prettyTime = "12:00:05.000"
        )
    )
    val logEntries = listOf(
        UiLogEntry(
            id = 100L,
            timestampMillis = System.currentTimeMillis(),
            prettyTime = "12:00:02.000",
            direction = LogDirection.OUTBOUND,
            channel = UdpChannel.CONTROL,
            endpoint = "192.168.43.200:3133",
            address = "/EmotiBit/Stream/Start",
            typeTags = ",si",
            payloadSummary = "s=\"192.168.43.1\", i=8000"
        ),
        UiLogEntry(
            id = 101L,
            timestampMillis = System.currentTimeMillis(),
            prettyTime = "12:00:02.500",
            direction = LogDirection.INBOUND,
            channel = UdpChannel.ADVERTISE,
            endpoint = "192.168.43.200:3131",
            address = "/EmotiBit/Advertise",
            typeTags = ",s",
            payloadSummary = "s=\"EmotiBit-1234\""
        )
    )
    EmotiBitConnectorTheme {
        EmotiBitScreen(
            state = EmotiBitUiState(
                commandMode = CommandMode.BROADCAST_CMD,
                listenPortInput = "8000",
                advertisePortInput = "3131",
                controlPortInput = "3133",
                deviceIpInput = "192.168.43.101",
                connectionState = ConnectionState.Streaming(8000),
                packetLog = packets,
                nonOscPacketLog = nonOscPackets,
                logEntries = logEntries,
                localWifiIp = "192.168.50.2"
            ),
            onDeviceAddressChange = {},
            onListenPortChange = {},
            onAdvertisePortChange = {},
            onControlPortChange = {},
            onCommandModeChange = {},
            onScanClick = {},
            onStartClick = {},
            onSendStartClick = {},
            onStopClick = {},
            onClearLogClick = {},
            onDismissError = {}
        )
    }
}
