package dev.arnv.bluke.bluetooth

import dev.arnv.bluke.gamepad.HidOutputProfileId
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class HidProfileOperationKind {
    CONNECT,
    SWITCH_PROFILE,
    DISCONNECT,
    RESTART,
    RETRY
}

data class HidProfileOperation(
    val token: Long,
    val kind: HidProfileOperationKind,
    val targetProfile: HidOutputProfileId?,
    val deviceAddress: String?
)

/**
 * Synchronous UI-facing operation gate for HID transactions.
 *
 * Bluetooth framework work still runs on coroutines, but [begin] publishes the pending target
 * before the coroutine is launched. This closes the click-to-coroutine race and ensures that a
 * requested Profile is never confused with the callback-confirmed active Profile.
 */
class HidProfileTransactionCoordinator {
    private val nextToken = AtomicLong(0L)
    private val lock = Any()
    private val _operation = MutableStateFlow<HidProfileOperation?>(null)
    val operation: StateFlow<HidProfileOperation?> = _operation

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    fun isCurrent(transaction: HidProfileOperation): Boolean =
        _operation.value?.token == transaction.token

    fun begin(
        kind: HidProfileOperationKind,
        targetProfile: HidOutputProfileId?,
        deviceAddress: String? = null
    ): HidProfileOperation? = synchronized(lock) {
        if (_operation.value != null) return@synchronized null
        HidProfileOperation(
            token = nextToken.incrementAndGet(),
            kind = kind,
            targetProfile = targetProfile,
            deviceAddress = deviceAddress
        ).also {
            _lastError.value = null
            _operation.value = it
        }
    }

    fun completeSuccess(
        transaction: HidProfileOperation,
        actualProfile: HidOutputProfileId?,
        commit: () -> Unit
    ): Boolean = synchronized(lock) {
        if (_operation.value?.token != transaction.token) return@synchronized false
        if (transaction.targetProfile != null && actualProfile != transaction.targetProfile) {
            _lastError.value =
                "Profile callback mismatch: target=${transaction.targetProfile}, actual=$actualProfile"
            _operation.value = null
            return@synchronized false
        }
        commit()
        _operation.value = null
        true
    }

    fun completeFailure(transaction: HidProfileOperation, message: String): Boolean =
        synchronized(lock) {
            if (_operation.value?.token != transaction.token) return@synchronized false
            _lastError.value = message
            _operation.value = null
            true
        }

    fun cancel(message: String? = null) = synchronized(lock) {
        if (message != null) _lastError.value = message
        _operation.value = null
    }
}
