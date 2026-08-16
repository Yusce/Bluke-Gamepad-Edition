package dev.arnv.bluke.bluetooth

import dev.arnv.bluke.gamepad.GamepadState
import dev.arnv.bluke.gamepad.HidOutputProfileId
import dev.arnv.bluke.gamepad.HidOutputProfiles

data class HidInputReport(
    val reportId: Int,
    val payload: ByteArray
)

/** Frozen neutral reports used at host-session boundaries. Report IDs remain separate. */
object HidNeutralReportPlan {
    fun forProfile(profileId: HidOutputProfileId): List<HidInputReport> = when (profileId) {
        HidOutputProfileId.PC_DIRECT -> listOf(
            HidInputReport(
                reportId = requireNotNull(HidOutputProfiles.pcDirect.keyboardInput).reportId,
                payload = ByteArray(requireNotNull(HidOutputProfiles.pcDirect.keyboardInput).payloadLength)
            ),
            HidInputReport(
                reportId = requireNotNull(HidOutputProfiles.pcDirect.mouseInput).reportId,
                payload = ByteArray(requireNotNull(HidOutputProfiles.pcDirect.mouseInput).payloadLength)
            ),
            HidInputReport(
                reportId = HidOutputProfiles.pcDirect.codec.reportId,
                payload = HidOutputProfiles.pcDirect.codec.encodeInput(GamepadState.Neutral)
            )
        )

        HidOutputProfileId.JOYPAD_OS -> listOf(
            HidInputReport(
                reportId = HidOutputProfiles.joypadOs.codec.reportId,
                payload = HidOutputProfiles.joypadOs.codec.encodeInput(GamepadState.Neutral)
            )
        )
    }
}

/**
 * Prevents reports from crossing host/Profile boundaries. A new freeze invalidates an older
 * reconnect task, so a late neutral completion cannot reopen input for a stale session.
 */
class HidInputSessionGate {
    private val lock = Any()
    private var generation = 0L
    @Volatile
    private var open = false

    val isOpen: Boolean get() = open

    fun isCurrent(token: Long): Boolean = synchronized(lock) { generation == token }

    fun freeze(): Long = synchronized(lock) {
        generation += 1L
        open = false
        generation
    }

    suspend fun neutralizeAndKeepClosed(
        profileId: HidOutputProfileId,
        sender: suspend (List<HidInputReport>) -> Unit
    ) {
        freeze()
        sender(HidNeutralReportPlan.forProfile(profileId))
    }

    suspend fun neutralizeAndOpen(
        profileId: HidOutputProfileId,
        sender: suspend (List<HidInputReport>) -> Unit,
        token: Long = freeze(),
        beforeOpen: () -> Unit = {}
    ): Boolean {
        if (!isCurrent(token)) return false
        sender(HidNeutralReportPlan.forProfile(profileId))
        return synchronized(lock) {
            if (generation != token) return@synchronized false
            beforeOpen()
            open = true
            true
        }
    }
}
