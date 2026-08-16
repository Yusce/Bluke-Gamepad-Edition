package dev.arnv.bluke.gamepad

data class GamepadTransmissionPolicy(
    val minimumIntervalMs: Long,
    val buttonBurstDurationMs: Long,
    val resendUnchangedDuringBurst: Boolean,
    val resendUnchangedWhileActive: Boolean = false
)
