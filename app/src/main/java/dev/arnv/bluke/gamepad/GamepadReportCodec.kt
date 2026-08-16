package dev.arnv.bluke.gamepad

interface GamepadReportCodec {
    val reportId: Int
    val payloadLength: Int

    fun encodeInput(state: GamepadState): ByteArray
}

internal fun normalizedAxisToUnsigned16(value: Float): Int =
    ((value.coerceIn(-1f, 1f) + 1f) * 32767.5f)
        .toInt()
        .coerceIn(0, 65535)

internal fun putUnsigned16LittleEndian(report: ByteArray, offset: Int, value: Int) {
    report[offset] = (value and 0xFF).toByte()
    report[offset + 1] = ((value shr 8) and 0xFF).toByte()
}

internal fun normalizedTriggerToUnsigned8(value: Float): Int =
    (value.coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255)
