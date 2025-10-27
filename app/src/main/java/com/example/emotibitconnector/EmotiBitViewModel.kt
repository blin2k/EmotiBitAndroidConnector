package com.example.emotibitconnector

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.emotibitconnector.Logx
import com.example.emotibitconnector.network.EmotiBitClient
import com.example.emotibitconnector.network.EmotiBitProto
import com.example.emotibitconnector.network.SessionConfig
import com.example.emotibitconnector.CsvRecorder
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.text.Charsets

class EmotiBitViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val client = EmotiBitClient(application, viewModelScope)
    private val recorder = CsvRecorder(application, viewModelScope)
    private val logCounter = AtomicLong(0)
    private val connectivityManager =
        application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiRequest = NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .build()
    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handleWifiChanged("available")
        }

        override fun onLost(network: Network) {
            handleWifiChanged("lost")
        }
    }

    private val _uiState = MutableStateFlow(EmotiBitUiState())
    val uiState: StateFlow<EmotiBitUiState> = _uiState.asStateFlow()

    init {
        val defaultStem = defaultCsvStem()
        _uiState.update {
            it.copy(
                recordFileStem = defaultStem,
                recordResolvedName = ensureCsvExtension(defaultStem)
            )
        }
        refreshLocalNetworkInfo()
        observeRecorder()
        runCatching { connectivityManager.registerNetworkCallback(wifiRequest, wifiCallback) }
            .onFailure { Logx.e("Failed to register Wi-Fi callback", it) }
    }

    fun updateDeviceIp(value: String) {
        _uiState.update { it.copy(deviceIpText = value.trim()) }
    }

    fun updateDp(value: String) {
        _uiState.update { it.copy(dpText = value.filter(Char::isDigit)) }
    }

    fun updateCp(value: String) {
        _uiState.update { it.copy(cpText = value.filter(Char::isDigit)) }
    }

    fun updateEcInterval(value: String) {
        _uiState.update { it.copy(ecIntervalText = value.filter(Char::isDigit)) }
    }

    fun updateRecordFileStem(value: String) {
        val trimmed = value.trim()
        val stem = if (trimmed.isNotEmpty()) trimmed else defaultCsvStem()
        _uiState.update {
            it.copy(
                recordFileStem = stem,
                recordResolvedName = ensureCsvExtension(stem)
            )
        }
    }

    fun setRecordUri(uri: Uri?) {
        val previous = _uiState.value.recordUri
        _uiState.update { it.copy(recordUri = uri, recordTarget = uri?.toString()) }
        when {
            uri != null -> appendLog("Recording destination set to $uri")
            previous != null -> appendLog("Recording destination cleared")
        }
    }

    fun startSession() {
        val state = _uiState.value
        val deviceIpRaw = state.deviceIpText
        if (deviceIpRaw.isBlank()) {
            setError("Device IP required")
            return
        }

        val deviceIp = runCatching { InetAddress.getByName(deviceIpRaw) }.getOrElse {
            setError("Invalid device IP")
            return
        }
        val localIp = state.localWifiIp
        if (localIp != null && localIp == deviceIp.hostAddress) {
            setError("Device IP cannot match local phone IP")
            return
        }

        val initialDp = state.dpText.toIntOrNull() ?: EmotiBitProto.DEFAULT_DATA_PORT
        val initialCp = state.cpText.toIntOrNull()
        val interval = state.ecIntervalText.toLongOrNull() ?: 1_000L

        val config = SessionConfig(
            deviceIp = deviceIp,
            initialDp = initialDp,
            initialCp = initialCp,
            ecIntervalMs = interval
        )

        viewModelScope.launch {
            appendLog("Starting session… deviceIp=${deviceIp.hostAddress} initialDp=$initialDp initialCp=${initialCp ?: "auto"} interval=${interval}ms")
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val network = client.bindToWifiNetwork()
                    requireNotNull(network) { "Wi-Fi network unavailable" }
                    client.startSession(config) { payload, length, address, port ->
                        handleIncomingPacket(payload, length, address, port)
                    }
                }
            }

            result.onSuccess {
                val (dp, cp) = client.currentPorts()
                refreshLocalNetworkInfo()
                _uiState.update {
                    it.copy(
                        dpText = dp.toString(),
                        cpText = cp.toString(),
                        isStreaming = true,
                        packetsRx = 0,
                        lastSender = null,
                        lastPayloadPreview = null,
                        errorMessage = null
                    )
                }
                appendLog("Session established dp=$dp cp=$cp")
            }.onFailure { throwable ->
                Logx.e("Failed to start session", throwable)
                setError(throwable.message ?: throwable.toString())
                client.stopSession()
            }
        }
    }

    fun stopSession() {
        client.stopSession()
        viewModelScope.launch(Dispatchers.IO) {
            recorder.stop()
        }
        appendLog("Session stopped")
        _uiState.update {
            it.copy(isStreaming = false)
        }
        refreshLocalNetworkInfo()
    }

    fun startRecording() {
        if (recorder.isRecording.value) return
        val state = _uiState.value
        val stem = state.recordFileStem.ifBlank { defaultCsvStem() }
        val config = CsvRecorder.Config(
            fileNameStem = stem,
            useSafUri = state.recordUri
        )
        viewModelScope.launch {
            runCatching { recorder.start(config) }
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            recordFileStem = stem,
                            recordResolvedName = ensureCsvExtension(stem)
                        )
                    }
                    appendLog("Recording started")
                }
                .onFailure { throwable ->
                    Logx.e("Failed to start recording", throwable)
                    setError("Recording failed: ${throwable.message ?: throwable}")
                }
        }
    }

    fun stopRecording() {
        if (!recorder.isRecording.value) return
        viewModelScope.launch(Dispatchers.IO) {
            recorder.stop()
        }
        appendLog("Recording stopped")
    }

    fun sendPn() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { client.sendStart("PN") }
                .onSuccess { appendLog("PN sent") }
                .onFailure { Logx.e("Failed to send PN", it) }
        }
    }

    fun sendPo() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { client.sendStart("PO") }
                .onSuccess { appendLog("PO sent") }
                .onFailure { Logx.e("Failed to send PO", it) }
        }
    }

    fun sendHe() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { client.sendHe() }
                .onSuccess { appendLog("HE sent") }
                .onFailure { Logx.e("Failed to send HE", it) }
        }
    }

    fun scanDevices() {
        if (_uiState.value.isScanning) return
        val cp = _uiState.value.cpText.toIntOrNull() ?: EmotiBitProto.DEFAULT_CTRL_BACK_PORT
        val dp = _uiState.value.dpText.toIntOrNull() ?: EmotiBitProto.DEFAULT_DATA_PORT
        viewModelScope.launch {
            _uiState.update { it.copy(isScanning = true, discoveredDevices = emptyList()) }
            appendLog("Scanning for EmotiBit devices…")
            val result = withContext(Dispatchers.IO) {
                runCatching { client.scanEmotiBits(ecCp = cp, ecDp = dp) }
            }
            result.onSuccess { devices ->
                val uiDevices = devices.map { UiDiscovered(it.ip.hostAddress, it.deviceId) }
                _uiState.update { it.copy(discoveredDevices = uiDevices, isScanning = false) }
                appendLog("Scan found ${devices.size} device(s)")
            }.onFailure { throwable ->
                Logx.e("Scanning failed", throwable)
                appendLog("Scan failed: ${throwable.message ?: throwable}")
                _uiState.update { it.copy(isScanning = false) }
            }
        }
    }

    fun applyDiscovered(ip: String) {
        _uiState.update { it.copy(deviceIpText = ip) }
        appendLog("Device IP set from scan: $ip")
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null, recordError = null) }
    }

    private fun handleIncomingPacket(payload: ByteArray, length: Int, address: InetAddress, port: Int) {
        val ascii = String(payload, 0, length, Charsets.US_ASCII)
        val timestamp = System.currentTimeMillis()
        if (recorder.isRecording.value) {
            recorder.append(timestamp, address.hostAddress, port, ascii)
        }
        val preview = ascii.take(MAX_PREVIEW_CHARS)
        _uiState.update { current ->
            current.copy(
                packetsRx = current.packetsRx + 1,
                lastSender = "${address.hostAddress}:$port",
                lastPayloadPreview = preview,
                isStreaming = true
            )
        }
    }

    private fun setError(message: String) {
        Logx.e("UI error: $message")
        _uiState.update { it.copy(errorMessage = message) }
    }

    private fun appendLog(message: String) {
        val entry = UiLogEntry(
            id = logCounter.incrementAndGet(),
            message = message,
            timestamp = System.currentTimeMillis()
        )
        _uiState.update { state ->
            val updated = (state.logEntries + entry).takeLast(MAX_LOG_ITEMS)
            state.copy(logEntries = updated)
        }
        Logx.i(message)
    }

    private fun handleWifiChanged(event: String) {
        viewModelScope.launch {
            refreshLocalNetworkInfo()
            if (_uiState.value.isStreaming) {
                appendLog("Wi-Fi changed ($event); stopping session")
                stopSession()
            } else {
                appendLog("Wi-Fi changed ($event)")
            }
        }
    }

    private fun refreshLocalNetworkInfo() {
        val info = client.getWifiNetInfo()
        if (info != null) {
            _uiState.update {
                it.copy(
                    localWifiIp = info.ipv4.hostAddress,
                    broadcastIp = info.broadcast.hostAddress,
                    localWifiPrefix = info.prefixLen
                )
            }
            return
        }
        val fallback = resolveLocalNetworkInfoLegacy()
        _uiState.update {
            it.copy(
                localWifiIp = fallback?.address?.hostAddress,
                broadcastIp = fallback?.broadcast?.hostAddress,
                localWifiPrefix = fallback?.prefix
            )
        }
    }

    private fun resolveLocalNetworkInfoLegacy(): LocalNetworkInfo? {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        while (interfaces.hasMoreElements()) {
            val ni = interfaces.nextElement()
            if (!ni.isUp || ni.isLoopback || ni.isVirtual) continue
            val addresses = ni.interfaceAddresses
            for (iface in addresses) {
                val address = iface.address
                if (address is Inet4Address && address.isSiteLocalAddress && !address.isLoopbackAddress) {
                    val broadcast = iface.broadcast ?: continue
                    val prefix = runCatching { iface.networkPrefixLength.toInt() }.getOrNull()
                    return LocalNetworkInfo(address, broadcast, prefix)
                }
            }
        }
        return null
    }

    private fun observeRecorder() {
        viewModelScope.launch {
            recorder.isRecording.collect { recording ->
                _uiState.update { it.copy(isRecordingCsv = recording) }
            }
        }
        viewModelScope.launch {
            recorder.rowsWritten.collect { count ->
                _uiState.update { it.copy(recordRows = count) }
            }
        }
        viewModelScope.launch {
            recorder.targetDisplay.collect { display ->
                _uiState.update { it.copy(recordTarget = display.ifBlank { null }) }
            }
        }
        viewModelScope.launch {
            recorder.lastError.collect { error ->
                _uiState.update { it.copy(recordError = error) }
                error?.let { appendLog("Recorder error: $it") }
            }
        }
    }

    override fun onCleared() {
        runBlocking {
            recorder.stop()
        }
        client.stopSession()
        runCatching { connectivityManager.unregisterNetworkCallback(wifiCallback) }
        super.onCleared()
    }

    private fun defaultCsvStem(): String =
        DateTimeFormatter.ofPattern("yyyyMMdd", Locale.US)
            .withZone(ZoneOffset.UTC)
            .format(Instant.now())
            .let { "EmotiBit-$it" }

    private fun ensureCsvExtension(stem: String): String {
        return if (stem.lowercase(Locale.US).endsWith(".csv")) stem else "$stem.csv"
    }

    data class LocalNetworkInfo(val address: InetAddress, val broadcast: InetAddress, val prefix: Int?)

    companion object {
        private const val MAX_LOG_ITEMS = 200
        private const val MAX_PREVIEW_CHARS = 80
    }
}

data class UiLogEntry(
    val id: Long,
    val message: String,
    val timestamp: Long
)

data class UiDiscovered(
    val ip: String,
    val deviceId: String?
)

data class EmotiBitUiState(
    val deviceIpText: String = "",
    val dpText: String = EmotiBitProto.DEFAULT_DATA_PORT.toString(),
    val cpText: String = EmotiBitProto.DEFAULT_CTRL_BACK_PORT.toString(),
    val ecIntervalText: String = "1000",
    val localWifiIp: String? = null,
    val broadcastIp: String? = null,
    val localWifiPrefix: Int? = null,
    val isStreaming: Boolean = false,
    val isScanning: Boolean = false,
    val packetsRx: Long = 0,
    val lastSender: String? = null,
    val lastPayloadPreview: String? = null,
    val errorMessage: String? = null,
    val logEntries: List<UiLogEntry> = emptyList(),
    val discoveredDevices: List<UiDiscovered> = emptyList(),
    val recordFileStem: String = "",
    val recordResolvedName: String = "",
    val recordUri: Uri? = null,
    val isRecordingCsv: Boolean = false,
    val recordRows: Long = 0,
    val recordTarget: String? = null,
    val recordError: String? = null
)
