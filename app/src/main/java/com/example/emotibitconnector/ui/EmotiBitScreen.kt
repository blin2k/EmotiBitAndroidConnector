package com.example.emotibitconnector.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
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
        onStreamAddressChange = viewModel::updateStreamAddress,
        onStartClick = viewModel::startListening,
        onStopClick = viewModel::stopListening,
        onRequestStream = viewModel::sendStreamRequest,
        onStopStream = viewModel::sendStopRequest,
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
    onStreamAddressChange: (String) -> Unit,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    onRequestStream: () -> Unit,
    onStopStream: () -> Unit,
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
                text = "Join the EmotiBit hotspot (2.4 GHz), then listen for OSC telemetry.",
                style = MaterialTheme.typography.bodyMedium
            )
            ConnectionStatusSummary(state.connectionStatus)
            OutlinedTextField(
                value = state.deviceAddress,
                onValueChange = onDeviceAddressChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("EmotiBit IPv4 address") },
                placeholder = { Text("192.168.4.1") }
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = state.listenPort,
                    onValueChange = onListenPortChange,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("Listen port") },
                    placeholder = { Text("8000") }
                )
                OutlinedTextField(
                    value = state.commandPort,
                    onValueChange = onCommandPortChange,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("Command port") },
                    placeholder = { Text("8001") }
                )
            }
            OutlinedTextField(
                value = state.streamAddress,
                onValueChange = onStreamAddressChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Stream command address") },
                placeholder = { Text("/EmotiBit/Stream") }
            )
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onRequestStream,
                    enabled = state.isListening
                ) {
                    Text("Request stream")
                }
                TextButton(
                    onClick = onStopStream,
                    enabled = state.isListening
                ) {
                    Text("Stop stream")
                }
            }
            state.errorMessage?.let { message ->
                ErrorBanner(message = message, onDismiss = onDismissError)
            }
            Divider()
            Text(
                text = "Recent OSC packets",
                style = MaterialTheme.typography.titleMedium
            )
            if (state.packetLog.isEmpty()) {
                EmptyState(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    items(state.packetLog, key = { it.id }) { packet ->
                        PacketRow(packet)
                    }
                }
            }
        }
    }
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
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "No data yet. Start listening then request the stream.",
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
            text = "${packet.timeFormatted} • ${packet.source}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = packet.address,
            style = MaterialTheme.typography.titleSmall
        )
        if (packet.argumentSummary.isNotEmpty()) {
            Text(
                text = packet.argumentSummary,
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
            address = "/EmotiBit/heartRate",
            argumentSummary = "f=72.4",
            receivedAtMillis = System.currentTimeMillis(),
            source = "192.168.4.1"
        )
    )
    EmotiBitConnectorTheme {
        EmotiBitScreen(
            state = EmotiBitUiState(
                deviceAddress = "192.168.4.1",
                listenPort = "8000",
                commandPort = "8001",
                streamAddress = "/EmotiBit/Stream",
                isListening = true,
                connectionStatus = ConnectionStatus.Listening(8000),
                packetLog = packets
            ),
            onDeviceAddressChange = {},
            onListenPortChange = {},
            onCommandPortChange = {},
            onStreamAddressChange = {},
            onStartClick = {},
            onStopClick = {},
            onRequestStream = {},
            onStopStream = {},
            onDismissError = {}
        )
    }
}
