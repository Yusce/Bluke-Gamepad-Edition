package dev.arnv.bluke.gamepad

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

data class GamepadAutomatedTestFrame(
    val state: GamepadState,
    val changeKind: GamepadStateChangeKind
)

/** Shared, deterministic time line used by the on-phone HID timing test. */
object GamepadAutomatedTestTimeline {
    const val OUTPUT_SAMPLE_INTERVAL_MS = 4L
    const val LEFT_STICK_END_MS = 5_000L
    const val RIGHT_STICK_END_MS = 7_500L
    const val A_START_MS = 8_500L
    const val A_END_MS = 11_000L
    const val LB_START_MS = 12_000L
    const val TOTAL_DURATION_MS = 12_500L

    /**
     * Keeps the legacy/125 Hz test producer unchanged, feeds a fresh state to every 250 Hz tick,
     * and removes the former 4 ms producer ceiling from the 500 Hz validation path.
     */
    fun outputSampleIntervalMs(reportRate: GamepadReportRate): Long = when (reportRate) {
        GamepadReportRate.HZ_500 -> 2L
        GamepadReportRate.HZ_125,
        GamepadReportRate.HZ_250 -> OUTPUT_SAMPLE_INTERVAL_MS
    }

    fun frameAt(elapsedNanos: Long): GamepadAutomatedTestFrame {
        val elapsedMs = elapsedNanos.coerceAtLeast(0L) / NANOS_PER_MILLISECOND.toDouble()
        return when {
            elapsedMs < LEFT_STICK_END_MS -> {
                val angle = TWO_PI * elapsedMs / 1_000.0
                GamepadAutomatedTestFrame(
                    state = GamepadState(
                        leftX = cos(angle).toFloat(),
                        leftY = -sin(angle).toFloat()
                    ),
                    changeKind = GamepadStateChangeKind.ANALOG
                )
            }
            elapsedMs < RIGHT_STICK_END_MS -> {
                val angle = TWO_PI * (elapsedMs - LEFT_STICK_END_MS) / 500.0
                GamepadAutomatedTestFrame(
                    state = GamepadState(
                        rightX = cos(angle).toFloat(),
                        rightY = sin(angle).toFloat()
                    ),
                    changeKind = GamepadStateChangeKind.ANALOG
                )
            }
            elapsedMs < A_START_MS -> neutralFrame()
            elapsedMs < A_END_MS -> buttonFrame(
                button = GamepadButton.SOUTH,
                elapsedInSectionMs = elapsedMs - A_START_MS,
                periodMs = 500.0
            )
            elapsedMs < LB_START_MS -> neutralFrame()
            elapsedMs < TOTAL_DURATION_MS -> buttonFrame(
                button = GamepadButton.LEFT_BUMPER,
                elapsedInSectionMs = elapsedMs - LB_START_MS,
                periodMs = 100.0
            )
            else -> neutralFrame(GamepadStateChangeKind.DIGITAL)
        }
    }

    fun isComplete(elapsedNanos: Long): Boolean =
        elapsedNanos >= TOTAL_DURATION_MS * NANOS_PER_MILLISECOND

    private fun buttonFrame(
        button: GamepadButton,
        elapsedInSectionMs: Double,
        periodMs: Double
    ): GamepadAutomatedTestFrame {
        val pressed = elapsedInSectionMs % periodMs < periodMs / 2.0
        return GamepadAutomatedTestFrame(
            state = if (pressed) GamepadState(buttons = setOf(button)) else GamepadState.Neutral,
            changeKind = GamepadStateChangeKind.DIGITAL
        )
    }

    private fun neutralFrame(
        changeKind: GamepadStateChangeKind = GamepadStateChangeKind.ANALOG
    ) = GamepadAutomatedTestFrame(GamepadState.Neutral, changeKind)

    private const val NANOS_PER_MILLISECOND = 1_000_000L
    private const val TWO_PI = PI * 2.0
}
