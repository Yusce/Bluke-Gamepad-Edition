package dev.arnv.bluke.gamepad

object HidInputPagePolicy {
    const val KEYBOARD = 0
    const val TOUCHPAD = 1
    const val GAMEPAD = 2

    fun allowedLaunchModes(
        profile: HidOutputProfileId,
        configuredModes: List<Int> = listOf(KEYBOARD, TOUCHPAD, GAMEPAD)
    ): List<Int> = when (profile) {
        HidOutputProfileId.JOYPAD_OS -> listOf(GAMEPAD)
        HidOutputProfileId.PC_DIRECT -> configuredModes
            .filter { it in KEYBOARD..GAMEPAD }
            .distinct()
            .ifEmpty { listOf(KEYBOARD) }
    }

    fun resolveLaunchMode(
        profile: HidOutputProfileId,
        currentMode: Int,
        savedPcMode: Int,
        configuredModes: List<Int> = listOf(KEYBOARD, TOUCHPAD, GAMEPAD)
    ): Int {
        val allowed = allowedLaunchModes(profile, configuredModes)
        return when (profile) {
            HidOutputProfileId.JOYPAD_OS -> GAMEPAD
            HidOutputProfileId.PC_DIRECT -> when {
                savedPcMode in allowed -> savedPcMode
                currentMode in allowed -> currentMode
                else -> allowed.first()
            }
        }
    }
}
