package com.example.emotibitconnector.network

import com.example.emotibitconnector.osc.OscArgument
import com.example.emotibitconnector.osc.OscMessage
import com.example.emotibitconnector.osc.OscPacketEncoder
import com.example.emotibitconnector.osc.OscPacketParser
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
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
                    bind(InetSocketAddress(config.listenPort))
                }
                socket.use { datagramSocket ->
                    listenSocket = datagramSocket
                    onReady(datagramSocket.localPort)
                    val buffer = ByteArray(MAX_PACKET_SIZE)
                    while (this.isActive) {
                        val packet = DatagramPacket(buffer, buffer.size)
                        datagramSocket.receive(packet)
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

    companion object {
        private const val MAX_PACKET_SIZE = 16 * 1024
    }
}
