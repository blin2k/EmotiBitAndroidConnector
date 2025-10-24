package com.example.emotibitconnector

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.emotibitconnector.network.ClientLogEvent
import com.example.emotibitconnector.network.DiscoveredDevice
import com.example.emotibitconnector.network.DiscoveryConfig
import com.example.emotibitconnector.network.EmotiBitClient
import com.example.emotibitconnector.network.EmotiBitPacket
import com.example.emotibitconnector.network.EmotiBitProto
import com.example.emotibitconnector.network.EmotiBitRawPacket
import com.example.emotibitconnector.network.LocalNetworkInfo
import com.example.emotibitconnector.network.UdpChannel
import com.example.emotibitconnector.osc.OscArgument
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Command delivery strategy when instructing an EmotiBit to stream towards the phone over Wi-Fi.
 */
enum class CommandMode { PASSIVE, BROADCAST_CMD, UNICAST_CMD }

sealed class ConnectionState {
    object Idle : ConnectionState()
    object Discovering : ConnectionState()
    data class Connecting(val listenPort: Int) : ConnectionState()
    data class Streaming(val listenPort: Int) : ConnectionState()
    object Stopped : ConnectionState()
}

enum class LogDirection { INBOUND, OUTBOUND, INFO, ERROR }

data class UiLogEntry(
    val id: Long,
    val timestampMillis: Long,
    val prettyTime: String,
    val direction: LogDirection,
    val channel: UdpChannel?,
    val endpoint: String,
    val address: String,
    val typeTags: String,
    val payloadSummary: String
)

class EmotiBitViewModel(
    application: Application,
    private val client: EmotiBitClient = EmotiBitClient()
) : AndroidViewModel(application) {

    constructor(application: Application) : this(application, EmotiBitClient())

    private val appContext = application.applicationContext
    private val logCounter = AtomicLong(0)
    private val packetCounter = AtomicLong(0)
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.US)
        .withZone(ZoneId.systemDefault())

    private val _uiState = MutableStateFlow(EmotiBitUiState())
    val uiState: StateFlow<EmotiBitUiState> = _uiState.asStateFlow()

    private var discoveryJob: Job? = null
    private var localNetworkInfo: LocalNetworkInfo? = null

    init {
        refreshLocalNetworkInfo(logWhenUpdated = true)
    }

    fun updateCommandMode(mode: CommandMode) {
        _uiState.update { it.copy(commandMode = mode) }
    }

    fun updateListenPort(value: String) {
        _uiState.update { it.copy(listenPortInput = value.filter(Char::isDigit)) }
    }

    fun updateAdvertisePort(value: String) {
        _uiState.update { it.copy(advertisePortInput = value.filter(Char::isDigit)) }
    }

    fun updateControlPort(value: String) {
        _uiState.update { it.copy(controlPortInput = value.filter(Char::isDigit)) }
    }

    fun updateDeviceAddress(value: String) {
        _uiState.update { it.copy(deviceIpInput = value.trim()) }
    }

    fun scan() {
        if (discoveryJob?.isActive == true) return
        discoveryJob = viewModelScope.launch {
            refreshLocalNetworkInfo(logWhenUpdated = true)
            val advertisePort = parsePort(
                value = _uiState.value.advertisePortInput,
                fallback = EmotiBitProto.DEFAULT_ADVERTISE_PORT,
                fieldName = "Advertise port"
            ) ?: return@launch

            _uiState.update {
                it.copy(
                    connectionState = ConnectionState.Discovering,
                    advertisePortResolved = advertisePort,
                    errorMessage = null,
                    infoMessage = null
                )
            }

            handleClientLog(
                ClientLogEvent.Info(
                    timestamp = System.currentTimeMillis(),
                    message = "Scanning via UDP broadcast on port $advertisePort"
                )
            )

            val result = client.discoverDevice(
                context = appContext,
                config = DiscoveryConfig(advertisePort = advertisePort),
                onLog = ::handleClientLog
            )

            if (result != null) {
                onDeviceDiscovered(result)
            } else {
                _uiState.update { state ->
                    state.copy(
                        infoMessage = "No advertise replies within timeout.",
                        connectionState = resumeStateAfterDiscovery(state.connectionState)
                    )
                }
            }
        }
    }

    private fun onDeviceDiscovered(result: DiscoveredDevice) {
        _uiState.update { state ->
            state.copy(
                deviceIpInput = result.deviceIp,
                discoveredDeviceId = result.deviceId,
                discoveredFirmware = result.firmwareVersion,
                infoMessage = "Discovered ${result.deviceId ?: result.deviceIp}",
                connectionState = resumeStateAfterDiscovery(state.connectionState)
            )
        }
    }

    fun startListening() {
        val listenPort = parsePort(
            value = _uiState.value.listenPortInput,
            fallback = EmotiBitProto.DEFAULT_DATA_PORT_HINT,
            fieldName = "Listen port"
        ) ?: return
        val advertisePort = parsePort(
            value = _uiState.value.advertisePortInput,
            fallback = EmotiBitProto.DEFAULT_ADVERTISE_PORT,
            fieldName = "Advertise port"
        ) ?: return
        val controlPort = parsePort(
            value = _uiState.value.controlPortInput,
            fallback = EmotiBitProto.DEFAULT_CONTROL_PORT,
            fieldName = "Control port"
        ) ?: return

        refreshLocalNetworkInfo(logWhenUpdated = true)
        val networkInfo = localNetworkInfo
        if (networkInfo == null) {
            setError("Unable to resolve local Wi-Fi IPv4 address. Ensure the phone is connected to the access point.")
            return
        }

        client.startDataListener(
            scope = viewModelScope,
            dataPort = listenPort,
            onReady = { boundPort ->
                _uiState.update { state ->
                    state.copy(
                        connectionState = ConnectionState.Connecting(boundPort),
                        listenPortResolved = boundPort,
                        advertisePortResolved = advertisePort,
                        controlPortResolved = controlPort,
                        errorMessage = null,
                        infoMessage = "Listening for EmotiBit data on $boundPort"
                    )
                }
            },
            onPacket = { packet ->
                appendPacket(packet)
            },
            onNonOscPacket = { packet ->
                appendNonOscPacket(packet)
            },
            onFailure = { throwable ->
                setError(throwable.message ?: throwable.toString())
                viewModelScope.launch {
                    client.stopDataListener()
                }
            },
            onLog = ::handleClientLog
        )
    }

    fun sendStart() {
        val state = _uiState.value
        if (state.commandMode == CommandMode.PASSIVE) {
            _uiState.update { it.copy(infoMessage = "PASSIVE mode active. No control packet sent.") }
            return
        }
        val listenPort = state.listenPortResolved ?: state.listenPortInput.toIntOrNull()
        if (listenPort == null) {
            setError("Start listening before sending a start command.")
            return
        }
        val controlPort = state.controlPortResolved ?: state.controlPortInput.toIntOrNull()
        if (controlPort == null) {
            setError("Control port is invalid.")
            return
        }
        val networkInfo = ensureLocalNetworkInfo() ?: return
        val localIp = networkInfo.address.hostAddress
        val dataPort = listenPort

        viewModelScope.launch {
            runCatching {
                when (state.commandMode) {
                    CommandMode.BROADCAST_CMD -> {
                        client.sendStartStreamBroadcast(
                            broadcast = networkInfo.broadcast,
                            controlPort = controlPort,
                            localIp = localIp,
                            dataPort = dataPort,
                            onLog = ::handleClientLog
                        )
                    }
                    CommandMode.UNICAST_CMD -> {
                        val deviceIp = state.deviceIpInput.ifBlank {
                            setError("Provide the EmotiBit IP for UNICAST mode.")
                            return@launch
                        }
                        client.sendStartStreamUnicast(
                            deviceIp = deviceIp,
                            controlPort = controlPort,
                            localIp = localIp,
                            dataPort = dataPort,
                            onLog = ::handleClientLog
                        )
                    }
                    CommandMode.PASSIVE -> Unit
                }
            }.onSuccess {
                _uiState.update {
                    it.copy(
                        infoMessage = when (state.commandMode) {
                            CommandMode.BROADCAST_CMD -> "Broadcast start command sent."
                            CommandMode.UNICAST_CMD -> "Start command sent to ${state.deviceIpInput}."
                            CommandMode.PASSIVE -> it.infoMessage
                        },
                        connectionState = ConnectionState.Connecting(listenPort)
                    )
                }
            }.onFailure { throwable ->
                setError(throwable.message ?: throwable.toString())
            }
        }
    }

    fun stopAll() {
        viewModelScope.launch {
            val state = _uiState.value
            val controlPort = state.controlPortResolved ?: state.controlPortInput.toIntOrNull()
            val networkInfo = localNetworkInfo
            val deviceIp = state.deviceIpInput.takeIf { it.isNotBlank() }

            if (controlPort != null && deviceIp != null) {
                runCatching {
                    client.sendStopStreamUnicast(
                        deviceIp = deviceIp,
                        controlPort = controlPort,
                        onLog = ::handleClientLog
                    )
                }
            }
            if (controlPort != null && networkInfo != null) {
                runCatching {
                    client.sendStopStreamBroadcast(
                        broadcast = networkInfo.broadcast,
                        controlPort = controlPort,
                        onLog = ::handleClientLog
                    )
                }
            }
            client.stopDataListener()
            _uiState.update {
                it.copy(
                    connectionState = ConnectionState.Stopped,
                    infoMessage = "Streaming stopped.",
                    listenPortResolved = null
                )
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun clearLog() {
        _uiState.update { it.copy(logEntries = emptyList()) }
    }

    private fun resumeStateAfterDiscovery(state: ConnectionState): ConnectionState =
        when (state) {
            is ConnectionState.Streaming -> state
            is ConnectionState.Connecting -> state
            else -> ConnectionState.Idle
        }

    private fun handleClientLog(event: ClientLogEvent) {
        val timestamp = event.timestamp
        val prettyTime = timeFormatter.format(Instant.ofEpochMilli(timestamp))
        when (event) {
            is ClientLogEvent.Outbound -> {
                appendLogEntry(
                    UiLogEntry(
                        id = logCounter.incrementAndGet(),
                        timestampMillis = timestamp,
                        prettyTime = prettyTime,
                        direction = LogDirection.OUTBOUND,
                        channel = event.channel,
                        endpoint = "${event.destination.hostAddress}:${event.port}",
                        address = event.oscAddress,
                        typeTags = event.typeTags,
                        payloadSummary = formatArguments(event.arguments)
                    )
                )
            }
            is ClientLogEvent.Inbound -> {
                val osc = event.oscMessage
                val address = osc?.address ?: "<raw>"
                val typeTags = osc?.typeTags ?: ""
                val payload = osc?.arguments?.let(::formatArguments)
                    ?: buildHexPreview(event.rawBytes)
                appendLogEntry(
                    UiLogEntry(
                        id = logCounter.incrementAndGet(),
                        timestampMillis = timestamp,
                        prettyTime = prettyTime,
                        direction = LogDirection.INBOUND,
                        channel = event.channel,
                        endpoint = "${event.source.hostAddress}:${event.port}",
                        address = address,
                        typeTags = typeTags,
                        payloadSummary = payload
                    )
                )
            }
            is ClientLogEvent.Info -> {
                appendLogEntry(
                    UiLogEntry(
                        id = logCounter.incrementAndGet(),
                        timestampMillis = timestamp,
                        prettyTime = prettyTime,
                        direction = LogDirection.INFO,
                        channel = null,
                        endpoint = "",
                        address = event.message,
                        typeTags = "",
                        payloadSummary = ""
                    )
                )
            }
            is ClientLogEvent.Error -> {
                val detail = event.throwable?.message ?: ""
                appendLogEntry(
                    UiLogEntry(
                        id = logCounter.incrementAndGet(),
                        timestampMillis = timestamp,
                        prettyTime = prettyTime,
                        direction = LogDirection.ERROR,
                        channel = null,
                        endpoint = "",
                        address = event.message,
                        typeTags = "",
                        payloadSummary = detail
                    )
                )
                setError(event.message)
            }
        }
    }

    private fun appendLogEntry(entry: UiLogEntry) {
        _uiState.update { state ->
            val updated = (state.logEntries + entry).takeLast(MAX_LOG_ITEMS)
            state.copy(logEntries = updated)
        }
    }

    private fun appendPacket(packet: EmotiBitPacket) {
        val timestamp = packet.receivedAtMillis
        val formattedTime = timeFormatter.format(Instant.ofEpochMilli(timestamp))
        val message = packet.oscMessage
        val valueSummary = formatArguments(message.arguments)
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
            val updatedPackets = (state.packetLog + logEntry).takeLast(MAX_PACKET_ITEMS)
            val listenPort = when (val current = state.connectionState) {
                is ConnectionState.Connecting -> current.listenPort
                is ConnectionState.Streaming -> current.listenPort
                else -> state.listenPortResolved
            } ?: 0
            val nextState = state.copy(
                packetLog = updatedPackets,
                lastPacketAt = timestamp,
                lastSource = logEntry.source
            )
            if (state.connectionState is ConnectionState.Streaming) {
                nextState
            } else {
                nextState.copy(connectionState = ConnectionState.Streaming(listenPort))
            }
        }
    }

    private fun appendNonOscPacket(packet: EmotiBitRawPacket) {
        val timestamp = packet.receivedAtMillis
        val formattedTime = timeFormatter.format(Instant.ofEpochMilli(timestamp))
        val preview = buildHexPreview(packet.payload)
        val logEntry = EmotiBitUiNonOscPacket(
            id = packetCounter.incrementAndGet(),
            timestampMillis = timestamp,
            source = packet.sourceAddress.hostAddress ?: packet.sourceAddress.hostName,
            sizeBytes = packet.payload.size,
            preview = preview,
            prettyTime = formattedTime
        )
        _uiState.update { state ->
            val updated = (state.nonOscPacketLog + logEntry).takeLast(MAX_PACKET_ITEMS)
            state.copy(nonOscPacketLog = updated)
        }
    }

    private fun setError(message: String) {
        _uiState.update { it.copy(errorMessage = message, infoMessage = null) }
    }

    private fun parsePort(value: String, fallback: Int, fieldName: String): Int? {
        val parsed = value.toIntOrNull() ?: fallback
        return if (parsed in VALID_PORT_RANGE) {
            parsed
        } else {
            setError("$fieldName must be between ${VALID_PORT_RANGE.first} and ${VALID_PORT_RANGE.last}.")
            null
        }
    }

    private fun formatArguments(arguments: List<OscArgument>): String = arguments.joinToString { argument ->
        when (argument) {
            is OscArgument.Int -> "i=${argument.value}"
            is OscArgument.Float -> "f=${"%.3f".format(argument.value)}"
            is OscArgument.String -> "s=\"${argument.value}\""
            is OscArgument.Unknown -> "${argument.typeTag}=${buildHexPreview(argument.payload)}"
        }
    }

    private fun buildHexPreview(payload: ByteArray): String {
        if (payload.isEmpty()) return "(empty payload)"
        val previewCount = payload.size.coerceAtMost(MAX_NON_OSC_PREVIEW_BYTES)
        val hexBytes = payload.take(previewCount)
            .joinToString(separator = " ") { byte ->
                "%02X".format(byte.toInt() and 0xFF)
            }
        return if (payload.size > MAX_NON_OSC_PREVIEW_BYTES) {
            "$hexBytes …"
        } else {
            hexBytes
        }
    }

    private fun refreshLocalNetworkInfo(logWhenUpdated: Boolean = false) {
        val previous = localNetworkInfo
        val resolved = client.resolveLocalNetworkInfo(appContext)
        localNetworkInfo = resolved
        if (logWhenUpdated && resolved != null) {
            val previousIp = previous?.address?.hostAddress
            val previousBroadcast = previous?.broadcast?.hostAddress
            val newIp = resolved.address.hostAddress
            val newBroadcast = resolved.broadcast.hostAddress
            if (newIp != previousIp || newBroadcast != previousBroadcast) {
                handleClientLog(
                    ClientLogEvent.Info(
                        timestamp = System.currentTimeMillis(),
                        message = "Local Wi-Fi IPv4 $newIp (/${resolved.prefixLength ?: 24}) broadcast $newBroadcast"
                    )
                )
            }
        }
        _uiState.update { state ->
            state.copy(
                localWifiIp = resolved?.address?.hostAddress,
                broadcastAddress = resolved?.broadcast?.hostAddress
            )
        }
    }

    private fun ensureLocalNetworkInfo(): LocalNetworkInfo? {
        if (localNetworkInfo == null) {
            refreshLocalNetworkInfo(logWhenUpdated = true)
        }
        return localNetworkInfo ?: run {
            setError("Unable to resolve local Wi-Fi network interface.")
            null
        }
    }

    override fun onCleared() {
        runBlocking {
            client.stopDataListener()
        }
        super.onCleared()
    }

    companion object {
        private val VALID_PORT_RANGE = 1024..65535
        private const val MAX_LOG_ITEMS = 400
        private const val MAX_PACKET_ITEMS = 200
        private const val MAX_NON_OSC_PREVIEW_BYTES = 48
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

data class EmotiBitUiNonOscPacket(
    val id: Long,
    val timestampMillis: Long,
    val source: String,
    val sizeBytes: Int,
    val preview: String,
    val prettyTime: String
)

data class EmotiBitUiState(
    val commandMode: CommandMode = CommandMode.PASSIVE,
    val listenPortInput: String = EmotiBitProto.DEFAULT_DATA_PORT_HINT.toString(),
    val advertisePortInput: String = EmotiBitProto.DEFAULT_ADVERTISE_PORT.toString(),
    val controlPortInput: String = EmotiBitProto.DEFAULT_CONTROL_PORT.toString(),
    val deviceIpInput: String = "",
    val listenPortResolved: Int? = null,
    val advertisePortResolved: Int? = null,
    val controlPortResolved: Int? = null,
    val localWifiIp: String? = null,
    val broadcastAddress: String? = null,
    val connectionState: ConnectionState = ConnectionState.Idle,
    val discoveredDeviceId: String? = null,
    val discoveredFirmware: String? = null,
    val logEntries: List<UiLogEntry> = emptyList(),
    val packetLog: List<EmotiBitUiPacket> = emptyList(),
    val nonOscPacketLog: List<EmotiBitUiNonOscPacket> = emptyList(),
    val lastPacketAt: Long? = null,
    val lastSource: String? = null,
    val errorMessage: String? = null,
    val infoMessage: String? = null
)
