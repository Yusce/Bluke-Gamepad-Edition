package dev.arnv.bluke.gamepad

enum class GamepadStateChangeKind {
    DIGITAL,
    DPAD,
    ANALOG,
    GYROSCOPE,
    KEEPALIVE
}

data class GamepadTransmissionSnapshot(
    val activeProfile: HidOutputProfileId?,
    val latestState: GamepadState,
    val dirty: Boolean,
    val burstUntilNanos: Long,
    val pendingEdgeCount: Int
)

private data class PendingGamepadEdge(
    val sequence: Long,
    val state: GamepadState
)

/** Pure, monotonic-clock-driven scheduler. Android owns the fixed-period [poll] loop. */
class GamepadTransmissionScheduler(
    private val router: GamepadOutputRouter = GamepadOutputRouter()
) {
    private var activeProfile: HidOutputProfileId? = null
    private var latestState = GamepadState.Neutral
    private var latestSequence = 0L
    private var lastSentSequence = 0L
    private var burstUntilNanos = 0L
    private var lastSentAtNanos: Long? = null
    private val pendingEdges = ArrayDeque<PendingGamepadEdge>()
    private var runtimeMinimumIntervalMs: Long? = null
    private var runtimeResendUnchangedWhileActive: Boolean? = null
    private var runtimePreserveDigitalEdges: Boolean? = null
    private var runtimeDispatchChangesImmediately: Boolean? = null
    private var runtimeKeepaliveIntervalMs: Long? = null

    @Synchronized
    fun configureRuntimePath(
        minimumIntervalMs: Long,
        resendUnchangedWhileActive: Boolean,
        preserveDigitalEdges: Boolean = resendUnchangedWhileActive,
        dispatchChangesImmediately: Boolean = !resendUnchangedWhileActive,
        keepaliveIntervalMs: Long? = null
    ) {
        require(minimumIntervalMs > 0L)
        require(keepaliveIntervalMs == null || keepaliveIntervalMs >= minimumIntervalMs)
        runtimeMinimumIntervalMs = minimumIntervalMs
        runtimeResendUnchangedWhileActive = resendUnchangedWhileActive
        runtimePreserveDigitalEdges = preserveDigitalEdges
        runtimeDispatchChangesImmediately = dispatchChangesImmediately
        runtimeKeepaliveIntervalMs = keepaliveIntervalMs
    }

    @Synchronized
    fun activate(profileId: HidOutputProfileId, nowNanos: Long): EncodedGamepadReport {
        activeProfile = profileId
        latestState = GamepadState.Neutral
        latestSequence = 0L
        lastSentSequence = 0L
        burstUntilNanos = 0L
        lastSentAtNanos = nowNanos
        pendingEdges.clear()
        return router.encode(profileId, GamepadState.Neutral)
    }

    @Synchronized
    fun submit(
        state: GamepadState,
        changeKind: GamepadStateChangeKind,
        nowNanos: Long
    ): EncodedGamepadReport? {
        val profileId = activeProfile ?: return null
        val previous = latestState
        val changed = previous != state
        latestState = state

        if (changed || changeKind == GamepadStateChangeKind.KEEPALIVE) {
            latestSequence += 1L
        }
        if (
            changed &&
            (changeKind == GamepadStateChangeKind.DIGITAL || changeKind == GamepadStateChangeKind.DPAD)
        ) {
            if (runtimePreserveDigitalEdges ?: (runtimeResendUnchangedWhileActive != false)) {
                pendingEdges.addLast(PendingGamepadEdge(latestSequence, state))
            }
            val policy = router.profile(profileId).transmissionPolicy
            if (policy.buttonBurstDurationMs > 0L) {
                burstUntilNanos = maxOf(
                    burstUntilNanos,
                    nowNanos + policy.buttonBurstDurationMs * NANOS_PER_MILLISECOND
                )
            }
        }
        val dispatchChangesImmediately =
            runtimeDispatchChangesImmediately ?: (runtimeResendUnchangedWhileActive == false)
        return if (dispatchChangesImmediately) pollLocked(nowNanos) else null
    }

    @Synchronized
    fun poll(nowNanos: Long): EncodedGamepadReport? = pollLocked(nowNanos)

    /** Attempts to send the oldest pending digital edge without forcing an unchanged report. */
    @Synchronized
    fun pollUrgentEdge(nowNanos: Long): EncodedGamepadReport? =
        if (pendingEdges.isEmpty()) null else pollLocked(nowNanos)

    /** Returns a final neutral report for the old Profile and clears dirty/burst/session state. */
    @Synchronized
    fun deactivate(): EncodedGamepadReport? {
        val profileId = activeProfile ?: return null
        val neutral = router.encode(profileId, GamepadState.Neutral)
        activeProfile = null
        latestState = GamepadState.Neutral
        latestSequence = 0L
        lastSentSequence = 0L
        burstUntilNanos = 0L
        lastSentAtNanos = null
        pendingEdges.clear()
        return neutral
    }

    @Synchronized
    fun snapshot(): GamepadTransmissionSnapshot = GamepadTransmissionSnapshot(
        activeProfile = activeProfile,
        latestState = latestState,
        dirty = pendingEdges.isNotEmpty() || lastSentSequence < latestSequence,
        burstUntilNanos = burstUntilNanos,
        pendingEdgeCount = pendingEdges.size
    )

    private fun pollLocked(nowNanos: Long): EncodedGamepadReport? {
        val profileId = activeProfile ?: return null
        val policy = router.profile(profileId).transmissionPolicy
        val burstActive =
            policy.resendUnchangedDuringBurst && nowNanos < burstUntilNanos
        val hasNewState = pendingEdges.isNotEmpty() || lastSentSequence < latestSequence
        val resendUnchangedWhileActive =
            runtimeResendUnchangedWhileActive ?: policy.resendUnchangedWhileActive
        val previousSend = lastSentAtNanos
        val keepaliveIntervalNanos = runtimeKeepaliveIntervalMs?.times(NANOS_PER_MILLISECOND)
        val keepaliveDue = previousSend != null && keepaliveIntervalNanos != null &&
            nowNanos - previousSend >= keepaliveIntervalNanos
        if (!hasNewState && !burstActive && !resendUnchangedWhileActive && !keepaliveDue) return null
        val minimumIntervalNanos =
            (runtimeMinimumIntervalMs ?: policy.minimumIntervalMs) * NANOS_PER_MILLISECOND
        if (previousSend != null && nowNanos - previousSend < minimumIntervalNanos) return null

        val edge = if (pendingEdges.isEmpty()) null else pendingEdges.removeFirst()
        val stateToSend = edge?.state ?: latestState
        if (edge != null) {
            lastSentSequence = maxOf(lastSentSequence, edge.sequence)
        } else {
            lastSentSequence = latestSequence
        }
        val encoded = router.encode(profileId, stateToSend)
        lastSentAtNanos = nowNanos
        return encoded
    }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
