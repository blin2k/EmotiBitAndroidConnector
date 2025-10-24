package com.example.emotibitconnector.network

import android.app.Application
import com.example.emotibitconnector.Logx
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.BindException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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

    @Volatile private var udpSock: DatagramSocket? = null
    @Volatile private var tcpServer: ServerSocket? = null
    @Volatile private var tcpClient: Socket? = null
    @Volatile private var controlWriter: BufferedWriter? = null
    @Volatile private var controlReader: BufferedReader? = null
    @Volatile private var ecJob: Job? = null
    @Volatile private var udpJob: Job? = null
    @Volatile private var tcpJob: Job? = null
    @Volatile private var configRef: SessionConfig? = null
    @Volatile private var dataCallback: ((ByteArray, Int, InetAddress) -> Unit)? = null
    @Volatile private var stopRequested: Boolean = false
    private val ecSeq = AtomicInteger(0)
    private val tcpClientRef = AtomicReference<Socket>()
    private val lock = Any()

    @Volatile private var chosenDp: Int = EmotiBitProto.DEFAULT_DATA_PORT
    @Volatile private var chosenCp: Int = EmotiBitProto.DEFAULT_CTRL_BACK_PORT

    fun currentPorts(): Pair<Int, Int> = chosenDp to chosenCp

    fun startSession(config: SessionConfig, onData: (ByteArray, Int, InetAddress) -> Unit) {
        synchronized(lock) {
            stopSessionLocked()
            stopRequested = false
            dataCallback = onData
            Logx.i("Starting session with initial dp=${config.initialDp} cp=${config.initialCp ?: "auto"}")
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
                dataCallback?.invoke(payload, packet.port, packet.address)
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
        val sendSocket = DatagramSocket()
        try {
            while (scope.isActive && !stopRequested) {
                val seq = ecSeq.getAndIncrement()
                val line = EmotiBitProto.buildEc(nowSec(), seq, chosenCp, chosenDp)
                val payload = line.toByteArray(StandardCharsets.US_ASCII)
                val packet = DatagramPacket(
                    payload,
                    payload.size,
                    InetSocketAddress(deviceIp, EmotiBitProto.DEVICE_CTRL_PORT)
                )
                sendSocket.send(packet)
                Logx.i("EC -> ${deviceIp.hostAddress}:${EmotiBitProto.DEVICE_CTRL_PORT} seq=$seq line=${line.trim()}")
                delay(intervalMs)
            }
        } catch (ex: Exception) {
            if (!stopRequested) {
                Logx.e("EC heartbeat stopped", ex)
            }
        } finally {
            sendSocket.close()
            Logx.i("EC heartbeat socket closed")
        }
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
    }
}
