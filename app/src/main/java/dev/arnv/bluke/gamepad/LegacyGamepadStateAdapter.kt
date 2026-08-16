package dev.arnv.bluke.gamepad

/** Converts the route-one UI bit mask into protocol-neutral state. */
object LegacyGamepadStateAdapter {
    fun fromRouteOne(
        buttonMask: Int,
        leftX: Float,
        leftY: Float,
        rightX: Float,
        rightY: Float
    ): GamepadState {
        val buttons = buildSet {
            addIfSet(buttonMask, 0, GamepadButton.SOUTH)
            addIfSet(buttonMask, 1, GamepadButton.EAST)
            addIfSet(buttonMask, 2, GamepadButton.WEST)
            addIfSet(buttonMask, 3, GamepadButton.NORTH)
            addIfSet(buttonMask, 4, GamepadButton.LEFT_BUMPER)
            addIfSet(buttonMask, 5, GamepadButton.RIGHT_BUMPER)
            addIfSet(buttonMask, 8, GamepadButton.BACK)
            addIfSet(buttonMask, 9, GamepadButton.START)
            addIfSet(buttonMask, 10, GamepadButton.LEFT_STICK)
            addIfSet(buttonMask, 11, GamepadButton.RIGHT_STICK)
            addIfSet(buttonMask, 16, GamepadButton.GUIDE)
            addIfSet(buttonMask, 17, GamepadButton.SHARE)
            addIfSet(buttonMask, 18, GamepadButton.TOUCHPAD_CLICK)
        }
        return GamepadState(
            buttons = buttons,
            dpad = DpadDirection.fromLegacyMask((buttonMask shr 12) and 0x0F),
            leftX = leftX,
            leftY = leftY,
            rightX = rightX,
            rightY = rightY,
            leftTrigger = if (buttonMask hasBit 6) 1f else 0f,
            rightTrigger = if (buttonMask hasBit 7) 1f else 0f
        )
    }

    private fun MutableSet<GamepadButton>.addIfSet(
        buttonMask: Int,
        bitIndex: Int,
        button: GamepadButton
    ) {
        if (buttonMask hasBit bitIndex) add(button)
    }

    private infix fun Int.hasBit(bitIndex: Int): Boolean =
        (this and (1 shl bitIndex)) != 0
}
