package com.example.emotibitconnector

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
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
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Collects GPS fixes and writes them to a CSV file once per second.
 */
class LocationLogger(
    private val app: Application,
    private val scope: CoroutineScope
) {

    data class Config(val fileNameStem: String)

    private data class OutputTarget(
        val stream: OutputStream,
        val display: String,
        val headerAlreadyPresent: Boolean
    )

    private val mutex = Mutex()
    private val locationManager = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager?
    private val latestLocation = AtomicReference<Location?>(null)
    private var outputStream: OutputStream? = null
    private var writer: BufferedWriter? = null
    private var logJob: Job? = null
    private var locationListener: LocationListener? = null

    private val _isLogging = MutableStateFlow(false)
    val isLogging: StateFlow<Boolean> = _isLogging.asStateFlow()

    private val _targetDisplay = MutableStateFlow("")
    val targetDisplay: StateFlow<String> = _targetDisplay.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    suspend fun start(config: Config) {
        mutex.withLock {
            stopLocked()
            ensurePermissions()
            val manager = locationManager ?: throw IllegalStateException("LocationManager unavailable")
            val target = resolveOutput(config)
            outputStream = target.stream
            writer = BufferedWriter(OutputStreamWriter(target.stream, StandardCharsets.UTF_8))
            _targetDisplay.value = target.display
            _lastError.value = null
            if (!target.headerAlreadyPresent) {
                writer?.apply {
                    write(CSV_HEADER)
                    write("\n")
                    flush()
                }
            }
            val listener = buildLocationListener()
            locationListener = listener
            requestLocationUpdates(manager, listener)
            logJob = scope.launch(Dispatchers.IO) {
                runLoggerLoop(writer!!)
            }
            _isLogging.value = true
            Logx.i("GPS logging started -> ${target.display}")
        }
    }

    suspend fun stop() {
        mutex.withLock {
            stopLocked()
        }
    }

    private suspend fun stopLocked() {
        val hadResources = outputStream != null || writer != null || logJob != null
        _isLogging.value = false
        val job = logJob
        if (job != null) {
            job.cancel()
            runCatching { job.join() }
            logJob = null
        }
        val manager = locationManager
        val listener = locationListener
        if (manager != null && listener != null) {
            withContext(Dispatchers.Main) {
                runCatching { manager.removeUpdates(listener) }
            }
        }
        locationListener = null
        runCatching { writer?.flush() }
        runCatching { writer?.close() }
        runCatching { outputStream?.close() }
        writer = null
        outputStream = null
        latestLocation.set(null)
        if (hadResources) {
            Logx.i("GPS logging stopped")
        }
    }

    private suspend fun runLoggerLoop(activeWriter: BufferedWriter) {
        try {
            while (coroutineContext.isActive) {
                val now = System.currentTimeMillis()
                val iso = now.toIsoUtc()
                val location = latestLocation.get()
                val fields = if (location != null) {
                    listOf(
                        iso,
                        now.toString(),
                        location.latitude.toCsvString(),
                        location.longitude.toCsvString(),
                        location.accuracy.toCsvAccuracy(),
                        location.provider.orEmpty()
                    )
                } else {
                    listOf(
                        iso,
                        now.toString(),
                        "",
                        "",
                        "",
                        "no_fix"
                    )
                }
                val line = fields.joinToString(",") { it.csvEscape() }
                activeWriter.write(line)
                activeWriter.write("\n")
                runCatching { activeWriter.flush() }
                delay(LOG_INTERVAL_MS)
            }
        } catch (_: CancellationException) {
            // expected on stop
        } catch (ex: Exception) {
            Logx.e("GPS logger encountered error", ex)
            _lastError.value = ex.message ?: ex.toString()
        } finally {
            _isLogging.value = false
        }
    }

    private suspend fun requestLocationUpdates(manager: LocationManager, listener: LocationListener) {
        withContext(Dispatchers.Main) {
            runCatching {
                manager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    LOCATION_UPDATE_INTERVAL_MS,
                    0f,
                    listener,
                    Looper.getMainLooper()
                )
            }.onFailure { Logx.e("GPS provider request failed", it) }
            runCatching {
                manager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    LOCATION_UPDATE_INTERVAL_MS,
                    0f,
                    listener,
                    Looper.getMainLooper()
                )
            }.onFailure { Logx.e("Network provider request failed", it) }
        }
    }

    private fun ensurePermissions() {
        val fineGranted = ContextCompat.checkSelfPermission(
            app,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            app,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!fineGranted && !coarseGranted) {
            throw SecurityException("Location permission not granted")
        }
    }

    private fun buildLocationListener(): LocationListener {
        return object : LocationListener {
            override fun onLocationChanged(location: Location) {
                latestLocation.set(location)
            }

            override fun onProviderDisabled(provider: String) {
                _lastError.value = "Provider disabled: $provider"
            }

            override fun onProviderEnabled(provider: String) {
                _lastError.value = null
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
                // no-op
            }
        }
    }

    private fun resolveOutput(config: Config): OutputTarget {
        val dir = File(app.filesDir, "EmotiBit")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val fileName = ensureCsvExtension("${config.fileNameStem}-location")
        val file = File(dir, fileName)
        val existedBefore = file.exists()
        val stream = FileOutputStream(file, /* append = */ true)
        return OutputTarget(
            stream = stream,
            display = file.absolutePath,
            headerAlreadyPresent = existedBefore && file.length() > 0L
        )
    }

    private fun ensureCsvExtension(stem: String): String {
        return if (stem.lowercase(Locale.US).endsWith(".csv")) stem else "$stem.csv"
    }

    private fun Long.toIsoUtc(): String =
        DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(this))

    private fun Double.toCsvString(): String = String.format(Locale.US, "%.7f", this)

    private fun Float.toCsvAccuracy(): String = String.format(Locale.US, "%.2f", this)

    companion object {
        private const val LOG_INTERVAL_MS = 1_000L
        private const val LOCATION_UPDATE_INTERVAL_MS = 1_000L
        private const val CSV_HEADER = "timestamp_iso8601,timestamp_epoch_ms,latitude_deg,longitude_deg,accuracy_m,provider"
    }
}
