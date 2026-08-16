package dev.arnv.bluke.gamepad

object JoypadOsCodec : GamepadReportCodec {
    override val reportId: Int = 1
    override val payloadLength: Int = 13

    override fun encodeInput(state: GamepadState): ByteArray {
        val report = ByteArray(payloadLength)
        var buttons = 0

        fun map(button: GamepadButton, reportBit: Int) {
            if (button in state.buttons) buttons = buttons or (1 shl reportBit)
        }

        map(GamepadButton.SOUTH, 0)
        map(GamepadButton.EAST, 1)
        map(GamepadButton.WEST, 2)
        map(GamepadButton.NORTH, 3)
        map(GamepadButton.LEFT_BUMPER, 4)
        map(GamepadButton.RIGHT_BUMPER, 5)
        if (state.leftTrigger > 0f) buttons = buttons or (1 shl 6)
        if (state.rightTrigger > 0f) buttons = buttons or (1 shl 7)
        map(GamepadButton.BACK, 8)
        map(GamepadButton.START, 9)
        map(GamepadButton.LEFT_STICK, 10)
        map(GamepadButton.RIGHT_STICK, 11)
        map(GamepadButton.GUIDE, 12)
        map(GamepadButton.SHARE, 13)
        map(GamepadButton.TOUCHPAD_CLICK, 14)

        report[0] = (buttons and 0xFF).toByte()
        report[1] = ((buttons shr 8) and 0xFF).toByte()
        report[2] = hatValue(state.dpad).toByte()

        putUnsigned16LittleEndian(report, 3, normalizedAxisToUnsigned16(state.leftX))
        putUnsigned16LittleEndian(report, 5, normalizedAxisToUnsigned16(state.leftY))
        putUnsigned16LittleEndian(report, 7, normalizedAxisToUnsigned16(state.rightX))
        putUnsigned16LittleEndian(report, 9, normalizedAxisToUnsigned16(state.rightY))
        report[11] = normalizedTriggerToUnsigned8(state.leftTrigger).toByte()
        report[12] = normalizedTriggerToUnsigned8(state.rightTrigger).toByte()
        return report
    }

    private fun hatValue(direction: DpadDirection): Int = when (direction) {
        DpadDirection.UP -> 0
        DpadDirection.UP_RIGHT -> 1
        DpadDirection.RIGHT -> 2
        DpadDirection.DOWN_RIGHT -> 3
        DpadDirection.DOWN -> 4
        DpadDirection.DOWN_LEFT -> 5
        DpadDirection.LEFT -> 6
        DpadDirection.UP_LEFT -> 7
        DpadDirection.NEUTRAL -> 8
    }
}
