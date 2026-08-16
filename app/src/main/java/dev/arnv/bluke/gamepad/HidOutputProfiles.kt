package dev.arnv.bluke.gamepad

object HidOutputProfiles {
    val joypadOs = HidOutputProfile(
        id = HidOutputProfileId.JOYPAD_OS,
        sdp = HidSdpSpec(
            name = "Bluke Gamepad",
            description = "Generic Bluetooth HID Gamepad",
            provider = "Bluke",
            subclass = HidDeviceSubclass.GAMEPAD
        ),
        classOfDevice = 0x00000508,
        descriptor = HidDescriptors.joypadOs(),
        codec = JoypadOsCodec,
        outputReportDecoder = JoypadOsOutputReportDecoder,
        transmissionPolicy = GamepadTransmissionPolicy(
            minimumIntervalMs = 4L,
            buttonBurstDurationMs = 140L,
            resendUnchangedDuringBurst = true,
            resendUnchangedWhileActive = true
        )
    )

    val pcDirect = HidOutputProfile(
        id = HidOutputProfileId.PC_DIRECT,
        sdp = HidSdpSpec(
            name = "Bluke",
            description = "Wireless Controller Combo",
            provider = "Bluke",
            subclass = HidDeviceSubclass.COMBO
        ),
        classOfDevice = 0x000005C0,
        descriptor = HidDescriptors.pcDirectComposite(),
        codec = PcDirectCodec,
        outputReportDecoder = PcDirectOutputReportDecoder,
        transmissionPolicy = GamepadTransmissionPolicy(
            minimumIntervalMs = 4L,
            buttonBurstDurationMs = 0L,
            resendUnchangedDuringBurst = false,
            resendUnchangedWhileActive = true
        ),
        keyboardInput = HidReportSpec(reportId = 1, payloadLength = 8),
        keyboardOutput = HidReportSpec(reportId = 1, payloadLength = 1),
        mouseInput = HidReportSpec(reportId = 2, payloadLength = 4),
        unsupportedButtons = setOf(GamepadButton.TOUCHPAD_CLICK)
    )

    val all: List<HidOutputProfile> = listOf(joypadOs, pcDirect)
}
