package com.example.emotibitconnector.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import com.example.emotibitconnector.ConnectionStatus
import com.example.emotibitconnector.EmotiBitUiPacket
import com.example.emotibitconnector.EmotiBitUiState
import com.example.emotibitconnector.EmotiBitViewModel
import com.example.emotibitconnector.ui.theme.EmotiBitConnectorTheme

@Composable
fun EmotiBitConnectorApp() {
    val viewModel: EmotiBitViewModel = viewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    EmotiBitScreen(
        state = state,
        onDeviceAddressChange = viewModel::updateDeviceAddress,
        onListenPortChange = viewModel::updateListenPort,
        onCommandPortChange = viewModel::updateCommandPort,
        onCommandModeChange = viewModel::updateCommandMode,
        onStartClick = viewModel::startListening,
        onStopClick = viewModel::stopListening,
        onSendStartCommand = viewModel::sendStartCommand,
        onDismissError = viewModel::clearError
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmotiBitScreen(
    state: EmotiBitUiState,
    onDeviceAddressChange: (String) -> Unit,
    onListenPortChange: (String) -> Unit,
    onCommandPortChange: (String) -> Unit,
    onCommandModeChange: (CommandMode) -> Unit,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    onSendStartCommand: () -> Unit,
    onDismissError: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("EmotiBit Connector") }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Ensure EmotiBit and this phone are on the same 2.4 GHz network (this phone's hotspot).",
                style = MaterialTheme.typography.bodyMedium
            )
            state.localHotspotIp?.let { localIp ->
                Text(
                    text = "Phone hotspot IPv4: $localIp",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            ConnectionStatusSummary(state.connectionStatus)
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
                    placeholder = { Text("8000") }
                )
                OutlinedTextField(
                    value = state.commandPortInput,
                    onValueChange = onCommandPortChange,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("Command port") },
                    placeholder = { Text("9000") }
                )
            }
            if (state.commandMode == CommandMode.UNICAST_CMD) {
                OutlinedTextField(
                    value = state.deviceIpInput,
                    onValueChange = onDeviceAddressChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("EmotiBit IPv4 (required for UNICAST)") },
                    placeholder = { Text("e.g. 192.168.43.101") }
                )
            }
            HintCard()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onStartClick,
                    enabled = !state.isListening
                ) {
                    Text("Start listening")
                }
                OutlinedButton(
                    onClick = onStopClick,
                    enabled = state.isListening
                ) {
                    Text("Stop")
                }
            }
            OutlinedButton(
                onClick = onSendStartCommand,
                enabled = state.isListening
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
            Text(
                text = "Recent OSC packets",
                style = MaterialTheme.typography.titleMedium
            )
            val listState = rememberLazyListState()
            val orderedLog = remember(state.packetLog) { state.packetLog.asReversed() }
            LaunchedEffect(orderedLog.size) {
                if (orderedLog.isNotEmpty()) {
                    listState.animateScrollToItem(orderedLog.lastIndex)
                }
            }
            if (orderedLog.isEmpty()) {
                EmptyState(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    items(orderedLog, key = { it.id }) { packet ->
                        PacketRow(packet)
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
    CommandMode.BROADCAST_CMD -> "Send a start message to the hotspot /24 broadcast address."
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
            text = "Hint: Some Android hotspot builds filter UDP broadcast. If broadcast commands fail, switch to UNICAST after noting the EmotiBit IP from hotspot connected devices.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(12.dp)
        )
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
private fun ConnectionStatusSummary(status: ConnectionStatus) {
    val text = when (status) {
        ConnectionStatus.Idle -> "Status: Idle"
        ConnectionStatus.Binding -> "Status: Binding socket"
        is ConnectionStatus.Listening -> "Status: Listening on port ${status.port}"
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
    EmotiBitConnectorTheme {
        EmotiBitScreen(
            state = EmotiBitUiState(
                commandMode = CommandMode.BROADCAST_CMD,
                listenPortInput = "8000",
                commandPortInput = "9000",
                deviceIpInput = "192.168.43.101",
                isListening = true,
                connectionStatus = ConnectionStatus.Listening(8000),
                packetLog = packets,
                localHotspotIp = "192.168.43.1"
            ),
            onDeviceAddressChange = {},
            onListenPortChange = {},
            onCommandPortChange = {},
            onCommandModeChange = {},
            onStartClick = {},
            onStopClick = {},
            onSendStartCommand = {},
            onDismissError = {}
        )
    }
}
