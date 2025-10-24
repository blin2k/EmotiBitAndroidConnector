package com.example.emotibitconnector.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import com.example.emotibitconnector.osc.OscArgument
import com.example.emotibitconnector.osc.OscMessage
import com.example.emotibitconnector.osc.OscPacketParser
import com.example.emotibitconnector.osc.buildAdvertiseProbeMessage
import com.example.emotibitconnector.osc.buildStartStreamMessage
import com.example.emotibitconnector.osc.buildStopStreamMessage
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.InterfaceAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** OSC payload captured from the EmotiBit stream socket. */
data class EmotiBitPacket(
    val oscMessage: OscMessage,
    val sourceAddress: InetAddress,
    val receivedAtMillis: Long
)

/** Raw UDP payload received when OSC parsing fails. */
data class EmotiBitRawPacket(
    val payload: ByteArray,
    val sourceAddress: InetAddress,
    val receivedAtMillis: Long
)

/** Details about a device that answered our advertise probe. */
data class DiscoveredDevice(
    val deviceIp: String,
    val deviceId: String?,
    val firmwareVersion: String?,
    val oscMessage: OscMessage?,
    val sourceAddress: InetAddress,
    val sourcePort: Int,
    val rawBytes: ByteArray,
    val receivedAtMillis: Long
)

/** Parameters controlling discovery cadence. */
data class DiscoveryConfig(
    val advertisePort: Int,
    val attempts: Int = 3,
    val attemptIntervalMs: Long = 500L,
    val overallTimeoutMs: Long = 8_000L
)

enum class UdpChannel { ADVERTISE, CONTROL, DATA }

sealed class ClientLogEvent(open val timestamp: Long) {
    data class Outbound(
        override val timestamp: Long,
        val channel: UdpChannel,
        val destination: InetAddress,
        val port: Int,
        val oscAddress: String,
        val typeTags: String,
        val arguments: List<OscArgument>
    ) : ClientLogEvent(timestamp)

    data class Inbound(
        override val timestamp: Long,
        val channel: UdpChannel,
        val source: InetAddress,
        val port: Int,
        val oscMessage: OscMessage?,
        val rawBytes: ByteArray
    ) : ClientLogEvent(timestamp)

    data class Info(
        override val timestamp: Long,
        val message: String
    ) : ClientLogEvent(timestamp)

    data class Error(
        override val timestamp: Long,
        val message: String,
        val throwable: Throwable? = null
    ) : ClientLogEvent(timestamp)
}

data class LocalNetworkInfo(
    val networkInterface: NetworkInterface,
    val address: Inet4Address,
    val prefixLength: Int?,
    val broadcast: InetAddress
)

class EmotiBitClient(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private var listenJob: Job? = null
    private var listenSocket: DatagramSocket? = null
    fun startDataListener(
        scope: CoroutineScope,
        dataPort: Int,
        onReady: (Int) -> Unit,
        onPacket: (EmotiBitPacket) -> Unit,
        onNonOscPacket: (EmotiBitRawPacket) -> Unit,
        onFailure: (Throwable) -> Unit,
        onLog: (ClientLogEvent) -> Unit
    ) {
        if (listenJob?.isActive == true) return
        listenJob = scope.launch(dispatcher) {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    soTimeout = SOCKET_TIMEOUT_MILLIS
                    bind(InetSocketAddress(dataPort))
                }
                listenSocket = socket
                onReady(socket.localPort)
                val buffer = ByteArray(MAX_PACKET_SIZE)
                while (isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (ex: SocketTimeoutException) {
                        continue
                    }
                    if (!isActive || packet.length == 0) continue
                    val payload = packet.data.copyOf(packet.length)
                    val oscMessage = OscPacketParser.parse(payload)
                    val timestamp = System.currentTimeMillis()
                    onLog(
                        ClientLogEvent.Inbound(
                            timestamp = timestamp,
                            channel = UdpChannel.DATA,
                            source = packet.address,
                            port = packet.port,
                            oscMessage = oscMessage,
                            rawBytes = payload
                        )
                    )
                    if (oscMessage != null) {
                        onPacket(
                            EmotiBitPacket(
                                oscMessage = oscMessage,
                                sourceAddress = packet.address,
                                receivedAtMillis = timestamp
                            )
                        )
                    } else {
                        onNonOscPacket(
                            EmotiBitRawPacket(
                                payload = payload,
                                sourceAddress = packet.address,
                                receivedAtMillis = timestamp
                            )
                        )
                    }
                }
            } catch (ex: Exception) {
                if (isActive) {
                    onLog(ClientLogEvent.Error(System.currentTimeMillis(), "Data listener error", ex))
                    onFailure(ex)
                }
            } finally {
                socket?.close()
                listenSocket = null
            }
        }
    }

    suspend fun stopDataListener() {
        val job = listenJob
        listenJob = null
        job?.cancel()
        job?.join()
        listenSocket?.close()
        listenSocket = null
    }

    suspend fun discoverDevice(
        context: Context,
        config: DiscoveryConfig,
        onLog: (ClientLogEvent) -> Unit
    ): DiscoveredDevice? = withContext(dispatcher) {
        val localInfo = resolveLocalNetworkInfo(context)?.also {
            onLog(
                ClientLogEvent.Info(
                    timestamp = System.currentTimeMillis(),
                    message = "Local Wi-Fi IPv4 ${it.address.hostAddress} (/${it.prefixLength ?: 24}) broadcast ${it.broadcast.hostAddress}"
                )
            )
        } ?: run {
            onLog(
                ClientLogEvent.Error(
                    System.currentTimeMillis(),
                    "Unable to determine Wi-Fi IPv4 for discovery"
                )
            )
            return@withContext null
        }

        val receiveSocket = DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            soTimeout = SOCKET_TIMEOUT_MILLIS
            bind(InetSocketAddress(config.advertisePort))
        }

        try {
            repeat(config.attempts) { attempt ->
                sendAdvertiseProbe(
                    broadcast = localInfo.broadcast,
                    advertisePort = config.advertisePort,
                    onLog = onLog
                )
                if (attempt < config.attempts - 1) {
                    delay(config.attemptIntervalMs)
                }
            }
            return@withContext listenForAdvertiseReplies(receiveSocket, config.overallTimeoutMs, onLog)
        } catch (ex: Exception) {
            onLog(ClientLogEvent.Error(System.currentTimeMillis(), "Discovery failed", ex))
        } finally {
            receiveSocket.close()
        }
        null
    }

    suspend fun sendStartStreamUnicast(
        deviceIp: String,
        controlPort: Int,
        localIp: String,
        dataPort: Int,
        onLog: (ClientLogEvent) -> Unit
    ) = withContext(dispatcher) {
        val payload = buildStartStreamMessage(localIp, dataPort)
        sendOscPacket(
            target = InetSocketAddress(InetAddress.getByName(deviceIp), controlPort),
            payload = payload,
            parsed = OscPacketParser.parse(payload),
            channel = UdpChannel.CONTROL,
            broadcast = false,
            onLog = onLog
        )
    }

    suspend fun sendStartStreamBroadcast(
        broadcast: InetAddress,
        controlPort: Int,
        localIp: String,
        dataPort: Int,
        onLog: (ClientLogEvent) -> Unit
    ) = withContext(dispatcher) {
        val payload = buildStartStreamMessage(localIp, dataPort)
        sendOscPacket(
            target = InetSocketAddress(broadcast, controlPort),
            payload = payload,
            parsed = OscPacketParser.parse(payload),
            channel = UdpChannel.CONTROL,
            broadcast = true,
            onLog = onLog
        )
    }

    suspend fun sendStopStreamUnicast(
        deviceIp: String,
        controlPort: Int,
        onLog: (ClientLogEvent) -> Unit
    ) = withContext(dispatcher) {
        val payload = buildStopStreamMessage()
        sendOscPacket(
            target = InetSocketAddress(InetAddress.getByName(deviceIp), controlPort),
            payload = payload,
            parsed = OscPacketParser.parse(payload),
            channel = UdpChannel.CONTROL,
            broadcast = false,
            onLog = onLog
        )
    }

    suspend fun sendStopStreamBroadcast(
        broadcast: InetAddress,
        controlPort: Int,
        onLog: (ClientLogEvent) -> Unit
    ) = withContext(dispatcher) {
        val payload = buildStopStreamMessage()
        sendOscPacket(
            target = InetSocketAddress(broadcast, controlPort),
            payload = payload,
            parsed = OscPacketParser.parse(payload),
            channel = UdpChannel.CONTROL,
            broadcast = true,
            onLog = onLog
        )
    }

    suspend fun sendAdvertiseProbe(
        broadcast: InetAddress,
        advertisePort: Int,
        onLog: (ClientLogEvent) -> Unit,
        includeGlobalBroadcast: Boolean = true
    ) = withContext(dispatcher) {
        val payload = buildAdvertiseProbeMessage()
        val parsed = OscPacketParser.parse(payload)
        val targets = mutableListOf(InetSocketAddress(broadcast, advertisePort))
        if (includeGlobalBroadcast) {
            targets += InetSocketAddress(InetAddress.getByName("255.255.255.255"), advertisePort)
        }
        targets.forEach { target ->
            sendOscPacket(
                target = target,
                payload = payload,
                parsed = parsed,
                channel = UdpChannel.ADVERTISE,
                broadcast = true,
                onLog = onLog
            )
        }
    }

    suspend fun awaitAdvertiseReply(
        advertisePort: Int,
        timeoutMs: Long,
        onLog: (ClientLogEvent) -> Unit
    ): DiscoveredDevice? = withContext(dispatcher) {
        val socket = DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            soTimeout = SOCKET_TIMEOUT_MILLIS
            bind(InetSocketAddress(advertisePort))
        }
        try {
            listenForAdvertiseReplies(socket, timeoutMs, onLog)
        } finally {
            socket.close()
        }
    }

    /**
     * Resolve the best local IPv4 for advertising to an EmotiBit. Prefer the active Wi-Fi
     * client address, but fall back to the first private interface address if Wi-Fi details
     * are unavailable.
     */
    fun resolveLocalNetworkInfo(context: Context): LocalNetworkInfo? {
        val wifiAddress = getLocalWifiIPv4(context)
        if (wifiAddress != null) {
            buildLocalNetworkInfo(wifiAddress)?.let { return it }
            val fallbacks = enumeratePrivateInterfaces()
            fallbacks.firstOrNull { it.address.hostAddress == wifiAddress.hostAddress }?.let { return it }
            return fallbacks.firstOrNull()
        }
        return enumeratePrivateInterfaces().firstOrNull()
    }

    /**
     * Attempt to extract the device's current Wi-Fi (STA) IPv4 address via ConnectivityManager.
     */
    fun getLocalWifiIPv4(context: Context): Inet4Address? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        return runCatching {
            val network = cm.allNetworks.firstOrNull {
                cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            } ?: return null
            val linkProperties = cm.getLinkProperties(network) ?: return null
            val address = linkProperties.linkAddresses
                .firstOrNull { it.address is Inet4Address }
                ?.address as? Inet4Address
            address?.takeIf { it.isSiteLocalAddress && !it.isLoopbackAddress }
        }.getOrElse {
            if (it is SecurityException) {
                null
            } else {
                throw it
            }
        }
    }

    /**
     * Calculate the broadcast address for the interface hosting [ip], using the subnet prefix
     * when available. Falls back to a .255 host portion if interface metadata is missing.
     */
    fun calcBroadcast(ip: Inet4Address): InetAddress {
        val networkInterface = NetworkInterface.getByInetAddress(ip)
            ?: return fallbackBroadcast(ip)
        val iface = networkInterface.interfaceAddresses.firstOrNull { it.address is Inet4Address }
            ?: return fallbackBroadcast(ip)
        val prefix = iface.networkPrefixLength.toInt()
        if (prefix !in 0..32) return fallbackBroadcast(ip)
        val mask = (-1 shl (32 - prefix))
        val ipInt = ByteBuffer.wrap(ip.address).order(ByteOrder.BIG_ENDIAN).int
        val broadcastInt = ipInt or mask.inv()
        val buffer = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(broadcastInt)
        return InetAddress.getByAddress(buffer.array())
    }

    fun calcBroadcast(info: LocalNetworkInfo): InetAddress = info.broadcast

    private fun fallbackBroadcast(ip: Inet4Address): InetAddress {
        val bytes = ip.address.copyOf()
        bytes[bytes.lastIndex] = 0xFF.toByte()
        return InetAddress.getByAddress(bytes)
    }

    private fun buildLocalNetworkInfo(address: Inet4Address): LocalNetworkInfo? {
        val networkInterface = NetworkInterface.getByInetAddress(address)
            ?: return null
        val iface = networkInterface.interfaceAddresses.firstOrNull { it.address is Inet4Address }
            ?: return null
        val prefix = iface.networkPrefixLength.toInt().takeIf { it in 0..32 }
        val broadcast = iface.broadcast ?: calcBroadcast(address)
        return if (address.isSiteLocalAddress && !address.isLoopbackAddress) {
            LocalNetworkInfo(
                networkInterface = networkInterface,
                address = address,
                prefixLength = prefix,
                broadcast = broadcast
            )
        } else {
            null
        }
    }

    private fun enumeratePrivateInterfaces(): List<LocalNetworkInfo> {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
        val results = mutableListOf<LocalNetworkInfo>()
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            if (!networkInterface.isUp || networkInterface.isLoopback || networkInterface.isVirtual) continue
            for (iface in networkInterface.interfaceAddresses) {
                val address = iface.address
                if (address is Inet4Address && address.isSiteLocalAddress && !address.isLoopbackAddress) {
                    val prefix = iface.networkPrefixLength.toInt().takeIf { it in 0..32 }
                    val broadcast = iface.broadcast ?: calcBroadcast(address)
                    results += LocalNetworkInfo(
                        networkInterface = networkInterface,
                        address = address,
                        prefixLength = prefix,
                        broadcast = broadcast
                    )
                }
            }
        }
        return results
    }

    private suspend fun listenForAdvertiseReplies(
        socket: DatagramSocket,
        timeoutMs: Long,
        onLog: (ClientLogEvent) -> Unit
    ): DiscoveredDevice? {
        if (timeoutMs <= 0) return null
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        val buffer = ByteArray(MAX_PACKET_SIZE)
        while (SystemClock.elapsedRealtime() < deadline && coroutineContext.isActive) {
            val remaining = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0)
            socket.soTimeout = remaining
                .coerceAtMost(SOCKET_TIMEOUT_MILLIS.toLong())
                .toInt()
                .coerceAtLeast(1)
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (timeout: SocketTimeoutException) {
                continue
            }
            val payload = packet.data.copyOf(packet.length)
            val osc = OscPacketParser.parse(payload)
            val timestamp = System.currentTimeMillis()
            onLog(
                ClientLogEvent.Inbound(
                    timestamp = timestamp,
                    channel = UdpChannel.ADVERTISE,
                    source = packet.address,
                    port = packet.port,
                    oscMessage = osc,
                    rawBytes = payload
                )
            )
            val deviceIp = extractDeviceIp(osc, packet.address)
            val deviceId = extractDeviceId(osc)
            val firmware = extractFirmwareVersion(osc)
            return DiscoveredDevice(
                deviceIp = deviceIp,
                deviceId = deviceId,
                firmwareVersion = firmware,
                oscMessage = osc,
                sourceAddress = packet.address,
                sourcePort = packet.port,
                rawBytes = payload,
                receivedAtMillis = timestamp
            )
        }
        return null
    }

    private suspend fun sendOscPacket(
        target: InetSocketAddress,
        payload: ByteArray,
        parsed: OscMessage?,
        channel: UdpChannel,
        broadcast: Boolean,
        onLog: (ClientLogEvent) -> Unit
    ) {
        val timestamp = System.currentTimeMillis()
        try {
            DatagramSocket().use { socket ->
                socket.reuseAddress = true
                socket.broadcast = broadcast
                socket.send(
                    DatagramPacket(
                        payload,
                        payload.size,
                        target
                    )
                )
            }
            val message = parsed ?: OscPacketParser.parse(payload)
            if (message != null) {
                onLog(
                    ClientLogEvent.Outbound(
                        timestamp = timestamp,
                        channel = channel,
                        destination = target.address,
                        port = target.port,
                        oscAddress = message.address,
                        typeTags = message.typeTags,
                        arguments = message.arguments
                    )
                )
            } else {
                onLog(
                    ClientLogEvent.Outbound(
                        timestamp = timestamp,
                        channel = channel,
                        destination = target.address,
                        port = target.port,
                        oscAddress = "<raw>",
                        typeTags = "",
                        arguments = emptyList()
                    )
                )
            }
        } catch (ex: Exception) {
            onLog(
                ClientLogEvent.Error(
                    timestamp = timestamp,
                    message = "Failed to send ${channel.name} packet to ${target.address.hostAddress}:${target.port}",
                    throwable = ex
                )
            )
            throw ex
        }
    }

    private fun extractDeviceIp(message: OscMessage?, sender: InetAddress): String {
        val stringCandidate = message?.arguments
            ?.filterIsInstance<OscArgument.String>()
            ?.firstOrNull { IPV4_REGEX.matches(it.value) }
        return stringCandidate?.value ?: sender.hostAddress
    }

    private fun extractDeviceId(message: OscMessage?): String? = message?.arguments
        ?.filterIsInstance<OscArgument.String>()
        ?.firstOrNull { !IPV4_REGEX.matches(it.value) }
        ?.value

    private fun extractFirmwareVersion(message: OscMessage?): String? = message?.arguments
        ?.dropWhile { it !is OscArgument.String }
        ?.filterIsInstance<OscArgument.String>()
        ?.firstOrNull { it.value.startsWith("v", ignoreCase = true) }
        ?.value

    companion object {
        private const val MAX_PACKET_SIZE = 16 * 1024
        private const val SOCKET_TIMEOUT_MILLIS = 1000
        private val IPV4_REGEX = Regex("\\b(25[0-5]|2[0-4]\\d|1?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}\\b")
    }
}
