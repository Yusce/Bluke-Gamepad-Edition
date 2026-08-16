package dev.arnv.bluke.gamepad

object PcDirectCodec : GamepadReportCodec {
    override val reportId: Int = 3
    override val payloadLength: Int = 11

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
        buttons = buttons or dpadButtonMask(state.dpad)
        map(GamepadButton.GUIDE, 16)
        map(GamepadButton.SHARE, 17)

        // Route 1 wrote bit 18 into the payload even though the descriptor marks it as padding.
        map(GamepadButton.TOUCHPAD_CLICK, 18)

        report[0] = (buttons and 0xFF).toByte()
        report[1] = ((buttons shr 8) and 0xFF).toByte()
        report[2] = ((buttons shr 16) and 0xFF).toByte()
        putUnsigned16LittleEndian(report, 3, normalizedAxisToUnsigned16(state.leftX))
        putUnsigned16LittleEndian(report, 5, normalizedAxisToUnsigned16(state.leftY))
        putUnsigned16LittleEndian(report, 7, normalizedAxisToUnsigned16(state.rightX))
        putUnsigned16LittleEndian(report, 9, normalizedAxisToUnsigned16(state.rightY))
        return report
    }

    private fun dpadButtonMask(direction: DpadDirection): Int = when (direction) {
        DpadDirection.NEUTRAL -> 0
        DpadDirection.UP -> 1 shl 12
        DpadDirection.UP_RIGHT -> (1 shl 12) or (1 shl 15)
        DpadDirection.RIGHT -> 1 shl 15
        DpadDirection.DOWN_RIGHT -> (1 shl 13) or (1 shl 15)
        DpadDirection.DOWN -> 1 shl 13
        DpadDirection.DOWN_LEFT -> (1 shl 13) or (1 shl 14)
        DpadDirection.LEFT -> 1 shl 14
        DpadDirection.UP_LEFT -> (1 shl 12) or (1 shl 14)
    }
}
