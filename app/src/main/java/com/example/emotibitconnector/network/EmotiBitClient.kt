package com.example.emotibitconnector.network

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.BindException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.ArrayList
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.Inet4Address
import java.nio.ByteBuffer
import com.example.emotibitconnector.Logx

/** Configuration for establishing a full EmotiBit Wi-Fi session. */
data class SessionConfig(
    val deviceIp: InetAddress,
    val initialDp: Int = EmotiBitProto.DEFAULT_DATA_PORT,
    val initialCp: Int? = null,
    val ecIntervalMs: Long = 1_000L
)

/**
 * Manages UDP receive, TCP control, and EC heartbeat for the ASCII control protocol.
 */
class EmotiBitClient(
    private val app: Application,
    private val scope: CoroutineScope,
    private val ioContext: CoroutineContext = Dispatchers.IO
) {

    @Volatile private var boundWifi: Network? = null

    @Volatile private var udpSock: DatagramSocket? = null
    @Volatile private var tcpServer: ServerSocket? = null
    @Volatile private var tcpClient: Socket? = null
    @Volatile private var controlWriter: BufferedWriter? = null
    @Volatile private var controlReader: BufferedReader? = null
    @Volatile private var ecJob: Job? = null
    @Volatile private var udpJob: Job? = null
    @Volatile private var tcpJob: Job? = null
    @Volatile private var configRef: SessionConfig? = null
    @Volatile private var dataCallback: ((ByteArray, Int, InetAddress, Int) -> Unit)? = null
    @Volatile private var stopRequested: Boolean = false
    private val ecSeq = AtomicInteger(0)
    private val tcpClientRef = AtomicReference<Socket>()
    private val lock = Any()

    @Volatile private var chosenDp: Int = EmotiBitProto.DEFAULT_DATA_PORT
    @Volatile private var chosenCp: Int = EmotiBitProto.DEFAULT_CTRL_BACK_PORT

    fun currentPorts(): Pair<Int, Int> = chosenDp to chosenCp

    data class NetInfo(
        val ipv4: Inet4Address,
        val prefixLen: Int,
        val broadcast: Inet4Address
    )

    data class DiscoveredDevice(
        val ip: InetAddress,
        val deviceId: String?
    )

    suspend fun bindToWifiNetwork(context: Context = app): Network? = withContext(ioContext) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cached = boundWifi?.takeIf { isWifiNetwork(cm, it) }
        if (cached != null) {
            Logx.i("Reusing previously bound Wi-Fi network ${cached}")
            bindProcessToNetwork(cm, cached)
            return@withContext cached
        }

        val existing = findExistingWifiNetwork(cm)
        if (existing != null) {
            Logx.i("Found active Wi-Fi network without request: $existing")
            bindProcessToNetwork(cm, existing)
            return@withContext existing.also { boundWifi = it }
        }

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val result = CompletableDeferred<Network?>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!result.isCompleted) {
                    result.complete(network)
                }
            }

            override fun onUnavailable() {
                if (!result.isCompleted) {
                    result.complete(null)
                }
            }
        }
        cm.requestNetwork(request, callback)
        try {
            val network = withTimeout(NETWORK_BIND_TIMEOUT_MS) { result.await() }
            if (network != null) {
                Logx.i("Wi-Fi network request succeeded: $network")
                bindProcessToNetwork(cm, network)
                boundWifi = network
            } else {
                Logx.w("Wi-Fi network request returned null; falling back to local-only interface if available")
            }
            network
        } catch (ex: Exception) {
            Logx.e("Failed to bind to Wi-Fi network", ex)
            null
        } finally {
            runCatching { cm.unregisterNetworkCallback(callback) }
        }
    }

    fun unbindWifi(context: Context = app) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        Logx.i("Unbinding process network from Wi-Fi")
        bindProcessToNetwork(cm, null)
        boundWifi = null
    }

    fun currentWifiNetInfo(context: Context = app): NetInfo? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = boundWifi?.takeIf { isWifiNetwork(cm, it) }
            ?: findExistingWifiNetwork(cm)
        if (network != null) {
            if (boundWifi != network) {
                bindProcessToNetwork(cm, network)
            }
            val linkProps = cm.getLinkProperties(network)
            val linkAddress = linkProps?.linkAddresses?.firstOrNull { it.address is Inet4Address }
            val ipv4 = linkAddress?.address as? Inet4Address
            val prefix = linkAddress?.prefixLength
            if (ipv4 != null && prefix != null) {
                val broadcast = computeBroadcast(ipv4, prefix)
                Logx.i("Wi-Fi net info ip=${ipv4.hostAddress} prefix=$prefix broadcast=${broadcast.hostAddress}")
                return NetInfo(ipv4, prefix, broadcast)
            }
        }

        val hotspotInfo = computeLegacyNetInfo()
        if (hotspotInfo != null) {
            Logx.i("Legacy net info ip=${hotspotInfo.ipv4.hostAddress} prefix=${hotspotInfo.prefixLen} broadcast=${hotspotInfo.broadcast.hostAddress}")
        } else {
            Logx.w("Unable to resolve local IPv4 network info")
        }
        return hotspotInfo
    }

    suspend fun scanEmotiBits(
        context: Context = app,
        ecCp: Int = EmotiBitProto.DEFAULT_CTRL_BACK_PORT,
        ecDp: Int = EmotiBitProto.DEFAULT_DATA_PORT,
        timeoutMs: Long = 1_500L,
        maxHosts: Int = 256
    ): List<DiscoveredDevice> = withContext(ioContext) {
        val network = bindToWifiNetwork(context)
        val net = currentWifiNetInfo(context) ?: run {
            Logx.w("scanEmotiBits: unable to resolve Wi-Fi net info")
            return@withContext emptyList<DiscoveredDevice>()
        }

        val hosts = computeHostRange(net.ipv4, net.prefixLen, maxHosts)
        Logx.i("Scan: subnet /${net.prefixLen}, hosts=${hosts.size}, broadcast=${net.broadcast.hostAddress}")

        val socket = DatagramSocket().apply {
            soTimeout = timeoutMs.toInt()
            broadcast = true
        }
        if (network != null) {
            runCatching { network.bindSocket(socket) }
                .onFailure { Logx.w("Scan: failed to bind socket to Wi-Fi network", it) }
        } else {
            Logx.i("Scan: using default interface (no ConnectivityManager Wi-Fi network)")
        }

        fun sendLine(dst: InetAddress, line: String) {
            val bytes = (line + "\n").toByteArray(StandardCharsets.US_ASCII)
            val packet = DatagramPacket(bytes, bytes.size, InetSocketAddress(dst, EmotiBitProto.DEVICE_CTRL_PORT))
            runCatching { socket.send(packet) }
                .onFailure { Logx.w("Scan: failed to send to ${dst.hostAddress}", it) }
        }

        val ts = nowSec()
        val he = EmotiBitProto.buildHe(ts)
        val ecLine = EmotiBitProto.buildEc(ts, 1, ecCp, ecDp)

        runCatching { sendLine(InetAddress.getByName("255.255.255.255"), he) }
        runCatching { sendLine(net.broadcast, he) }

        hosts.forEach { host ->
            sendLine(host, he)
            sendLine(host, ecLine)
        }

        val discovered = LinkedHashMap<String, DiscoveredDevice>()
        val buffer = ByteArray(2048)
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val payload = String(packet.data, 0, packet.length, StandardCharsets.US_ASCII)
                if (payload.contains(",HH,")) {
                    val deviceId = parseDeviceIdFromHh(payload)
                    val key = packet.address.hostAddress
                    if (discovered.putIfAbsent(key, DiscoveredDevice(packet.address, deviceId)) == null) {
                        Logx.i("Scan: discovered $key id=${deviceId ?: "Unknown"}")
                    }
                }
            } catch (_: SocketTimeoutException) {
                break
            }
        }
        socket.close()
        discovered.values.toList()
    }

    fun startSession(config: SessionConfig, onData: (ByteArray, Int, InetAddress, Int) -> Unit) {
        synchronized(lock) {
            stopSessionLocked()
            stopRequested = false
            dataCallback = onData
            Logx.i("Starting session with initial dp=${config.initialDp} cp=${config.initialCp ?: "auto"}")
            val netInfo = currentWifiNetInfo()
            if (netInfo == null) {
                Logx.w("Starting session without Wi-Fi net info – ensure device is connected to hotspot")
            }
            val binding = try {
                bindSockets(config)
            } catch (ex: Exception) {
                Logx.e("Failed to bind sockets", ex)
                throw ex
            }

            chosenDp = binding.dp
            chosenCp = binding.cp
            Logx.i("Session ports resolved dp=$chosenDp cp=$chosenCp")

            udpSock = binding.udp
            tcpServer = binding.server
            configRef = config.copy(initialDp = chosenDp, initialCp = chosenCp, ecIntervalMs = config.ecIntervalMs)
            udpJob = scope.launch(ioContext) { runUdpLoop(binding.udp) }
            tcpJob = scope.launch(ioContext) { runTcpServer(binding.server) }
            ecJob = scope.launch(ioContext) { runEcHeartbeat(config.deviceIp, config.ecIntervalMs) }
        }
    }

    fun stopSession() {
        synchronized(lock) {
            stopSessionLocked()
        }
    }

    suspend fun sendStart(opcode: String = "PN") {
        val socket = tcpClientRef.get() ?: run {
            Logx.w("sendStart($opcode) ignored – no TCP client connected")
            return
        }
        val writer = controlWriter ?: BufferedWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII)).also {
            controlWriter = it
        }
        val ts = nowSec()
        val dp = chosenDp
        val line = when (opcode.uppercase()) {
            "PN" -> EmotiBitProto.buildPn(ts, dp)
            "PO" -> EmotiBitProto.buildPo(ts, dp)
            else -> throw IllegalArgumentException("Unsupported opcode: $opcode")
        }
        writer.write(line)
        writer.flush()
        Logx.i("TCP send $opcode -> ${socket.inetAddress.hostAddress}:${socket.port} line=${line.trim()}")
    }

    suspend fun sendHe() {
        val config = configRef ?: return
        val sendSocket = DatagramSocket()
        boundWifi?.let { network -> runCatching { network.bindSocket(sendSocket) } }
        try {
            val payload = EmotiBitProto.buildHe(nowSec()).toByteArray(StandardCharsets.US_ASCII)
            val packet = DatagramPacket(
                payload,
                payload.size,
                InetSocketAddress(config.deviceIp, EmotiBitProto.DEVICE_CTRL_PORT)
            )
            sendSocket.send(packet)
            Logx.i("HE -> ${config.deviceIp.hostAddress}:${EmotiBitProto.DEVICE_CTRL_PORT} line=${String(payload, StandardCharsets.US_ASCII).trim()}")
        } finally {
            sendSocket.close()
        }
    }

    private fun stopSessionLocked() {
        stopRequested = true
        ecJob?.cancel()
        udpJob?.cancel()
        tcpJob?.cancel()
        ecJob = null
        udpJob = null
        tcpJob = null

        runCatching { udpSock?.close() }
        udpSock = null

        runCatching { controlReader?.close() }
        runCatching { controlWriter?.close() }
        controlReader = null
        controlWriter = null

        runCatching { tcpClient?.close() }
        tcpClient = null
        tcpClientRef.set(null)

        runCatching { tcpServer?.close() }
        tcpServer = null
        configRef = null
        dataCallback = null
        ecSeq.set(0)
        Logx.i("Session stopped")
        if (boundWifi != null) {
            unbindWifi(app)
        }
    }

    private fun bindSockets(config: SessionConfig): PortBinding {
        var dpCandidate = if (config.initialDp % 2 == 0) config.initialDp else config.initialDp + 1
        var cpCandidate = config.initialCp ?: (dpCandidate + 1)
        repeat(MAX_PORT_ATTEMPTS) { attempt ->
            var udp: DatagramSocket? = null
            var server: ServerSocket? = null
            try {
                udp = DatagramSocket(null).apply {
                    reuseAddress = true
                    soTimeout = 1_000
                    bind(InetSocketAddress(dpCandidate))
                    boundWifi?.let { network -> runCatching { network.bindSocket(this) } }
                }
                server = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(cpCandidate))
                }
                Logx.i("Bound candidate dp=$dpCandidate cp=$cpCandidate (attempt=${attempt + 1})")
                return PortBinding(dpCandidate, cpCandidate, udp, server)
            } catch (bind: BindException) {
                Logx.w("Port in use dp=$dpCandidate cp=$cpCandidate. Retrying…", bind)
                udp?.close()
                server?.close()
            } catch (socketEx: SocketException) {
                Logx.w("Socket exception while probing dp=$dpCandidate cp=$cpCandidate", socketEx)
                udp?.close()
                server?.close()
            }
            dpCandidate += 2
            cpCandidate = if (config.initialCp != null) cpCandidate + 2 else dpCandidate + 1
        }
        throw BindException("Unable to bind DP/CP near ${config.initialDp}")
    }

    private suspend fun runUdpLoop(socket: DatagramSocket) {
        val buffer = ByteArray(MAX_UDP_PACKET)
        var packetCount = 0L
        try {
            while (scope.isActive && !stopRequested) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (timeout: SocketTimeoutException) {
                    if (!scope.isActive || stopRequested) break
                    continue
                }
                packetCount++
                if (packetCount == 1L || packetCount % 100L == 0L) {
                    Logx.i("UDP received count=$packetCount from ${packet.address.hostAddress}:${packet.port} len=${packet.length}")
                }
                val payload = packet.data.copyOf(packet.length)
                dataCallback?.invoke(payload, packet.length, packet.address, packet.port)
            }
        } catch (ex: Exception) {
            if (!stopRequested) {
                Logx.e("UDP loop terminated", ex)
            }
        } finally {
            runCatching { socket.close() }
            Logx.i("UDP socket closed dp=$chosenDp")
        }
    }

    private suspend fun runTcpServer(server: ServerSocket) {
        try {
            while (scope.isActive && !stopRequested) {
                Logx.i("Waiting for TCP connection on cp=$chosenCp")
                val client = try {
                    server.accept()
                } catch (ex: Exception) {
                    if (stopRequested) break
                    Logx.e("TCP accept failed", ex)
                    break
                }
                Logx.i("TCP connected from ${client.inetAddress.hostAddress}:${client.port}")
                tcpClientRef.set(client)
                tcpClient = client
                client.keepAlive = true
                controlWriter = BufferedWriter(OutputStreamWriter(client.getOutputStream(), StandardCharsets.US_ASCII))
                controlReader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII))
                drainTcpInput(client)
                tcpClientRef.compareAndSet(client, null)
                runCatching { client.close() }
                controlWriter = null
                controlReader = null
                Logx.i("TCP client disconnected")
            }
        } finally {
            runCatching { server.close() }
            Logx.i("TCP server closed cp=$chosenCp")
        }
    }

    private suspend fun drainTcpInput(socket: Socket) {
        val reader = controlReader ?: return
        try {
            while (scope.isActive && !stopRequested && !socket.isClosed) {
                if (reader.readLine() == null) {
                    break
                }
            }
        } catch (_: Exception) {
            // Ignored; disconnect handled by caller.
        }
    }

    private suspend fun runEcHeartbeat(deviceIp: InetAddress, intervalMs: Long) {
        var sendSocket: DatagramSocket? = null
        try {
            while (scope.isActive && !stopRequested) {
                if (sendSocket == null || sendSocket!!.isClosed) {
                    sendSocket = createEcSocket()
                    if (sendSocket == null) {
                        if (!stopRequested) {
                            Logx.w("EC heartbeat socket unavailable; retrying in $EC_SOCKET_RETRY_DELAY_MS ms")
                            delay(EC_SOCKET_RETRY_DELAY_MS)
                        }
                        continue
                    }
                }

                val seq = ecSeq.getAndIncrement()
                val line = EmotiBitProto.buildEc(nowSec(), seq, chosenCp, chosenDp)
                val payload = line.toByteArray(StandardCharsets.US_ASCII)
                val packet = DatagramPacket(
                    payload,
                    payload.size,
                    InetSocketAddress(deviceIp, EmotiBitProto.DEVICE_CTRL_PORT)
                )

                try {
                    sendSocket!!.send(packet)
                    Logx.i("EC -> ${deviceIp.hostAddress}:${EmotiBitProto.DEVICE_CTRL_PORT} seq=$seq line=${line.trim()}")
                    delay(intervalMs)
                } catch (io: IOException) {
                    if (!stopRequested) {
                        Logx.w("EC heartbeat send failed; recreating socket", io)
                    }
                    runCatching { sendSocket?.close() }
                    sendSocket = null
                    if (!stopRequested) {
                        delay(EC_SOCKET_RETRY_DELAY_MS)
                    }
                } catch (ex: Exception) {
                    if (!stopRequested) {
                        Logx.e("EC heartbeat stopped", ex)
                    }
                    break
                }
            }
        } finally {
            runCatching { sendSocket?.close() }
            Logx.i("EC heartbeat socket closed")
        }
    }

    private fun createEcSocket(): DatagramSocket? {
        return try {
            DatagramSocket().apply {
                boundWifi?.let { network ->
                    runCatching { network.bindSocket(this) }.onFailure {
                        Logx.w("Failed to bind EC socket to Wi-Fi network", it)
                    }
                }
            }
        } catch (ex: Exception) {
            if (!stopRequested) {
                Logx.w("Unable to open EC heartbeat socket", ex)
            }
            null
        }
    }

    fun getWifiNetInfo(): NetInfo? = currentWifiNetInfo(app)

    private fun computeHostRange(ip: Inet4Address, prefix: Int, cap: Int): List<InetAddress> {
        if (prefix >= 31) return emptyList()
        val mask = prefixToMask(prefix)
        val ipInt = ipv4ToInt(ip)
        val network = ipInt and mask
        val broadcast = network or mask.inv()
        val start = network + 1L
        val end = broadcast - 1L
        if (end < start) return emptyList()
        val results = ArrayList<InetAddress>()
        var current = start
        while (current <= end && results.size < cap) {
            if (current.toInt() != ipInt) {
                results.add(intToInet(current.toInt()))
            }
            current++
        }
        return results
    }

    private fun ipv4ToInt(ip: Inet4Address): Int = ByteBuffer.wrap(ip.address).int

    private fun intToInet(value: Int): Inet4Address {
        val bytes = byteArrayOf(
            ((value ushr 24) and 0xFF).toByte(),
            ((value ushr 16) and 0xFF).toByte(),
            ((value ushr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte()
        )
        return InetAddress.getByAddress(bytes) as Inet4Address
    }

    private fun computeBroadcast(ip: Inet4Address, prefix: Int): Inet4Address {
        val mask = prefixToMask(prefix)
        val ipInt = ipv4ToInt(ip)
        val broadcast = ipInt or mask.inv()
        return intToInet(broadcast)
    }

    private fun computeLegacyNetInfo(): NetInfo? {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        while (interfaces.hasMoreElements()) {
            val ni = interfaces.nextElement()
            if (!ni.isUp || ni.isLoopback || ni.isVirtual) continue
            val ifaceAddresses = ni.interfaceAddresses ?: continue
            for (iface in ifaceAddresses) {
                val addr = iface.address
                if (addr is Inet4Address && addr.isSiteLocalAddress && !addr.isLoopbackAddress) {
                    val broadcast = iface.broadcast as? Inet4Address ?: continue
                    val prefix = runCatching { iface.networkPrefixLength.toInt() }.getOrNull() ?: continue
                    return NetInfo(addr, prefix, broadcast)
                }
            }
        }
        return null
    }

    private fun prefixToMask(prefix: Int): Int = when {
        prefix <= 0 -> 0
        prefix >= 32 -> -1
        else -> -1 shl (32 - prefix)
    }

    private fun findExistingWifiNetwork(cm: ConnectivityManager): Network? {
        cm.allNetworks?.forEach { network ->
            if (isWifiNetwork(cm, network)) {
                return network.also { boundWifi = it }
            }
        }
        return null
    }

    private fun isWifiNetwork(cm: ConnectivityManager, network: Network?): Boolean {
        if (network == null) return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun bindProcessToNetwork(cm: ConnectivityManager, network: Network?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            cm.bindProcessToNetwork(network)
        } else {
            @Suppress("DEPRECATION")
            val deprecated = network
            @Suppress("DEPRECATION")
            ConnectivityManager.setProcessDefaultNetwork(deprecated)
        }
        boundWifi = network
        if (network != null) {
            Logx.i("Process bound to Wi-Fi network $network")
        } else {
            Logx.i("Process network binding cleared")
        }
    }

    private fun parseDeviceIdFromHh(message: String): String? {
        val parts = message.split(',')
        val index = parts.indexOf("DI")
        return if (index >= 0 && index + 1 < parts.size) parts[index + 1].trim() else null
    }

    private data class PortBinding(
        val dp: Int,
        val cp: Int,
        val udp: DatagramSocket,
        val server: ServerSocket
    )

    private fun nowSec(): Long = System.currentTimeMillis() / 1000L

    companion object {
        private const val MAX_PORT_ATTEMPTS = 10
        private const val MAX_UDP_PACKET = 64 * 1024
        private const val NETWORK_BIND_TIMEOUT_MS = 4_000L
        private const val EC_SOCKET_RETRY_DELAY_MS = 500L
    }
}
