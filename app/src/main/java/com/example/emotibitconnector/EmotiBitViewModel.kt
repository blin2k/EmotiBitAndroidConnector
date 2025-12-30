package com.example.emotibitconnector

import android.app.Application
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.MediaStore
import android.net.wifi.WifiManager
import com.google.android.gms.tasks.Tasks
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.emotibitconnector.CsvRecorder
import com.example.emotibitconnector.Logx
import com.example.emotibitconnector.SessionService
import com.example.emotibitconnector.network.EmotiBitClient
import com.example.emotibitconnector.network.EmotiBitProto
import com.example.emotibitconnector.network.SessionConfig
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import com.google.firebase.storage.UploadTask
import java.io.File
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
import kotlin.text.Regex

class EmotiBitViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val client = EmotiBitClient(application, viewModelScope)
    private val recorder = CsvRecorder(application, viewModelScope)
    private val locationLogger = LocationLogger(application, viewModelScope)
    private val recordingWakeManager = RecordingWakeManager(application)
    private val sessionWakeManager = SessionWakeManager(application)
    private val wifiLockManager = WifiLockManager(application)
    private val logCounter = AtomicLong(0)
    private val recordingsDir = File(application.filesDir, "EmotiBit")
    private val preferences: SharedPreferences = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
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
    private val storage = FirebaseStorage.getInstance()

    init {
        val savedUserId = loadSavedUserId()
        val stem = buildCsvStem(savedUserId.takeIf { it.isNotBlank() })
        _uiState.update {
            it.copy(
                userIdText = savedUserId,
                recordFileStem = stem,
                recordResolvedName = ensureCsvExtension(stem)
            )
        }
        refreshLocalNetworkInfo()
        observeRecorder()
        observeLocationLogger()
        refreshRecordings()
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

    fun updateUserId(value: String) {
        val sanitized = sanitizeUserId(value)
        persistUserId(sanitized)
        val stem = buildCsvStem(sanitized.takeIf { it.isNotBlank() })
        _uiState.update {
            it.copy(
                userIdText = sanitized,
                recordFileStem = stem,
                recordResolvedName = ensureCsvExtension(stem)
            )
        }
    }

    fun toggleOptionalUi() {
        _uiState.update { it.copy(showOptionalUi = !it.showOptionalUi) }
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

        val appContext = getApplication<Application>()
        viewModelScope.launch {
            appendLog("Starting session… deviceIp=${deviceIp.hostAddress} initialDp=$initialDp initialCp=${initialCp ?: "auto"} interval=${interval}ms")
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val network = client.bindToWifiNetwork()
                    if (network == null) {
                        appendLog("No ConnectivityManager Wi-Fi network detected; continuing with local interface")
                    }
                    client.startSession(config) { payload, length, address, port ->
                        handleIncomingPacket(payload, length, address, port)
                    }
                }
            }

            result.onSuccess {
                val (dp, cp) = client.currentPorts()
                refreshLocalNetworkInfo()
                wifiLockManager.acquire()
                sessionWakeManager.acquire()
                SessionService.start(appContext, deviceIp.hostAddress, dp, cp)
                _uiState.update {
                    it.copy(
                        dpText = dp.toString(),
                        cpText = cp.toString(),
                        isStreaming = true,
                        packetsRx = 0,
                        lastSender = null,
                        lastPayloadPreview = null,
                        errorMessage = null,
                        isStopSessionInProgress = false,
                        showStopSessionBusyDialog = false
                    )
                }
                appendLog("Session established dp=$dp cp=$cp")
            }.onFailure { throwable ->
                Logx.e("Failed to start session", throwable)
                setError(throwable.message ?: throwable.toString())
                client.stopSession()
                wifiLockManager.release()
                sessionWakeManager.release()
                SessionService.stop(appContext)
                _uiState.update {
                    it.copy(
                        isStopSessionInProgress = false,
                        showStopSessionBusyDialog = false,
                        isStreaming = false
                    )
                }
            }
        }
    }

    fun stopSession() {
        if (_uiState.value.isStopSessionInProgress) {
            _uiState.update { it.copy(showStopSessionBusyDialog = true) }
            return
        }
        _uiState.update { it.copy(isStopSessionInProgress = true, showStopSessionBusyDialog = false) }
        val appContext = getApplication<Application>()
        wifiLockManager.release()
        sessionWakeManager.release()
        SessionService.stop(appContext)
        viewModelScope.launch(Dispatchers.IO) {
            client.stopSession()
            try {
                recorder.stop()
            } catch (throwable: Throwable) {
                Logx.e("Failed to stop session recorder", throwable)
                setError("Stop listening cleanup failed: ${throwable.message ?: throwable}")
            } finally {
                _uiState.update {
                    it.copy(
                        isStreaming = false,
                        isStopSessionInProgress = false,
                        showStopSessionBusyDialog = false
                    )
                }
                appendLog("Session stopped")
                refreshLocalNetworkInfo()
            }
        }
    }

    fun startRecording() {
        if (recorder.isRecording.value) return
        val state = _uiState.value
        val sanitizedUserId = sanitizeUserId(state.userIdText)
        persistUserId(sanitizedUserId)
        val stem = buildCsvStem(sanitizedUserId.takeIf { it.isNotBlank() })
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
                            recordResolvedName = ensureCsvExtension(stem),
                            userIdText = sanitizedUserId,
                            isRecordingStopInProgress = false,
                            showRecordingBusyDialog = false
                        )
                    }
                    val gpsStartResult = runCatching { locationLogger.start(LocationLogger.Config(stem)) }
                    gpsStartResult.onSuccess {
                        val target = locationLogger.targetDisplay.value
                        val detail = if (target.isNotBlank()) " -> $target" else ""
                        appendLog("GPS logging started$detail")
                    }.onFailure { throwable ->
                        Logx.e("Failed to start GPS logging", throwable)
                        appendLog("GPS logging unavailable: ${throwable.message ?: throwable}")
                    }
                    refreshRecordings()
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
        val state = _uiState.value
        if (state.isRecordingStopInProgress) {
            _uiState.update { it.copy(showRecordingBusyDialog = true) }
            return
        }
        _uiState.update { it.copy(isRecordingStopInProgress = true, showRecordingBusyDialog = false) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                runCatching { locationLogger.stop() }
                    .onFailure { Logx.e("Failed to stop GPS logging", it) }
                recorder.stop()
                refreshRecordings()
                appendLog("Recording stopped")
            } catch (throwable: Throwable) {
                Logx.e("Failed to stop recording", throwable)
                setError("Stop recording failed: ${throwable.message ?: throwable}")
            } finally {
                _uiState.update { current ->
                    current.copy(
                        isRecordingStopInProgress = false,
                        showRecordingBusyDialog = false
                    )
                }
            }
        }
    }

    fun dismissRecordingBusyPrompt() {
        _uiState.update { it.copy(showRecordingBusyDialog = false) }
    }

    fun dismissStopSessionBusyPrompt() {
        _uiState.update { it.copy(showStopSessionBusyDialog = false) }
    }

    fun exportRecording(fileName: String, destination: RecordExportTarget) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            setError("Export requires Android 10 or higher")
            return
        }
        val file = findRecordingFile(fileName)
        if (file == null) {
            setError("Recording not found: $fileName")
            return
        }
        viewModelScope.launch {
            beginRecordFileOperation(fileName)
            val result = withContext(Dispatchers.IO) {
                runCatching { performExport(file, destination) }
            }
            endRecordFileOperation()
            result.onSuccess {
                val message = "Exported ${file.name} to ${destination.label}"
                _uiState.update {
                    it.copy(
                        recordFileStatusMessage = message,
                        recordFileErrorMessage = null
                    )
                }
                appendLog(message)
            }.onFailure { throwable ->
                val msg = throwable.message ?: throwable.toString()
                _uiState.update {
                    it.copy(
                        recordFileStatusMessage = null,
                        recordFileErrorMessage = msg
                    )
                }
                Logx.e("Failed to export recordings", throwable)
            }
            refreshRecordings()
        }
    }

    fun deleteRecording(fileName: String) {
        val file = findRecordingFile(fileName)
        if (file == null) {
            setError("Recording not found: $fileName")
            return
        }
        val activeTarget = _uiState.value.recordTarget
        if (recorder.isRecording.value && activeTarget == file.absolutePath) {
            setError("Stop recording before deleting the active file")
            return
        }
        viewModelScope.launch {
            beginRecordFileOperation(fileName)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (!file.delete()) {
                        throw IllegalStateException("Unable to delete ${file.name}")
                    }
                }
            }
            endRecordFileOperation()
            result.onSuccess {
                val message = "Deleted ${file.name} from private storage"
                _uiState.update {
                    it.copy(
                        recordFileStatusMessage = message,
                        recordFileErrorMessage = null
                    )
                }
                appendLog(message)
            }.onFailure { throwable ->
                val msg = throwable.message ?: throwable.toString()
                _uiState.update {
                    it.copy(
                        recordFileStatusMessage = null,
                        recordFileErrorMessage = msg
                    )
                }
                Logx.e("Failed to delete recording", throwable)
            }
            refreshRecordings()
        }
    }

    fun uploadRecording(fileName: String) {
        val file = findRecordingFile(fileName)
        if (file == null) {
            setError("Recording not found: $fileName")
            return
        }
        viewModelScope.launch {
            beginRecordFileOperation(fileName)
            val userId = _uiState.value.userIdText.takeIf { it.isNotBlank() }
            val result = withContext(Dispatchers.IO) {
                runCatching { performUpload(file, userId) }
            }
            endRecordFileOperation()
            result.onSuccess { upload ->
                val message = "Uploaded ${file.name} to Firebase Storage (${upload.remotePath})"
                _uiState.update {
                    it.copy(
                        recordFileStatusMessage = message,
                        recordFileErrorMessage = null
                    )
                }
                appendLog(message)
                upload.downloadUrl?.let { url ->
                    appendLog("Firebase download URL: $url")
                }
            }.onFailure { throwable ->
                val msg = throwable.message ?: throwable.toString()
                _uiState.update {
                    it.copy(
                        recordFileStatusMessage = null,
                        recordFileErrorMessage = msg
                    )
                }
                Logx.e("Failed to upload recording", throwable)
            }
            refreshRecordings()
        }
    }

    fun clearRecordFileStatus() {
        _uiState.update { it.copy(recordFileStatusMessage = null, recordFileErrorMessage = null) }
    }

    fun refreshRecordingList() {
        refreshRecordings()
    }

    private fun refreshRecordings() {
        viewModelScope.launch(Dispatchers.IO) {
            if (!recordingsDir.exists()) {
                recordingsDir.mkdirs()
            }
            val files = listRecordingFiles()
            _uiState.update { state ->
                state.copy(recordings = files)
            }
        }
    }

    private fun listRecordingFiles(): List<RecordFileInfo> {
        if (!recordingsDir.exists()) return emptyList()
        return recordingsDir
            .listFiles()
            ?.filter { it.isFile && it.extension.equals("csv", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?.map { file ->
                RecordFileInfo(
                    name = file.name,
                    absolutePath = file.absolutePath,
                    sizeBytes = file.length(),
                    lastModifiedMs = file.lastModified()
                )
            }
            .orEmpty()
    }

    private fun findRecordingFile(fileName: String): File? {
        if (!recordingsDir.exists()) return null
        val file = File(recordingsDir, fileName)
        return file.takeIf { it.exists() && it.isFile }
    }

    private fun beginRecordFileOperation(fileName: String) {
        _uiState.update {
            it.copy(
                isRecordFileOperationRunning = true,
                recordFileInProgress = fileName,
                recordFileStatusMessage = null,
                recordFileErrorMessage = null
            )
        }
    }

    private fun endRecordFileOperation() {
        _uiState.update {
            it.copy(
                isRecordFileOperationRunning = false,
                recordFileInProgress = null
            )
        }
    }

    private class RecordingWakeManager(app: Application) {
        private val powerManager = app.getSystemService(Context.POWER_SERVICE) as PowerManager?
        private val wakeLock: PowerManager.WakeLock? = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "EmotiBit:RecordingWake"
        )?.apply { setReferenceCounted(false) }

        fun acquire() {
            val lock = wakeLock ?: return
            if (!lock.isHeld) {
                runCatching { lock.acquire() }
            }
        }

        fun release() {
            val lock = wakeLock ?: return
            if (lock.isHeld) {
                runCatching { lock.release() }
            }
        }
    }

    private class SessionWakeManager(app: Application) {
        private val powerManager = app.getSystemService(Context.POWER_SERVICE) as PowerManager?
        private val wakeLock: PowerManager.WakeLock? = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "EmotiBit:Session"
        )?.apply { setReferenceCounted(false) }

        fun acquire() {
            val lock = wakeLock ?: return
            if (!lock.isHeld) {
                runCatching { lock.acquire() }
            }
        }

        fun release() {
            val lock = wakeLock ?: return
            if (lock.isHeld) {
                runCatching { lock.release() }
            }
        }
    }

    private class WifiLockManager(app: Application) {
        private val wifiManager = app.getSystemService(Context.WIFI_SERVICE) as WifiManager?
        private val wifiLock = wifiManager?.createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            "EmotiBit:WifiLock"
        )?.apply { setReferenceCounted(false) }
        private val multicastLock = wifiManager?.createMulticastLock("EmotiBit:Multicast")
            ?.apply { setReferenceCounted(false) }

        fun acquire() {
            wifiLock?.let { lock -> if (!lock.isHeld) runCatching { lock.acquire() } }
            multicastLock?.let { lock -> if (!lock.isHeld) runCatching { lock.acquire() } }
        }

        fun release() {
            wifiLock?.let { lock -> if (lock.isHeld) runCatching { lock.release() } }
            multicastLock?.let { lock -> if (lock.isHeld) runCatching { lock.release() } }
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

    private fun performExport(file: File, destination: RecordExportTarget) {
        val resolver = getApplication<Application>().contentResolver
        val uri = insertMediaEntry(resolver, file.name, destination)
        resolver.openOutputStream(uri)?.use { output ->
            file.inputStream().use { input ->
                input.copyTo(output)
            }
        } ?: throw IllegalStateException("Unable to open export destination for ${file.name}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val finalizeValues = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(uri, finalizeValues, null, null)
        }
    }

    private fun performUpload(file: File, userId: String?): UploadResult {
        val sanitized = userId?.let(::sanitizeUserId).orEmpty()
        val ownerSegment = sanitized.ifBlank { DEFAULT_UPLOAD_OWNER }
        val remotePath = listOf(FIREBASE_STORAGE_ROOT, ownerSegment, file.name).joinToString("/")
        val metadata = StorageMetadata.Builder()
            .setContentType("text/csv")
            .apply {
                setCustomMetadata("source", "EmotiBitConnector")
                setCustomMetadata("sizeBytes", file.length().toString())
                setCustomMetadata("uploadedAt", Instant.now().toString())
                if (sanitized.isNotBlank()) {
                    setCustomMetadata("userId", sanitized)
                }
            }
            .build()
        val ref = storage.reference.child(remotePath)
        val uri = Uri.fromFile(file)
        val uploadTask: UploadTask = ref.putFile(uri, metadata)
        Tasks.await<UploadTask.TaskSnapshot>(uploadTask)
        val downloadUrl = runCatching {
            Tasks.await<Uri>(ref.downloadUrl).toString()
        }.getOrNull()
        return UploadResult(remotePath = remotePath, downloadUrl = downloadUrl)
    }

    private data class UploadResult(
        val remotePath: String,
        val downloadUrl: String?
    )

    private fun insertMediaEntry(
        resolver: ContentResolver,
        displayName: String,
        destination: RecordExportTarget
    ): Uri {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw IllegalStateException("MediaStore export requires Android 10+")
        }
        val subDir = "${destination.relativePath}/EmotiBit"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
            put(MediaStore.MediaColumns.RELATIVE_PATH, subDir)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return resolver.insert(destination.collectionUri, values)
            ?: throw IllegalStateException("Unable to create export entry for $displayName")
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
                if (recording) {
                    recordingWakeManager.acquire()
                } else {
                    recordingWakeManager.release()
                }
                _uiState.update { current ->
                    current.copy(
                        isRecordingCsv = recording,
                        isRecordingStopInProgress = if (recording) current.isRecordingStopInProgress else false,
                        showRecordingBusyDialog = if (recording) current.showRecordingBusyDialog else false
                    )
                }
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

    private fun observeLocationLogger() {
        viewModelScope.launch {
            locationLogger.lastError.collect { error ->
                error?.let { appendLog("GPS logger error: $it") }
            }
        }
    }

    override fun onCleared() {
        runBlocking {
            locationLogger.stop()
            recorder.stop()
        }
        client.stopSession()
        runCatching { connectivityManager.unregisterNetworkCallback(wifiCallback) }
        recordingWakeManager.release()
        wifiLockManager.release()
        sessionWakeManager.release()
        SessionService.stop(getApplication())
        super.onCleared()
    }

    private fun currentDateStamp(): String =
        DateTimeFormatter.ofPattern("yyyyMMdd", Locale.US)
            .withZone(ZoneOffset.UTC)
            .format(Instant.now())

    private fun buildCsvStem(userId: String?): String {
        val prefix = userId?.takeIf { it.isNotBlank() }?.let(::sanitizeUserId)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_STEM_PREFIX
        return "$prefix-${currentDateStamp()}"
    }

    private fun defaultCsvStem(): String = buildCsvStem(null)

    private fun sanitizeUserId(raw: String): String {
        val filtered = raw.trim().mapNotNull { ch ->
            when {
                ch.isLetterOrDigit() -> ch
                ch == '-' || ch == '_' -> ch
                ch == ' ' -> '-' // convert spaces to dash
                else -> null
            }
        }
        return filtered.joinToString("")
            .replace(Regex("-+"), "-")
            .trim('-','_')
    }

    private fun loadSavedUserId(): String = sanitizeUserId(preferences.getString(KEY_USER_ID, "") ?: "")

    private fun persistUserId(value: String) {
        preferences.edit().apply {
            if (value.isBlank()) remove(KEY_USER_ID) else putString(KEY_USER_ID, value)
        }.apply()
    }

    private fun ensureCsvExtension(stem: String): String {
        return if (stem.lowercase(Locale.US).endsWith(".csv")) stem else "$stem.csv"
    }

    data class LocalNetworkInfo(val address: InetAddress, val broadcast: InetAddress, val prefix: Int?)

    companion object {
        private const val MAX_LOG_ITEMS = 200
        private const val MAX_PREVIEW_CHARS = 80
        private const val DEFAULT_STEM_PREFIX = "EmotiBit"
        private const val PREFS_NAME = "emotibit_connector_prefs"
        private const val KEY_USER_ID = "user_id"
        private const val FIREBASE_STORAGE_ROOT = "recordings"
        private const val DEFAULT_UPLOAD_OWNER = "anonymous"
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

data class RecordFileInfo(
    val name: String,
    val absolutePath: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long
)

    data class EmotiBitUiState(
        val deviceIpText: String = "",
        val dpText: String = EmotiBitProto.DEFAULT_DATA_PORT.toString(),
        val cpText: String = EmotiBitProto.DEFAULT_CTRL_BACK_PORT.toString(),
        val ecIntervalText: String = "1000",
        val userIdText: String = "",
        val showOptionalUi: Boolean = false,
        val isStopSessionInProgress: Boolean = false,
        val showStopSessionBusyDialog: Boolean = false,
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
        val isRecordingStopInProgress: Boolean = false,
        val recordRows: Long = 0,
        val recordTarget: String? = null,
        val recordError: String? = null,
        val isRecordFileOperationRunning: Boolean = false,
        val recordFileStatusMessage: String? = null,
        val recordFileErrorMessage: String? = null,
        val recordFileInProgress: String? = null,
        val recordings: List<RecordFileInfo> = emptyList(),
        val showRecordingBusyDialog: Boolean = false
    )

enum class RecordExportTarget(
    val label: String,
    val relativePath: String,
    val collectionUri: Uri
) {
    Downloads(
        label = "Downloads",
        relativePath = Environment.DIRECTORY_DOWNLOADS,
        collectionUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
    ),
    Documents(
        label = "Documents",
        relativePath = Environment.DIRECTORY_DOCUMENTS,
        collectionUri = MediaStore.Files.getContentUri("external")
    )
}
