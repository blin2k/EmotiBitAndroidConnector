package com.example.emotibitconnector.network

/**
 * OSC protocol constants mirrored from the desktop EmotiBit oscilloscope host.
 */
object EmotiBitProto {
    // Default UDP port hints described by the host: advertise, data, control (increments of +1).
    const val DEFAULT_ADVERTISE_PORT = 3131
    const val DEFAULT_DATA_PORT_HINT = 3132
    const val DEFAULT_CONTROL_PORT = 3133

    // OSC addresses emitted/consumed by the PC host.
    const val OSC_ADDR_ADVERTISE = "/EmotiBit/Advertise"
    const val OSC_ADDR_ADVERTISE_REPLY = "/EmotiBit/Advertise/Reply"
    const val OSC_ADDR_START_STREAM = "/EmotiBit/Stream/Start"
    const val OSC_ADDR_STOP_STREAM = "/EmotiBit/Stream/Stop"

    // Type tag documentation for control flows.
    const val TYPE_TAG_START_STREAM = ",si" // args: hostIp (string), hostDataPort (int)
    const val TYPE_TAG_STOP_STREAM = ","     // no arguments
}
