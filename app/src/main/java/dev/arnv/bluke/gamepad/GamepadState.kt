package dev.arnv.bluke.gamepad

/** Semantic buttons shared by every output profile. */
enum class GamepadButton {
    SOUTH,
    EAST,
    WEST,
    NORTH,
    LEFT_BUMPER,
    RIGHT_BUMPER,
    BACK,
    START,
    LEFT_STICK,
    RIGHT_STICK,
    GUIDE,
    SHARE,
    TOUCHPAD_CLICK
}

/** Semantic D-pad direction; no HID hat or button number is encoded here. */
enum class DpadDirection(
    internal val horizontal: Int,
    internal val vertical: Int
) {
    NEUTRAL(0, 0),
    UP(0, -1),
    UP_RIGHT(1, -1),
    RIGHT(1, 0),
    DOWN_RIGHT(1, 1),
    DOWN(0, 1),
    DOWN_LEFT(-1, 1),
    LEFT(-1, 0),
    UP_LEFT(-1, -1);

    companion object {
        internal fun fromVector(horizontal: Int, vertical: Int): DpadDirection =
            entries.firstOrNull {
                it.horizontal == horizontal.coerceIn(-1, 1) &&
                    it.vertical == vertical.coerceIn(-1, 1)
            } ?: NEUTRAL

        internal fun fromLegacyMask(mask: Int): DpadDirection = when (mask and 0x0F) {
            0x01 -> UP
            0x09 -> UP_RIGHT
            0x08 -> RIGHT
            0x0A -> DOWN_RIGHT
            0x02 -> DOWN
            0x06 -> DOWN_LEFT
            0x04 -> LEFT
            0x05 -> UP_LEFT
            else -> NEUTRAL
        }
    }
}

data class GamepadState(
    val buttons: Set<GamepadButton> = emptySet(),
    val dpad: DpadDirection = DpadDirection.NEUTRAL,
    val leftX: Float = 0f,
    val leftY: Float = 0f,
    val rightX: Float = 0f,
    val rightY: Float = 0f,
    val leftTrigger: Float = 0f,
    val rightTrigger: Float = 0f
) {
    companion object {
        val Neutral = GamepadState()
    }
}
