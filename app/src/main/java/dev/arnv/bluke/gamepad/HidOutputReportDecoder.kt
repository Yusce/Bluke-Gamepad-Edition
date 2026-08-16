package dev.arnv.bluke.gamepad

data class KeyboardLedState(
    val numLock: Boolean,
    val capsLock: Boolean,
    val scrollLock: Boolean
)

sealed interface HidOutputReportResult {
    data class KeyboardLeds(val state: KeyboardLedState) : HidOutputReportResult
    data class Unknown(val reportId: Int, val payloadLength: Int) : HidOutputReportResult
}

fun interface HidOutputReportDecoder {
    fun decode(reportId: Int, data: ByteArray): HidOutputReportResult
}

object PcDirectOutputReportDecoder : HidOutputReportDecoder {
    override fun decode(reportId: Int, data: ByteArray): HidOutputReportResult {
        if (reportId != 1 || data.isEmpty()) {
            return HidOutputReportResult.Unknown(reportId, data.size)
        }
        // Some Android stacks include Report ID again in callback data; preserve route-one handling.
        val ledByte = if (data.size > 1 && (data[0].toInt() and 0xFF) == 1) {
            data[1].toInt() and 0xFF
        } else {
            data[0].toInt() and 0xFF
        }
        return HidOutputReportResult.KeyboardLeds(
            KeyboardLedState(
                numLock = ledByte and 0x01 != 0,
                capsLock = ledByte and 0x02 != 0,
                scrollLock = ledByte and 0x04 != 0
            )
        )
    }
}

object JoypadOsOutputReportDecoder : HidOutputReportDecoder {
    override fun decode(reportId: Int, data: ByteArray): HidOutputReportResult =
        HidOutputReportResult.Unknown(reportId, data.size)
}
