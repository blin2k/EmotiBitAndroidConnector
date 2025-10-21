package com.example.emotibitconnector.network

import android.util.Log
import com.example.emotibitconnector.CommandMode
import com.example.emotibitconnector.osc.OscArgument
import com.example.emotibitconnector.osc.OscMessage
import com.example.emotibitconnector.osc.OscPacketEncoder
import com.example.emotibitconnector.osc.OscPacketParser
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.NetworkInterface
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Configuration for discovering and streaming from an EmotiBit over UDP/OSC. */
data class EmotiBitConnectionConfig(
    val deviceAddress: String,
    val deviceCommandPort: Int,
    val listenPort: Int
)

data class EmotiBitPacket(
    val oscMessage: OscMessage,
    val sourceAddress: InetAddress,
    val receivedAtMillis: Long
)

class EmotiBitClient(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private var listenJob: Job? = null
    private var listenSocket: DatagramSocket? = null

    fun startListening(
        scope: CoroutineScope,
        config: EmotiBitConnectionConfig,
        onReady: (Int) -> Unit,
        onPacket: (EmotiBitPacket) -> Unit,
        onFailure: (Throwable) -> Unit
    ) {
        if (listenJob?.isActive == true) return
        listenJob = scope.launch(dispatcher) {
            try {
                val socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    soTimeout = SOCKET_TIMEOUT_MILLIS
                    bind(InetSocketAddress(config.listenPort))
                }
                socket.use { datagramSocket ->
                    listenSocket = datagramSocket
                    onReady(datagramSocket.localPort)
                    val buffer = ByteArray(MAX_PACKET_SIZE)
                    while (this.isActive) {
                        val packet = DatagramPacket(buffer, buffer.size)
                        val receiveResult = runCatching {
                            datagramSocket.receive(packet)
                        }
                        val failure = receiveResult.exceptionOrNull()
                        if (failure is SocketTimeoutException) {
                            continue
                        } else if (failure != null) {
                            throw failure
                        }
                        if (!this.isActive || packet.length == 0) {
                            continue
                        }
                        val payload = packet.data.copyOf(packet.length)
                        val oscMessage = OscPacketParser.parse(payload)
                        if (oscMessage != null) {
                            val emotibitPacket = EmotiBitPacket(
                                oscMessage = oscMessage,
                                sourceAddress = packet.address,
                                receivedAtMillis = System.currentTimeMillis()
                            )
                            onPacket(emotibitPacket)
                        }
                    }
                }
            } catch (ex: SocketException) {
                if (this.isActive) {
                    onFailure(ex)
                }
            } catch (ex: Exception) {
                if (this.isActive) {
                    onFailure(ex)
                }
            } finally {
                listenSocket?.close()
                listenSocket = null
            }
        }
    }

    suspend fun stopListening() {
        val job = listenJob
        listenJob = null
        job?.cancel()
        job?.join()
        listenSocket?.close()
        listenSocket = null
    }

    suspend fun sendOscMessage(
        config: EmotiBitConnectionConfig,
        address: String,
        arguments: List<OscArgument>
    ) = withContext(dispatcher) {
        val data = OscPacketEncoder.encode(address, arguments)
        DatagramSocket().use { socket ->
            socket.send(
                DatagramPacket(
                    data,
                    data.size,
                    InetAddress.getByName(config.deviceAddress),
                    config.deviceCommandPort
                )
            )
        }
    }

    /**
     * Attempt to trigger an EmotiBit stream depending on the selected [mode].
     *
     * Acceptance checklist:
     * - PASSIVE short-circuits so passive listening surfaces OSC traffic with no commands.
     * - BROADCAST_CMD uses the hotspot /24 broadcast address for compatibility with devices that look for it.
     * - UNICAST_CMD targets the provided EmotiBit IP for deterministic routing over the hotspot.
     */
    suspend fun requestStream(
        mode: CommandMode,
        deviceIp: String?,
        deviceCommandPort: Int,
        targetListenPortOnApp: Int
    ) = withContext(dispatcher) {
        if (mode == CommandMode.PASSIVE) {
            Log.d(LOG_TAG, "PASSIVE mode selected; no command sent.")
            return@withContext
        }

        val localIp = getLocalHotspotIPv4()
        if (localIp == null) {
            Log.w(LOG_TAG, "Unable to determine hotspot IPv4; skipping command send.")
            return@withContext
        }

        val targetAddress: InetAddress = when (mode) {
            CommandMode.BROADCAST_CMD -> calcBroadcast(localIp)
            CommandMode.UNICAST_CMD -> {
                val target = deviceIp?.takeIf { it.isNotBlank() }
                if (target == null) {
                    Log.w(LOG_TAG, "UNICAST_CMD requires a device IP; skipping command send.")
                    return@withContext
                }
                InetAddress.getByName(target)
            }
            CommandMode.PASSIVE -> error("Already handled above")
        }

        val payload = buildStartCommandPayload(
            destinationIp = localIp.hostAddress,
            destinationPort = targetListenPortOnApp
        )
        val data = OscPacketEncoder.encode(payload.first, payload.second)

        DatagramSocket().use { socket ->
            socket.broadcast = mode == CommandMode.BROADCAST_CMD
            val packet = DatagramPacket(
                data,
                data.size,
                targetAddress,
                deviceCommandPort
            )
            socket.send(packet)
            Log.i(LOG_TAG, "Sent ${mode.name} command to ${targetAddress.hostAddress}:${deviceCommandPort}")
        }
    }

    /** Returns the phone hotspot's IPv4 address (first private /24). */
    fun getLocalHotspotIPv4(): Inet4Address? {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            if (!networkInterface.isUp || networkInterface.isLoopback) continue
            val addresses = networkInterface.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (address is Inet4Address && address.isSiteLocalAddress) {
                    return address
                }
            }
        }
        return null
    }

    /** Calculates a simple /24 broadcast IP for the given [ip] (last octet -> 255). */
    fun calcBroadcast(ip: Inet4Address): InetAddress {
        val hostBytes = ip.address.copyOf()
        hostBytes[hostBytes.lastIndex] = 0xFF.toByte()
        return InetAddress.getByAddress(hostBytes)
    }

    private fun buildStartCommandPayload(
        destinationIp: String,
        destinationPort: Int
    ): Pair<String, List<OscArgument>> {
        // Built to be easily tweaked if EmotiBit firmware expects a different OSC shape.
        val commandAddress = "/EmotiBit/Stream"
        val args = listOf(
            OscArgument.String("start"),
            OscArgument.String(destinationIp),
            OscArgument.Int(destinationPort)
        )
        return commandAddress to args
    }

    companion object {
        private const val MAX_PACKET_SIZE = 16 * 1024
        private const val SOCKET_TIMEOUT_MILLIS = 1000
        private const val LOG_TAG = "EmotiBitClient"
    }
}
