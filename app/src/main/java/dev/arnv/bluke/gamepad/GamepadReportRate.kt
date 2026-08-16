package dev.arnv.bluke.gamepad

import kotlin.math.roundToInt

enum class GamepadReportRate(
    val hz: Int,
    val intervalMs: Long,
    val usesEnhancedPipeline: Boolean
) {
    HZ_125(hz = 125, intervalMs = 8L, usesEnhancedPipeline = false),
    HZ_250(hz = 250, intervalMs = 4L, usesEnhancedPipeline = true),
    HZ_500(hz = 500, intervalMs = 2L, usesEnhancedPipeline = true);

    val intervalNanos: Long
        get() = intervalMs * NANOS_PER_MILLISECOND

    val lateTickThresholdNanos: Long
        get() = intervalNanos / 8L

    val sliderPosition: Float
        get() = ordinal.toFloat()

    companion object {
        fun fromHz(value: Int): GamepadReportRate =
            entries.firstOrNull { it.hz == value } ?: HZ_250

        fun fromSliderPosition(value: Float): GamepadReportRate =
            entries[value.roundToInt().coerceIn(0, entries.lastIndex)]

        private const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
