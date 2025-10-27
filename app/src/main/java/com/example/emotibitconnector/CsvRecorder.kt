package com.example.emotibitconnector

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import com.example.emotibitconnector.Logx
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class CsvRecorder(
    private val app: Application,
    private val scope: CoroutineScope
) {

    data class Config(
        val fileNameStem: String? = null,
        val useSafUri: Uri? = null
    )

    private data class OutputTarget(
        val stream: OutputStream,
        val display: String,
        val headerAlreadyPresent: Boolean,
        val headerDelayMs: Long
    )

    private data class CsvRow(
        val timestampMs: Long,
        val remoteIp: String,
        val remotePort: Int,
        val payload: String
    )

    private val mutex = Mutex()
    private var channel: Channel<CsvRow>? = null
    private var writerJob: Job? = null
    private var outputStream: OutputStream? = null
    private var writer: BufferedWriter? = null
    private var headerWritten = false
    private var headerDelayMs = 0L

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _rowsWritten = MutableStateFlow(0L)
    val rowsWritten: StateFlow<Long> = _rowsWritten.asStateFlow()

    private val _targetDisplay = MutableStateFlow("")
    val targetDisplay: StateFlow<String> = _targetDisplay.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    suspend fun start(config: Config) {
        mutex.withLock {
            stopLocked()

            val target = withContext(Dispatchers.IO) {
                resolveOutput(config)
            }

            val stream = target.stream
            outputStream = stream
            writer = BufferedWriter(OutputStreamWriter(stream, StandardCharsets.UTF_8))
            headerWritten = target.headerAlreadyPresent
            headerDelayMs = target.headerDelayMs
            channel = Channel(capacity = CHANNEL_CAPACITY, onBufferOverflow = BufferOverflow.DROP_OLDEST)
            _rowsWritten.value = 0L
            _targetDisplay.value = target.display
            _lastError.value = null

            val localChannel = channel!!
            writerJob = scope.launch(Dispatchers.IO) {
                runWriterLoop(localChannel)
            }
            _isRecording.value = true
            Logx.i("CSV recording started -> ${target.display}")
        }
    }

    fun append(timestampMs: Long, remoteIp: String, remotePort: Int, payload: String) {
        if (!_isRecording.value) return
        channel?.trySend(CsvRow(timestampMs, remoteIp, remotePort, payload))
    }

    suspend fun stop() {
        mutex.withLock {
            stopLocked()
        }
    }

    private suspend fun stopLocked() {
        val hadResources = channel != null || writer != null || outputStream != null
        _isRecording.value = false
        channel?.close()
        channel = null
        writerJob?.join()
        writerJob = null
        withContext(Dispatchers.IO) {
            runCatching { writer?.flush() }
            runCatching { writer?.close() }
            runCatching { outputStream?.close() }
        }
        writer = null
        outputStream = null
        headerWritten = false
        headerDelayMs = 0L
        if (hadResources) {
            Logx.i("CSV recording stopped")
        }
    }

    private suspend fun runWriterLoop(channel: Channel<CsvRow>) {
        val activeWriter = writer ?: return
        val buffer = ArrayList<CsvRow>(BATCH_FLUSH_THRESHOLD)
        var lastFlushAt = System.currentTimeMillis()
        try {
            val delayBeforeHeader = headerDelayMs
            if (!headerWritten && delayBeforeHeader > 0L) {
                delay(delayBeforeHeader)
                headerDelayMs = 0L
            }
            ensureHeader(activeWriter)
            while (scope.isActive) {
                val first = try {
                    withTimeoutOrNull(FLUSH_INTERVAL_MS) { channel.receive() }
                } catch (_: ClosedReceiveChannelException) {
                    break
                }
                if (first == null) {
                    if (buffer.isNotEmpty()) {
                        writeBatch(buffer, activeWriter)
                        activeWriter.flush()
                        lastFlushAt = System.currentTimeMillis()
                    }
                    continue
                }
                buffer.add(first)
                while (buffer.size < BATCH_FLUSH_THRESHOLD) {
                    val next = channel.tryReceive().getOrNull() ?: break
                    buffer.add(next)
                }
                val now = System.currentTimeMillis()
                if (buffer.size >= BATCH_FLUSH_THRESHOLD || now - lastFlushAt >= FLUSH_INTERVAL_MS) {
                    writeBatch(buffer, activeWriter)
                    activeWriter.flush()
                    lastFlushAt = now
                }
            }
        } catch (ex: Exception) {
            Logx.e("CSV writer encountered error", ex)
            _lastError.value = ex.message ?: ex.toString()
        } finally {
            while (true) {
                val next = channel.tryReceive().getOrNull() ?: break
                buffer.add(next)
                if (buffer.size >= BATCH_FLUSH_THRESHOLD) {
                    writeBatch(buffer, activeWriter)
                }
            }
            if (buffer.isNotEmpty()) {
                writeBatch(buffer, activeWriter)
            }
            runCatching { activeWriter.flush() }
            _isRecording.value = false
        }
    }

    private fun ensureHeader(writer: BufferedWriter) {
        if (headerWritten) return
        writer.write(CSV_HEADER)
        writer.write("\n")
        headerWritten = true
        writer.flush()
    }

    private fun writeBatch(rows: MutableList<CsvRow>, writer: BufferedWriter) {
        if (rows.isEmpty()) return
        val sb = StringBuilder(rows.size * 64)
        for (row in rows) {
            val iso = row.timestampMs.toIsoUtc()
            val fields = listOf(
                iso,
                row.timestampMs.toString(),
                row.remoteIp,
                row.remotePort.toString(),
                row.payload
            )
            sb.append(fields.joinToString(",") { it.csvEscape() })
            sb.append('\n')
        }
        writer.write(sb.toString())
        _rowsWritten.value = _rowsWritten.value + rows.size
        rows.clear()
    }

    private fun resolveOutput(config: Config): OutputTarget {
        config.useSafUri?.let { uri ->
            val resolver: ContentResolver = app.contentResolver
            val stream = resolver.openOutputStream(uri, "wa")
                ?: throw IllegalStateException("Unable to open SAF Uri: $uri")
            return OutputTarget(stream, uri.toString(), headerAlreadyPresent = false, headerDelayMs = 0L)
        }

        val stem = config.fileNameStem?.takeIf { it.isNotBlank() } ?: defaultStem()
        val dir = File(app.filesDir, "EmotiBit")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val file = File(dir, ensureCsvExtension(stem))
        val existedBefore = file.exists()
        val headerAlreadyPresent = existedBefore && file.length() > 0L
        val stream = FileOutputStream(file, /* append = */ true)
        val headerDelay = if (existedBefore) 0L else NEW_FILE_HEADER_DELAY_MS
        return OutputTarget(stream, file.absolutePath, headerAlreadyPresent, headerDelay)
    }

    private fun defaultStem(): String =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US)
            .withZone(ZoneOffset.UTC)
            .format(Instant.now())
            .let { "EmotiBit-$it" }

    private fun ensureCsvExtension(stem: String): String {
        return if (stem.lowercase(Locale.US).endsWith(".csv")) stem else "$stem.csv"
    }

    private fun Long.toIsoUtc(): String =
        DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(this))

    companion object {
        private const val CHANNEL_CAPACITY = 8_192
        private const val BATCH_FLUSH_THRESHOLD = 200
        private const val FLUSH_INTERVAL_MS = 250L
        private const val CSV_HEADER = "timestamp_iso8601,timestamp_epoch_ms,remote_ip,remote_port,payload"
        private const val NEW_FILE_HEADER_DELAY_MS = 1_000L
    }
}

fun String.csvEscape(): String {
    val needsQuotes = any { it == ',' || it == '"' || it == '\n' || it == '\r' }
    if (!needsQuotes) return this
    val escaped = StringBuilder(length + 4)
    escaped.append('"')
    for (ch in this) {
        if (ch == '"') {
            escaped.append('"')
            escaped.append('"')
        } else {
            escaped.append(ch)
        }
    }
    escaped.append('"')
    return escaped.toString()
}
