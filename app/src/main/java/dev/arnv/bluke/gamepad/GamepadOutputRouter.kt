package dev.arnv.bluke.gamepad

data class EncodedGamepadReport(
    val profileId: HidOutputProfileId,
    val reportId: Int,
    val payload: ByteArray,
    val transmissionPolicy: GamepadTransmissionPolicy
)

/** Stateless protocol router shared by the runtime registration controller and pure tests. */
class GamepadOutputRouter(
    profiles: Collection<HidOutputProfile> = HidOutputProfiles.all
) {
    private val profilesById = profiles.associateBy { it.id }

    init {
        require(profilesById.size == profiles.size) { "Duplicate HID output profile id" }
    }

    fun profile(profileId: HidOutputProfileId): HidOutputProfile =
        requireNotNull(profilesById[profileId]) {
            "No HID output profile registered for $profileId"
        }

    fun encode(profileId: HidOutputProfileId, state: GamepadState): EncodedGamepadReport {
        val profile = profile(profileId)
        val payload = profile.codec.encodeInput(state)
        check(payload.size == profile.codec.payloadLength) {
            "${profile.id} codec returned ${payload.size} bytes; expected ${profile.codec.payloadLength}"
        }
        return EncodedGamepadReport(
            profileId = profile.id,
            reportId = profile.codec.reportId,
            payload = payload,
            transmissionPolicy = profile.transmissionPolicy
        )
    }
}
