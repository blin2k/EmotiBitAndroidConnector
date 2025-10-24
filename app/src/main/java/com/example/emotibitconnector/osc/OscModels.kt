package com.example.emotibitconnector.osc

import com.example.emotibitconnector.network.EmotiBitProto
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Represents an OSC packet consisting of an address pattern and any arguments. */
data class OscMessage(
    val address: String,
    val arguments: List<OscArgument>,
    val typeTags: String,
    val rawBytes: ByteArray
)

sealed class OscArgument {
    data class Int(val value: kotlin.Int) : OscArgument()
    data class Float(val value: kotlin.Float) : OscArgument()
    data class String(val value: kotlin.String) : OscArgument()
    data class Unknown(val typeTag: Char, val payload: ByteArray) : OscArgument()
}

/** Minimal OSC parser/encoder tailored to EmotiBit payloads (strings, ints, floats). */
object OscPacketParser {
    fun parse(bytes: ByteArray): OscMessage? = runCatching {
        var cursor = 0
        val (address, nextCursor) = readString(bytes, cursor)
        cursor = nextCursor
        if (address.isEmpty()) return null
        val (typeTagString, afterTags) = readString(bytes, cursor)
        cursor = afterTags
        val arguments = mutableListOf<OscArgument>()
        val typeTags = typeTagString.ifEmpty { "," }
        require(typeTags.first() == ',') { "OSC type tag string must start with ','" }
        for (typeTag in typeTags.drop(1)) {
            when (typeTag) {
                'i' -> {
                    ensureRemaining(bytes, cursor, 4)
                    val intValue = ByteBuffer.wrap(bytes, cursor, 4)
                        .order(ByteOrder.BIG_ENDIAN)
                        .int
                    arguments.add(OscArgument.Int(intValue))
                    cursor += 4
                }
                'f' -> {
                    ensureRemaining(bytes, cursor, 4)
                    val floatValue = ByteBuffer.wrap(bytes, cursor, 4)
                        .order(ByteOrder.BIG_ENDIAN)
                        .float
                    arguments.add(OscArgument.Float(floatValue))
                    cursor += 4
                }
                's' -> {
                    val (stringValue, next) = readString(bytes, cursor)
                    arguments.add(OscArgument.String(stringValue))
                    cursor = next
                }
                else -> {
                    // Acceptance requirement: unknown typetags should not drop the packet.
                    val remaining = bytes.copyOfRange(cursor, bytes.size)
                    arguments.add(OscArgument.Unknown(typeTag = typeTag, payload = remaining))
                    break
                }
            }
        }
        OscMessage(address = address, arguments = arguments, typeTags = typeTags, rawBytes = bytes)
    }.getOrNull()

    private fun readString(bytes: ByteArray, start: Int): Pair<String, Int> {
        var idx = start
        while (idx < bytes.size && bytes[idx] != 0.toByte()) {
            idx++
        }
        require(idx < bytes.size) { "OSC string missing null terminator" }
        val str = String(bytes, start, idx - start)
        // Move cursor to next 4-byte boundary past the null terminator
        var next = idx + 1
        while (next % 4 != 0) {
            next++
        }
        return str to next
    }

    private fun ensureRemaining(bytes: ByteArray, start: Int, count: Int) {
        require(start + count <= bytes.size) { "Malformed OSC packet" }
    }
}

object OscPacketEncoder {
    fun encode(address: String, arguments: List<OscArgument>): ByteArray {
        require(address.startsWith("/")) { "OSC address must start with '/'" }
        val typeTags = buildString {
            append(',')
            arguments.forEach { arg ->
                append(
                    when (arg) {
                        is OscArgument.Int -> 'i'
                        is OscArgument.Float -> 'f'
                        is OscArgument.String -> 's'
                        is OscArgument.Unknown -> error("Cannot encode unknown OSC argument type ${arg.typeTag}")
                    }
                )
            }
        }
        val buffer = ByteBuffer.allocate(estimateSize(address, arguments))
            .order(ByteOrder.BIG_ENDIAN)
        writePaddedString(buffer, address)
        writePaddedString(buffer, typeTags)
        arguments.forEach { arg ->
            when (arg) {
                is OscArgument.Int -> buffer.putInt(arg.value)
                is OscArgument.Float -> buffer.putFloat(arg.value)
                is OscArgument.String -> writePaddedString(buffer, arg.value)
                is OscArgument.Unknown -> throw IllegalArgumentException(
                    "Cannot encode unknown OSC argument type ${arg.typeTag}"
                )
            }
        }
        buffer.flip()
        val data = ByteArray(buffer.limit())
        buffer.get(data)
        return data
    }

    private fun estimateSize(address: String, arguments: List<OscArgument>): Int {
        val paddedAddress = align4(address.length + 1)
        val paddedTags = align4(arguments.size + 1)
        val argumentBytes = arguments.sumOf { argument ->
            when (argument) {
                is OscArgument.Int, is OscArgument.Float -> 4
                is OscArgument.String -> align4(argument.value.length + 1)
                is OscArgument.Unknown -> throw IllegalArgumentException(
                    "Cannot estimate size of unknown OSC argument type ${argument.typeTag}"
                )
            }
        }
        return paddedAddress + paddedTags + argumentBytes
    }

    private fun writePaddedString(buffer: ByteBuffer, value: String) {
        val bytes = value.encodeToByteArray()
        buffer.put(bytes)
        buffer.put(0)
        repeat((align4(bytes.size + 1) - (bytes.size + 1))) {
            buffer.put(0)
        }
    }

    private fun align4(length: Int): Int {
        var aligned = length
        while (aligned % 4 != 0) {
            aligned++
        }
        return aligned
    }
}

fun buildAdvertiseProbeMessage(): ByteArray =
    OscPacketEncoder.encode(
        address = EmotiBitProto.OSC_ADDR_ADVERTISE,
        arguments = emptyList()
    )

fun buildStartStreamMessage(localIp: String, dataPort: Int): ByteArray =
    OscPacketEncoder.encode(
        address = EmotiBitProto.OSC_ADDR_START_STREAM,
        arguments = listOf(
            OscArgument.String(localIp),
            OscArgument.Int(dataPort)
        )
    )

fun buildStopStreamMessage(): ByteArray =
    OscPacketEncoder.encode(
        address = EmotiBitProto.OSC_ADDR_STOP_STREAM,
        arguments = emptyList()
    )
