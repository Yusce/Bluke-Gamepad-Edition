package dev.arnv.bluke.bluetooth

import dev.arnv.bluke.gamepad.HidOutputProfile
import dev.arnv.bluke.gamepad.HidOutputProfileId
import dev.arnv.bluke.gamepad.HidOutputProfiles
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

data class HidHostDevice(
    val address: String,
    val name: String? = null
)

sealed interface HidRegistrationState {
    data object Unregistered : HidRegistrationState
    data class Registering(val profile: HidOutputProfileId) : HidRegistrationState
    data class Ready(val profile: HidOutputProfileId) : HidRegistrationState
    data class Connecting(
        val profile: HidOutputProfileId,
        val device: HidHostDevice
    ) : HidRegistrationState
    data class Connected(
        val profile: HidOutputProfileId,
        val device: HidHostDevice
    ) : HidRegistrationState
    data class Disconnecting(
        val profile: HidOutputProfileId,
        val device: HidHostDevice
    ) : HidRegistrationState
    data class Unregistering(val previousProfile: HidOutputProfileId?) : HidRegistrationState
    data class Failed(val operation: String, val message: String) : HidRegistrationState
}

enum class HidConnectionCallbackState {
    CONNECTED,
    DISCONNECTED
}

interface HidRegistrationBackend {
    fun register(profile: HidOutputProfile): Boolean
    fun unregister(): Boolean
    fun connect(device: HidHostDevice): Boolean
    fun disconnect(device: HidHostDevice): Boolean
}

data class HidRegistrationTimeouts(
    val registerMs: Long = 5_000L,
    val unregisterMs: Long = 5_000L,
    val connectMs: Long = 10_000L,
    val disconnectMs: Long = 5_000L
)

private enum class LastHidRequestKind {
    ENSURE_PROFILE,
    CONNECT,
    RESTART,
    DISCONNECT
}

private data class LastHidRequest(
    val kind: LastHidRequestKind,
    val profile: HidOutputProfileId,
    val device: HidHostDevice?
)

class HidOperationException(
    val operation: String,
    message: String
) : IllegalStateException(message)

/**
 * Callback-driven HID registration state machine. Android framework objects stay behind
 * [HidRegistrationBackend], so every transition can be exercised with a deterministic fake.
 */
class BluetoothHidRegistrationController(
    profiles: Collection<HidOutputProfile> = HidOutputProfiles.all,
    private val backend: HidRegistrationBackend,
    private val timeouts: HidRegistrationTimeouts = HidRegistrationTimeouts(),
    private val debounceDelay: suspend (Long) -> Unit = { delay(it) },
    private val beforeDisconnect: suspend (HidOutputProfileId, HidHostDevice) -> Unit = { _, _ -> },
    private val onSessionReset: () -> Unit = {},
    private val logger: (String) -> Unit = {}
) {
    private val profilesById = profiles.associateBy { it.id }
    private val operationMutex = Mutex()
    private val callbackLock = Any()

    private val _state = MutableStateFlow<HidRegistrationState>(HidRegistrationState.Unregistered)
    val state: StateFlow<HidRegistrationState> = _state

    private val _activeProfile = MutableStateFlow<HidOutputProfileId?>(null)
    val activeProfile: StateFlow<HidOutputProfileId?> = _activeProfile

    @Volatile
    private var closed = false
    @Volatile
    private var registeredProfile: HidOutputProfileId? = null
    @Volatile
    private var registrationTarget: HidOutputProfileId? = null
    @Volatile
    private var connectedDevice: HidHostDevice? = null

    private var registrationWaiter: CompletableDeferred<Boolean>? = null
    private var unregistrationWaiter: CompletableDeferred<Unit>? = null
    private var connectionWaiter: CompletableDeferred<Boolean>? = null
    private var disconnectionWaiter: CompletableDeferred<Unit>? = null
    private var connectionTargetAddress: String? = null
    private var disconnectionTargetAddress: String? = null
    private var lastRequest: LastHidRequest? = null

    init {
        require(profilesById.size == profiles.size) { "Duplicate HID profile id" }
        require(profilesById.keys.containsAll(HidOutputProfileId.entries)) {
            "Every HID output profile must be registered"
        }
    }

    suspend fun ensureProfile(target: HidOutputProfileId): Result<Unit> = runOperation("register") {
        operationMutex.withLock {
            ensureOpen()
            lastRequest = LastHidRequest(LastHidRequestKind.ENSURE_PROFILE, target, null)
            ensureProfileLocked(target)
        }
    }

    suspend fun connect(device: HidHostDevice, target: HidOutputProfileId): Result<Unit> =
        runOperation("connect") {
            operationMutex.withLock {
                ensureOpen()
                lastRequest = LastHidRequest(LastHidRequestKind.CONNECT, target, device)
                if (
                    registeredProfile == target &&
                    connectedDevice?.address.equals(device.address, ignoreCase = true)
                ) {
                    _state.value = HidRegistrationState.Connected(target, connectedDevice ?: device)
                    return@withLock
                }
                if (
                    connectedDevice != null &&
                    !connectedDevice?.address.equals(device.address, ignoreCase = true)
                ) {
                    disconnectLockedIfNeeded()
                }
                ensureProfileLocked(target)
                connectLocked(device, target)
            }
        }

    suspend fun restart(target: HidOutputProfileId): Result<Unit> = runOperation("restart") {
        operationMutex.withLock {
            ensureOpen()
            val reconnectDevice = connectedDevice
            lastRequest = LastHidRequest(LastHidRequestKind.RESTART, target, reconnectDevice)
            disconnectLockedIfNeeded()
            unregisterLockedIfNeeded()
            registerLocked(target)
            if (reconnectDevice != null) connectLocked(reconnectDevice, target)
        }
    }

    suspend fun disconnect(): Result<Unit> = runOperation("disconnect") {
        operationMutex.withLock {
            ensureOpen()
            val profile = registeredProfile ?: registrationTarget
            if (profile != null) {
                lastRequest = LastHidRequest(
                    LastHidRequestKind.DISCONNECT,
                    profile,
                    connectedDevice
                )
            }
            disconnectLockedIfNeeded()
        }
    }

    suspend fun retry(): Result<Unit> {
        val request = lastRequest
            ?: return Result.failure(HidOperationException("retry", "No HID operation is available to retry"))
        return when (request.kind) {
            LastHidRequestKind.ENSURE_PROFILE -> ensureProfile(request.profile)
            LastHidRequestKind.CONNECT -> connect(requireNotNull(request.device), request.profile)
            LastHidRequestKind.RESTART -> restart(request.profile)
            LastHidRequestKind.DISCONNECT -> disconnect()
        }
    }

    fun onAppStatusChanged(isRegistered: Boolean) {
        synchronized(callbackLock) {
            if (closed) return
            if (isRegistered) {
                val target = registrationTarget ?: registeredProfile
                if (target == null) {
                    failFromCallback("register", "HID registered without a requested Profile")
                    registrationWaiter?.complete(false)
                    return
                }
                registeredProfile = target
                _activeProfile.value = target
                logger("register callback: profile=$target reportId=${profile(target).codec.reportId} payload=${profile(target).codec.payloadLength}")
                registrationWaiter?.complete(true)
            } else {
                val expectedUnregister = unregistrationWaiter != null
                registeredProfile = null
                registrationTarget = null
                connectedDevice = null
                _activeProfile.value = null
                onSessionReset()
                unregistrationWaiter?.complete(Unit)
                registrationWaiter?.complete(false)
                if (!expectedUnregister && registrationWaiter == null) {
                    val error = HidOperationException(
                        "unexpected_unregister",
                        "Bluetooth HID application was unregistered unexpectedly; restart HID service or toggle Bluetooth"
                    )
                    connectionWaiter?.completeExceptionally(error)
                    disconnectionWaiter?.completeExceptionally(error)
                    failFromCallback(
                        error.operation,
                        error.message ?: "Bluetooth HID application was unregistered unexpectedly"
                    )
                }
            }
        }
    }

    fun onConnectionStateChanged(device: HidHostDevice, callbackState: HidConnectionCallbackState) {
        synchronized(callbackLock) {
            if (closed) return
            when (callbackState) {
                HidConnectionCallbackState.CONNECTED -> {
                    val profile = registeredProfile ?: registrationTarget
                    if (profile == null) {
                        failFromCallback("connect", "Host connected while no HID Profile was registered")
                        connectionWaiter?.complete(false)
                        return
                    }
                    connectedDevice = device
                    _state.value = HidRegistrationState.Connected(profile, device)
                    if (connectionTargetAddress.equals(device.address, ignoreCase = true)) {
                        connectionWaiter?.complete(true)
                    }
                    logger("connect callback: profile=$profile device=${device.address}")
                }

                HidConnectionCallbackState.DISCONNECTED -> {
                    if (connectedDevice?.address.equals(device.address, ignoreCase = true)) {
                        connectedDevice = null
                    }
                    if (disconnectionTargetAddress.equals(device.address, ignoreCase = true)) {
                        disconnectionWaiter?.complete(Unit)
                    }
                    if (connectionTargetAddress.equals(device.address, ignoreCase = true)) {
                        connectionWaiter?.complete(false)
                    }
                    onSessionReset()
                    registeredProfile?.let { profile ->
                        if (_state.value !is HidRegistrationState.Unregistering) {
                            _state.value = HidRegistrationState.Ready(profile)
                        }
                    } ?: run {
                        _state.value = HidRegistrationState.Unregistered
                    }
                    logger("disconnect callback: device=${device.address}")
                }
            }
        }
    }

    fun onBluetoothUnavailable(message: String = "Bluetooth was turned off") {
        synchronized(callbackLock) {
            if (closed) return
            val failure = HidOperationException("bluetooth_unavailable", message)
            cancelWaiters(failure)
            registeredProfile = null
            registrationTarget = null
            connectedDevice = null
            _activeProfile.value = null
            onSessionReset()
            _state.value = HidRegistrationState.Failed("bluetooth_unavailable", message)
        }
    }

    fun retainRegisteredProfileAfterConnectFailure(target: HidOutputProfileId): Boolean =
        synchronized(callbackLock) {
            if (closed || registeredProfile != target || connectedDevice != null) {
                return@synchronized false
            }
            _activeProfile.value = target
            _state.value = HidRegistrationState.Ready(target)
            logger("connect recovery: retaining registered profile=$target for host reconnect")
            true
        }

    fun close() {
        synchronized(callbackLock) {
            if (closed) return
            closed = true
            cancelWaiters(CancellationException("Bluetooth HID registration controller closed"))
            registeredProfile = null
            registrationTarget = null
            connectedDevice = null
            _activeProfile.value = null
            onSessionReset()
            _state.value = HidRegistrationState.Unregistered
        }
    }

    private suspend fun ensureProfileLocked(target: HidOutputProfileId) {
        if (registeredProfile == target) {
            if (connectedDevice == null) _state.value = HidRegistrationState.Ready(target)
            return
        }
        val switchingProfile = registeredProfile != null || registrationTarget != null
        disconnectLockedIfNeeded()
        unregisterLockedIfNeeded()
        registerLocked(target)
        if (switchingProfile) debounceDelay(BLUETOOTH_STACK_DEBOUNCE_MS)
    }

    private suspend fun disconnectLockedIfNeeded() {
        val device = connectedDevice ?: return
        val profile = registeredProfile ?: registrationTarget
            ?: throw HidOperationException("disconnect", "Cannot disconnect without a registered Profile")
        _state.value = HidRegistrationState.Disconnecting(profile, device)
        beforeDisconnect(profile, device)
        onSessionReset()
        val waiter = CompletableDeferred<Unit>()
        synchronized(callbackLock) {
            disconnectionWaiter = waiter
            disconnectionTargetAddress = device.address
        }
        try {
            if (!backend.disconnect(device)) {
                throw HidOperationException("disconnect", "disconnect() rejected for ${device.address}")
            }
            await("disconnect", timeouts.disconnectMs) { waiter.await() }
            connectedDevice = null
            _state.value = HidRegistrationState.Ready(profile)
        } finally {
            synchronized(callbackLock) {
                if (disconnectionWaiter === waiter) disconnectionWaiter = null
                disconnectionTargetAddress = null
            }
        }
    }

    private suspend fun unregisterLockedIfNeeded() {
        // A registerApp() call can be accepted while its positive callback is lost/timed out.
        // registrationTarget therefore also requires an explicit unregister barrier before any
        // rollback registration; otherwise a late callback could be mistaken for the new Profile.
        val previous = registeredProfile ?: registrationTarget ?: return
        _state.value = HidRegistrationState.Unregistering(previous)
        val waiter = CompletableDeferred<Unit>()
        synchronized(callbackLock) { unregistrationWaiter = waiter }
        try {
            if (!backend.unregister()) {
                throw HidOperationException("unregister", "unregisterApp() rejected for $previous")
            }
            await("unregister", timeouts.unregisterMs) { waiter.await() }
            registeredProfile = null
            registrationTarget = null
            _activeProfile.value = null
            _state.value = HidRegistrationState.Unregistered
            logger("unregister callback complete: previousProfile=$previous")
            debounceDelay(BLUETOOTH_STACK_DEBOUNCE_MS)
        } finally {
            synchronized(callbackLock) {
                if (unregistrationWaiter === waiter) unregistrationWaiter = null
            }
        }
    }

    private suspend fun registerLocked(target: HidOutputProfileId) {
        val profile = profile(target)
        registrationTarget = target
        _state.value = HidRegistrationState.Registering(target)
        logger(
            "register start: profile=$target sdp=${profile.sdp.name} subclass=${profile.sdp.subclass} " +
                "reportId=${profile.codec.reportId} payload=${profile.codec.payloadLength}"
        )
        val waiter = CompletableDeferred<Boolean>()
        synchronized(callbackLock) { registrationWaiter = waiter }
        try {
            if (!backend.register(profile)) {
                registrationTarget = null
                throw HidOperationException("register", "registerApp() rejected for $target")
            }
            val callbackRegistered = await("register", timeouts.registerMs) { waiter.await() }
            if (!callbackRegistered || registeredProfile != target) {
                throw HidOperationException("register", "onAppStatusChanged(false) while registering $target")
            }
            _state.value = HidRegistrationState.Ready(target)
        } finally {
            synchronized(callbackLock) {
                if (registrationWaiter === waiter) registrationWaiter = null
            }
        }
    }

    private suspend fun connectLocked(device: HidHostDevice, target: HidOutputProfileId) {
        _state.value = HidRegistrationState.Connecting(target, device)
        logger(
            "connect start: profile=$target device=${device.address} " +
                "reportId=${profile(target).codec.reportId} payload=${profile(target).codec.payloadLength}"
        )
        val waiter = CompletableDeferred<Boolean>()
        synchronized(callbackLock) {
            connectionWaiter = waiter
            connectionTargetAddress = device.address
        }
        try {
            if (!backend.connect(device)) {
                throw HidOperationException("connect", "connect() rejected for ${device.address}")
            }
            if (!await("connect", timeouts.connectMs) { waiter.await() }) {
                throw HidOperationException("connect", "Host disconnected while connecting to ${device.address}")
            }
            connectedDevice = device
            _state.value = HidRegistrationState.Connected(target, device)
        } finally {
            synchronized(callbackLock) {
                if (connectionWaiter === waiter) connectionWaiter = null
                connectionTargetAddress = null
            }
        }
    }

    private suspend fun <T> await(operation: String, timeoutMs: Long, block: suspend () -> T): T =
        try {
            withTimeout(timeoutMs) { block() }
        } catch (_: TimeoutCancellationException) {
            throw HidOperationException(operation, "$operation callback timed out after ${timeoutMs}ms")
        }

    private suspend fun runOperation(operation: String, block: suspend () -> Unit): Result<Unit> =
        try {
            block()
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            if (closed) {
                Result.failure(cancelled)
            } else {
                throw cancelled
            }
        } catch (error: Throwable) {
            val message = error.message ?: error::class.java.simpleName
            val failedOperation = (error as? HidOperationException)?.operation ?: operation
            logger("$failedOperation failed: $message")
            _state.value = HidRegistrationState.Failed(failedOperation, message)
            Result.failure(error)
        }

    private fun profile(id: HidOutputProfileId): HidOutputProfile =
        requireNotNull(profilesById[id]) { "No immutable HID Profile registered for $id" }

    private fun ensureOpen() {
        if (closed) throw CancellationException("Bluetooth HID registration controller is closed")
    }

    private fun failFromCallback(operation: String, message: String) {
        logger("$operation failed: $message")
        _state.value = HidRegistrationState.Failed(operation, message)
    }

    private fun cancelWaiters(error: Throwable) {
        registrationWaiter?.completeExceptionally(error)
        unregistrationWaiter?.completeExceptionally(error)
        connectionWaiter?.completeExceptionally(error)
        disconnectionWaiter?.completeExceptionally(error)
        registrationWaiter = null
        unregistrationWaiter = null
        connectionWaiter = null
        disconnectionWaiter = null
        connectionTargetAddress = null
        disconnectionTargetAddress = null
    }

    companion object {
        const val BLUETOOTH_STACK_DEBOUNCE_MS = 300L
    }
}
