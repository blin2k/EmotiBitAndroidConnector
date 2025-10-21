package com.example.emotibitconnector

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.emotibitconnector.network.EmotiBitClient
import com.example.emotibitconnector.network.EmotiBitConnectionConfig
import com.example.emotibitconnector.network.EmotiBitPacket
import com.example.emotibitconnector.osc.OscArgument
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Command delivery strategy when instructing an EmotiBit to stream towards the phone hotspot.
 * PASSIVE satisfies the acceptance criterion that listening alone surfaces EmotiBit broadcasts.
 */
enum class CommandMode { PASSIVE, BROADCAST_CMD, UNICAST_CMD }

class EmotiBitViewModel(
    private val client: EmotiBitClient = EmotiBitClient()
) : ViewModel() {

    private val packetCounter = AtomicLong(0)
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.US)
        .withZone(ZoneId.systemDefault())

    private val _uiState = MutableStateFlow(EmotiBitUiState())
    val uiState: StateFlow<EmotiBitUiState> = _uiState.asStateFlow()

    fun updateCommandMode(mode: CommandMode) {
        _uiState.update { it.copy(commandMode = mode) }
    }

    fun updateListenPort(value: String) {
        _uiState.update { it.copy(listenPortInput = value.filter { char -> char.isDigit() }) }
    }

    fun updateCommandPort(value: String) {
        _uiState.update { it.copy(commandPortInput = value.filter { char -> char.isDigit() }) }
    }

    fun updateDeviceAddress(value: String) {
        _uiState.update { it.copy(deviceIpInput = value.trim()) }
    }

    fun startListening() {
        if (_uiState.value.isListening) return
        val listenPort = _uiState.value.listenPortInput.toIntOrNull()
        if (listenPort == null || listenPort !in VALID_PORT_RANGE) {
            setError("Listen port must be between ${VALID_PORT_RANGE.first} and ${VALID_PORT_RANGE.last}.")
            return
        }
        val commandPort = _uiState.value.commandPortInput.toIntOrNull()
        if (commandPort == null || commandPort !in VALID_PORT_RANGE) {
            setError("Command port must be between ${VALID_PORT_RANGE.first} and ${VALID_PORT_RANGE.last}.")
            return
        }
        _uiState.update {
            it.copy(
                isListening = true,
                connectionStatus = ConnectionStatus.Binding,
                errorMessage = null,
                infoMessage = null,
                listenPortResolved = listenPort,
                commandPortResolved = commandPort,
                localHotspotIp = client.getLocalHotspotIPv4()?.hostAddress
            )
        }
        val config = EmotiBitConnectionConfig(
            deviceAddress = _uiState.value.deviceIpInput,
            deviceCommandPort = commandPort,
            listenPort = listenPort
        )
        client.startListening(
            scope = viewModelScope,
            config = config,
            onReady = { boundPort ->
                _uiState.update {
                    it.copy(connectionStatus = ConnectionStatus.Listening(boundPort))
                }
            },
            onPacket = { packet ->
                appendPacket(packet)
            },
            onFailure = { throwable ->
                setError(throwable.message ?: throwable.toString())
                stopListening()
            }
        )
    }

    fun stopListening() {
        viewModelScope.launch {
            client.stopListening()
        }
        _uiState.update {
            it.copy(
                isListening = false,
                connectionStatus = ConnectionStatus.Idle
            )
        }
    }

    fun sendStartCommand() {
        val state = _uiState.value
        val listenPort = state.listenPortResolved ?: state.listenPortInput.toIntOrNull()
        val commandPort = state.commandPortResolved ?: state.commandPortInput.toIntOrNull()
        if (listenPort == null || commandPort == null) {
            setError("Specify listen and command ports before sending a command.")
            return
        }
        if (state.commandMode == CommandMode.UNICAST_CMD && state.deviceIpInput.isBlank()) {
            setError("UNICAST command mode requires the EmotiBit device IP.")
            return
        }
        viewModelScope.launch {
            runCatching {
                client.requestStream(
                    mode = state.commandMode,
                    deviceIp = state.deviceIpInput.ifBlank { null },
                    deviceCommandPort = commandPort,
                    targetListenPortOnApp = listenPort
                )
            }.onFailure { throwable ->
                setError(throwable.message ?: throwable.toString())
            }.onSuccess {
                setInfo(
                    when (state.commandMode) {
                        CommandMode.PASSIVE -> ""
                        CommandMode.BROADCAST_CMD -> "Broadcast start command sent."
                        CommandMode.UNICAST_CMD -> "Start command sent to ${state.deviceIpInput}."
                    }
                )
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    private fun appendPacket(packet: EmotiBitPacket) {
        val timestamp = packet.receivedAtMillis
        val formattedTime = timeFormatter.format(Instant.ofEpochMilli(timestamp))
        val message = packet.oscMessage
        val valueSummary = message.arguments.joinToString { argument ->
            when (argument) {
                is OscArgument.Int -> "i=${argument.value}"
                is OscArgument.Float -> "f=${"%.3f".format(argument.value)}"
                is OscArgument.String -> "s=\"${argument.value}\""
                is OscArgument.Unknown -> "unknown typetag=${argument.typeTag}"
            }
        }
        val logEntry = EmotiBitUiPacket(
            id = packetCounter.incrementAndGet(),
            timestampMillis = timestamp,
            source = packet.sourceAddress.hostAddress ?: packet.sourceAddress.hostName,
            address = message.address,
            typeTags = message.typeTags,
            values = valueSummary,
            prettyTime = formattedTime
        )
        _uiState.update { state ->
            val nextLog = (listOf(logEntry) + state.packetLog).take(MAX_LOG_ITEMS)
            state.copy(
                packetLog = nextLog,
                lastPacketAt = timestamp,
                lastSource = logEntry.source
            )
        }
    }

    private fun setError(message: String) {
        _uiState.update { it.copy(errorMessage = message, infoMessage = null) }
    }

    private fun setInfo(message: String) {
        if (message.isBlank()) return
        _uiState.update { it.copy(infoMessage = message, errorMessage = null) }
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch {
            client.stopListening()
        }
    }

    companion object {
        private val VALID_PORT_RANGE = 1024..65535
        private const val MAX_LOG_ITEMS = 200
    }
}

data class EmotiBitUiPacket(
    val id: Long,
    val timestampMillis: Long,
    val source: String,
    val address: String,
    val typeTags: String,
    val values: String,
    val prettyTime: String
)

sealed class ConnectionStatus {
    object Idle : ConnectionStatus()
    object Binding : ConnectionStatus()
    data class Listening(val port: Int) : ConnectionStatus()
}

data class EmotiBitUiState(
    val commandMode: CommandMode = CommandMode.PASSIVE,
    val listenPortInput: String = "3132",
    val commandPortInput: String = "3133",
    val deviceIpInput: String = "",
    val listenPortResolved: Int? = null,
    val commandPortResolved: Int? = null,
    val localHotspotIp: String? = null,
    val isListening: Boolean = false,
    val connectionStatus: ConnectionStatus = ConnectionStatus.Idle,
    val packetLog: List<EmotiBitUiPacket> = emptyList(),
    val lastPacketAt: Long? = null,
    val lastSource: String? = null,
    val errorMessage: String? = null,
    val infoMessage: String? = null
)
