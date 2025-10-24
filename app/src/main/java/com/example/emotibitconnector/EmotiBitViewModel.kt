package com.example.emotibitconnector

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.emotibitconnector.Logx
import com.example.emotibitconnector.network.EmotiBitClient
import com.example.emotibitconnector.network.EmotiBitProto
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class EmotiBitViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val client = EmotiBitClient(application, viewModelScope)
    private val logCounter = AtomicLong(0)
    private val _uiState = MutableStateFlow(EmotiBitUiState())
    val uiState: StateFlow<EmotiBitUiState> = _uiState.asStateFlow()

    init {
        refreshLocalNetworkInfo()
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

        appendLog("Starting session… deviceIp=${deviceIp.hostAddress} initialDp=$initialDp initialCp=${initialCp ?: "auto"} interval=${interval}ms")

        runCatching {
            client.startSession(
                config = com.example.emotibitconnector.network.SessionConfig(
                    deviceIp = deviceIp,
                    initialDp = initialDp,
                    initialCp = initialCp,
                    ecIntervalMs = interval
                )
            ) { payload, port, address ->
                val preview = payload.toPreview()
                _uiState.update { current ->
                    current.copy(
                        packetsRx = current.packetsRx + 1,
                        lastSender = "${address.hostAddress}:$port",
                        lastPayloadPreview = preview,
                        isStreaming = true
                    )
                }
            }
        }.onSuccess {
            val (dp, cp) = client.currentPorts()
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

    fun stopSession() {
        client.stopSession()
        appendLog("Session stopped")
        _uiState.update {
            it.copy(isStreaming = false)
        }
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

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
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

    private fun refreshLocalNetworkInfo() {
        val info = resolveLocalNetworkInfo()
        _uiState.update {
            it.copy(
                localWifiIp = info?.address?.hostAddress,
                broadcastIp = info?.broadcast?.hostAddress
            )
        }
    }

    private fun resolveLocalNetworkInfo(): LocalNetworkInfo? {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        while (interfaces.hasMoreElements()) {
            val ni = interfaces.nextElement()
            if (!ni.isUp || ni.isLoopback || ni.isVirtual) continue
            val addresses = ni.interfaceAddresses
            for (iface in addresses) {
                val address = iface.address
                if (address is Inet4Address && address.isSiteLocalAddress && !address.isLoopbackAddress) {
                    val broadcast = iface.broadcast ?: continue
                    return LocalNetworkInfo(address, broadcast)
                }
            }
        }
        return null
    }

    override fun onCleared() {
        client.stopSession()
        super.onCleared()
    }

    data class LocalNetworkInfo(val address: InetAddress, val broadcast: InetAddress)

    companion object {
        private const val MAX_LOG_ITEMS = 200
    }
}

private fun ByteArray.toPreview(max: Int = 80): String {
    val text = String(this, Charsets.US_ASCII)
    return if (text.length <= max) text else text.substring(0, max)
}

data class EmotiBitUiState(
    val deviceIpText: String = "",
    val dpText: String = EmotiBitProto.DEFAULT_DATA_PORT.toString(),
    val cpText: String = EmotiBitProto.DEFAULT_CTRL_BACK_PORT.toString(),
    val ecIntervalText: String = "1000",
    val localWifiIp: String? = null,
    val broadcastIp: String? = null,
    val isStreaming: Boolean = false,
    val packetsRx: Long = 0,
    val lastSender: String? = null,
    val lastPayloadPreview: String? = null,
    val errorMessage: String? = null,
    val logEntries: List<UiLogEntry> = emptyList()
)

data class UiLogEntry(
    val id: Long,
    val message: String,
    val timestamp: Long
)
