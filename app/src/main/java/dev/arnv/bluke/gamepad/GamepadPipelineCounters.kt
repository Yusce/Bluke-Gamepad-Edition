package dev.arnv.bluke.gamepad

import java.util.concurrent.atomic.AtomicLong

data class GamepadPipelineStats(
    val windowMillis: Long,
    val touchSamples: Long,
    val gyroscopeSamples: Long,
    val stateSubmissions: Long,
    val schedulerTicks: Long,
    val schedulerLateTicks: Long,
    val schedulerPhaseResets: Long,
    val maxSchedulerLatenessMicros: Long,
    val urgentEdgeWakeups: Long,
    val urgentEdgeReports: Long,
    val scheduledReports: Long,
    val sendAttempts: Long,
    val sendAccepted: Long,
    val sendRejected: Long
) {
    val hasActivity: Boolean
        get() = touchSamples != 0L || gyroscopeSamples != 0L || stateSubmissions != 0L ||
            schedulerTicks != 0L || scheduledReports != 0L || sendAttempts != 0L

    companion object {
        val Empty = GamepadPipelineStats(
            windowMillis = 0L,
            touchSamples = 0L,
            gyroscopeSamples = 0L,
            stateSubmissions = 0L,
            schedulerTicks = 0L,
            schedulerLateTicks = 0L,
            schedulerPhaseResets = 0L,
            maxSchedulerLatenessMicros = 0L,
            urgentEdgeWakeups = 0L,
            urgentEdgeReports = 0L,
            scheduledReports = 0L,
            sendAttempts = 0L,
            sendAccepted = 0L,
            sendRejected = 0L
        )
    }
}

class GamepadPipelineCounters {
    private val touchSamples = AtomicLong()
    private val gyroscopeSamples = AtomicLong()
    private val stateSubmissions = AtomicLong()
    private val schedulerTicks = AtomicLong()
    private val schedulerLateTicks = AtomicLong()
    private val schedulerPhaseResets = AtomicLong()
    private val maxSchedulerLatenessMicros = AtomicLong()
    private val urgentEdgeWakeups = AtomicLong()
    private val urgentEdgeReports = AtomicLong()
    private val scheduledReports = AtomicLong()
    private val sendAttempts = AtomicLong()
    private val sendAccepted = AtomicLong()
    private val sendRejected = AtomicLong()

    fun recordTouchSamples(count: Int) {
        if (count > 0) touchSamples.addAndGet(count.toLong())
    }

    fun recordGyroscopeSample() = record(gyroscopeSamples)
    fun recordStateSubmission() = record(stateSubmissions)
    fun recordSchedulerTick(
        latenessNanos: Long = 0L,
        lateThresholdNanos: Long = 500_000L
    ) {
        record(schedulerTicks)
        if (latenessNanos > lateThresholdNanos) {
            record(schedulerLateTicks)
            maxSchedulerLatenessMicros.accumulateAndGet(latenessNanos / 1_000L) { current, value ->
                maxOf(current, value)
            }
        }
    }

    fun recordSchedulerPhaseReset() = record(schedulerPhaseResets)
    fun recordUrgentEdgeWakeup() = record(urgentEdgeWakeups)
    fun recordUrgentEdgeReport() = record(urgentEdgeReports)
    fun recordScheduledReport() = record(scheduledReports)
    fun recordSendAttempt() = record(sendAttempts)
    fun recordSendAccepted() = record(sendAccepted)
    fun recordSendRejected() = record(sendRejected)

    fun snapshotAndReset(windowMillis: Long): GamepadPipelineStats = GamepadPipelineStats(
        windowMillis = windowMillis,
        touchSamples = touchSamples.getAndSet(0L),
        gyroscopeSamples = gyroscopeSamples.getAndSet(0L),
        stateSubmissions = stateSubmissions.getAndSet(0L),
        schedulerTicks = schedulerTicks.getAndSet(0L),
        schedulerLateTicks = schedulerLateTicks.getAndSet(0L),
        schedulerPhaseResets = schedulerPhaseResets.getAndSet(0L),
        maxSchedulerLatenessMicros = maxSchedulerLatenessMicros.getAndSet(0L),
        urgentEdgeWakeups = urgentEdgeWakeups.getAndSet(0L),
        urgentEdgeReports = urgentEdgeReports.getAndSet(0L),
        scheduledReports = scheduledReports.getAndSet(0L),
        sendAttempts = sendAttempts.getAndSet(0L),
        sendAccepted = sendAccepted.getAndSet(0L),
        sendRejected = sendRejected.getAndSet(0L)
    )

    private fun record(counter: AtomicLong) {
        counter.incrementAndGet()
    }
}
