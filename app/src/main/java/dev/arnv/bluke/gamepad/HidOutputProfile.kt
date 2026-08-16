package dev.arnv.bluke.gamepad

enum class HidDeviceSubclass {
    GAMEPAD,
    COMBO
}

data class HidSdpSpec(
    val name: String,
    val description: String,
    val provider: String,
    val subclass: HidDeviceSubclass
)

data class HidReportSpec(
    val reportId: Int,
    val payloadLength: Int
)

data class HidOutgoingQosSpec(
    val tokenRateBytesPerSecond: Int,
    val tokenBucketSizeBytes: Int,
    val peakBandwidthBytesPerSecond: Int,
    val latencyMicros: Int,
    val delayVariationMicros: Int
)

class HidOutputProfile(
    val id: HidOutputProfileId,
    val sdp: HidSdpSpec,
    val classOfDevice: Int,
    descriptor: ByteArray,
    val codec: GamepadReportCodec,
    val outputReportDecoder: HidOutputReportDecoder,
    val transmissionPolicy: GamepadTransmissionPolicy,
    val keyboardInput: HidReportSpec? = null,
    val keyboardOutput: HidReportSpec? = null,
    val mouseInput: HidReportSpec? = null,
    val unsupportedButtons: Set<GamepadButton> = emptySet()
) {
    private val frozenDescriptor = descriptor.copyOf()

    fun descriptorBytes(): ByteArray = frozenDescriptor.copyOf()

    /**
     * Android's HID QoS values are expressed in bytes/second and microseconds.
     * The estimate includes one byte for the report ID and one HIDP transaction byte.
     */
    fun outgoingQosSpec(targetRateHz: Int = 250): HidOutgoingQosSpec {
        require(targetRateHz > 0)
        val estimatedBytesPerReport = codec.payloadLength + 2
        val requiredBytesPerSecond = estimatedBytesPerReport * targetRateHz
        val targetIntervalMicros = (1_000_000 + targetRateHz - 1) / targetRateHz
        return HidOutgoingQosSpec(
            tokenRateBytesPerSecond = maxOf(4_000, requiredBytesPerSecond),
            tokenBucketSizeBytes = estimatedBytesPerReport,
            peakBandwidthBytesPerSecond = maxOf(8_000, requiredBytesPerSecond * 2),
            latencyMicros = targetIntervalMicros,
            delayVariationMicros = targetIntervalMicros
        )
    }
}
