package com.example.emotibitconnector.network

/**
 * OSC protocol constants mirrored from the desktop EmotiBit oscilloscope host.
 */
object EmotiBitProto {
    const val DEVICE_CTRL_PORT = 3131
    const val DEFAULT_DATA_PORT = 3132
    const val DEFAULT_CTRL_BACK_PORT = 3133
    const val DEFAULT_DP = DEFAULT_DATA_PORT
    const val DEFAULT_CP = DEFAULT_CTRL_BACK_PORT

    // Legacy OSC constants retained for parsers and existing UI components.
    const val OSC_ADDR_ADVERTISE = "/EmotiBit/Advertise"
    const val OSC_ADDR_ADVERTISE_REPLY = "/EmotiBit/Advertise/Reply"
    const val OSC_ADDR_START_STREAM = "/EmotiBit/Stream/Start"
    const val OSC_ADDR_STOP_STREAM = "/EmotiBit/Stream/Stop"

    const val TYPE_TAG_START_STREAM = ",si"
    const val TYPE_TAG_STOP_STREAM = ","

    fun buildEc(ts: Long, seq: Int, cp: Int, dp: Int): String =
        "$ts,$seq,4,EC,1,100,CP,$cp,DP,$dp\n"

    fun buildHe(ts: Long): String = "$ts,0,0,HE,1,100\n"

    fun buildPn(ts: Long, dp: Int): String = "$ts,2,2,PN,1,100,DP,$dp\n"

    fun buildPo(ts: Long, dp: Int): String = "$ts,2,2,PO,1,100,DP,$dp\n"
}
