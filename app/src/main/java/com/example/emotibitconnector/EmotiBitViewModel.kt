package com.example.emotibitconnector

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.emotibitconnector.network.EmotiBitClient
import com.example.emotibitconnector.network.EmotiBitConnectionConfig
import com.example.emotibitconnector.network.EmotiBitPacket
import com.example.emotibitconnector.osc.OscArgument
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val DEFAULT_STREAM_ADDRESS = "/EmotiBit/Stream"

class EmotiBitViewModel(
    private val client: EmotiBitClient = EmotiBitClient()
) : ViewModel() {

    private val packetCounter = AtomicLong(0)

    private val _uiState = MutableStateFlow(EmotiBitUiState())
    val uiState: StateFlow<EmotiBitUiState> = _uiState.asStateFlow()

    fun updateDeviceAddress(value: String) {
        _uiState.update { it.copy(deviceAddress = value.trim()) }
    }

    fun updateListenPort(value: String) {
        _uiState.update { it.copy(listenPort = value.filter { char -> char.isDigit() }) }
    }

    fun updateCommandPort(value: String) {
        _uiState.update { it.copy(commandPort = value.filter { char -> char.isDigit() }) }
    }

    fun updateStreamAddress(value: String) {
        _uiState.update { it.copy(streamAddress = value) }
    }

    fun startListening() {
        val state = _uiState.value
        if (state.isListening) return
        val listenPort = state.listenPort.toIntOrNull()
        val commandPort = state.commandPort.toIntOrNull()
        val deviceAddress = state.deviceAddress.ifBlank { DEFAULT_DEVICE_IP }
        if (listenPort == null || listenPort !in VALID_PORT_RANGE) {
            setError("Invalid listen port: ${state.listenPort}")
            return
        }
        if (commandPort == null || commandPort !in VALID_PORT_RANGE) {
            setError("Invalid command port: ${state.commandPort}")
            return
        }
        _uiState.update {
            it.copy(
                deviceAddress = deviceAddress,
                isListening = true,
                connectionStatus = ConnectionStatus.Binding,
                errorMessage = null
            )
        }
        val config = EmotiBitConnectionConfig(
            deviceAddress = deviceAddress,
            deviceCommandPort = commandPort,
            listenPort = listenPort
        )
        client.startListening(
            scope = viewModelScope,
            config = config,
            onReady = { boundPort ->
                _uiState.update { stateUpdate ->
                    stateUpdate.copy(
                        connectionStatus = ConnectionStatus.Listening(boundPort)
                    )
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

    fun sendStreamRequest() {
        val state = _uiState.value
        val listenPort = state.listenPort.toIntOrNull() ?: return
        val commandPort = state.commandPort.toIntOrNull() ?: return
        val deviceAddress = state.deviceAddress.ifBlank { DEFAULT_DEVICE_IP }
        val streamAddress = state.streamAddress.ifBlank { DEFAULT_STREAM_ADDRESS }
        val config = EmotiBitConnectionConfig(
            deviceAddress = deviceAddress,
            deviceCommandPort = commandPort,
            listenPort = listenPort
        )
        viewModelScope.launch {
            runCatching {
                client.sendOscMessage(
                    config = config,
                    address = streamAddress,
                    arguments = listOf(
                        OscArgument.String("start"),
                        OscArgument.Int(listenPort)
                    )
                )
            }.onFailure { throwable ->
                setError(throwable.message ?: throwable.toString())
            }
        }
    }

    fun sendStopRequest() {
        val state = _uiState.value
        val listenPort = state.listenPort.toIntOrNull() ?: return
        val commandPort = state.commandPort.toIntOrNull() ?: return
        val deviceAddress = state.deviceAddress.ifBlank { DEFAULT_DEVICE_IP }
        val streamAddress = state.streamAddress.ifBlank { DEFAULT_STREAM_ADDRESS }
        val config = EmotiBitConnectionConfig(
            deviceAddress = deviceAddress,
            deviceCommandPort = commandPort,
            listenPort = listenPort
        )
        viewModelScope.launch {
            runCatching {
                client.sendOscMessage(
                    config = config,
                    address = streamAddress,
                    arguments = listOf(OscArgument.String("stop"))
                )
            }.onFailure { throwable ->
                setError(throwable.message ?: throwable.toString())
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    private fun appendPacket(packet: EmotiBitPacket) {
        val logEntry = EmotiBitUiPacket(
            id = packetCounter.incrementAndGet(),
            address = packet.oscMessage.address,
            argumentSummary = packet.oscMessage.arguments.joinToString { arg ->
                when (arg) {
                    is OscArgument.Int -> "i=${arg.value}"
                    is OscArgument.Float -> "f=${"%.3f".format(arg.value)}"
                    is OscArgument.String -> "s=\"${arg.value}\""
                }
            },
            receivedAtMillis = packet.receivedAtMillis,
            source = packet.sourceAddress.hostAddress
        )
        _uiState.update { state ->
            val nextLog = (listOf(logEntry) + state.packetLog).take(MAX_LOG_ITEMS)
            state.copy(
                packetLog = nextLog,
                lastPacketAt = logEntry.receivedAtMillis,
                lastSource = logEntry.source
            )
        }
    }

    private fun setError(message: String) {
        _uiState.update {
            it.copy(errorMessage = message)
        }
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch {
            client.stopListening()
        }
    }

    companion object {
        private const val DEFAULT_DEVICE_IP = "192.168.4.1"
        private val VALID_PORT_RANGE = 1024..65535
        private const val MAX_LOG_ITEMS = 50
    }
}

data class EmotiBitUiPacket(
    val id: Long,
    val address: String,
    val argumentSummary: String,
    val receivedAtMillis: Long,
    val source: String
) {
    val timeFormatted: String
        get() = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(receivedAtMillis))
}

sealed class ConnectionStatus {
    object Idle : ConnectionStatus()
    object Binding : ConnectionStatus()
    data class Listening(val port: Int) : ConnectionStatus()
}

data class EmotiBitUiState(
    val deviceAddress: String = "192.168.4.1",
    val listenPort: String = "8000",
    val commandPort: String = "8001",
    val streamAddress: String = DEFAULT_STREAM_ADDRESS,
    val isListening: Boolean = false,
    val connectionStatus: ConnectionStatus = ConnectionStatus.Idle,
    val packetLog: List<EmotiBitUiPacket> = emptyList(),
    val lastPacketAt: Long? = null,
    val lastSource: String? = null,
    val errorMessage: String? = null
)
