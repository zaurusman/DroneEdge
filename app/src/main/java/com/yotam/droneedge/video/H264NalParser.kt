package com.droneedge.app.video

/** Minimal H.264 Annex-B NAL helpers (start code 00 00 01 or 00 00 00 01). */
object H264NalParser {
    const val NAL_IDR = 5
    const val NAL_SPS = 7
    const val NAL_PPS = 8

    /** NAL unit type (header byte & 0x1F), or -1 if [nal] has no payload after the start code. */
    fun nalType(nal: ByteArray): Int {
        val h = when {
            nal.size >= 4 && nal[0] == 0.toByte() && nal[1] == 0.toByte() &&
                nal[2] == 0.toByte() && nal[3] == 1.toByte() -> 4
            nal.size >= 3 && nal[0] == 0.toByte() && nal[1] == 0.toByte() &&
                nal[2] == 1.toByte() -> 3
            else -> 0
        }
        if (h >= nal.size) return -1
        return nal[h].toInt() and 0x1F
    }
}
