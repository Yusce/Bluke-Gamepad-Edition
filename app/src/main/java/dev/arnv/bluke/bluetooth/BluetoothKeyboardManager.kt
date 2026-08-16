package dev.arnv.bluke.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.arnv.bluke.R
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import dev.arnv.bluke.gamepad.EncodedGamepadReport
import dev.arnv.bluke.gamepad.GamepadState
import dev.arnv.bluke.gamepad.GamepadStateChangeKind
import dev.arnv.bluke.gamepad.GamepadPipelineCounters
import dev.arnv.bluke.gamepad.GamepadPipelineStats
import dev.arnv.bluke.gamepad.GamepadOutputPacer
import dev.arnv.bluke.gamepad.GamepadReportRate
import dev.arnv.bluke.gamepad.GamepadTransmissionScheduler
import dev.arnv.bluke.gamepad.HidDeviceSubclass
import dev.arnv.bluke.gamepad.HidOutputProfile
import dev.arnv.bluke.gamepad.HidOutputProfileId
import dev.arnv.bluke.gamepad.HidOutputProfiles
import dev.arnv.bluke.gamepad.HidOutputReportResult
import dev.arnv.bluke.utils.DeveloperLogManager
import dev.arnv.bluke.utils.LogType
import androidx.core.content.edit
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

sealed class BluetoothState {
    object Unsupported : BluetoothState()
    object PermissionRequired : BluetoothState()
    object BluetoothOff : BluetoothState()
    object ProfileNotSupported : BluetoothState()
    object ReadyDisconnected : BluetoothState()
    data class PairingMode(val name: String) : BluetoothState()
    data class Connected(val deviceName: String) : BluetoothState()
}

private data class PendingProxyConnection(
    val device: BluetoothDevice,
    val profile: HidOutputProfileId,
    val delayMs: Long
)

class BluetoothKeyboardManager(private val context: Context) {

    private val reportExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_FOREGROUND)
            runnable.run()
        }, "bt-report-sender")
    }
    private var enhancedReportThreadPriority = false
    @Volatile
    private var reportSenderClosed = false

    @SuppressLint("MissingPermission")
    private fun submitReport(
        dev: BluetoothDevice,
        reportId: Int,
        report: ByteArray,
        countAsGamepad: Boolean = false
    ) {
        if (!inputSessionGate.isOpen) return
        val hid = hidDeviceProfile
        if (hid != null) {
            try {
                reportExecutor.submit {
                    sendReportAttempt(hid, dev, reportId, report, countAsGamepad)
                }
            } catch (_: RejectedExecutionException) {
                Log.w("BluetoothKeyboard", "Report sender is already closed; dropping input report")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendReportAttempt(
        hid: BluetoothHidDevice,
        dev: BluetoothDevice,
        reportId: Int,
        report: ByteArray,
        countAsGamepad: Boolean = false
    ): Boolean = try {
        if (countAsGamepad) gamepadPipelineCounters.recordSendAttempt()
        if (DeveloperLogManager.isEnabled(LogType.BLUETOOTH_PACKET)) {
            DeveloperLogManager.log(
                "BluetoothKeyboard",
                "sendReport ID=0x${reportId.toString(16)} Data=[${report.joinToString(" ") { String.format("%02X", it) }}]",
                LogType.BLUETOOTH_PACKET
            )
        }
        hid.sendReport(dev, reportId, report).also { accepted ->
            if (countAsGamepad) {
                if (accepted) gamepadPipelineCounters.recordSendAccepted()
                else gamepadPipelineCounters.recordSendRejected()
            }
        }
    } catch (e: Exception) {
        if (countAsGamepad) gamepadPipelineCounters.recordSendRejected()
        Log.e("BluetoothKeyboard", "Error transmitting HID report ID $reportId", e)
        false
    }

    private suspend fun sendNeutralBarrier(
        dev: BluetoothDevice,
        reports: List<HidInputReport>
    ) {
        val future = queueNeutralReports(dev, reports)
        try {
            withContext(Dispatchers.IO) {
                future.get(NEUTRAL_REPORT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            }
        } catch (_: TimeoutException) {
            future.cancel(false)
            throw HidOperationException(
                "neutral",
                "Neutral report queue timed out after ${NEUTRAL_REPORT_TIMEOUT_MS}ms"
            )
        }
    }

    private fun queueNeutralReports(
        dev: BluetoothDevice,
        reports: List<HidInputReport>
    ): Future<Boolean> {
        val hid = hidDeviceProfile
            ?: throw HidOperationException("neutral", "HID service unavailable while neutralizing input")
        return try {
            reportExecutor.submit<Boolean> {
                var attempted = true
                reports.forEach { report ->
                    attempted = sendReportAttempt(hid, dev, report.reportId, report.payload) && attempted
                }
                attempted
            }
        } catch (error: RejectedExecutionException) {
            throw HidOperationException("neutral", "Report sender closed before neutral reports")
        }
    }

    private val _serviceState = MutableStateFlow<BluetoothState>(BluetoothState.ReadyDisconnected)
    val serviceState: StateFlow<BluetoothState> = _serviceState

    private val _statusMessage = MutableStateFlow("Initializing Bluetooth Controller...")
    val statusMessage: StateFlow<String> = _statusMessage



    // Device lists for scan / connect UI
    private val _bondedDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val bondedDevices: StateFlow<List<BluetoothDevice>> = _bondedDevices

    private val _scannedDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val scannedDevices: StateFlow<List<BluetoothDevice>> = _scannedDevices

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning

    private val _capsLockState = MutableStateFlow(false)
    val capsLockState: StateFlow<Boolean> = _capsLockState

    private val _numLockState = MutableStateFlow(true)
    val numLockState: StateFlow<Boolean> = _numLockState

    private val _scrollLockState = MutableStateFlow(false)
    val scrollLockState: StateFlow<Boolean> = _scrollLockState

    private val bluetoothAdapter: BluetoothAdapter? = try {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    } catch (_: Exception) {
        null
    }

    @SuppressLint("MissingPermission")
    private fun safeDeviceName(device: BluetoothDevice?): String? = try {
        device?.name
    } catch (_: SecurityException) {
        null
    }

    @SuppressLint("MissingPermission")
    private fun safeAdapterName(): String? = try {
        bluetoothAdapter?.name
    } catch (_: SecurityException) {
        null
    }

    private var hidDeviceProfile: BluetoothHidDevice? = null
    private var lastConnectedDevice: BluetoothDevice? = null
    private val _connectedDevice = MutableStateFlow<BluetoothDevice?>(null)
    val connectedDevice: StateFlow<BluetoothDevice?> = _connectedDevice
    private val manualConnectionGate = ManualHidConnectionGate()

    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            runnable.run()
        }, "bt-manager-scheduler")
    }

    private val managerScope = CoroutineScope(Dispatchers.IO + Job())
    private var isReceiverRegistered = false
    private var isBondReceiverRegistered = false

    private val bondStateReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(c: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                checkBluetoothCapabilities()
            } else if (action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
                val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                val prevBondState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE)
                
                if (device != null) {
                    val dName = device.name ?: device.address
                    when (bondState) {
                        BluetoothDevice.BOND_BONDING -> {
                            _statusMessage.value = "Pairing with '$dName'... Please accept the pairing prompt."
                        }
                        BluetoothDevice.BOND_BONDED -> {
                            _statusMessage.value = "Pairing successful! Connecting to '$dName'..."
                            updateBondedDevices()
                            connectDevice(
                                device = device,
                                requestedProfile = pendingPairingProfiles.remove(device.address),
                                delayMs = 1500
                            )
                        }
                        BluetoothDevice.BOND_NONE -> {
                            updateBondedDevices()
                            if (prevBondState == BluetoothDevice.BOND_BONDING) {
                                _statusMessage.value = "Pairing with '$dName' refused or failed."
                            } else {
                                _statusMessage.value = "Unpaired from '$dName'."
                            }
                        }
                    }
                }
            }
        }
    }

    private fun registerBondReceiver() {
        if (!isBondReceiverRegistered) {
            try {
                val filter = IntentFilter().apply {
                    addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                    addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(bondStateReceiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    context.registerReceiver(bondStateReceiver, filter)
                }
                isBondReceiverRegistered = true
            } catch (e: Exception) {
                Log.e("BluetoothKeyboard", "Error registering bond receiver: ${e.message}", e)
            }
        }
    }

    // 8-byte Keyboard HID report parameters
    private val reportId = 1
    private var activeModifiers = 0
    private val activeKeys = ByteArray(6)

    // Discovery receiver to catch found devices and scanning events
    private val discoveryReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(c: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            when (action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    if (device != null) {
                        val currentList = _scannedDevices.value
                        if (!currentList.any { it.address == device.address }) {
                            _scannedDevices.value = currentList + device
                        }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                    _isScanning.value = true
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    _isScanning.value = false
                }
            }
        }
    }

    private val sharedPrefs = context.getSharedPreferences("bluetooth_keyboard_prefs", Context.MODE_PRIVATE)
    private val profilePreferenceStore = HidProfilePreferenceStore(
        object : StringPreferenceStore {
            override fun getString(key: String): String? = sharedPrefs.getString(key, null)
            override fun putString(key: String, value: String) {
                sharedPrefs.edit { putString(key, value) }
            }
            override fun remove(key: String) {
                sharedPrefs.edit { remove(key) }
            }
        }
    )
    private val _profilePreferenceRevision = MutableStateFlow(0L)
    val profilePreferenceRevision: StateFlow<Long> = _profilePreferenceRevision
    private val profileTransactions = HidProfileTransactionCoordinator()
    val profileOperation: StateFlow<HidProfileOperation?> = profileTransactions.operation
    val profileTransactionError: StateFlow<String?> = profileTransactions.lastError
    private val inputSessionGate = HidInputSessionGate()
    private val knownDevices = ConcurrentHashMap<String, BluetoothDevice>()
    private val gamepadScheduler = GamepadTransmissionScheduler()
    private val _gamepadReportRate = MutableStateFlow(
        GamepadReportRate.fromHz(
            sharedPrefs.getInt(GAMEPAD_REPORT_RATE_PREF, GamepadReportRate.HZ_250.hz)
        )
    )
    val gamepadReportRate: StateFlow<GamepadReportRate> = _gamepadReportRate
    private val enhancedTickerScheduled = AtomicBoolean(false)
    private val enhancedUrgentEdgeDispatchScheduled = AtomicBoolean(false)
    @Volatile
    private var lastEnhancedReportAttemptNanos: Long? = null
    private val gamepadPipelineCounters = GamepadPipelineCounters()
    private val _gamepadPipelineStats = MutableStateFlow(GamepadPipelineStats.Empty)
    val gamepadPipelineStats: StateFlow<GamepadPipelineStats> = _gamepadPipelineStats
    private val _gamepadSessionGeneration = MutableStateFlow(0L)
    val gamepadSessionGeneration: StateFlow<Long> = _gamepadSessionGeneration
    private val _inputSessionGeneration = MutableStateFlow(0L)
    val inputSessionGeneration: StateFlow<Long> = _inputSessionGeneration

    private val registrationBackend: HidRegistrationBackend = object : HidRegistrationBackend {
        @SuppressLint("MissingPermission")
        override fun register(profile: HidOutputProfile): Boolean {
            val hid = hidDeviceProfile ?: return false
            val subclass = when (profile.sdp.subclass) {
                HidDeviceSubclass.GAMEPAD -> BluetoothHidDevice.SUBCLASS2_GAMEPAD
                HidDeviceSubclass.COMBO -> BluetoothHidDevice.SUBCLASS1_COMBO
            }
            val settings = BluetoothHidDeviceAppSdpSettings(
                profile.sdp.name,
                profile.sdp.description,
                profile.sdp.provider,
                subclass,
                profile.descriptorBytes()
            )
            val outgoingQos = if (_gamepadReportRate.value.usesEnhancedPipeline) {
                val reportRate = _gamepadReportRate.value
                val qosSpec = profile.outgoingQosSpec(reportRate.hz)
                Log.i(
                    "BluetoothKeyboard",
                    "Registering ${profile.id} ${reportRate.hz}Hz outgoing QoS: " +
                        "tokenRate=${qosSpec.tokenRateBytesPerSecond}B/s " +
                        "bucket=${qosSpec.tokenBucketSizeBytes}B latency=${qosSpec.latencyMicros}us"
                )
                BluetoothHidDeviceAppQosSettings(
                    BluetoothHidDeviceAppQosSettings.SERVICE_GUARANTEED,
                    qosSpec.tokenRateBytesPerSecond,
                    qosSpec.tokenBucketSizeBytes,
                    qosSpec.peakBandwidthBytesPerSecond,
                    qosSpec.latencyMicros,
                    qosSpec.delayVariationMicros
                )
            } else {
                Log.i("BluetoothKeyboard", "Registering ${profile.id} legacy 125Hz path without outgoing QoS")
                null
            }
            return hid.registerApp(settings, null, outgoingQos, executor, hidCallback)
        }

        @SuppressLint("MissingPermission")
        override fun unregister(): Boolean = hidDeviceProfile?.unregisterApp() == true

        @SuppressLint("MissingPermission")
        override fun connect(device: HidHostDevice): Boolean {
            manualConnectionGate.authorize(device.address)
            return knownDevices[device.address]?.let { hidDeviceProfile?.connect(it) } == true
        }

        @SuppressLint("MissingPermission")
        override fun disconnect(device: HidHostDevice): Boolean {
            manualConnectionGate.revoke(device.address)
            return knownDevices[device.address]?.let { hidDeviceProfile?.disconnect(it) } == true
        }
    }

    private val registrationController: BluetoothHidRegistrationController = BluetoothHidRegistrationController(
        backend = registrationBackend,
        beforeDisconnect = { profile, host -> prepareConnectedHostForDisconnect(profile, host) },
        onSessionReset = {
            inputSessionGate.freeze()
            clearLocalInputState()
        },
        logger = { message ->
            DeveloperLogManager.log("HidRegistration", message)
            Log.d("HidRegistration", message)
        }
    )
    val registrationState: StateFlow<HidRegistrationState> = registrationController.state
    val activeProfile: StateFlow<HidOutputProfileId?> = registrationController.activeProfile

    private var lastConnectedDeviceAddress: String?
        get() = sharedPrefs.getString("last_connected_device_address", null)
        set(value) {
            if (value == null) {
                sharedPrefs.edit { remove("last_connected_device_address") }
            } else {
                sharedPrefs.edit { putString("last_connected_device_address", value) }
            }
        }

    private val pendingPairingProfiles = ConcurrentHashMap<String, HidOutputProfileId>()
    @Volatile
    private var pendingProxyConnection: PendingProxyConnection? = null
    private val unknownOutputLogTimes = ConcurrentHashMap<String, Long>()
    private var audioProfilesDisconnectedForSession = false

    init {
        try {
            configureGamepadScheduler(_gamepadReportRate.value)
            managerScope.launch {
                registrationState.collect { state -> updateRegistrationStatus(state) }
            }
            ensureEnhancedGamepadOutputTicker()
            managerScope.launch {
                while (isActive) {
                    delay(GamepadReportRate.HZ_125.intervalMs)
                    if (_gamepadReportRate.value == GamepadReportRate.HZ_125) {
                        gamepadPipelineCounters.recordSchedulerTick()
                        dispatchGamepadReport(
                            gamepadScheduler.poll(SystemClock.elapsedRealtimeNanos())
                        )
                    }
                }
            }
            managerScope.launch {
                var previousSnapshotAt = SystemClock.elapsedRealtime()
                while (isActive) {
                    delay(PIPELINE_STATS_INTERVAL_MS)
                    val now = SystemClock.elapsedRealtime()
                    val stats = gamepadPipelineCounters.snapshotAndReset(now - previousSnapshotAt)
                    previousSnapshotAt = now
                    _gamepadPipelineStats.value = stats
                    if (stats.hasActivity) {
                        val message = "window=${stats.windowMillis}ms touch=${stats.touchSamples} " +
                            "gyro=${stats.gyroscopeSamples} state=${stats.stateSubmissions} " +
                            "tick=${stats.schedulerTicks} late=${stats.schedulerLateTicks} " +
                            "phaseReset=${stats.schedulerPhaseResets} " +
                            "maxLateUs=${stats.maxSchedulerLatenessMicros} " +
                            "urgentWake=${stats.urgentEdgeWakeups} " +
                            "urgentSent=${stats.urgentEdgeReports} " +
                            "scheduled=${stats.scheduledReports} " +
                            "attempt=${stats.sendAttempts} accepted=${stats.sendAccepted} " +
                            "rejected=${stats.sendRejected}"
                        Log.i(GAMEPAD_RATE_LOG_TAG, message)
                        DeveloperLogManager.log(GAMEPAD_RATE_LOG_TAG, message)
                    }
                }
            }
            checkBluetoothCapabilities()
            registerBondReceiver()
        } catch (e: Throwable) {
            Log.e("BluetoothKeyboard", "Error during init: ${e.message}", e)
            _serviceState.value = BluetoothState.ProfileNotSupported
            _statusMessage.value = "Bluetooth HID profile is not supported on this device firmware."
        }
    }

    fun checkBluetoothCapabilities() {
        if (bluetoothAdapter == null) {
            _serviceState.value = BluetoothState.Unsupported
            _statusMessage.value = "Bluetooth is not supported on this device's hardware."
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            _serviceState.value = BluetoothState.BluetoothOff
            _statusMessage.value = "Bluetooth is currently turned off. Please enable Bluetooth."
            hidDeviceProfile = null
            profileTransactions.cancel("Bluetooth was turned off")
            registrationController.onBluetoothUnavailable()
            return
        }

        // Check permissions on API 31+ (BLUETOOTH_CONNECT, BLUETOOTH_ADVERTISE, BLUETOOTH_SCAN)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val hasConnect = context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val hasAdvertise = context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_ADVERTISE) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val hasScan = context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!hasConnect || !hasAdvertise || !hasScan) {
                _serviceState.value = BluetoothState.PermissionRequired
                _statusMessage.value = "Bluetooth Connect, Advertise & Scan permissions are required."
                return
            }
        } else {
            // On Android 9 and 10 (API 28–30), ACCESS_FINE_LOCATION is required at runtime for
            // Bluetooth device scanning and HID profile operations. Without it, getProfileProxy
            // and startDiscovery may silently do nothing with no error in logcat.
            val hasLocation = context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                              context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!hasLocation) {
                _serviceState.value = BluetoothState.PermissionRequired
                _statusMessage.value = "Location permission is required on Android 10 and earlier to use Bluetooth."
                Log.w("BluetoothKeyboard", "Missing ACCESS_FINE_LOCATION on API ${Build.VERSION.SDK_INT} — Bluetooth HID will not work.")
                return
            }
        }

        updateBondedDevices()
        // Initialize HID Device Profile safely
        val hid = hidDeviceProfile
        if (hid == null) {
            initProfileListener()
        } else if (registrationState.value is HidRegistrationState.Failed) {
            updateRegistrationStatus(registrationState.value)
        } else if (activeProfile.value == null) {
            managerScope.launch { registrationController.ensureProfile(DEFAULT_PROFILE) }
        } else {
            // Already initialized and registered. Sync connection state.
            try {
                val connectedDevs = hid.connectedDevices
                if (!connectedDevs.isNullOrEmpty()) {
                    val activeDev = connectedDevs.first()
                    knownDevices[activeDev.address] = activeDev
                    if (manualConnectionGate.isAuthorized(activeDev.address)) {
                        registrationController.onConnectionStateChanged(
                            HidHostDevice(activeDev.address, activeDev.name),
                            HidConnectionCallbackState.CONNECTED
                        )
                        _connectedDevice.value = activeDev
                        val sessionToken = inputSessionGate.freeze()
                        clearLocalInputState()
                        activeProfile.value?.let { profile ->
                            managerScope.launch {
                                openConnectedInputSession(profile, activeDev, sessionToken)
                            }
                        }
                        lastConnectedDevice = activeDev
                        _serviceState.value = BluetoothState.Connected(activeDev.name ?: "Paired Host")
                        _statusMessage.value =
                            "Link established with '${activeDev.name ?: "Host"}'! Keyboard active."
                    } else {
                        rejectAutomaticConnection(activeDev, "Bluetooth state synchronization")
                    }
                } else {
                    _connectedDevice.value = null
                    _serviceState.value = BluetoothState.PairingMode(bluetoothAdapter.name ?: context.getString(R.string.app_name))
                    _statusMessage.value = "Bluke Bluetooth Deck is ready and advertising."
                }
            } catch (e: Exception) {
                Log.e("BluetoothKeyboard", "Error restoring connected devices", e)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun updateBondedDevices() {
        if (bluetoothAdapter != null && bluetoothAdapter.isEnabled) {
            try {
                _bondedDevices.value = bluetoothAdapter.bondedDevices.toList().also { devices ->
                    devices.forEach { knownDevices[it.address] = it }
                }
            } catch (e: Exception) {
                Log.e("BluetoothKeyboard", "Error listing bonded devices", e)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startScanning() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) return

        _scannedDevices.value = emptyList()

        if (!isReceiverRegistered) {
            try {
                val filter = IntentFilter().apply {
                    addAction(BluetoothDevice.ACTION_FOUND)
                    addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
                    addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(discoveryReceiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    context.registerReceiver(discoveryReceiver, filter)
                }
                isReceiverRegistered = true
            } catch (e: Exception) {
                Log.e("BluetoothKeyboard", "Error registering discovery receiver: ${e.message}", e)
                _statusMessage.value = "Failed to register scanner: ${e.localizedMessage}"
            }
        }

        try {
            if (bluetoothAdapter.isDiscovering) {
                bluetoothAdapter.cancelDiscovery()
            }
            val started = bluetoothAdapter.startDiscovery()
            if (started) {
                _isScanning.value = true
                _statusMessage.value = "Scanning for other Bluetooth hosts..."
            } else {
                _statusMessage.value = "Failed to start Bluetooth discovery scanning."
            }
        } catch (e: Exception) {
            Log.e("BluetoothKeyboard", "Error during discovery initialization", e)
            _statusMessage.value = "Scanning error: ${e.localizedMessage}"
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScanning() {
        if (bluetoothAdapter == null) return
        try {
            if (bluetoothAdapter.isDiscovering) {
                bluetoothAdapter.cancelDiscovery()
            }
        } catch (e: Exception) {
            Log.e("BluetoothKeyboard", "Error stopping discovery", e)
        }
        _isScanning.value = false
    }

    @SuppressLint("MissingPermission")
    fun pairDevice(device: BluetoothDevice) {
        stopScanning()
        val dName = device.name ?: device.address
        _statusMessage.value = "Requesting Bluetooth Pairing with '$dName'..."
        try {
            val success = device.createBond()
            if (success) {
                _statusMessage.value = "Pairing requested. Approve prompt on '$dName'."
            } else {
                _statusMessage.value = "Failed to start pairing request for '$dName'."
            }
        } catch (e: Exception) {
            Log.e("BluetoothKeyboard", "Error calling createBond", e)
            _statusMessage.value = "Pairing failed: ${e.localizedMessage}"
        }
    }

    fun savedProfileForDevice(address: String): HidOutputProfileId? =
        profilePreferenceStore.savedProfile(address)

    fun isProfileChoiceConfirmed(address: String): Boolean =
        profilePreferenceStore.isProfileChoiceConfirmed(address)

    fun clearSavedProfile(address: String) {
        profilePreferenceStore.clearForDevice(address)
        _profilePreferenceRevision.value += 1L
    }

    @SuppressLint("MissingPermission")
    fun connectDevice(
        device: BluetoothDevice,
        requestedProfile: HidOutputProfileId? = null,
        delayMs: Long = 0,
        confirmProfileChoice: Boolean = false
    ) {
        knownDevices[device.address] = device
        manualConnectionGate.authorize(device.address)
        stopScanning()
        val saved = profilePreferenceStore.savedProfile(device.address)
        val target = when {
            requestedProfile != null -> requestedProfile
            saved != null -> saved
            else -> DEFAULT_PROFILE
        }
        val deviceName = device.name ?: device.address
        val profileChoicePrecommitted = confirmProfileChoice
        if (profileChoicePrecommitted) {
            profilePreferenceStore.saveForDevice(device.address, target)
            profilePreferenceStore.markProfileChoiceConfirmed(device.address)
            _profilePreferenceRevision.value += 1L
        }

        if (
            _connectedDevice.value?.address.equals(device.address, ignoreCase = true) &&
            activeProfile.value == target
        ) {
            if (!profileChoicePrecommitted) {
                profilePreferenceStore.saveForDevice(device.address, target)
                _profilePreferenceRevision.value += 1L
            }
            return
        }

        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            pendingPairingProfiles[device.address] = target
            _statusMessage.value = "Credentials required. Pair '$deviceName' for ${profileLabel(target)}."
            pairDevice(device)
            return
        }
        if (hidDeviceProfile == null) {
            _statusMessage.value = "Waiting for Bluetooth HID service before connecting '$deviceName'."
            pendingProxyConnection = PendingProxyConnection(device, target, delayMs)
            initProfileListener()
            return
        }

        val previousProfile = activeProfile.value
        val previousDevice = _connectedDevice.value
        val operationKind = if (previousDevice != null && previousProfile != target) {
            HidProfileOperationKind.SWITCH_PROFILE
        } else {
            HidProfileOperationKind.CONNECT
        }
        val transaction = profileTransactions.begin(
            kind = operationKind,
            targetProfile = target,
            deviceAddress = device.address
        ) ?: return
        if (previousDevice != null) inputSessionGate.freeze()
        managerScope.launch {
            if (delayMs > 0) delay(delayMs)
            val result = registrationController.connect(
                HidHostDevice(device.address, device.name),
                target
            )
            completeProfileTransaction(
                transaction = transaction,
                result = result,
                previousProfile = previousProfile,
                previousDevice = previousDevice,
                retainTargetProfileOnConnectionFailure = profileChoicePrecommitted,
                commit = {
                    if (!profileChoicePrecommitted) {
                        profilePreferenceStore.saveForDevice(device.address, target)
                        _profilePreferenceRevision.value += 1L
                    }
                    lastConnectedDeviceAddress = device.address
                }
            )
        }
    }

    fun confirmDeviceProfile(device: BluetoothDevice, profile: HidOutputProfileId) {
        connectDevice(
            device = device,
            requestedProfile = profile,
            confirmProfileChoice = true
        )
    }

    fun disconnectDevice() {
        val device = _connectedDevice.value ?: return
        manualConnectionGate.revoke(device.address)
        val transaction = profileTransactions.begin(
            kind = HidProfileOperationKind.DISCONNECT,
            targetProfile = null,
            deviceAddress = device.address
        ) ?: return
        inputSessionGate.freeze()
        managerScope.launch {
            val result = registrationController.disconnect()
            if (result.isSuccess) {
                profileTransactions.completeSuccess(transaction, activeProfile.value) {
                    lastConnectedDeviceAddress = null
                    lastConnectedDevice = null
                }
            } else {
                val message = result.exceptionOrNull()?.message ?: "Disconnect failed"
                val fullMessage = "$message. Automatic reconnection remains disabled; tap Connect to try again."
                profileTransactions.completeFailure(transaction, fullMessage)
                _statusMessage.value = profileFailureMessage(fullMessage)
                updateBondedDevices()
            }
        }
    }

    private suspend fun completeProfileTransaction(
        transaction: HidProfileOperation,
        result: Result<Unit>,
        previousProfile: HidOutputProfileId?,
        previousDevice: BluetoothDevice?,
        retainTargetProfileOnConnectionFailure: Boolean = false,
        commit: () -> Unit
    ) {
        if (!profileTransactions.isCurrent(transaction)) return
        if (result.isSuccess && activeProfile.value == transaction.targetProfile) {
            if (profileTransactions.completeSuccess(transaction, activeProfile.value, commit)) return
        }

        val targetProfile = transaction.targetProfile
        if (
            retainTargetProfileOnConnectionFailure &&
            targetProfile != null &&
            registrationController.retainRegisteredProfileAfterConnectFailure(targetProfile)
        ) {
            if (profileTransactions.completeSuccess(transaction, activeProfile.value, commit)) {
                val reconnectName = safeDeviceName(previousDevice) ?: transaction.deviceAddress ?: "host"
                _statusMessage.value =
                    "${profileLabel(targetProfile)} selected. Reconnect '$reconnectName' to continue."
                updateBondedDevices()
                return
            }
        }

        val originalMessage = result.exceptionOrNull()?.message
            ?: "Profile callback did not confirm ${transaction.targetProfile}"
        val rollbackMessage = rollbackProfileTransaction(previousProfile, previousDevice)
        val retainedPreferenceMessage = if (retainTargetProfileOnConnectionFailure && targetProfile != null) {
            " ${profileLabel(targetProfile)} remains selected for the next connection."
        } else {
            ""
        }
        val message = if (rollbackMessage == null && previousProfile != null) {
            "$originalMessage. Previous runtime Profile was restored.$retainedPreferenceMessage"
        } else if (rollbackMessage == null) {
            "$originalMessage.$retainedPreferenceMessage"
        } else {
            "$originalMessage. Rollback also failed: $rollbackMessage"
        }
        profileTransactions.completeFailure(transaction, message)
        _statusMessage.value = profileFailureMessage(message)
    }

    private suspend fun rollbackProfileTransaction(
        previousProfile: HidOutputProfileId?,
        previousDevice: BluetoothDevice?
    ): String? {
        previousProfile ?: return null
        val rollback = if (previousDevice == null) {
            registrationController.ensureProfile(previousProfile)
        } else {
            knownDevices[previousDevice.address] = previousDevice
            registrationController.connect(
                HidHostDevice(previousDevice.address, safeDeviceName(previousDevice)),
                previousProfile
            )
        }
        if (rollback.isFailure) return rollback.exceptionOrNull()?.message ?: "unknown rollback error"
        if (
            previousDevice != null &&
            _connectedDevice.value?.address.equals(previousDevice.address, ignoreCase = true)
        ) {
            openConnectedInputSession(previousProfile, previousDevice)
        }
        return null
    }

    private fun initProfileListener() {
        _statusMessage.value = "Connecting to HID service profile proxy..."
        _serviceState.value = BluetoothState.ReadyDisconnected

        managerScope.launch {
            val hidDeviceProfileConst = 19 // BluetoothProfile.HID_DEVICE is 19
            var success = false
            for (attempt in 1..3) {
                try {
                    success = bluetoothAdapter?.getProfileProxy(
                        context,
                        profileListener,
                        hidDeviceProfileConst
                    ) ?: false
                    if (success) {
                        Log.d("BluetoothKeyboard", "getProfileProxy succeeded on attempt $attempt")
                        break
                    }
                } catch (e: Throwable) {
                    Log.w("BluetoothKeyboard", "Attempt $attempt calling getProfileProxy failed: ${e.message}")
                }
                if (attempt < 3) {
                    kotlinx.coroutines.delay(500)
                }
            }

            if (!success) {
                Log.e("BluetoothKeyboard", "getProfileProxy returned false after 3 attempts — HID Device profile absent on this firmware")
                _serviceState.value = BluetoothState.ProfileNotSupported
                _statusMessage.value = "Bluetooth HID Device profile is not supported on this device."
            }
        }
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        @SuppressLint("MissingPermission")
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                val hid = proxy as BluetoothHidDevice
                hidDeviceProfile = hid
                Log.d("BluetoothKeyboard", "HID Device profile proxy obtained — firmware supports HID peripheral role")
                try {
                    hid.connectedDevices.orEmpty().forEach { knownDevices[it.address] = it }
                } catch (e: Exception) {
                    Log.e("BluetoothKeyboard", "Error restoring connected devices", e)
                }
                managerScope.launch {
                    val pending = pendingProxyConnection
                    val selected = pending?.profile
                        ?: lastConnectedDeviceAddress?.let(profilePreferenceStore::savedProfile)
                        ?: DEFAULT_PROFILE
                    val registered = registrationController.ensureProfile(selected)
                    if (registered.isSuccess) {
                        if (pending != null) {
                            pendingProxyConnection = null
                            connectDevice(
                                pending.device,
                                pending.profile,
                                pending.delayMs
                            )
                        }
                    }
                }
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDeviceProfile = null
                profileTransactions.cancel("Android HID Device service disconnected")
                registrationController.onBluetoothUnavailable("Android HID Device service disconnected")
                _statusMessage.value = "HID Service Proxy disconnected. Rebinding..."
            }
        }
    }

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        @SuppressLint("MissingPermission")
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            super.onAppStatusChanged(pluggedDevice, registered)
            
            DeveloperLogManager.log("BluetoothKeyboard", "onAppStatusChanged: registered=$registered, device=${pluggedDevice?.address}")
            pluggedDevice?.let { knownDevices[it.address] = it }
            registrationController.onAppStatusChanged(registered)
            if (registered) {
                activeProfile.value?.let { profileId ->
                    spoofLocalDeviceClass(bluetoothAdapter, profileFor(profileId).classOfDevice)
                }
                updateBondedDevices()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            super.onConnectionStateChanged(device, state)
            knownDevices[device.address] = device
            val host = HidHostDevice(device.address, device.name)
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (!manualConnectionGate.isAuthorized(device.address)) {
                        rejectAutomaticConnection(device, "unsolicited host callback")
                        return
                    }
                    val sessionToken = inputSessionGate.freeze()
                    registrationController.onConnectionStateChanged(host, HidConnectionCallbackState.CONNECTED)
                    _connectedDevice.value = device
                    lastConnectedDevice = device
                    lastConnectedDeviceAddress = device.address
                    _serviceState.value = BluetoothState.Connected(device.name ?: "Paired Host")
                    clearLocalInputState()
                    activeProfile.value?.let { profile ->
                        managerScope.launch {
                            openConnectedInputSession(profile, device, sessionToken)
                        }
                    }
                    updateBondedDevices()
                    if (!audioProfilesDisconnectedForSession) {
                        audioProfilesDisconnectedForSession = true
                        disconnectAudioProfiles(device)
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    manualConnectionGate.revoke(device.address)
                    registrationController.onConnectionStateChanged(host, HidConnectionCallbackState.DISCONNECTED)
                    _connectedDevice.value = null
                    audioProfilesDisconnectedForSession = false
                    _serviceState.value = BluetoothState.PairingMode(bluetoothAdapter?.name ?: context.getString(R.string.app_name))
                    inputSessionGate.freeze()
                    clearLocalInputState()
                    updateBondedDevices()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            super.onSetReport(device, type, id, data)
            if (type == BluetoothHidDevice.REPORT_TYPE_OUTPUT) {
                routeOutputReport(id.toInt() and 0xFF, data)
            }
            try {
                hidDeviceProfile?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
            } catch (e: Exception) {
                Log.e("BluetoothKeyboard", "Failed to send reportError success: $e")
            }
        }

        override fun onInterruptData(device: BluetoothDevice, reportId: Byte, data: ByteArray) {
            super.onInterruptData(device, reportId, data)
            routeOutputReport(reportId.toInt() and 0xFF, data)
        }
    }

    @SuppressLint("MissingPermission")
    fun restartHidService() {
        if (hidDeviceProfile == null) {
            initProfileListener()
            return
        }
        val target = activeProfile.value ?: DEFAULT_PROFILE
        val previousProfile = activeProfile.value
        val previousDevice = _connectedDevice.value
        val transaction = profileTransactions.begin(
            kind = HidProfileOperationKind.RESTART,
            targetProfile = target,
            deviceAddress = previousDevice?.address
        ) ?: return
        if (previousDevice != null) inputSessionGate.freeze()
        managerScope.launch {
            val result = registrationController.restart(target)
            completeProfileTransaction(
                transaction,
                result,
                previousProfile,
                previousDevice,
                commit = {}
            )
        }
    }

    fun retryHidOperation() {
        val target = activeProfile.value ?: DEFAULT_PROFILE
        val previousProfile = activeProfile.value
        val previousDevice = _connectedDevice.value
        val transaction = profileTransactions.begin(
            kind = HidProfileOperationKind.RETRY,
            targetProfile = target,
            deviceAddress = previousDevice?.address
        ) ?: return
        if (previousDevice != null) inputSessionGate.freeze()
        managerScope.launch {
            val result = registrationController.retry()
            completeProfileTransaction(
                transaction,
                result,
                previousProfile,
                previousDevice,
                commit = {}
            )
        }
    }

    private fun profileFor(id: HidOutputProfileId): HidOutputProfile =
        requireNotNull(HidOutputProfiles.all.firstOrNull { it.id == id })

    private fun profileLabel(id: HidOutputProfileId): String = when (id) {
        HidOutputProfileId.JOYPAD_OS -> "Joypad OS"
        HidOutputProfileId.PC_DIRECT -> "PC Direct"
    }

    private fun updateRegistrationStatus(state: HidRegistrationState) {
        when (_serviceState.value) {
            BluetoothState.Unsupported,
            BluetoothState.PermissionRequired,
            BluetoothState.BluetoothOff,
            BluetoothState.ProfileNotSupported -> return
            else -> Unit
        }
        when (state) {
            is HidRegistrationState.Ready -> {
                _serviceState.value = BluetoothState.PairingMode(
                    safeAdapterName() ?: context.getString(R.string.app_name)
                )
            }
            is HidRegistrationState.Connected -> {
                _serviceState.value = BluetoothState.Connected(state.device.name ?: "Paired Host")
            }
            is HidRegistrationState.Failed -> _serviceState.value = BluetoothState.ReadyDisconnected
            else -> Unit
        }
        _statusMessage.value = when (state) {
            HidRegistrationState.Unregistered -> "HID Profile is not registered."
            is HidRegistrationState.Registering -> "Registering ${profileLabel(state.profile)} HID Profile..."
            is HidRegistrationState.Ready -> "${profileLabel(state.profile)} is registered and ready."
            is HidRegistrationState.Connecting ->
                "Connecting ${state.device.name ?: state.device.address} with ${profileLabel(state.profile)}..."
            is HidRegistrationState.Connected ->
                "Connected to ${state.device.name ?: state.device.address} with ${profileLabel(state.profile)}."
            is HidRegistrationState.Disconnecting ->
                "Disconnecting ${state.device.name ?: state.device.address} from ${profileLabel(state.profile)}..."
            is HidRegistrationState.Unregistering ->
                "Unregistering ${state.previousProfile?.let(::profileLabel) ?: "HID Profile"}..."
            is HidRegistrationState.Failed -> profileFailureMessage(state.message)
        }
    }

    private fun profileFailureMessage(message: String?): String =
        "HID operation failed: ${message ?: "unknown error"}. Retry HID service; if the host cached the wrong Profile, clear Bluetooth cache and pair again."

    @SuppressLint("MissingPermission")
    private fun rejectAutomaticConnection(device: BluetoothDevice, source: String) {
        manualConnectionGate.revoke(device.address)
        inputSessionGate.freeze()
        clearLocalInputState()
        if (_connectedDevice.value?.address.equals(device.address, ignoreCase = true)) {
            _connectedDevice.value = null
        }
        if (lastConnectedDevice?.address.equals(device.address, ignoreCase = true)) {
            lastConnectedDevice = null
        }
        _serviceState.value = BluetoothState.PairingMode(
            bluetoothAdapter?.name ?: context.getString(R.string.app_name)
        )
        _statusMessage.value =
            "Ignored an automatic connection from '${device.name ?: device.address}'. Tap Connect to allow it."
        val disconnected = runCatching { hidDeviceProfile?.disconnect(device) == true }.getOrDefault(false)
        Log.i(
            "BluetoothKeyboard",
            "Rejected automatic HID connection from ${device.address} ($source), disconnectAccepted=$disconnected"
        )
        updateBondedDevices()
    }

    private suspend fun prepareConnectedHostForDisconnect(
        profileId: HidOutputProfileId,
        host: HidHostDevice
    ) {
        inputSessionGate.freeze()
        clearLocalInputState()
        val device = knownDevices[host.address] ?: _connectedDevice.value
            ?.takeIf { it.address.equals(host.address, ignoreCase = true) }
            ?: throw HidOperationException(
                "neutral",
                "Cannot resolve connected host ${host.address} for neutral reports"
            )
        inputSessionGate.neutralizeAndKeepClosed(profileId) { reports ->
            sendNeutralBarrier(device, reports)
        }
    }

    private suspend fun openConnectedInputSession(
        profileId: HidOutputProfileId,
        device: BluetoothDevice,
        sessionToken: Long = inputSessionGate.freeze()
    ) {
        if (!_connectedDevice.value?.address.equals(device.address, ignoreCase = true)) return
        if (!inputSessionGate.isCurrent(sessionToken)) return
        clearLocalInputState()
        try {
            val opened = inputSessionGate.neutralizeAndOpen(
                profileId = profileId,
                sender = { reports -> sendNeutralBarrier(device, reports) },
                token = sessionToken,
                beforeOpen = {
                    gamepadScheduler.activate(profileId, SystemClock.elapsedRealtimeNanos())
                    _gamepadSessionGeneration.value += 1L
                }
            )
            if (!opened) {
                Log.d("BluetoothKeyboard", "Ignored stale input-session open for ${device.address}")
            }
        } catch (error: Throwable) {
            inputSessionGate.freeze()
            val message = error.message ?: "Unable to neutralize host input"
            Log.e("BluetoothKeyboard", message, error)
            _statusMessage.value = profileFailureMessage(
                "$message Input remains disabled to prevent stale host state"
            )
        }
    }

    private fun clearLocalInputState() {
        resetKeyboardState()
        resetGamepadOutput(sendNeutral = false)
        _inputSessionGeneration.value += 1L
    }

    private fun resetGamepadOutput(sendNeutral: Boolean) {
        val neutral = gamepadScheduler.deactivate()
        lastEnhancedReportAttemptNanos = null
        if (sendNeutral) dispatchGamepadReport(neutral)
        _gamepadSessionGeneration.value += 1L
    }

    private fun ensureEnhancedGamepadOutputTicker() {
        if (!_gamepadReportRate.value.usesEnhancedPipeline) return
        if (!enhancedTickerScheduled.compareAndSet(false, true)) return
        scheduleGamepadOutputTick(
            SystemClock.elapsedRealtimeNanos() + _gamepadReportRate.value.intervalNanos
        )
    }

    private fun scheduleGamepadOutputTick(deadlineNanos: Long) {
        if (reportSenderClosed) return
        val delayNanos = (deadlineNanos - SystemClock.elapsedRealtimeNanos()).coerceAtLeast(0L)
        try {
            reportExecutor.schedule(
                { runGamepadOutputTick(deadlineNanos) },
                delayNanos,
                TimeUnit.NANOSECONDS
            )
        } catch (_: RejectedExecutionException) {
            if (!reportSenderClosed) {
                Log.w("BluetoothKeyboard", "Unable to schedule gamepad output tick")
            }
        }
    }

    private fun runGamepadOutputTick(deadlineNanos: Long) {
        val reportRate = _gamepadReportRate.value
        if (reportSenderClosed || !reportRate.usesEnhancedPipeline) {
            updateReportThreadPriority(enhanced = false)
            enhancedTickerScheduled.set(false)
            return
        }
        updateReportThreadPriority(enhanced = true)
        val tickStartedAtNanos = SystemClock.elapsedRealtimeNanos()
        if (inputSessionGate.isOpen) {
            gamepadPipelineCounters.recordSchedulerTick(
                latenessNanos = (tickStartedAtNanos - deadlineNanos).coerceAtLeast(0L),
                lateThresholdNanos = reportRate.lateTickThresholdNanos
            )
            // Poll against the nominal deadline so ordinary executor jitter does not halve a tick.
            val report = gamepadScheduler.poll(deadlineNanos)
            if (report != null) {
                gamepadPipelineCounters.recordScheduledReport()
                sendGamepadReportImmediately(report)
            }
        }

        val tickCompletedAtNanos = SystemClock.elapsedRealtimeNanos()
        val pacing = GamepadOutputPacer.nextDeadline(
            previousDeadlineNanos = deadlineNanos,
            tickStartedAtNanos = tickStartedAtNanos,
            tickCompletedAtNanos = tickCompletedAtNanos,
            intervalNanos = reportRate.intervalNanos,
            catchUpToleranceNanos = reportRate.lateTickThresholdNanos
        )
        if (pacing.phaseReset) {
            gamepadPipelineCounters.recordSchedulerPhaseReset()
        }
        scheduleGamepadOutputTick(pacing.nextDeadlineNanos)
    }

    private fun scheduleUrgentGamepadEdgeDispatch() {
        if (!_gamepadReportRate.value.usesEnhancedPipeline) return
        if (!enhancedUrgentEdgeDispatchScheduled.compareAndSet(false, true)) return
        gamepadPipelineCounters.recordUrgentEdgeWakeup()
        try {
            reportExecutor.execute {
                try {
                    if (
                        !reportSenderClosed &&
                        _gamepadReportRate.value.usesEnhancedPipeline &&
                        inputSessionGate.isOpen
                    ) {
                        updateReportThreadPriority(enhanced = true)
                        val reportRate = _gamepadReportRate.value
                        val nowNanos = SystemClock.elapsedRealtimeNanos()
                        val report = if (
                            GamepadOutputPacer.canDispatchUrgentEdge(
                                lastActualSendNanos = lastEnhancedReportAttemptNanos,
                                nowNanos = nowNanos,
                                intervalNanos = reportRate.intervalNanos,
                                catchUpToleranceNanos = reportRate.lateTickThresholdNanos
                            )
                        ) {
                            gamepadScheduler.pollUrgentEdge(nowNanos)
                        } else {
                            null
                        }
                        if (report != null) {
                            gamepadPipelineCounters.recordUrgentEdgeReport()
                            gamepadPipelineCounters.recordScheduledReport()
                            sendGamepadReportImmediately(report)
                        }
                    }
                } finally {
                    enhancedUrgentEdgeDispatchScheduled.set(false)
                }
            }
        } catch (_: RejectedExecutionException) {
            enhancedUrgentEdgeDispatchScheduled.set(false)
            if (!reportSenderClosed) {
                Log.w("BluetoothKeyboard", "Unable to schedule urgent gamepad edge")
            }
        }
    }

    private fun updateReportThreadPriority(enhanced: Boolean) {
        if (enhancedReportThreadPriority == enhanced) return
        android.os.Process.setThreadPriority(
            if (enhanced) {
                android.os.Process.THREAD_PRIORITY_DISPLAY
            } else {
                android.os.Process.THREAD_PRIORITY_FOREGROUND
            }
        )
        enhancedReportThreadPriority = enhanced
    }

    @SuppressLint("MissingPermission")
    private fun sendGamepadReportImmediately(report: EncodedGamepadReport) {
        if (!inputSessionGate.isOpen) return
        val connected = registrationState.value as? HidRegistrationState.Connected ?: return
        if (connected.profile != report.profileId) return
        val profile = profileFor(report.profileId)
        if (report.reportId != profile.codec.reportId || report.payload.size != profile.codec.payloadLength) {
            Log.e(
                "BluetoothKeyboard",
                "Rejected mismatched gamepad report profile=${report.profileId} id=${report.reportId} length=${report.payload.size}"
            )
            return
        }
        val device = _connectedDevice.value ?: knownDevices[connected.device.address] ?: return
        val hid = hidDeviceProfile ?: return
        lastEnhancedReportAttemptNanos = SystemClock.elapsedRealtimeNanos()
        sendReportAttempt(hid, device, report.reportId, report.payload, countAsGamepad = true)
    }

    private fun dispatchGamepadReport(report: EncodedGamepadReport?) {
        report ?: return
        val connected = registrationState.value as? HidRegistrationState.Connected ?: return
        if (connected.profile != report.profileId) return
        val profile = profileFor(report.profileId)
        if (report.reportId != profile.codec.reportId || report.payload.size != profile.codec.payloadLength) {
            Log.e(
                "BluetoothKeyboard",
                "Rejected mismatched gamepad report profile=${report.profileId} id=${report.reportId} length=${report.payload.size}"
            )
            return
        }
        val device = _connectedDevice.value ?: knownDevices[connected.device.address] ?: return
        gamepadPipelineCounters.recordScheduledReport()
        submitReport(device, report.reportId, report.payload, countAsGamepad = true)
    }

    fun submitGamepadState(state: GamepadState, changeKind: GamepadStateChangeKind) {
        if (!inputSessionGate.isOpen) return
        gamepadPipelineCounters.recordStateSubmission()
        val immediate = gamepadScheduler.submit(
            state,
            changeKind,
            SystemClock.elapsedRealtimeNanos()
        )
        if (_gamepadReportRate.value == GamepadReportRate.HZ_125) {
            dispatchGamepadReport(immediate)
        } else if (
            changeKind == GamepadStateChangeKind.DIGITAL ||
            changeKind == GamepadStateChangeKind.DPAD
        ) {
            scheduleUrgentGamepadEdgeDispatch()
        }
    }

    fun setGamepadReportRate(rate: GamepadReportRate) {
        if (_gamepadReportRate.value == rate) return
        _gamepadReportRate.value = rate
        sharedPrefs.edit { putInt(GAMEPAD_REPORT_RATE_PREF, rate.hz) }
        configureGamepadScheduler(rate)
        if (rate.usesEnhancedPipeline) ensureEnhancedGamepadOutputTicker()
        DeveloperLogManager.log("BluetoothKeyboard", "Gamepad report path changed to ${rate.hz}Hz")
        restartHidService()
    }

    private fun configureGamepadScheduler(rate: GamepadReportRate) {
        val enhanced = rate.usesEnhancedPipeline
        gamepadScheduler.configureRuntimePath(
            minimumIntervalMs = rate.intervalMs,
            resendUnchangedWhileActive = false,
            preserveDigitalEdges = enhanced,
            dispatchChangesImmediately = !enhanced,
            keepaliveIntervalMs = if (enhanced) ENHANCED_GAMEPAD_KEEPALIVE_MS else null
        )
    }

    fun recordGamepadTouchSamples(count: Int) {
        gamepadPipelineCounters.recordTouchSamples(count)
    }

    fun recordGamepadGyroscopeSample() {
        gamepadPipelineCounters.recordGyroscopeSample()
    }

    private fun routeOutputReport(reportId: Int, data: ByteArray) {
        val profileId = activeProfile.value ?: return
        when (val result = profileFor(profileId).outputReportDecoder.decode(reportId, data)) {
            is HidOutputReportResult.KeyboardLeds -> {
                _numLockState.value = result.state.numLock
                _capsLockState.value = result.state.capsLock
                _scrollLockState.value = result.state.scrollLock
                Log.d("BluetoothKeyboard", "PC keyboard LEDs: ${result.state}")
            }
            is HidOutputReportResult.Unknown -> {
                val key = "$profileId:${result.reportId}:${result.payloadLength}"
                val now = System.currentTimeMillis()
                val previous = unknownOutputLogTimes[key]
                if (previous == null || now - previous >= UNKNOWN_OUTPUT_LOG_INTERVAL_MS) {
                    unknownOutputLogTimes[key] = now
                    Log.w(
                        "BluetoothKeyboard",
                        "Unknown output report profile=$profileId id=${result.reportId} length=${result.payloadLength}"
                    )
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun sendKey(keyCode: Int, isPress: Boolean) {
        if (!inputSessionGate.isOpen || activeProfile.value != HidOutputProfileId.PC_DIRECT) return
        val dev = _connectedDevice.value
        
        val report = ByteArray(8)
        synchronized(activeKeys) {
            // Update local HID state variables (Modifiers or standard key codes)
            if (keyCode in 0xE0..0xE7) {
                // It's a modifier key (Left Ctrl to Right GUI)
                val bitMask = 1 shl (keyCode - 0xE0)
                activeModifiers = if (isPress) {
                    activeModifiers or bitMask
                } else {
                    activeModifiers and bitMask.inv()
                }
            } else {
                // It's a standard key
                if (isPress) {
                    // Find empty slot (0x00) or check if already placed
                    var placed = false
                    for (j in 0 until 6) {
                        if (activeKeys[j] == keyCode.toByte()) {
                            placed = true
                            break
                        }
                    }
                    if (!placed) {
                        for (j in 0 until 6) {
                            if (activeKeys[j] == 0.toByte()) {
                                activeKeys[j] = keyCode.toByte()
                                break
                            }
                        }
                    }
                } else {
                    // Key release: remove from slots and shift left
                    for (j in 0 until 6) {
                        if (activeKeys[j] == keyCode.toByte()) {
                            activeKeys[j] = 0.toByte()
                        }
                    }
                    // Compact active keys
                    val compact = ByteArray(6)
                    var writeIdx = 0
                    for (j in 0 until 6) {
                        if (activeKeys[j] != 0.toByte()) {
                            compact[writeIdx++] = activeKeys[j]
                        }
                    }
                    compact.copyInto(activeKeys)
                }
            }

            // Package report: 8 bytes
            // byte 0: Modifiers
            // byte 1: Reserved (0x00)
            // bytes 2-7: Scancodes
            report[0] = activeModifiers.toByte()
            report[1] = 0x00.toByte()
            for (j in 0 until 6) {
                report[j + 2] = activeKeys[j]
            }
        }

        // Transmit HID report
        if (dev != null) {
            submitReport(dev, reportId, report)
        }
    }

    @SuppressLint("MissingPermission")
    fun sendMouseReport(buttons: Byte, x: Byte, y: Byte, wheel: Byte) {
        if (!inputSessionGate.isOpen || activeProfile.value != HidOutputProfileId.PC_DIRECT) return
        val dev = _connectedDevice.value
        if (dev != null) {
            val report = ByteArray(4)
            report[0] = buttons
            report[1] = x
            report[2] = y
            report[3] = wheel
            submitReport(dev, 2, report) // Mouse report ID is 2
        }
    }

    fun resetKeyboardState() {
        synchronized(activeKeys) {
            activeModifiers = 0
            activeKeys.fill(0)
        }
    }

    private fun spoofLocalDeviceClass(adapter: BluetoothAdapter?, classOfDevice: Int): Boolean {
        if (adapter == null) return false
        try {
            val setBluetoothClassMethod = BluetoothAdapter::class.java.getDeclaredMethod(
                "setBluetoothClass",
                Int::class.javaPrimitiveType
            )
            setBluetoothClassMethod.isAccessible = true
            val success = setBluetoothClassMethod.invoke(adapter, classOfDevice) as Boolean
            Log.d("BluetoothKeyboard", "Spoofed local device Class of Device to $classOfDevice, success=$success")
            return success
        } catch (e: Exception) {
            Log.e("BluetoothKeyboard", "Failed to spoof Class of Device via reflection", e)
            return false
        }
    }

    @SuppressLint("MissingPermission")
    private fun disconnectAudioProfiles(device: BluetoothDevice) {
        val adapter = bluetoothAdapter ?: return
        
        managerScope.launch {
            // Linux/Arch hosts often initiate A2DP audio connections asynchronously *after* HID connects.
            // We do 3 aggressive sweeps over 4 seconds to abort any incoming or established audio links.
            for (i in 0..2) {
                delay(if (i == 0) 500L else 1500L) // Sweeps at 0.5s, 2.0s, 3.5s
                
                adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                        try {
                            // Blindly invoke disconnect to abort even if it's currently in a 'Connecting' state
                            val disconnectMethod = proxy.javaClass.getMethod("disconnect", BluetoothDevice::class.java)
                            val success = disconnectMethod.invoke(proxy, device) as Boolean
                            Log.d("BluetoothKeyboard", "Sweep $i: Disconnected A2DP profile for host, success=$success")
                        } catch (e: Exception) {
                            Log.d("BluetoothKeyboard", "Sweep $i: No A2DP profile to disconnect or reflection failed.")
                        } finally {
                            adapter.closeProfileProxy(BluetoothProfile.A2DP, proxy)
                        }
                    }
                    override fun onServiceDisconnected(profile: Int) {}
                }, BluetoothProfile.A2DP)

                adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                        try {
                            val disconnectMethod = proxy.javaClass.getMethod("disconnect", BluetoothDevice::class.java)
                            val success = disconnectMethod.invoke(proxy, device) as Boolean
                            Log.d("BluetoothKeyboard", "Sweep $i: Disconnected Headset profile for host, success=$success")
                        } catch (e: Exception) {
                            Log.d("BluetoothKeyboard", "Sweep $i: No Headset profile to disconnect or reflection failed.")
                        } finally {
                            adapter.closeProfileProxy(BluetoothProfile.HEADSET, proxy)
                        }
                    }
                    override fun onServiceDisconnected(profile: Int) {}
                }, BluetoothProfile.HEADSET)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun close() {
        val closingDevice = _connectedDevice.value
        val closingProfile = activeProfile.value
        inputSessionGate.freeze()
        profileTransactions.cancel("Bluetooth manager closed")
        clearLocalInputState()
        if (closingDevice != null && closingProfile != null) {
            try {
                queueNeutralReports(
                    closingDevice,
                    HidNeutralReportPlan.forProfile(closingProfile)
                ).get(NEUTRAL_REPORT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (error: Throwable) {
                Log.w("BluetoothKeyboard", "Unable to flush neutral reports during close", error)
            }
        }
        registrationController.close()
        managerScope.cancel()
        stopScanning()
        if (isReceiverRegistered) {
            try {
                context.unregisterReceiver(discoveryReceiver)
            } catch (e: Exception) {
                Log.e("BluetoothKeyboard", "Error unregistering receiver", e)
            }
            isReceiverRegistered = false
        }
        if (isBondReceiverRegistered) {
            try {
                context.unregisterReceiver(bondStateReceiver)
            } catch (e: Exception) {
                Log.e("BluetoothKeyboard", "Error unregistering bond receiver", e)
            }
            isBondReceiverRegistered = false
        }
        reportSenderClosed = true
        reportExecutor.shutdown()
        try {
            if (!reportExecutor.awaitTermination(250L, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                reportExecutor.shutdownNow()
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            reportExecutor.shutdownNow()
        }
        val hid = hidDeviceProfile
        if (hid != null) {
            try {
                hid.unregisterApp()
            } catch (e: Exception) {
                Log.e("BluetoothKeyboard", "Error during app unregistration", e)
            }
        }
        try {
            bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid)
        } catch (e: Exception) {
            Log.e("BluetoothKeyboard", "Error closing profile proxy", e)
        }
        hidDeviceProfile = null
        lastConnectedDevice = null
        _connectedDevice.value = null
        executor.shutdownNow()
    }

    @SuppressLint("MissingPermission")
    fun cleanup() {
        close()
    }

    companion object {
        private const val GAMEPAD_REPORT_RATE_PREF = "gamepad_report_rate_hz"
        private const val PIPELINE_STATS_INTERVAL_MS = 1_000L
        private const val ENHANCED_GAMEPAD_KEEPALIVE_MS = 20L
        private const val GAMEPAD_RATE_LOG_TAG = "BlukeRate"
        private const val UNKNOWN_OUTPUT_LOG_INTERVAL_MS = 2_000L
        private const val NEUTRAL_REPORT_TIMEOUT_MS = 750L
        private val DEFAULT_PROFILE = HidOutputProfileId.PC_DIRECT
    }
}
