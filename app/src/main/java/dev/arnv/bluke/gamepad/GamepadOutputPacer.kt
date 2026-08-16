package dev.arnv.bluke.gamepad

data class GamepadOutputPacingDecision(
    val nextDeadlineNanos: Long,
    val phaseReset: Boolean
)

/**
 * Keeps small wake-up jitter on the nominal phase, but re-anchors meaningful lateness to the
 * actual tick so a late report is not followed by a catch-up report a fraction of a period later.
 * If work crosses the selected next deadline, the expired tick is discarded and a fresh interval
 * starts after completion.
 */
object GamepadOutputPacer {
    fun canDispatchUrgentEdge(
        lastActualSendNanos: Long?,
        nowNanos: Long,
        intervalNanos: Long,
        catchUpToleranceNanos: Long = intervalNanos / 8L
    ): Boolean {
        require(intervalNanos > 0L)
        require(catchUpToleranceNanos in 0L until intervalNanos)
        if (lastActualSendNanos == null) return true
        require(nowNanos >= lastActualSendNanos)
        return nowNanos - lastActualSendNanos >= intervalNanos - catchUpToleranceNanos
    }

    fun nextDeadline(
        previousDeadlineNanos: Long,
        tickStartedAtNanos: Long,
        tickCompletedAtNanos: Long = tickStartedAtNanos,
        intervalNanos: Long,
        catchUpToleranceNanos: Long = intervalNanos / 8L
    ): GamepadOutputPacingDecision {
        require(intervalNanos > 0L)
        require(catchUpToleranceNanos in 0L until intervalNanos)
        require(tickCompletedAtNanos >= tickStartedAtNanos)
        require(previousDeadlineNanos <= Long.MAX_VALUE - intervalNanos)
        require(tickStartedAtNanos <= Long.MAX_VALUE - intervalNanos)
        require(tickCompletedAtNanos <= Long.MAX_VALUE - intervalNanos)

        val nominalNextDeadline = previousDeadlineNanos + intervalNanos
        val latenessNanos = (tickStartedAtNanos - previousDeadlineNanos).coerceAtLeast(0L)
        val candidateDeadline = if (latenessNanos > catchUpToleranceNanos) {
            tickStartedAtNanos + intervalNanos
        } else {
            nominalNextDeadline
        }
        val tickOverranNextDeadline = tickCompletedAtNanos >= candidateDeadline
        return GamepadOutputPacingDecision(
            nextDeadlineNanos = if (tickOverranNextDeadline) {
                tickCompletedAtNanos + intervalNanos
            } else {
                candidateDeadline
            },
            phaseReset = latenessNanos > catchUpToleranceNanos || tickOverranNextDeadline
        )
    }
}
