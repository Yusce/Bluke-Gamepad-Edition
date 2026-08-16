package dev.arnv.bluke.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.content.edit
import androidx.core.content.FileProvider
import dev.arnv.bluke.R
import dev.arnv.bluke.bluetooth.BluetoothKeyboardManager
import dev.arnv.bluke.gamepad.GamepadAutomatedTestTimeline
import dev.arnv.bluke.gamepad.GamepadReportRate
import dev.arnv.bluke.gamepad.GamepadState
import dev.arnv.bluke.gamepad.GamepadStateChangeKind
import dev.arnv.bluke.gamepad.LegacyGamepadStateAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.locks.LockSupport
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.milliseconds

private const val GAMEPAD_ASSISTED_PRESS_DURATION_MS = 5L
private const val GAMEPAD_TEST_PROGRESS_UPDATE_INTERVAL_MS = 50L
private const val GAMEPAD_TEST_SPIN_WINDOW_NANOS = 150_000L
private const val GAMEPAD_ASSISTED_PRESS_AMPLITUDE = 5
private const val GAMEPAD_PRESS_RELEASE_PRIMITIVE_SCALE = 1.0f
private const val GAMEPAD_PRESS_RELEASE_FALLBACK_DURATION_MS = 3L
private const val GAMEPAD_PRESS_RELEASE_FALLBACK_AMPLITUDE = 255

private suspend fun awaitGamepadTestDeadline(deadlineNanos: Long) {
    while (true) {
        currentCoroutineContext().ensureActive()
        val remainingNanos = deadlineNanos - SystemClock.elapsedRealtimeNanos()
        if (remainingNanos <= 0L) return
        if (remainingNanos > GAMEPAD_TEST_SPIN_WINDOW_NANOS) {
            LockSupport.parkNanos(remainingNanos - GAMEPAD_TEST_SPIN_WINDOW_NANOS)
        } else {
            while (SystemClock.elapsedRealtimeNanos() < deadlineNanos) {
                // Test-only short spin avoids rounding a 2 ms source cadence to UI timer ticks.
            }
            return
        }
    }
}

private const val PRIMARY_GAMEPAD_CONTROL_SIZE_DP = 134f
private const val PRIMARY_GAMEPAD_CONTROL_SPACING_DP = 16f
private const val CENTER_CLUSTER_VERTICAL_SPACING_DP = 12f
private const val STICK_CONTROL_AREA_SIZE_DP = PRIMARY_GAMEPAD_CONTROL_SIZE_DP
private const val STICK_BUTTON_SIZE_DP = 30f
private const val ANALOG_STICK_WELL_SIZE_DP = 90f
private const val ANALOG_STICK_VISUAL_OVERDRAW_DP = 3f
private const val ANALOG_STICK_EDIT_FRAME_INSET_DP =
    (STICK_CONTROL_AREA_SIZE_DP - ANALOG_STICK_WELL_SIZE_DP -
        ANALOG_STICK_VISUAL_OVERDRAW_DP * 2f) / 2f
private const val FACE_BUTTONS_EDIT_FRAME_INSET_DP = 3f
private const val DPAD_WELL_OUTER_INSET_FRACTION = 0.02f
private const val DPAD_EDIT_FRAME_INSET_DP =
    PRIMARY_GAMEPAD_CONTROL_SIZE_DP * DPAD_WELL_OUTER_INSET_FRACTION
private const val STICKPAD_BASE_WIDTH_DP = 220f
private const val STICKPAD_BASE_HEIGHT_DP = 126f
private const val STICKPAD_REFERENCE_CANVAS_WIDTH_DP = 800f
private const val STICKPAD_REFERENCE_CANVAS_HEIGHT_DP = 360f
private const val SHOULDER_BUTTON_BASE_WIDTH_DP = 120f
private const val BUMPER_BUTTON_BASE_HEIGHT_DP = 34f
private const val TRIGGER_BUTTON_BASE_HEIGHT_DP = 48f
private const val SHOULDER_BUTTON_VERTICAL_SPACING_DP = 4f
private const val SHOULDER_STACK_LAYOUT_HEIGHT_DP =
    TRIGGER_BUTTON_BASE_HEIGHT_DP + SHOULDER_BUTTON_VERTICAL_SPACING_DP +
        BUMPER_BUTTON_BASE_HEIGHT_DP
private const val DPAD_BODY_BAR_WIDTH_FRACTION = 0.28f
private const val DPAD_BODY_OUTER_INSET_FRACTION = 0.06f
private const val DPAD_CENTER_DEAD_ZONE_RADIUS_FRACTION = 0.08f
private val GamepadDpadTouchAssistMasks = intArrayOf(
    1,
    2,
    4,
    8,
    1 or 4,
    1 or 8,
    2 or 4,
    2 or 8
)
private const val XBOX_CONFIG_ID = "xbox_series"
private const val PS5_CONFIG_ID = "playstation_5"
private const val DEFAULT_XBOX_LAYOUT_ID = "default_xbox_series"
private const val DEFAULT_PS5_LAYOUT_ID = "default_playstation_5"
private const val GAMEPAD_LAYOUT_EXPORT_TYPE = "bluke_gamepad_layouts"
private const val GAMEPAD_LAYOUT_EXPORT_VERSION = 2
private const val GAMEPAD_LAYOUT_EXPORT_MIME_TYPE = "application/json"
private const val GAMEPAD_BUNDLED_LAYOUT_IMPORT_VERSION_PREF =
    "gamepad_bundled_layout_import_version"
private const val GAMEPAD_BUNDLED_LAYOUT_IMPORT_VERSION = 2
private const val GAMEPAD_BUNDLED_LAYOUT_ASSET =
    "layouts/ps5_stickpad.bluke-layouts.json"
private const val GAMEPAD_BUNDLED_LAYOUT_NAME = "PS5 Stickpad"
private const val GAMEPAD_BUNDLED_XBOX_LAYOUT_IMPORT_VERSION_PREF =
    "gamepad_bundled_xbox_layout_import_version"
private const val GAMEPAD_BUNDLED_XBOX_LAYOUT_IMPORT_VERSION = 1
private const val GAMEPAD_BUNDLED_XBOX_LAYOUT_ASSET =
    "layouts/xbox_stickpad.bluke-layouts.json"
private const val GAMEPAD_BUNDLED_XBOX_LAYOUT_NAME = "Xbox Stickpad"
private const val GAMEPAD_CONTROL_INSTANCES_SUFFIX = "control_instances_json"
private const val GAMEPAD_LAYOUT_SETTINGS_SUFFIX = "settings_json"
private const val GAMEPAD_CONTROL_INSTANCE_LIMIT = 8
private const val GAMEPAD_NEW_CONTROL_VERTICAL_OFFSET_DP = -80f
private const val GAMEPAD_ACTIVE_LAYOUT_PREF = "gamepad_active_layout_id"
private const val GAMEPAD_VIBRATION_ENABLED_PREF = "gamepad_vibration_enabled"
private const val GAMEPAD_L3_R3_TOGGLE_MODE_PREF = "gamepad_l3_r3_toggle_mode"
internal const val GAMEPAD_L3_R3_TOGGLE_MODE_DEFAULT = false
private const val GAMEPAD_STICK_CLICK_ENABLED_PREF = "gamepad_stick_click_enabled"
private const val GAMEPAD_FULL_STICK_OUTPUT_ENABLED_PREF = "gamepad_full_stick_output_enabled"
private const val GAMEPAD_TOUCH_ASSIST_ENABLED_PREF = "gamepad_touch_assist_enabled"
internal const val GAMEPAD_TOUCH_ASSIST_DEFAULT_ENABLED = true
private const val GAMEPAD_GYROSCOPE_ENABLED_PREF = "gamepad_gyroscope_enabled"
private const val GAMEPAD_GYROSCOPE_MODE_PREF = "gamepad_gyroscope_mode"
private const val GAMEPAD_GYROSCOPE_RIGHT_STICK_PREF = "gamepad_gyroscope_right_stick"
private const val GAMEPAD_GYROSCOPE_SENSITIVITY_PREF = "gamepad_gyroscope_sensitivity"
private const val GAMEPAD_GYROSCOPE_INVERT_HORIZONTAL_PREF = "gamepad_gyroscope_invert_horizontal"
private const val GAMEPAD_GYROSCOPE_INVERT_VERTICAL_PREF = "gamepad_gyroscope_invert_vertical"
private const val GAMEPAD_GYROSCOPE_JITTER_SUPPRESSION_PREF =
    "gamepad_gyroscope_jitter_suppression"
private const val GAMEPAD_SIMPLIFIED_CHINESE_ENABLED_PREF = "gamepad_simplified_chinese_enabled"
private const val GAMEPAD_SNAP_ALIGNMENT_ENABLED_PREF = "gamepad_snap_alignment_enabled"
internal const val GAMEPAD_REVERSE_LANDSCAPE_ENABLED_PREF = "gamepad_reverse_landscape_enabled"
private const val GAMEPAD_LAST_CONNECTED_DEVICE_PREF = "gamepad_last_connected_device_address"
private const val BLUETOOTH_KEYBOARD_PREFS = "bluetooth_keyboard_prefs"
private const val BLUETOOTH_LAST_CONNECTED_DEVICE_PREF = "last_connected_device_address"
private const val GAMEPAD_EDIT_TOP_MARGIN_DP = 4f
private const val GAMEPAD_SNAP_GESTURE_RESET_MS = 160L
private const val GAMEPAD_SNAP_GUIDE_THRESHOLD_DP = 1f
private const val GAMEPAD_MIN_EDIT_SCALE = 0.6f
private const val GAMEPAD_MAX_EDIT_SCALE = 1.8f
private const val SHOULDER_MIN_RESIZE_AXIS_SCALE = 0.25f
private const val SHOULDER_MAX_RESIZE_AXIS_SCALE = 4f
private const val GAMEPAD_RESIZE_DRAG_SENSITIVITY = 0.88f
private const val GAMEPAD_EDIT_HISTORY_LIMIT = 50
private const val GAMEPAD_EDIT_HISTORY_DEBOUNCE_MS = 180L
private const val SHOW_BLANK_LAYOUT_TEMPLATE_ENTRY = false
private val LayoutMenuBackdropColor = Color.Transparent
private val LayoutMenuSurfaceColor = Color(0xCC101114)
private val LayoutMenuButtonColor = Color(0xFF383A3E)
private val LayoutMenuButtonSelectedColor = Color(0xFF505257)
private val LayoutMenuButtonDisabledColor = Color(0xFF282A2E)
private val LayoutMenuLayoutRowColor = Color(0xFF242528)
private val LayoutMenuLayoutRowSelectedColor = Color(0xFF55585E)
private val LayoutDialogBackdropColor = Color(0xFF2A2B30).copy(alpha = 0.34f)
private val LayoutDialogSurfaceColor = Color(0xFF202127)
private val LayoutDeleteDialogSurfaceColor = Color(0xFF211B1D)
private val GamepadSnapGuideYellow = Color(0xFFFFD54F)
private val GamepadSnapGuideGreen = Color(0xFF54D96F)
private val GamepadSnapGuideRed = Color(0xFFE53935)

private const val GAMEPAD_MOTION_QUICK_MS = 100
private const val GAMEPAD_MOTION_STATE_MS = 150
private const val GAMEPAD_MOTION_EXIT_MS = 90
private const val GAMEPAD_MENU_PRESSED_SCALE = 1.035f
private const val GAMEPAD_CONTROL_PRESSED_SCALE = 1.02f
private const val GAMEPAD_PRESS_DAMPING_RATIO = 0.9f
private const val GAMEPAD_PRESS_STIFFNESS = 1400f
private const val GAMEPAD_RELEASE_DAMPING_RATIO = 0.72f
private const val GAMEPAD_RELEASE_STIFFNESS = 900f
private val GamepadStandardEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
private val GamepadEmphasizedDecelerateEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private val GamepadEmphasizedAccelerateEasing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

private fun <T> gamepadPressSpring(isPressed: Boolean): SpringSpec<T> = spring(
    dampingRatio = if (isPressed) GAMEPAD_PRESS_DAMPING_RATIO else GAMEPAD_RELEASE_DAMPING_RATIO,
    stiffness = if (isPressed) GAMEPAD_PRESS_STIFFNESS else GAMEPAD_RELEASE_STIFFNESS
)

private fun Modifier.gamepadPressScale(
    enabled: Boolean = true,
    pressedScale: Float = GAMEPAD_MENU_PRESSED_SCALE
): Modifier = composed {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (enabled && isPressed) pressedScale else 1f,
        animationSpec = gamepadPressSpring(isPressed = enabled && isPressed),
        label = "gamepadPressScale"
    )

    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .pointerInput(enabled) {
            if (!enabled) {
                isPressed = false
                return@pointerInput
            }
            awaitEachGesture {
                awaitFirstDown(
                    requireUnconsumed = false,
                    pass = PointerEventPass.Initial
                )
                isPressed = true
                try {
                    waitForUpOrCancellation(pass = PointerEventPass.Initial)
                } finally {
                    isPressed = false
                }
            }
        }
}

private enum class GamepadSnapGuideOrientation {
    Vertical,
    Horizontal
}

private enum class GamepadLayoutMenuPage {
    LAYOUTS,
    FEATURES,
    GYROSCOPE,
    BLANK,
    CONTROLS,
    SPECIAL_CONTROLS
}

private data class GamepadSnapGuide(
    val orientation: GamepadSnapGuideOrientation,
    val positionPx: Float,
    val color: Color
)

private data class GamepadSnapResult(
    val offsetX: Float,
    val offsetY: Float,
    val guides: List<GamepadSnapGuide>
)

private data class GamepadSnapController(
    val enabled: Boolean,
    val rootSize: IntSize,
    val componentBounds: MutableMap<Any, Rect>,
    val onGuidesChange: (List<GamepadSnapGuide>) -> Unit
)

private val LocalGamepadSnapController = staticCompositionLocalOf<GamepadSnapController?> { null }
private val LocalGamepadMenuControlScale = staticCompositionLocalOf { 1f }
private val LocalGamepadSimplifiedChineseEnabled = staticCompositionLocalOf { false }

private data class GamepadControlDeleteController(
    val selectedInstanceId: String?,
    val confirmingInstanceId: String?,
    val onSelect: (String) -> Unit,
    val onRequest: (String) -> Unit,
    val onConfirm: (String) -> Unit,
    val onClear: () -> Unit
)

private val LocalGamepadControlDeleteController =
    staticCompositionLocalOf<GamepadControlDeleteController?> { null }

internal data class GamepadTouchAssistTarget(
    val targetId: Any,
    val mappingId: Int,
    val bounds: Rect,
    val secondaryMappingId: Int? = null,
    val hitTest: ((Offset) -> Boolean)? = null,
    val isActionOnly: Boolean = false,
    val onPressAction: (() -> Unit)? = null
) {
    val mappingIds: List<Int>
        get() = if (isActionOnly) emptyList() else listOfNotNull(mappingId, secondaryMappingId)

    fun contains(position: Offset): Boolean = hitTest?.invoke(position) ?: bounds.contains(position)
}

internal data class GamepadTouchAssistActivePress(
    val sourceId: String,
    val target: GamepadTouchAssistTarget
)

internal class GamepadAssistedPressTracker {
    private val activeCounts = mutableMapOf<Any, Int>()
    private val activeTargetIds = mutableStateListOf<Any>()

    fun begin(targetId: Any) {
        val nextCount = (activeCounts[targetId] ?: 0) + 1
        activeCounts[targetId] = nextCount
        if (nextCount == 1) activeTargetIds.add(targetId)
    }

    fun end(targetId: Any) {
        val currentCount = activeCounts[targetId] ?: return
        if (currentCount <= 1) {
            activeCounts.remove(targetId)
            activeTargetIds.remove(targetId)
        } else {
            activeCounts[targetId] = currentCount - 1
        }
    }

    fun clear(targetId: Any) {
        activeCounts.remove(targetId)
        activeTargetIds.remove(targetId)
    }

    fun isPressed(targetId: Any): Boolean = targetId in activeTargetIds
}

internal class GamepadTouchAssistController {
    val targets = mutableMapOf<Any, GamepadTouchAssistTarget>()
    val exclusions = mutableMapOf<Any, Rect>()
    private val assistedPresses = GamepadAssistedPressTracker()
    private var sourceCounter = 0L
    var enabled by mutableStateOf(false)
    var onPress: (String, GamepadTouchAssistTarget) -> Unit = { _, _ -> }
    var onRelease: (String, GamepadTouchAssistTarget) -> Unit = { _, _ -> }

    fun canRoutePointer(position: Offset): Boolean =
        exclusions.values.none { it.contains(position) }

    fun findTargets(position: Offset): List<GamepadTouchAssistTarget> =
        targets.values
            .asSequence()
            .filter { it.contains(position) }
            .sortedBy { target ->
                target.distanceSquaredTo(position)
            }
            .toList()

    fun findTarget(position: Offset): GamepadTouchAssistTarget? = findTargets(position).firstOrNull()

    fun beginPress(target: GamepadTouchAssistTarget): GamepadTouchAssistActivePress {
        sourceCounter++
        val activePress = GamepadTouchAssistActivePress(
            sourceId = "touch_assist_$sourceCounter",
            target = target
        )
        assistedPresses.begin(target.targetId)
        onPress(activePress.sourceId, target)
        return activePress
    }

    fun endPress(activePress: GamepadTouchAssistActivePress) {
        assistedPresses.end(activePress.target.targetId)
        onRelease(activePress.sourceId, activePress.target)
    }

    fun updatePress(
        activePress: GamepadTouchAssistActivePress?,
        position: Offset
    ): GamepadTouchAssistActivePress? {
        val nextTarget = findTarget(position)
        if (activePress?.target?.targetId == nextTarget?.targetId) return activePress

        activePress?.let(::endPress)
        return nextTarget?.let(::beginPress)
    }

    fun updatePresses(
        activePresses: List<GamepadTouchAssistActivePress>,
        position: Offset
    ): List<GamepadTouchAssistActivePress> {
        val nextTargets = findTargets(position)
        val nextTargetIds = nextTargets.mapTo(mutableSetOf()) { it.targetId }
        val activeByTargetId = activePresses.associateBy { it.target.targetId }

        activePresses.forEach { activePress ->
            if (activePress.target.targetId !in nextTargetIds) endPress(activePress)
        }
        return nextTargets.map { target ->
            activeByTargetId[target.targetId] ?: beginPress(target)
        }
    }

    fun isAssistedPressed(targetId: Any): Boolean = assistedPresses.isPressed(targetId)

    fun unregisterTarget(targetId: Any) {
        targets.remove(targetId)
        assistedPresses.clear(targetId)
    }
}

private data class GamepadTouchAssistPointerState(
    val routable: Boolean,
    var activePresses: List<GamepadTouchAssistActivePress> = emptyList()
)

private data class GamepadAnalogSourceState(
    val x: Float,
    val y: Float
)

internal class GamepadDigitalInputAggregator {
    private val pressedSources = mutableMapOf<Int, MutableSet<String>>()
    private val dpadSources = mutableMapOf<String, Int>()
    private var latchedMask = 0

    fun press(sourceId: String, bitIndex: Int): Boolean =
        pressedSources.getOrPut(bitIndex) { mutableSetOf() }.add("$sourceId:$bitIndex")

    fun release(sourceId: String, bitIndex: Int): Boolean {
        val sources = pressedSources[bitIndex] ?: return false
        val removed = sources.remove("$sourceId:$bitIndex")
        if (sources.isEmpty()) pressedSources.remove(bitIndex)
        return removed
    }

    fun setLatched(bitIndex: Int, enabled: Boolean) {
        val bit = 1 shl bitIndex
        latchedMask = if (enabled) latchedMask or bit else latchedMask and bit.inv()
    }

    fun clearMapping(bitIndex: Int) {
        pressedSources.remove(bitIndex)
        setLatched(bitIndex, false)
    }

    fun setDpadSource(sourceId: String, mask: Int) {
        if (mask == 0) dpadSources.remove(sourceId) else dpadSources[sourceId] = mask
    }

    fun clearTransientSources() {
        pressedSources.clear()
        dpadSources.clear()
    }

    fun reset() {
        pressedSources.clear()
        dpadSources.clear()
        latchedMask = 0
    }

    fun currentMask(): Int {
        var heldMask = 0
        pressedSources.forEach { (bitIndex, sources) ->
            if (sources.isNotEmpty()) heldMask = heldMask or (1 shl bitIndex)
        }
        val dpadMask = dpadSources.values.fold(0) { aggregate, sourceMask ->
            aggregate or sourceMask
        }
        return heldMask or latchedMask or (dpadMask shl 12)
    }
}

internal class AveragedGamepadAnalogSources {
    private val sources = mutableMapOf<String, GamepadAnalogSourceState>()

    fun activate(sourceId: String) {
        sources[sourceId] = GamepadAnalogSourceState(0f, 0f)
    }

    fun move(sourceId: String, x: Float, y: Float) {
        val current = sources[sourceId] ?: return
        sources[sourceId] = current.copy(x = x, y = y)
    }

    fun deactivate(sourceId: String) {
        sources.remove(sourceId)
    }

    fun clear() {
        sources.clear()
    }

    fun current(): Pair<Float, Float> {
        var directionX = 0f
        var directionY = 0f
        var magnitudeSum = 0f
        var inputCount = 0

        sources.values.forEach { source ->
            val magnitude = sqrt(source.x * source.x + source.y * source.y)
            if (magnitude > 0f) {
                directionX += source.x / magnitude
                directionY += source.y / magnitude
                magnitudeSum += magnitude
                inputCount++
            }
        }
        if (inputCount == 0) return 0f to 0f

        val directionMagnitude = sqrt(directionX * directionX + directionY * directionY)
        if (directionMagnitude <= 0.000001f) return 0f to 0f

        val averageMagnitude = magnitudeSum / inputCount
        return (directionX / directionMagnitude * averageMagnitude) to
            (directionY / directionMagnitude * averageMagnitude)
    }
}

internal fun gamepadFullMagnitudeInput(x: Float, y: Float): Pair<Float, Float> {
    val magnitude = sqrt(x * x + y * y)
    return if (magnitude == 0f) {
        0f to 0f
    } else {
        x / magnitude to y / magnitude
    }
}

private enum class GamepadHaptic {
    AssistedPress,
    DirectPress,
    Release
}

private val LocalGamepadTouchAssistController =
    staticCompositionLocalOf<GamepadTouchAssistController?> { null }

private data object GamepadSharedPointerInputElement :
    ModifierNodeElement<GamepadSharedPointerInputNode>() {
    override fun create() = GamepadSharedPointerInputNode()

    override fun update(node: GamepadSharedPointerInputNode) = Unit

    override fun InspectorInfo.inspectableProperties() {
        name = "gamepadSharedPointerInput"
    }
}

private class GamepadSharedPointerInputNode : Modifier.Node(), PointerInputModifierNode {
    override fun onPointerEvent(
        pointerEvent: PointerEvent,
        pass: PointerEventPass,
        bounds: IntSize
    ) = Unit

    override fun onCancelPointerInput() = Unit

    override fun sharePointerInputWithSiblings(): Boolean = true
}

private fun Modifier.gamepadSharedPointerInput(): Modifier =
    this.then(GamepadSharedPointerInputElement)

private fun GamepadTouchAssistTarget.distanceSquaredTo(position: Offset): Float {
    val dx = position.x - bounds.center.x
    val dy = position.y - bounds.center.y
    return dx * dx + dy * dy
}

private fun transformedBoundsInRoot(
    coordinates: androidx.compose.ui.layout.LayoutCoordinates,
    localBounds: Rect = Rect(
        0f,
        0f,
        coordinates.size.width.toFloat(),
        coordinates.size.height.toFloat()
    )
): Rect {
    val corners = listOf(
        coordinates.localToRoot(Offset(localBounds.left, localBounds.top)),
        coordinates.localToRoot(Offset(localBounds.right, localBounds.top)),
        coordinates.localToRoot(Offset(localBounds.left, localBounds.bottom)),
        coordinates.localToRoot(Offset(localBounds.right, localBounds.bottom))
    )
    return Rect(
        left = corners.minOf { it.x },
        top = corners.minOf { it.y },
        right = corners.maxOf { it.x },
        bottom = corners.maxOf { it.y }
    )
}

private fun Modifier.gamepadTouchAssistTarget(
    mappingId: Int,
    targetId: Any
): Modifier = composed {
    val controller = LocalGamepadTouchAssistController.current
    DisposableEffect(controller, targetId) {
        onDispose { controller?.unregisterTarget(targetId) }
    }
    onGloballyPositioned { coordinates ->
        controller?.targets?.set(
            targetId,
            GamepadTouchAssistTarget(targetId, mappingId, transformedBoundsInRoot(coordinates))
        )
    }
}

private fun Modifier.gamepadTouchAssistExclusion(): Modifier = composed {
    val controller = LocalGamepadTouchAssistController.current
    val exclusionKey = remember { Any() }
    DisposableEffect(controller, exclusionKey) {
        onDispose { controller?.exclusions?.remove(exclusionKey) }
    }
    onGloballyPositioned { coordinates ->
        controller?.exclusions?.set(exclusionKey, transformedBoundsInRoot(coordinates))
    }
}

private fun Modifier.gamepadTouchAssistDpadTargets(targetIds: List<Any>): Modifier = composed {
    val controller = LocalGamepadTouchAssistController.current
    DisposableEffect(controller, targetIds) {
        onDispose { targetIds.forEach { controller?.unregisterTarget(it) } }
    }
    onGloballyPositioned { coordinates ->
        val width = coordinates.size.width.toFloat()
        val height = coordinates.size.height.toFloat()
        val totalSize = minOf(width, height)
        val boundsInRoot = transformedBoundsInRoot(coordinates)
        GamepadDpadTouchAssistMasks.forEachIndexed { index, directionMask ->
            val mappingIds = gamepadDpadMappingIds(directionMask)
            controller?.targets?.set(
                targetIds[index],
                GamepadTouchAssistTarget(
                    targetId = targetIds[index],
                    mappingId = mappingIds.first(),
                    bounds = boundsInRoot,
                    secondaryMappingId = mappingIds.getOrNull(1),
                    hitTest = { positionInRoot ->
                        val localX = (positionInRoot.x - boundsInRoot.left) /
                            boundsInRoot.width * width
                        val localY = (positionInRoot.y - boundsInRoot.top) /
                            boundsInRoot.height * height
                        gamepadDpadMaskAt(
                            x = localX,
                            y = localY,
                            totalSize = totalSize
                        ) == directionMask
                    }
                )
            )
        }
    }
}

private fun Modifier.gamepadTouchAssistRouter(
    controller: GamepadTouchAssistController
): Modifier = pointerInput(controller) {
    awaitPointerEventScope {
        val pointerStates = mutableMapOf<PointerId, GamepadTouchAssistPointerState>()
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { change ->
                    when {
                        !change.previousPressed && change.pressed -> {
                            val state = GamepadTouchAssistPointerState(
                                routable = controller.enabled &&
                                    controller.canRoutePointer(change.position)
                            )
                            pointerStates[change.id] = state
                            if (state.routable) {
                                state.activePresses = controller.updatePresses(
                                    emptyList(),
                                    change.position
                                )
                            }
                        }
                        change.pressed -> {
                            val state = pointerStates[change.id] ?: return@forEach
                            if (state.routable) {
                                state.activePresses = if (controller.enabled) {
                                    controller.updatePresses(state.activePresses, change.position)
                                } else {
                                    state.activePresses.forEach(controller::endPress)
                                    emptyList()
                                }
                            }
                        }
                        change.previousPressed -> {
                            val state = pointerStates.remove(change.id) ?: return@forEach
                            state.activePresses.forEach(controller::endPress)
                        }
                    }
                }
            }
        } finally {
            pointerStates.values.forEach { state ->
                state.activePresses.forEach(controller::endPress)
            }
            pointerStates.clear()
        }
    }
}

// ── Console configuration ──

private data class ButtonDef(
    val label: String,
    val mappingId: Int,
    val color: Color = Color(0xFF3A3D42)
)

private data class ConsoleConfig(
    val id: String,
    val name: String,
    val faceTop: ButtonDef,
    val faceRight: ButtonDef,
    val faceBottom: ButtonDef,
    val faceLeft: ButtonDef,
    val leftBumper: ButtonDef,
    val rightBumper: ButtonDef,
    val leftTrigger: ButtonDef,
    val rightTrigger: ButtonDef,
    val selectButton: ButtonDef,
    val startButton: ButtonDef,
    val guideButton: ButtonDef,
    val shareButton: ButtonDef = ButtonDef("SHARE", 17),
    val leftStickAboveDpad: Boolean = true,
    val hasTouchpad: Boolean = false,
    val touchpadMappingId: Int = -1
)

private val CONSOLES = listOf(
    ConsoleConfig(
        id = XBOX_CONFIG_ID,
        name = "Xbox Series",
        faceTop = ButtonDef("Y", 3, Color(0xFFFFCA28)),
        faceRight = ButtonDef("B", 1, Color(0xFFEF5350)),
        faceBottom = ButtonDef("A", 0, Color(0xFF66BB6A)),
        faceLeft = ButtonDef("X", 2, Color(0xFF42A5F5)),
        leftBumper = ButtonDef("LB", 4),
        rightBumper = ButtonDef("RB", 5),
        leftTrigger = ButtonDef("LT", 6),
        rightTrigger = ButtonDef("RT", 7),
        selectButton = ButtonDef("VIEW", 8),
        startButton = ButtonDef("MENU", 9),
        guideButton = ButtonDef("XBOX", 16, Color(0xFF2E7D32)),
        shareButton = ButtonDef("SHARE", 17),
        leftStickAboveDpad = true
    ),
    ConsoleConfig(
        id = PS5_CONFIG_ID,
        name = "PlayStation 5",
        faceTop = ButtonDef("△", 3, Color(0xFF4DB6AC)),
        faceRight = ButtonDef("◯", 1, Color(0xFFEF5350)),
        faceBottom = ButtonDef("✕", 0, Color(0xFF5C6BC0)),
        faceLeft = ButtonDef("☐", 2, Color(0xFFEC407A)),
        leftBumper = ButtonDef("L1", 4),
        rightBumper = ButtonDef("R1", 5),
        leftTrigger = ButtonDef("L2", 6),
        rightTrigger = ButtonDef("R2", 7),
        selectButton = ButtonDef("CREATE", 8),
        startButton = ButtonDef("OPTIONS", 9),
        guideButton = ButtonDef("PS", 16, Color(0xFF1565C0)),
        shareButton = ButtonDef("SHARE", 17),
        leftStickAboveDpad = false,
        hasTouchpad = true,
        touchpadMappingId = 18
    )
)

private data class GamepadLayoutProfile(
    val id: String,
    val configId: String,
    val name: String,
    val isDefault: Boolean
) {
    val storageKey: String
        get() = id
}

private fun Modifier.gamepadTouchAssistActionTarget(
    targetId: Any,
    onPress: () -> Unit
): Modifier = composed {
    val controller = LocalGamepadTouchAssistController.current
    val currentOnPress by rememberUpdatedState(onPress)
    DisposableEffect(controller, targetId) {
        onDispose { controller?.unregisterTarget(targetId) }
    }
    onGloballyPositioned { coordinates ->
        controller?.targets?.set(
            targetId,
            GamepadTouchAssistTarget(
                targetId = targetId,
                mappingId = 0,
                bounds = transformedBoundsInRoot(coordinates),
                isActionOnly = true,
                onPressAction = { currentOnPress() }
            )
        )
    }
}

internal data class GamepadLayoutSettings(
    val l3R3ToggleMode: Boolean = GAMEPAD_L3_R3_TOGGLE_MODE_DEFAULT,
    val stickClickEnabled: Boolean = true,
    val fullStickOutputEnabled: Boolean = false,
    val touchAssistEnabled: Boolean = GAMEPAD_TOUCH_ASSIST_DEFAULT_ENABLED,
    val gyroscopeEnabled: Boolean = false,
    val gyroscopeMode: GamepadGyroscopeMode = GamepadGyroscopeMode.ANGULAR_VELOCITY,
    val gyroscopeMappedToRightStick: Boolean = true,
    val gyroscopeSensitivity: Float = GAMEPAD_GYRO_DEFAULT_SENSITIVITY,
    val gyroscopeInvertHorizontal: Boolean = GAMEPAD_GYRO_DEFAULT_INVERT_HORIZONTAL,
    val gyroscopeInvertVertical: Boolean = GAMEPAD_GYRO_DEFAULT_INVERT_VERTICAL,
    val gyroscopeJitterSuppression: GamepadGyroJitterSuppression =
        GAMEPAD_GYRO_DEFAULT_JITTER_SUPPRESSION
)

internal data class ImportedGamepadLayout(
    val configId: String,
    val name: String,
    val values: Map<String, Float>,
    val controlInstances: List<GamepadControlInstance>,
    val settings: GamepadLayoutSettings
)

internal fun defaultGamepadLayoutSettings() = GamepadLayoutSettings()

private fun defaultLayoutIdFor(configId: String): String {
    return if (configId == PS5_CONFIG_ID) DEFAULT_PS5_LAYOUT_ID else DEFAULT_XBOX_LAYOUT_ID
}

private fun defaultLayoutNameFor(configId: String): String {
    return if (configId == PS5_CONFIG_ID) "PS5 Default" else "Xbox Default"
}

private fun copiedLayoutName(sourceName: String, existingNames: Set<String>): String {
    val baseName = "$sourceName Copy"
    if (baseName !in existingNames) return baseName
    var copyIndex = 2
    while ("$baseName $copyIndex" in existingNames) {
        copyIndex++
    }
    return "$baseName $copyIndex"
}

private fun profileIdsKey(configId: String) = "gamepad_layout_ids_$configId"
private fun profileNameKey(profileId: String) = "gamepad_layout_name_$profileId"
private fun gamepadLayoutSettingsKey(storageKey: String) =
    "${storageKey}_$GAMEPAD_LAYOUT_SETTINGS_SUFFIX"
private val layoutPreferenceSuffixes = listOf(
    "dpad_x",
    "dpad_y",
    "dpad_scale",
    "left_stick_x",
    "left_stick_y",
    "left_stick_scale",
    "l3_x",
    "l3_y",
    "l3_scale",
    "right_stick_x",
    "right_stick_y",
    "right_stick_scale",
    "r3_x",
    "r3_y",
    "r3_scale",
    "face_buttons_x",
    "face_buttons_y",
    "face_buttons_scale",
    "left_trigger_x",
    "left_trigger_y",
    "left_trigger_scale",
    "left_trigger_width_scale",
    "left_trigger_height_scale",
    "left_bumper_x",
    "left_bumper_y",
    "left_bumper_scale",
    "left_bumper_width_scale",
    "left_bumper_height_scale",
    "right_trigger_x",
    "right_trigger_y",
    "right_trigger_scale",
    "right_trigger_width_scale",
    "right_trigger_height_scale",
    "right_bumper_x",
    "right_bumper_y",
    "right_bumper_scale",
    "right_bumper_width_scale",
    "right_bumper_height_scale",
    "guide_x",
    "guide_y",
    "guide_scale",
    "select_x",
    "select_y",
    "select_scale",
    "share_x",
    "share_y",
    "share_scale",
    "start_x",
    "start_y",
    "start_scale"
)

private fun defaultLayoutValues(configId: String): Map<String, Float> {
    return if (configId == PS5_CONFIG_ID) {
        mapOf(
            "dpad_x" to 0f,
            "dpad_y" to 0f,
            "dpad_scale" to 1f,
            "left_stick_x" to 67f,
            "left_stick_y" to -6f,
            "left_stick_scale" to 1f,
            "l3_x" to 37f,
            "l3_y" to 128f,
            "l3_scale" to 1f,
            "right_stick_x" to -67f,
            "right_stick_y" to -6f,
            "right_stick_scale" to 1f,
            "r3_x" to 67f,
            "r3_y" to 128f,
            "r3_scale" to 1f,
            "face_buttons_x" to 0f,
            "face_buttons_y" to 0f,
            "face_buttons_scale" to 1f,
            "left_trigger_x" to 0f,
            "left_trigger_y" to -92f,
            "left_trigger_scale" to 1f,
            "left_trigger_width_scale" to 1f,
            "left_trigger_height_scale" to 1f,
            "left_bumper_x" to 0f,
            "left_bumper_y" to -86f,
            "left_bumper_scale" to 1f,
            "left_bumper_width_scale" to 1f,
            "left_bumper_height_scale" to 1f,
            "right_trigger_x" to 0f,
            "right_trigger_y" to -92f,
            "right_trigger_scale" to 1f,
            "right_trigger_width_scale" to 1f,
            "right_trigger_height_scale" to 1f,
            "right_bumper_x" to 0f,
            "right_bumper_y" to -86f,
            "right_bumper_scale" to 1f,
            "right_bumper_width_scale" to 1f,
            "right_bumper_height_scale" to 1f,
            "guide_x" to 0f,
            "guide_y" to -27f,
            "guide_scale" to 1f,
            "select_x" to 0f,
            "select_y" to 0f,
            "select_scale" to 1f,
            "share_x" to 0f,
            "share_y" to 0f,
            "share_scale" to 1f,
            "start_x" to 0f,
            "start_y" to 0f,
            "start_scale" to 1f
        )
    } else {
        mapOf(
            "dpad_x" to 67f,
            "dpad_y" to -6f,
            "dpad_scale" to 1f,
            "left_stick_x" to 0f,
            "left_stick_y" to 0f,
            "left_stick_scale" to 1f,
            "l3_x" to -30f,
            "l3_y" to 134f,
            "l3_scale" to 1f,
            "right_stick_x" to -67f,
            "right_stick_y" to -6f,
            "right_stick_scale" to 1f,
            "r3_x" to 67f,
            "r3_y" to 128f,
            "r3_scale" to 1f,
            "face_buttons_x" to 0f,
            "face_buttons_y" to 0f,
            "face_buttons_scale" to 1f,
            "left_trigger_x" to 0f,
            "left_trigger_y" to -92f,
            "left_trigger_scale" to 1f,
            "left_trigger_width_scale" to 1f,
            "left_trigger_height_scale" to 1f,
            "left_bumper_x" to 0f,
            "left_bumper_y" to -86f,
            "left_bumper_scale" to 1f,
            "left_bumper_width_scale" to 1f,
            "left_bumper_height_scale" to 1f,
            "right_trigger_x" to 0f,
            "right_trigger_y" to -92f,
            "right_trigger_scale" to 1f,
            "right_trigger_width_scale" to 1f,
            "right_trigger_height_scale" to 1f,
            "right_bumper_x" to 0f,
            "right_bumper_y" to -86f,
            "right_bumper_scale" to 1f,
            "right_bumper_width_scale" to 1f,
            "right_bumper_height_scale" to 1f,
            "guide_x" to 0f,
            "guide_y" to -27f,
            "guide_scale" to 1f,
            "select_x" to 0f,
            "select_y" to 0f,
            "select_scale" to 1f,
            "share_x" to 0f,
            "share_y" to 0f,
            "share_scale" to 1f,
            "start_x" to 0f,
            "start_y" to 0f,
            "start_scale" to 1f
        )
    }
}

private fun SharedPreferences.loadLayoutValues(storageKey: String, configId: String): Map<String, Float> {
    val defaults = defaultLayoutValues(configId)
    if (storageKey == defaultLayoutIdFor(configId)) {
        return defaults
    }
    return defaults.mapValues { (suffix, defaultValue) ->
        getFloat("${storageKey}_$suffix", defaultValue)
    }
}

private fun SharedPreferences.saveLayoutValues(storageKey: String, values: Map<String, Float>) {
    edit {
        layoutPreferenceSuffixes.forEach { suffix ->
            values[suffix]?.let { value -> putFloat("${storageKey}_$suffix", value) }
        }
    }
}

private fun SharedPreferences.loadGamepadControlInstances(
    storageKey: String,
    configId: String
): List<GamepadControlInstance> {
    if (storageKey == defaultLayoutIdFor(configId)) return defaultGamepadControlInstances()
    val stored = getString(gamepadControlInstancesKey(storageKey), null)
        ?: return defaultGamepadControlInstances()
    return runCatching { parseGamepadControlInstances(JSONArray(stored)) }
        .getOrElse { defaultGamepadControlInstances() }
}

private fun SharedPreferences.saveGamepadControlInstances(
    storageKey: String,
    instances: List<GamepadControlInstance>
) {
    edit {
        putString(
            gamepadControlInstancesKey(storageKey),
            gamepadControlInstancesToJson(instances).toString()
        )
    }
}

internal fun gamepadLayoutSettingsToJson(settings: GamepadLayoutSettings): JSONObject =
    JSONObject()
        .put("l3R3ToggleMode", settings.l3R3ToggleMode)
        .put("stickClickEnabled", settings.stickClickEnabled)
        .put("fullStickOutputEnabled", settings.fullStickOutputEnabled)
        .put("touchAssistEnabled", settings.touchAssistEnabled)
        .put(
            "gyroscope",
            JSONObject()
                .put("enabled", settings.gyroscopeEnabled)
                .put("mode", settings.gyroscopeMode.preferenceValue)
                .put("mappedToRightStick", settings.gyroscopeMappedToRightStick)
                .put("sensitivity", settings.gyroscopeSensitivity.toDouble())
                .put("invertHorizontal", settings.gyroscopeInvertHorizontal)
                .put("invertVertical", settings.gyroscopeInvertVertical)
                .put(
                    "jitterSuppression",
                    settings.gyroscopeJitterSuppression.preferenceValue
                )
        )

internal fun parseGamepadLayoutSettings(
    settingsJson: JSONObject?
): GamepadLayoutSettings {
    val defaults = defaultGamepadLayoutSettings()
    if (settingsJson == null) return defaults
    val gyroscopeJson = settingsJson.optJSONObject("gyroscope")
    return GamepadLayoutSettings(
        l3R3ToggleMode = settingsJson.optBoolean(
            "l3R3ToggleMode",
            defaults.l3R3ToggleMode
        ),
        stickClickEnabled = settingsJson.optBoolean(
            "stickClickEnabled",
            defaults.stickClickEnabled
        ),
        fullStickOutputEnabled = settingsJson.optBoolean(
            "fullStickOutputEnabled",
            defaults.fullStickOutputEnabled
        ),
        touchAssistEnabled = settingsJson.optBoolean(
            "touchAssistEnabled",
            defaults.touchAssistEnabled
        ),
        gyroscopeEnabled = gyroscopeJson?.optBoolean(
            "enabled",
            defaults.gyroscopeEnabled
        ) ?: defaults.gyroscopeEnabled,
        gyroscopeMode = GamepadGyroscopeMode.fromPreference(
            gyroscopeJson?.optInt(
                "mode",
                defaults.gyroscopeMode.preferenceValue
            ) ?: defaults.gyroscopeMode.preferenceValue
        ),
        gyroscopeMappedToRightStick = gyroscopeJson?.optBoolean(
            "mappedToRightStick",
            defaults.gyroscopeMappedToRightStick
        ) ?: defaults.gyroscopeMappedToRightStick,
        gyroscopeSensitivity = (gyroscopeJson?.optDouble(
            "sensitivity",
            defaults.gyroscopeSensitivity.toDouble()
        )?.toFloat() ?: defaults.gyroscopeSensitivity).coerceIn(
            GAMEPAD_GYRO_MIN_SENSITIVITY,
            GAMEPAD_GYRO_MAX_SENSITIVITY
        ),
        gyroscopeInvertHorizontal = gyroscopeJson?.optBoolean(
            "invertHorizontal",
            defaults.gyroscopeInvertHorizontal
        ) ?: defaults.gyroscopeInvertHorizontal,
        gyroscopeInvertVertical = gyroscopeJson?.optBoolean(
            "invertVertical",
            defaults.gyroscopeInvertVertical
        ) ?: defaults.gyroscopeInvertVertical,
        gyroscopeJitterSuppression = GamepadGyroJitterSuppression.fromPreference(
            gyroscopeJson?.optInt(
                "jitterSuppression",
                defaults.gyroscopeJitterSuppression.preferenceValue
            ) ?: defaults.gyroscopeJitterSuppression.preferenceValue
        )
    )
}

internal fun SharedPreferences.loadGamepadLayoutSettings(
    storageKey: String
): GamepadLayoutSettings {
    val stored = getString(gamepadLayoutSettingsKey(storageKey), null)
        ?: return defaultGamepadLayoutSettings()
    return runCatching { parseGamepadLayoutSettings(JSONObject(stored)) }
        .getOrElse { defaultGamepadLayoutSettings() }
}

private fun SharedPreferences.saveGamepadLayoutSettings(
    storageKey: String,
    settings: GamepadLayoutSettings
) {
    edit {
        putString(
            gamepadLayoutSettingsKey(storageKey),
            gamepadLayoutSettingsToJson(settings).toString()
        )
    }
}

private fun SharedPreferences.updateGamepadLayoutSettings(
    storageKey: String,
    transform: (GamepadLayoutSettings) -> GamepadLayoutSettings
) {
    saveGamepadLayoutSettings(storageKey, transform(loadGamepadLayoutSettings(storageKey)))
}

private fun normalizedLayoutConfigId(configId: String): String? {
    return when (configId) {
        XBOX_CONFIG_ID, PS5_CONFIG_ID -> configId
        DEFAULT_XBOX_LAYOUT_ID -> XBOX_CONFIG_ID
        DEFAULT_PS5_LAYOUT_ID -> PS5_CONFIG_ID
        else -> null
    }
}

private fun SharedPreferences.exportLayoutProfilesToJson(profiles: List<GamepadLayoutProfile>): String {
    val layouts = JSONArray()
    profiles.forEach { profile ->
        val values = JSONObject()
        loadLayoutValues(profile.storageKey, profile.configId).forEach { (suffix, value) ->
            values.put(suffix, value.toDouble())
        }
        layouts.put(
            JSONObject()
                .put("configId", profile.configId)
                .put("name", profile.name)
                .put("isDefault", profile.isDefault)
                .put("values", values)
                .put(
                    "settings",
                    gamepadLayoutSettingsToJson(loadGamepadLayoutSettings(profile.storageKey))
                )
                .put(
                    "controls",
                    gamepadControlInstancesToJson(
                        loadGamepadControlInstances(profile.storageKey, profile.configId)
                    )
                )
        )
    }
    return JSONObject()
        .put("type", GAMEPAD_LAYOUT_EXPORT_TYPE)
        .put("version", GAMEPAD_LAYOUT_EXPORT_VERSION)
        .put("layouts", layouts)
        .toString(2)
}

internal fun parseGamepadLayoutImportJson(json: String): List<ImportedGamepadLayout> {
    val trimmed = json.trim()
    if (trimmed.isBlank()) return emptyList()
    val layoutsJson = if (trimmed.startsWith("[")) {
        JSONArray(trimmed)
    } else {
        val root = JSONObject(trimmed)
        root.optJSONArray("layouts") ?: JSONArray().put(root)
    }
    val parsedLayouts = mutableListOf<ImportedGamepadLayout>()
    for (index in 0 until layoutsJson.length()) {
        val item = layoutsJson.optJSONObject(index) ?: continue
        val configId = normalizedLayoutConfigId(item.optString("configId", "")) ?: continue
        val fallbackName = if (configId == PS5_CONFIG_ID) "Imported PS Layout" else "Imported Xbox Layout"
        val name = item.optString("name", fallbackName).trim().ifBlank { fallbackName }
        val valuesJson = item.optJSONObject("values") ?: JSONObject()
        val defaults = defaultLayoutValues(configId)
        val values = defaults.mapValues { (suffix, defaultValue) ->
            if (valuesJson.has(suffix) && !valuesJson.isNull(suffix)) {
                valuesJson.optDouble(suffix, defaultValue.toDouble()).toFloat()
            } else {
                defaultValue
            }
        }
        val controlInstances = parseGamepadControlInstances(item.optJSONArray("controls"))
        val settings = parseGamepadLayoutSettings(item.optJSONObject("settings"))
        parsedLayouts += ImportedGamepadLayout(
            configId = configId,
            name = name,
            values = values,
            controlInstances = controlInstances,
            settings = settings
        )
    }
    return parsedLayouts
}

private fun SharedPreferences.importLayoutProfilesFromJson(json: String): List<GamepadLayoutProfile> {
    val parsedLayouts = parseGamepadLayoutImportJson(json)
    if (parsedLayouts.isEmpty()) return emptyList()
    val idsByConfig = mutableMapOf(
        XBOX_CONFIG_ID to getCustomProfileIds(XBOX_CONFIG_ID).toMutableList(),
        PS5_CONFIG_ID to getCustomProfileIds(PS5_CONFIG_ID).toMutableList()
    )
    val now = System.currentTimeMillis()
    val importedProfiles = mutableListOf<GamepadLayoutProfile>()
    edit {
        parsedLayouts.forEachIndexed { index, layout ->
            val id = "gamepad_layout_${layout.configId}_${now}_$index"
            idsByConfig.getValue(layout.configId).add(id)
            putString(profileNameKey(id), layout.name)
            layoutPreferenceSuffixes.forEach { suffix ->
                layout.values[suffix]?.let { value -> putFloat("${id}_$suffix", value) }
            }
            putString(
                gamepadControlInstancesKey(id),
                gamepadControlInstancesToJson(layout.controlInstances).toString()
            )
            putString(
                gamepadLayoutSettingsKey(id),
                gamepadLayoutSettingsToJson(layout.settings).toString()
            )
            importedProfiles += GamepadLayoutProfile(
                id = id,
                configId = layout.configId,
                name = layout.name,
                isDefault = false
            )
        }
        idsByConfig.forEach { (configId, ids) ->
            putString(profileIdsKey(configId), ids.distinct().joinToString("|"))
        }
    }
    return importedProfiles
}

internal fun ensureBundledGamepadLayoutsImported(
    context: Context,
    sharedPrefs: SharedPreferences
): Boolean {
    val importedVersion = sharedPrefs.getInt(GAMEPAD_BUNDLED_LAYOUT_IMPORT_VERSION_PREF, 0)
    if (importedVersion >= GAMEPAD_BUNDLED_LAYOUT_IMPORT_VERSION) {
        return false
    }

    val existingBundledProfile = sharedPrefs.loadLayoutProfiles(PS5_CONFIG_ID).firstOrNull { profile ->
        !profile.isDefault && profile.name == GAMEPAD_BUNDLED_LAYOUT_NAME
    }
    val bundledJson = runCatching {
        context.assets.open(GAMEPAD_BUNDLED_LAYOUT_ASSET).bufferedReader().use { it.readText() }
    }.getOrNull() ?: return false
    val bundledLayout = runCatching {
        parseGamepadLayoutImportJson(bundledJson).singleOrNull { layout ->
            layout.configId == PS5_CONFIG_ID && layout.name == GAMEPAD_BUNDLED_LAYOUT_NAME
        }
    }.getOrNull() ?: return false

    if (importedVersion > 0) {
        sharedPrefs.edit {
            existingBundledProfile?.let { profile ->
                putString(profileNameKey(profile.id), bundledLayout.name)
                layoutPreferenceSuffixes.forEach { suffix ->
                    bundledLayout.values[suffix]?.let { value ->
                        putFloat("${profile.storageKey}_$suffix", value)
                    }
                }
                putString(
                    gamepadControlInstancesKey(profile.storageKey),
                    gamepadControlInstancesToJson(bundledLayout.controlInstances).toString()
                )
                putString(
                    gamepadLayoutSettingsKey(profile.storageKey),
                    gamepadLayoutSettingsToJson(bundledLayout.settings).toString()
                )
            }
            putInt(
                GAMEPAD_BUNDLED_LAYOUT_IMPORT_VERSION_PREF,
                GAMEPAD_BUNDLED_LAYOUT_IMPORT_VERSION
            )
        }
        return existingBundledProfile != null
    }

    if (existingBundledProfile != null) {
        sharedPrefs.edit {
            putInt(
                GAMEPAD_BUNDLED_LAYOUT_IMPORT_VERSION_PREF,
                GAMEPAD_BUNDLED_LAYOUT_IMPORT_VERSION
            )
        }
        return false
    }

    val importedProfiles = sharedPrefs.importLayoutProfilesFromJson(bundledJson)
    if (importedProfiles.isEmpty()) return false

    sharedPrefs.edit {
        putInt(
            GAMEPAD_BUNDLED_LAYOUT_IMPORT_VERSION_PREF,
            GAMEPAD_BUNDLED_LAYOUT_IMPORT_VERSION
        )
    }
    return true
}

internal fun ensureBundledXboxGamepadLayoutImported(
    context: Context,
    sharedPrefs: SharedPreferences
): Boolean {
    val importedVersion = sharedPrefs.getInt(GAMEPAD_BUNDLED_XBOX_LAYOUT_IMPORT_VERSION_PREF, 0)
    if (importedVersion >= GAMEPAD_BUNDLED_XBOX_LAYOUT_IMPORT_VERSION) {
        return false
    }

    val existingBundledProfile = sharedPrefs.loadLayoutProfiles(XBOX_CONFIG_ID).firstOrNull { profile ->
        !profile.isDefault && profile.name == GAMEPAD_BUNDLED_XBOX_LAYOUT_NAME
    }
    val bundledJson = runCatching {
        context.assets.open(GAMEPAD_BUNDLED_XBOX_LAYOUT_ASSET).bufferedReader().use { it.readText() }
    }.getOrNull() ?: return false
    val bundledLayout = runCatching {
        parseGamepadLayoutImportJson(bundledJson).singleOrNull { layout ->
            layout.configId == XBOX_CONFIG_ID && layout.name == GAMEPAD_BUNDLED_XBOX_LAYOUT_NAME
        }
    }.getOrNull() ?: return false

    if (importedVersion > 0) {
        sharedPrefs.edit {
            existingBundledProfile?.let { profile ->
                putString(profileNameKey(profile.id), bundledLayout.name)
                layoutPreferenceSuffixes.forEach { suffix ->
                    bundledLayout.values[suffix]?.let { value ->
                        putFloat("${profile.storageKey}_$suffix", value)
                    }
                }
                putString(
                    gamepadControlInstancesKey(profile.storageKey),
                    gamepadControlInstancesToJson(bundledLayout.controlInstances).toString()
                )
                putString(
                    gamepadLayoutSettingsKey(profile.storageKey),
                    gamepadLayoutSettingsToJson(bundledLayout.settings).toString()
                )
            }
            putInt(
                GAMEPAD_BUNDLED_XBOX_LAYOUT_IMPORT_VERSION_PREF,
                GAMEPAD_BUNDLED_XBOX_LAYOUT_IMPORT_VERSION
            )
        }
        return existingBundledProfile != null
    }

    if (existingBundledProfile != null) {
        sharedPrefs.edit {
            putInt(
                GAMEPAD_BUNDLED_XBOX_LAYOUT_IMPORT_VERSION_PREF,
                GAMEPAD_BUNDLED_XBOX_LAYOUT_IMPORT_VERSION
            )
        }
        return false
    }

    val importedProfiles = sharedPrefs.importLayoutProfilesFromJson(bundledJson)
    if (importedProfiles.isEmpty()) return false

    sharedPrefs.edit {
        putInt(
            GAMEPAD_BUNDLED_XBOX_LAYOUT_IMPORT_VERSION_PREF,
            GAMEPAD_BUNDLED_XBOX_LAYOUT_IMPORT_VERSION
        )
    }
    return true
}

private fun gamepadLayoutExportFileName(profiles: List<GamepadLayoutProfile>): String {
    val baseName = if (profiles.size == 1) {
        profiles.first().name
    } else {
        "Bluke Layouts ${profiles.size}"
    }
    val safeName = baseName
        .replace(Regex("[\\\\/:*?\"<>|]+"), "_")
        .trim()
        .take(48)
        .ifBlank { "Bluke Layouts" }
    return "$safeName.bluke-layouts.json"
}

private fun gamepadLayoutShareIntent(context: Context, file: File): Intent {
    val contentUri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file
    )
    return gamepadLayoutShareIntent(contentUri, file.name)
}

internal fun gamepadLayoutShareIntent(contentUri: Uri, fileName: String): Intent {
    return Intent(Intent.ACTION_SEND).apply {
        type = GAMEPAD_LAYOUT_EXPORT_MIME_TYPE
        putExtra(Intent.EXTRA_STREAM, contentUri)
        putExtra(Intent.EXTRA_SUBJECT, fileName)
        clipData = ClipData.newRawUri(fileName, contentUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

private fun SharedPreferences.getCustomProfileIds(configId: String): List<String> {
    return getString(profileIdsKey(configId), "")
        .orEmpty()
        .split("|")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
}

private fun SharedPreferences.saveCustomProfileIds(configId: String, ids: List<String>) {
    edit { putString(profileIdsKey(configId), ids.distinct().joinToString("|")) }
}

private fun SharedPreferences.loadLayoutProfiles(configId: String): List<GamepadLayoutProfile> {
    val defaultProfile = GamepadLayoutProfile(
        id = defaultLayoutIdFor(configId),
        configId = configId,
        name = defaultLayoutNameFor(configId),
        isDefault = true
    )
    val customProfiles = getCustomProfileIds(configId).mapIndexed { index, id ->
        GamepadLayoutProfile(
            id = id,
            configId = configId,
            name = getString(profileNameKey(id), null)?.takeIf { it.isNotBlank() }
                ?: "Layout ${index + 1}",
            isDefault = false
        )
    }
    return listOf(defaultProfile) + customProfiles
}

private fun SharedPreferences.resolveLayoutProfile(profileId: String): GamepadLayoutProfile? {
    return when (profileId) {
        DEFAULT_XBOX_LAYOUT_ID -> loadLayoutProfiles(XBOX_CONFIG_ID).first()
        DEFAULT_PS5_LAYOUT_ID -> loadLayoutProfiles(PS5_CONFIG_ID).first()
        else -> {
            val configId = when {
                getCustomProfileIds(XBOX_CONFIG_ID).contains(profileId) -> XBOX_CONFIG_ID
                getCustomProfileIds(PS5_CONFIG_ID).contains(profileId) -> PS5_CONFIG_ID
                else -> return null
            }
            loadLayoutProfiles(configId).firstOrNull { it.id == profileId }
        }
    }
}

// ── Main View ──

@SuppressLint("MissingPermission")
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun GamepadView(
    btManager: BluetoothKeyboardManager,
    onClose: () -> Unit,
    launchMode: Int,
    onModeChange: (Int) -> Unit,
    sharedPrefs: SharedPreferences,
    @Suppress("UNUSED_PARAMETER") caseBrush: Brush
) {
    val context = LocalContext.current
    val view = LocalView.current
    val bluetoothPrefs = remember(context) {
        context.getSharedPreferences(BLUETOOTH_KEYBOARD_PREFS, Context.MODE_PRIVATE)
    }
    val scope = rememberCoroutineScope()

    remember(sharedPrefs) {
        ensureBundledGamepadLayoutsImported(context.applicationContext, sharedPrefs)
        ensureBundledXboxGamepadLayoutImported(context.applicationContext, sharedPrefs)
    }

    var selectedLayoutId by rememberSaveable {
        mutableStateOf(sharedPrefs.getString(GAMEPAD_ACTIVE_LAYOUT_PREF, DEFAULT_XBOX_LAYOUT_ID) ?: DEFAULT_XBOX_LAYOUT_ID)
    }
    var layoutRevision by remember { mutableIntStateOf(0) }
    var showLayoutMenu by rememberSaveable { mutableStateOf(false) }
    var showFeatureListPage by rememberSaveable { mutableStateOf(false) }
    var showGyroscopeMappingPage by rememberSaveable { mutableStateOf(false) }
    var showGyroscopeCalibration by rememberSaveable { mutableStateOf(false) }
    var showBlankListPage by rememberSaveable { mutableStateOf(false) }
    var showSpecialControlListPage by rememberSaveable { mutableStateOf(false) }
    var selectedControlTypeId by rememberSaveable { mutableStateOf<String?>(null) }
    var renameTargetId by rememberSaveable { mutableStateOf<String?>(null) }
    var renameText by rememberSaveable { mutableStateOf("") }
    var showLayoutPastePanel by remember { mutableStateOf(false) }
    var layoutPasteText by remember { mutableStateOf("") }
    var showLayoutPasteError by remember { mutableStateOf(false) }
    var deleteTargetId by rememberSaveable { mutableStateOf<String?>(null) }
    var multiSelectedLayoutIds by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var confirmingSelectedDelete by rememberSaveable { mutableStateOf(false) }
    var confirmingControlDeleteInstanceId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedEditControlInstanceId by rememberSaveable { mutableStateOf<String?>(null) }

    val activeLayout = remember(selectedLayoutId, layoutRevision) {
        sharedPrefs.resolveLayoutProfile(selectedLayoutId)
            ?: sharedPrefs.loadLayoutProfiles(XBOX_CONFIG_ID).first()
    }
    val config = CONSOLES.first { it.id == activeLayout.configId }
    val layoutStorageKey = activeLayout.storageKey
    val activeLayoutSettings = remember(layoutStorageKey) {
        sharedPrefs.loadGamepadLayoutSettings(layoutStorageKey)
    }
    val isDefaultLayout = activeLayout.isDefault
    val canEditLayout = !isDefaultLayout
    var controlInstances by remember(layoutStorageKey, isDefaultLayout) {
        mutableStateOf(
            sharedPrefs.loadGamepadControlInstances(layoutStorageKey, activeLayout.configId)
        )
    }
    val xboxLayoutProfiles = remember(layoutRevision) { sharedPrefs.loadLayoutProfiles(XBOX_CONFIG_ID) }
    val psLayoutProfiles = remember(layoutRevision) { sharedPrefs.loadLayoutProfiles(PS5_CONFIG_ID) }
    val activeCustomLayoutIds = remember(activeLayout.configId, layoutRevision) {
        sharedPrefs.getCustomProfileIds(activeLayout.configId)
    }
    val activeCustomLayoutIndex = activeCustomLayoutIds.indexOf(activeLayout.id)
    val canMoveActiveLayoutUp = !activeLayout.isDefault && activeCustomLayoutIndex in 0 until activeCustomLayoutIds.lastIndex
    val canMoveActiveLayoutDown = !activeLayout.isDefault && activeCustomLayoutIndex > 0

    var isEditMode by rememberSaveable { mutableStateOf(false) }
    var editBaselineValues by remember { mutableStateOf<Map<String, Float>?>(null) }
    var editBaselineControlInstances by remember {
        mutableStateOf<List<GamepadControlInstance>?>(null)
    }
    var editUndoHistory by remember(layoutStorageKey) {
        mutableStateOf<List<GamepadLayoutEditSnapshot>>(emptyList())
    }
    var editRedoHistory by remember(layoutStorageKey) {
        mutableStateOf<List<GamepadLayoutEditSnapshot>>(emptyList())
    }
    var editHistoryCurrent by remember(layoutStorageKey) {
        mutableStateOf<GamepadLayoutEditSnapshot?>(null)
    }
    var pendingUnsavedDiscardActionKey by rememberSaveable { mutableStateOf<String?>(null) }
    var unsavedWarningPulse by rememberSaveable { mutableIntStateOf(0) }
    var snapAlignmentEnabled by rememberSaveable {
        mutableStateOf(sharedPrefs.getBoolean(GAMEPAD_SNAP_ALIGNMENT_ENABLED_PREF, true))
    }
    var gamepadRootSize by remember { mutableStateOf(IntSize.Zero) }
    var snapGuides by remember { mutableStateOf<List<GamepadSnapGuide>>(emptyList()) }
    val editableComponentBounds = remember { mutableStateMapOf<Any, Rect>() }
    val snapController = remember(snapAlignmentEnabled, gamepadRootSize, editableComponentBounds) {
        GamepadSnapController(
            enabled = snapAlignmentEnabled,
            rootSize = gamepadRootSize,
            componentBounds = editableComponentBounds,
            onGuidesChange = { guides -> snapGuides = guides }
        )
    }

    LaunchedEffect(isEditMode, snapAlignmentEnabled, layoutStorageKey) {
        if (!isEditMode || !snapAlignmentEnabled) {
            snapGuides = emptyList()
        }
        if (!isEditMode) {
            editableComponentBounds.clear()
        }
    }

    LaunchedEffect(snapGuides) {
        if (snapGuides.isNotEmpty()) {
            delay(900L.milliseconds)
            snapGuides = emptyList()
        }
    }
    val isLayoutModalOpen =
        showLayoutMenu ||
            renameTargetId != null ||
            showLayoutPastePanel ||
            showGyroscopeCalibration

    LaunchedEffect(multiSelectedLayoutIds) {
        confirmingSelectedDelete = false
    }

    LaunchedEffect(activeLayout.id) {
        if (selectedLayoutId != activeLayout.id) {
            selectedLayoutId = activeLayout.id
        }
        sharedPrefs.edit { putString(GAMEPAD_ACTIVE_LAYOUT_PREF, activeLayout.id) }
        if (activeLayout.isDefault) {
            isEditMode = false
            editBaselineValues = null
            editBaselineControlInstances = null
        }
        selectedControlTypeId = null
    }

    var isVibrationEnabled by rememberSaveable {
        mutableStateOf(sharedPrefs.getBoolean(GAMEPAD_VIBRATION_ENABLED_PREF, true))
    }

    LaunchedEffect(isEditMode, showLayoutMenu, layoutStorageKey, controlInstances) {
        val confirmingId = confirmingControlDeleteInstanceId
        val selectedInstanceId = selectedEditControlInstanceId
        if (
            !isEditMode ||
            showLayoutMenu ||
            confirmingId != null && controlInstances.none { it.id == confirmingId }
        ) {
            confirmingControlDeleteInstanceId = null
        }
        if (
            !isEditMode ||
            showLayoutMenu ||
            selectedInstanceId != null && controlInstances.none { it.id == selectedInstanceId }
        ) {
            selectedEditControlInstanceId = null
        }
    }
    var isL3R3ToggleMode by rememberSaveable(layoutStorageKey) {
        mutableStateOf(activeLayoutSettings.l3R3ToggleMode)
    }
    var isStickClickEnabled by rememberSaveable(layoutStorageKey) {
        mutableStateOf(activeLayoutSettings.stickClickEnabled)
    }
    var isFullStickOutputEnabled by rememberSaveable(layoutStorageKey) {
        mutableStateOf(activeLayoutSettings.fullStickOutputEnabled)
    }
    var isTouchAssistEnabled by rememberSaveable(layoutStorageKey) {
        mutableStateOf(activeLayoutSettings.touchAssistEnabled)
    }
    val sensorManager = remember(context) {
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    }
    val gamepadGyroscope = remember(sensorManager) {
        sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    }
    val gamepadTiltSensor = remember(sensorManager) {
        sensorManager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    }
    var isGamepadGyroscopeEnabled by rememberSaveable(layoutStorageKey) {
        mutableStateOf(activeLayoutSettings.gyroscopeEnabled)
    }
    var gamepadGyroscopeMode by rememberSaveable(layoutStorageKey) {
        mutableStateOf(activeLayoutSettings.gyroscopeMode)
    }
    val activeGamepadMotionSensor = when (gamepadGyroscopeMode) {
        GamepadGyroscopeMode.ANGULAR_VELOCITY -> gamepadGyroscope
        GamepadGyroscopeMode.TILT -> gamepadTiltSensor
    }
    val isGamepadGyroscopeAvailable = activeGamepadMotionSensor != null
    var isGamepadGyroscopeMappedToRightStick by rememberSaveable(layoutStorageKey) {
        mutableStateOf(activeLayoutSettings.gyroscopeMappedToRightStick)
    }
    var gamepadGyroscopeSensitivity by rememberSaveable(layoutStorageKey) {
        mutableFloatStateOf(activeLayoutSettings.gyroscopeSensitivity)
    }
    var isGamepadGyroscopeHorizontalInverted by rememberSaveable(layoutStorageKey) {
        mutableStateOf(activeLayoutSettings.gyroscopeInvertHorizontal)
    }
    var isGamepadGyroscopeVerticalInverted by rememberSaveable(layoutStorageKey) {
        mutableStateOf(activeLayoutSettings.gyroscopeInvertVertical)
    }
    var gamepadGyroscopeJitterSuppression by rememberSaveable(layoutStorageKey) {
        mutableStateOf(activeLayoutSettings.gyroscopeJitterSuppression)
    }
    var isSimplifiedChineseEnabled by rememberSaveable {
        mutableStateOf(sharedPrefs.getBoolean(GAMEPAD_SIMPLIFIED_CHINESE_ENABLED_PREF, false))
    }
    val triggerVibration = { milliseconds: Long ->
        if (isVibrationEnabled) {
            @Suppress("DEPRECATION")
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            vibrator?.vibrate(VibrationEffect.createOneShot(milliseconds, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }
    val toggleVibration = {
        val active = !isVibrationEnabled
        isVibrationEnabled = active
        sharedPrefs.edit { putBoolean(GAMEPAD_VIBRATION_ENABLED_PREF, active) }
        if (active) triggerVibration(50)
    }

    val dismissLayoutMenu = {
        showLayoutMenu = false
        showGyroscopeCalibration = false
        deleteTargetId = null
        renameTargetId = null
        renameText = ""
        showLayoutPastePanel = false
        layoutPasteText = ""
        showLayoutPasteError = false
    }

    BackHandler {
        if (showLayoutPastePanel) {
            showLayoutPastePanel = false
            layoutPasteText = ""
            showLayoutPasteError = false
            triggerVibration(15)
        } else if (showGyroscopeCalibration) {
            showGyroscopeCalibration = false
            triggerVibration(15)
        } else if (renameTargetId != null || showLayoutMenu) {
            dismissLayoutMenu()
            triggerVibration(15)
        } else {
            showLayoutMenu = true
            triggerVibration(15)
        }
    }

    LaunchedEffect(isLayoutModalOpen) {
        hideGamepadSystemBars(context)
        delay(120L.milliseconds)
        hideGamepadSystemBars(context)
    }

    val triggerGamepadDirectPressHaptic = {
        if (isVibrationEnabled) {
            vibrateGamepadHaptic(context, GamepadHaptic.DirectPress)
        }
    }
    val triggerTouchAssistStickPressHaptic = {
        if (
            isVibrationEnabled && isTouchAssistEnabled &&
            !isEditMode && !isLayoutModalOpen
        ) {
            vibrateGamepadHaptic(context, GamepadHaptic.AssistedPress)
        }
    }
    val triggerTouchAssistStickReleaseHaptic = {
        if (
            isVibrationEnabled && isTouchAssistEnabled &&
            !isEditMode && !isLayoutModalOpen
        ) {
            vibrateGamepadReleaseHaptic(view, context)
        }
    }

    val saveLayoutPref = { key: String, value: Float ->
        if (!isDefaultLayout && !isEditMode) {
            sharedPrefs.edit { putFloat(key, value) }
        }
    }

    // Positions & Scales loaded dynamically per console layout (Xbox Series / PlayStation 5)
    val layoutDefaults = remember(config.id) { defaultLayoutValues(config.id) }
    val loadLayoutValue = { suffix: String ->
        val defaultValue = layoutDefaults.getValue(suffix)
        if (isDefaultLayout) {
            defaultValue
        } else {
            sharedPrefs.getFloat("${layoutStorageKey}_$suffix", defaultValue)
        }
    }
    var dpadOffsetX by remember(config.id, layoutStorageKey, isDefaultLayout) { mutableFloatStateOf(loadLayoutValue("dpad_x")) }
    var dpadOffsetY by remember(config.id, layoutStorageKey, isDefaultLayout) { mutableFloatStateOf(loadLayoutValue("dpad_y")) }
    var dpadScale by remember(config.id, layoutStorageKey, isDefaultLayout) { mutableFloatStateOf(loadLayoutValue("dpad_scale")) }

    var leftStickOffsetX by remember(config.id, layoutStorageKey, isDefaultLayout) { mutableFloatStateOf(loadLayoutValue("left_stick_x")) }
    var leftStickOffsetY by remember(config.id, layoutStorageKey, isDefaultLayout) { mutableFloatStateOf(loadLayoutValue("left_stick_y")) }
    var leftStickScale by remember(config.id, layoutStorageKey, isDefaultLayout) { mutableFloatStateOf(loadLayoutValue("left_stick_scale")) }

    var leftStickButtonOffsetX by remember(config.id, layoutStorageKey, isDefaultLayout) { mutableFloatStateOf(loadLayoutValue("l3_x")) }
    var leftStickButtonOffsetY by remember(config.id, layoutStorageKey, isDefaultLayout) { mutableFloatStateOf(loadLayoutValue("l3_y")) }
    var leftStickButtonScale by remember(config.id, layoutStorageKey, isDefaultLayout) { mutableFloatStateOf(loadLayoutValue("l3_scale")) }

    var rightStickOffsetX by remember(config.id, layoutStorageKey, isDefaultLayout) { mutableFloatStateOf(loadLayoutValue("right_stick_x")) }
    var rightStickOffsetY by remember(config.id, layoutStorageKey, isDefaultLayout) { mutableFloatStateOf(loadLayoutValue("right_stick_y")) }
    var rightStickScale by remember(config.id, layoutStorageKey, isDefaultLayout) { mutableFloatStateOf(loadLayoutValue("right_stick_scale")) }

    var rightStickButtonOffsetX by remember(config.id, layoutStorageKey, isDefaultLayout) { mutableFloatStateOf(loadLayoutValue("r3_x")) }
    var rightStickButtonOffsetY by remember(config.id, layoutStorageKey, isDefaultLayout) { mutableFloatStateOf(loadLayoutValue("r3_y")) }
    var rightStickButtonScale by remember(config.id, layoutStorageKey, isDefaultLayout) { mutableFloatStateOf(loadLayoutValue("r3_scale")) }

    var faceButtonsOffsetX by remember(config.id, layoutStorageKey, isDefaultLayout) { mutableFloatStateOf(loadLayoutValue("face_buttons_x")) }
    var faceButtonsOffsetY by remember(config.id, layoutStorageKey, isDefaultLayout) { mutableFloatStateOf(loadLayoutValue("face_buttons_y")) }
    var faceButtonsScale by remember(config.id, layoutStorageKey, isDefaultLayout) { mutableFloatStateOf(loadLayoutValue("face_buttons_scale")) }

    var leftTriggerOffsetX by remember(config.id, layoutStorageKey, isDefaultLayout) { mutableFloatStateOf(loadLayoutValue("left_trigger_x")) }
    var leftTriggerOffsetY by remember(config.id, layoutStorageKey, isDefaultLayout) { mutableFloatStateOf(loadLayoutValue("left_trigger_y")) }
    var leftTriggerScale by remember(config.id, layoutStorageKey, isDefaultLayout) { mutableFloatStateOf(loadLayoutValue("left_trigger_scale")) }
    var leftTriggerWidthScale by remember(config.id, layoutStorageKey, isDefaultLayout) { mutableFloatStateOf(loadLayoutValue("left_trigger_width_scale")) }
    var leftTriggerHeightScale by remember(config.id, layoutStorageKey, isDefaultLayout) { mutableFloatStateOf(loadLayoutValue("left_trigger_height_scale")) }

    var leftBumperOffsetX by remember(config.id, layoutStorageKey, isDefaultLayout) { mutableFloatStateOf(loadLayoutValue("left_bumper_x")) }
    var leftBumperOffsetY by remember(config.id, layoutStorageKey, isDefaultLayout) { mutableFloatStateOf(loadLayoutValue("left_bumper_y")) }
    var leftBumperScale by remember(config.id, layoutStorageKey, isDefaultLayout) { mutableFloatStateOf(loadLayoutValue("left_bumper_scale")) }
    var leftBumperWidthScale by remember(config.id, layoutStorageKey, isDefaultLayout) { mutableFloatStateOf(loadLayoutValue("left_bumper_width_scale")) }
    var leftBumperHeightScale by remember(config.id, layoutStorageKey, isDefaultLayout) { mutableFloatStateOf(loadLayoutValue("left_bumper_height_scale")) }

    var rightTriggerOffsetX by remember(config.id, layoutStorageKey, isDefaultLayout) { mutableFloatStateOf(loadLayoutValue("right_trigger_x")) }
    var rightTriggerOffsetY by remember(config.id, layoutStorageKey, isDefaultLayout) { mutableFloatStateOf(loadLayoutValue("right_trigger_y")) }
    var rightTriggerScale by remember(config.id, layoutStorageKey, isDefaultLayout) { mutableFloatStateOf(loadLayoutValue("right_trigger_scale")) }
    var rightTriggerWidthScale by remember(config.id, layoutStorageKey, isDefaultLayout) { mutableFloatStateOf(loadLayoutValue("right_trigger_width_scale")) }
    var rightTriggerHeightScale by remember(config.id, layoutStorageKey, isDefaultLayout) { mutableFloatStateOf(loadLayoutValue("right_trigger_height_scale")) }

    var rightBumperOffsetX by remember(config.id, layoutStorageKey, isDefaultLayout) { mutableFloatStateOf(loadLayoutValue("right_bumper_x")) }
    var rightBumperOffsetY by remember(config.id, layoutStorageKey, isDefaultLayout) { mutableFloatStateOf(loadLayoutValue("right_bumper_y")) }
    var rightBumperScale by remember(config.id, layoutStorageKey, isDefaultLayout) { mutableFloatStateOf(loadLayoutValue("right_bumper_scale")) }
    var rightBumperWidthScale by remember(config.id, layoutStorageKey, isDefaultLayout) { mutableFloatStateOf(loadLayoutValue("right_bumper_width_scale")) }
    var rightBumperHeightScale by remember(config.id, layoutStorageKey, isDefaultLayout) { mutableFloatStateOf(loadLayoutValue("right_bumper_height_scale")) }

    val resizeLeftTrigger: (Float, Float) -> Unit = { dragAmountXDp, dragAmountYDp ->
        val resized = gamepadIndependentResizeTransform(
            currentOffsetX = leftTriggerOffsetX,
            currentOffsetY = leftTriggerOffsetY,
            currentWidthScale = leftTriggerScale * leftTriggerWidthScale,
            currentHeightScale = leftTriggerScale * leftTriggerHeightScale,
            dragAmountXDp = dragAmountXDp,
            dragAmountYDp = dragAmountYDp,
            baseWidthDp = SHOULDER_BUTTON_BASE_WIDTH_DP,
            baseHeightDp = TRIGGER_BUTTON_BASE_HEIGHT_DP,
            minimumAxisScale = SHOULDER_MIN_RESIZE_AXIS_SCALE,
            maximumAxisScale = SHOULDER_MAX_RESIZE_AXIS_SCALE
        )
        leftTriggerOffsetX = resized.offsetX
        leftTriggerOffsetY = resized.offsetY
        leftTriggerScale = 1f
        leftTriggerWidthScale = resized.widthScale
        leftTriggerHeightScale = resized.heightScale
        saveLayoutPref("${layoutStorageKey}_left_trigger_x", resized.offsetX)
        saveLayoutPref("${layoutStorageKey}_left_trigger_y", resized.offsetY)
        saveLayoutPref("${layoutStorageKey}_left_trigger_scale", 1f)
        saveLayoutPref("${layoutStorageKey}_left_trigger_width_scale", resized.widthScale)
        saveLayoutPref("${layoutStorageKey}_left_trigger_height_scale", resized.heightScale)
    }
    val resizeLeftBumper: (Float, Float) -> Unit = { dragAmountXDp, dragAmountYDp ->
        val resized = gamepadIndependentResizeTransform(
            currentOffsetX = leftBumperOffsetX,
            currentOffsetY = leftBumperOffsetY,
            currentWidthScale = leftBumperScale * leftBumperWidthScale,
            currentHeightScale = leftBumperScale * leftBumperHeightScale,
            dragAmountXDp = dragAmountXDp,
            dragAmountYDp = dragAmountYDp,
            baseWidthDp = SHOULDER_BUTTON_BASE_WIDTH_DP,
            baseHeightDp = BUMPER_BUTTON_BASE_HEIGHT_DP,
            minimumAxisScale = SHOULDER_MIN_RESIZE_AXIS_SCALE,
            maximumAxisScale = SHOULDER_MAX_RESIZE_AXIS_SCALE
        )
        leftBumperOffsetX = resized.offsetX
        leftBumperOffsetY = resized.offsetY
        leftBumperScale = 1f
        leftBumperWidthScale = resized.widthScale
        leftBumperHeightScale = resized.heightScale
        saveLayoutPref("${layoutStorageKey}_left_bumper_x", resized.offsetX)
        saveLayoutPref("${layoutStorageKey}_left_bumper_y", resized.offsetY)
        saveLayoutPref("${layoutStorageKey}_left_bumper_scale", 1f)
        saveLayoutPref("${layoutStorageKey}_left_bumper_width_scale", resized.widthScale)
        saveLayoutPref("${layoutStorageKey}_left_bumper_height_scale", resized.heightScale)
    }
    val resizeRightTrigger: (Float, Float) -> Unit = { dragAmountXDp, dragAmountYDp ->
        val resized = gamepadIndependentResizeTransform(
            currentOffsetX = rightTriggerOffsetX,
            currentOffsetY = rightTriggerOffsetY,
            currentWidthScale = rightTriggerScale * rightTriggerWidthScale,
            currentHeightScale = rightTriggerScale * rightTriggerHeightScale,
            dragAmountXDp = dragAmountXDp,
            dragAmountYDp = dragAmountYDp,
            baseWidthDp = SHOULDER_BUTTON_BASE_WIDTH_DP,
            baseHeightDp = TRIGGER_BUTTON_BASE_HEIGHT_DP,
            minimumAxisScale = SHOULDER_MIN_RESIZE_AXIS_SCALE,
            maximumAxisScale = SHOULDER_MAX_RESIZE_AXIS_SCALE
        )
        rightTriggerOffsetX = resized.offsetX
        rightTriggerOffsetY = resized.offsetY
        rightTriggerScale = 1f
        rightTriggerWidthScale = resized.widthScale
        rightTriggerHeightScale = resized.heightScale
        saveLayoutPref("${layoutStorageKey}_right_trigger_x", resized.offsetX)
        saveLayoutPref("${layoutStorageKey}_right_trigger_y", resized.offsetY)
        saveLayoutPref("${layoutStorageKey}_right_trigger_scale", 1f)
        saveLayoutPref("${layoutStorageKey}_right_trigger_width_scale", resized.widthScale)
        saveLayoutPref("${layoutStorageKey}_right_trigger_height_scale", resized.heightScale)
    }
    val resizeRightBumper: (Float, Float) -> Unit = { dragAmountXDp, dragAmountYDp ->
        val resized = gamepadIndependentResizeTransform(
            currentOffsetX = rightBumperOffsetX,
            currentOffsetY = rightBumperOffsetY,
            currentWidthScale = rightBumperScale * rightBumperWidthScale,
            currentHeightScale = rightBumperScale * rightBumperHeightScale,
            dragAmountXDp = dragAmountXDp,
            dragAmountYDp = dragAmountYDp,
            baseWidthDp = SHOULDER_BUTTON_BASE_WIDTH_DP,
            baseHeightDp = BUMPER_BUTTON_BASE_HEIGHT_DP,
            minimumAxisScale = SHOULDER_MIN_RESIZE_AXIS_SCALE,
            maximumAxisScale = SHOULDER_MAX_RESIZE_AXIS_SCALE
        )
        rightBumperOffsetX = resized.offsetX
        rightBumperOffsetY = resized.offsetY
        rightBumperScale = 1f
        rightBumperWidthScale = resized.widthScale
        rightBumperHeightScale = resized.heightScale
        saveLayoutPref("${layoutStorageKey}_right_bumper_x", resized.offsetX)
        saveLayoutPref("${layoutStorageKey}_right_bumper_y", resized.offsetY)
        saveLayoutPref("${layoutStorageKey}_right_bumper_scale", 1f)
        saveLayoutPref("${layoutStorageKey}_right_bumper_width_scale", resized.widthScale)
        saveLayoutPref("${layoutStorageKey}_right_bumper_height_scale", resized.heightScale)
    }

    var guideOffsetX by remember(config.id, layoutStorageKey, isDefaultLayout) { mutableFloatStateOf(loadLayoutValue("guide_x")) }
    var guideOffsetY by remember(config.id, layoutStorageKey, isDefaultLayout) { mutableFloatStateOf(loadLayoutValue("guide_y")) }
    var guideScale by remember(config.id, layoutStorageKey, isDefaultLayout) { mutableFloatStateOf(loadLayoutValue("guide_scale")) }

    var selectOffsetX by remember(config.id, layoutStorageKey, isDefaultLayout) { mutableFloatStateOf(loadLayoutValue("select_x")) }
    var selectOffsetY by remember(config.id, layoutStorageKey, isDefaultLayout) { mutableFloatStateOf(loadLayoutValue("select_y")) }
    var selectScale by remember(config.id, layoutStorageKey, isDefaultLayout) { mutableFloatStateOf(loadLayoutValue("select_scale")) }

    var shareOffsetX by remember(config.id, layoutStorageKey, isDefaultLayout) { mutableFloatStateOf(loadLayoutValue("share_x")) }
    var shareOffsetY by remember(config.id, layoutStorageKey, isDefaultLayout) { mutableFloatStateOf(loadLayoutValue("share_y")) }
    var shareScale by remember(config.id, layoutStorageKey, isDefaultLayout) { mutableFloatStateOf(loadLayoutValue("share_scale")) }

    var startOffsetX by remember(config.id, layoutStorageKey, isDefaultLayout) { mutableFloatStateOf(loadLayoutValue("start_x")) }
    var startOffsetY by remember(config.id, layoutStorageKey, isDefaultLayout) { mutableFloatStateOf(loadLayoutValue("start_y")) }
    var startScale by remember(config.id, layoutStorageKey, isDefaultLayout) { mutableFloatStateOf(loadLayoutValue("start_scale")) }

    fun currentLayoutValues(): Map<String, Float> = mapOf(
        "dpad_x" to dpadOffsetX,
        "dpad_y" to dpadOffsetY,
        "dpad_scale" to dpadScale,
        "left_stick_x" to leftStickOffsetX,
        "left_stick_y" to leftStickOffsetY,
        "left_stick_scale" to leftStickScale,
        "l3_x" to leftStickButtonOffsetX,
        "l3_y" to leftStickButtonOffsetY,
        "l3_scale" to leftStickButtonScale,
        "right_stick_x" to rightStickOffsetX,
        "right_stick_y" to rightStickOffsetY,
        "right_stick_scale" to rightStickScale,
        "r3_x" to rightStickButtonOffsetX,
        "r3_y" to rightStickButtonOffsetY,
        "r3_scale" to rightStickButtonScale,
        "face_buttons_x" to faceButtonsOffsetX,
        "face_buttons_y" to faceButtonsOffsetY,
        "face_buttons_scale" to faceButtonsScale,
        "left_trigger_x" to leftTriggerOffsetX,
        "left_trigger_y" to leftTriggerOffsetY,
        "left_trigger_scale" to leftTriggerScale,
        "left_trigger_width_scale" to leftTriggerWidthScale,
        "left_trigger_height_scale" to leftTriggerHeightScale,
        "left_bumper_x" to leftBumperOffsetX,
        "left_bumper_y" to leftBumperOffsetY,
        "left_bumper_scale" to leftBumperScale,
        "left_bumper_width_scale" to leftBumperWidthScale,
        "left_bumper_height_scale" to leftBumperHeightScale,
        "right_trigger_x" to rightTriggerOffsetX,
        "right_trigger_y" to rightTriggerOffsetY,
        "right_trigger_scale" to rightTriggerScale,
        "right_trigger_width_scale" to rightTriggerWidthScale,
        "right_trigger_height_scale" to rightTriggerHeightScale,
        "right_bumper_x" to rightBumperOffsetX,
        "right_bumper_y" to rightBumperOffsetY,
        "right_bumper_scale" to rightBumperScale,
        "right_bumper_width_scale" to rightBumperWidthScale,
        "right_bumper_height_scale" to rightBumperHeightScale,
        "guide_x" to guideOffsetX,
        "guide_y" to guideOffsetY,
        "guide_scale" to guideScale,
        "select_x" to selectOffsetX,
        "select_y" to selectOffsetY,
        "select_scale" to selectScale,
        "share_x" to shareOffsetX,
        "share_y" to shareOffsetY,
        "share_scale" to shareScale,
        "start_x" to startOffsetX,
        "start_y" to startOffsetY,
        "start_scale" to startScale
    )

    fun applyLayoutValues(values: Map<String, Float>) {
        dpadOffsetX = values.getValue("dpad_x")
        dpadOffsetY = values.getValue("dpad_y")
        dpadScale = values.getValue("dpad_scale")
        leftStickOffsetX = values.getValue("left_stick_x")
        leftStickOffsetY = values.getValue("left_stick_y")
        leftStickScale = values.getValue("left_stick_scale")
        leftStickButtonOffsetX = values.getValue("l3_x")
        leftStickButtonOffsetY = values.getValue("l3_y")
        leftStickButtonScale = values.getValue("l3_scale")
        rightStickOffsetX = values.getValue("right_stick_x")
        rightStickOffsetY = values.getValue("right_stick_y")
        rightStickScale = values.getValue("right_stick_scale")
        rightStickButtonOffsetX = values.getValue("r3_x")
        rightStickButtonOffsetY = values.getValue("r3_y")
        rightStickButtonScale = values.getValue("r3_scale")
        faceButtonsOffsetX = values.getValue("face_buttons_x")
        faceButtonsOffsetY = values.getValue("face_buttons_y")
        faceButtonsScale = values.getValue("face_buttons_scale")
        leftTriggerOffsetX = values.getValue("left_trigger_x")
        leftTriggerOffsetY = values.getValue("left_trigger_y")
        leftTriggerScale = values.getValue("left_trigger_scale")
        leftTriggerWidthScale = values.getValue("left_trigger_width_scale")
        leftTriggerHeightScale = values.getValue("left_trigger_height_scale")
        leftBumperOffsetX = values.getValue("left_bumper_x")
        leftBumperOffsetY = values.getValue("left_bumper_y")
        leftBumperScale = values.getValue("left_bumper_scale")
        leftBumperWidthScale = values.getValue("left_bumper_width_scale")
        leftBumperHeightScale = values.getValue("left_bumper_height_scale")
        rightTriggerOffsetX = values.getValue("right_trigger_x")
        rightTriggerOffsetY = values.getValue("right_trigger_y")
        rightTriggerScale = values.getValue("right_trigger_scale")
        rightTriggerWidthScale = values.getValue("right_trigger_width_scale")
        rightTriggerHeightScale = values.getValue("right_trigger_height_scale")
        rightBumperOffsetX = values.getValue("right_bumper_x")
        rightBumperOffsetY = values.getValue("right_bumper_y")
        rightBumperScale = values.getValue("right_bumper_scale")
        rightBumperWidthScale = values.getValue("right_bumper_width_scale")
        rightBumperHeightScale = values.getValue("right_bumper_height_scale")
        guideOffsetX = values.getValue("guide_x")
        guideOffsetY = values.getValue("guide_y")
        guideScale = values.getValue("guide_scale")
        selectOffsetX = values.getValue("select_x")
        selectOffsetY = values.getValue("select_y")
        selectScale = values.getValue("select_scale")
        shareOffsetX = values.getValue("share_x")
        shareOffsetY = values.getValue("share_y")
        shareScale = values.getValue("share_scale")
        startOffsetX = values.getValue("start_x")
        startOffsetY = values.getValue("start_y")
        startScale = values.getValue("start_scale")
    }

    fun currentLayoutEditSnapshot() = GamepadLayoutEditSnapshot(
        layoutValues = currentLayoutValues().toMap(),
        controlInstances = controlInstances.toList()
    )

    fun appendEditHistorySnapshot(
        history: List<GamepadLayoutEditSnapshot>,
        snapshot: GamepadLayoutEditSnapshot
    ): List<GamepadLayoutEditSnapshot> {
        if (history.lastOrNull() == snapshot) return history
        return (history + snapshot).takeLast(GAMEPAD_EDIT_HISTORY_LIMIT)
    }

    fun commitPendingLayoutEditHistory(): GamepadLayoutEditSnapshot {
        val current = currentLayoutEditSnapshot()
        val recorded = editHistoryCurrent
        if (recorded == null) {
            editHistoryCurrent = current
        } else if (recorded != current) {
            editUndoHistory = appendEditHistorySnapshot(editUndoHistory, recorded)
            editRedoHistory = emptyList()
            editHistoryCurrent = current
        }
        return current
    }

    fun applyLayoutEditSnapshot(snapshot: GamepadLayoutEditSnapshot) {
        editHistoryCurrent = snapshot
        applyLayoutValues(snapshot.layoutValues)
        controlInstances = snapshot.controlInstances
        confirmingControlDeleteInstanceId = null
        snapGuides = emptyList()
    }

    val observedLayoutEditSnapshot = if (isEditMode) currentLayoutEditSnapshot() else null
    LaunchedEffect(isEditMode, observedLayoutEditSnapshot) {
        val snapshot = observedLayoutEditSnapshot ?: return@LaunchedEffect
        delay(GAMEPAD_EDIT_HISTORY_DEBOUNCE_MS.milliseconds)
        val recorded = editHistoryCurrent
        if (recorded == null) {
            editHistoryCurrent = snapshot
        } else if (recorded != snapshot) {
            editUndoHistory = appendEditHistorySnapshot(editUndoHistory, recorded)
            editRedoHistory = emptyList()
            editHistoryCurrent = snapshot
        }
    }

    val hasPendingLayoutHistoryChange = isEditMode &&
        editHistoryCurrent?.let { it != observedLayoutEditSnapshot } == true
    val canUndoLayoutEdit = isEditMode &&
        (editUndoHistory.isNotEmpty() || hasPendingLayoutHistoryChange)
    val canRedoLayoutEdit = isEditMode &&
        editRedoHistory.isNotEmpty() && !hasPendingLayoutHistoryChange

    val undoLayoutEdit = {
        if (isEditMode) {
            val current = commitPendingLayoutEditHistory()
            val target = editUndoHistory.lastOrNull()
            if (target != null) {
                editUndoHistory = editUndoHistory.dropLast(1)
                editRedoHistory = appendEditHistorySnapshot(editRedoHistory, current)
                applyLayoutEditSnapshot(target)
                triggerVibration(18)
            }
        }
    }

    val redoLayoutEdit = {
        if (isEditMode) {
            val current = commitPendingLayoutEditHistory()
            val target = editRedoHistory.lastOrNull()
            if (target != null) {
                editRedoHistory = editRedoHistory.dropLast(1)
                editUndoHistory = appendEditHistorySnapshot(editUndoHistory, current)
                applyLayoutEditSnapshot(target)
                triggerVibration(18)
            }
        }
    }

    val hasUnsavedLayoutChanges = isEditMode && !isDefaultLayout && (
        editBaselineValues?.let { currentLayoutValues() != it } == true ||
            editBaselineControlInstances?.let { controlInstances != it } == true
        )

    LaunchedEffect(hasUnsavedLayoutChanges) {
        if (!hasUnsavedLayoutChanges) {
            pendingUnsavedDiscardActionKey = null
        }
    }

    val beginLayoutEdit = {
        if (canEditLayout) {
            val initialSnapshot = currentLayoutEditSnapshot()
            editBaselineValues = initialSnapshot.layoutValues
            editBaselineControlInstances = initialSnapshot.controlInstances
            editUndoHistory = emptyList()
            editRedoHistory = emptyList()
            editHistoryCurrent = initialSnapshot
            multiSelectedLayoutIds = emptyList()
            deleteTargetId = null
            pendingUnsavedDiscardActionKey = null
            selectedControlTypeId = null
            showFeatureListPage = false
            showGyroscopeMappingPage = false
            showBlankListPage = false
            showSpecialControlListPage = false
            isEditMode = true
            triggerVibration(30)
        }
    }

    val finishLayoutEdit = {
        if (canEditLayout) {
            sharedPrefs.saveLayoutValues(layoutStorageKey, currentLayoutValues())
            sharedPrefs.saveGamepadControlInstances(layoutStorageKey, controlInstances)
            editBaselineValues = null
            editBaselineControlInstances = null
            editUndoHistory = emptyList()
            editRedoHistory = emptyList()
            editHistoryCurrent = null
            pendingUnsavedDiscardActionKey = null
            selectedControlTypeId = null
            showSpecialControlListPage = false
            isEditMode = false
            layoutRevision++
            triggerVibration(30)
        }
    }

    val resetLayoutEdit = {
        if (canEditLayout && isEditMode) {
            commitPendingLayoutEditHistory()
            val baseline = editBaselineValues ?: sharedPrefs.loadLayoutValues(layoutStorageKey, config.id)
            applyLayoutValues(baseline)
            controlInstances = editBaselineControlInstances
                ?: sharedPrefs.loadGamepadControlInstances(layoutStorageKey, config.id)
            pendingUnsavedDiscardActionKey = null
            triggerVibration(50)
        }
    }

    val toggleLayoutEdit = {
        if (isEditMode) {
            finishLayoutEdit()
        } else {
            beginLayoutEdit()
        }
    }

    val selectedControlType = selectedControlTypeId?.let { GamepadControlType.fromStorageId(it) }
    val selectedControlCount = selectedControlType?.let { type ->
        controlInstances.count { it.type == type }
    }
    val addSelectedControlInstance = {
        val type = selectedControlType
        if (isEditMode && type != null && controlInstances.count { it.type == type } < GAMEPAD_CONTROL_INSTANCE_LIMIT) {
            commitPendingLayoutEditHistory()
            controlInstances = addGamepadControlInstance(
                instances = controlInstances,
                type = type,
                id = "${type.storageId}_${System.currentTimeMillis()}_${System.nanoTime()}"
            )
            triggerVibration(18)
        }
    }
    val removeSelectedControlInstance = {
        val type = selectedControlType
        if (isEditMode && type != null) {
            val nextInstances = removeNewestGamepadControlInstance(controlInstances, type)
            if (nextInstances !== controlInstances) {
                commitPendingLayoutEditHistory()
                controlInstances = nextInstances
                triggerVibration(18)
            }
        }
    }
    val updateControlInstance = { updated: GamepadControlInstance ->
        controlInstances = controlInstances.map { instance ->
            if (instance.id == updated.id) updated else instance
        }
    }
    val controlDeleteController = GamepadControlDeleteController(
        selectedInstanceId = selectedEditControlInstanceId,
        confirmingInstanceId = confirmingControlDeleteInstanceId,
        onSelect = { instanceId ->
            if (isEditMode && controlInstances.any { it.id == instanceId }) {
                if (selectedEditControlInstanceId != instanceId) {
                    confirmingControlDeleteInstanceId = null
                }
                selectedEditControlInstanceId = instanceId
            }
        },
        onRequest = { instanceId ->
            if (isEditMode && controlInstances.any { it.id == instanceId }) {
                confirmingControlDeleteInstanceId = instanceId
                triggerVibration(18)
            }
        },
        onConfirm = { instanceId ->
            if (isEditMode && confirmingControlDeleteInstanceId == instanceId) {
                val nextInstances = removeGamepadControlInstance(controlInstances, instanceId)
                if (nextInstances !== controlInstances) {
                    commitPendingLayoutEditHistory()
                    controlInstances = nextInstances
                    triggerVibration(30)
                }
                confirmingControlDeleteInstanceId = null
            }
        },
        onClear = { confirmingControlDeleteInstanceId = null }
    )

    val discardUnsavedLayoutEdit = {
        editBaselineValues?.let { applyLayoutValues(it) }
        editBaselineControlInstances?.let { controlInstances = it }
        isEditMode = false
        editBaselineValues = null
        editBaselineControlInstances = null
        editUndoHistory = emptyList()
        editRedoHistory = emptyList()
        editHistoryCurrent = null
        pendingUnsavedDiscardActionKey = null
        selectedControlTypeId = null
        showSpecialControlListPage = false
        snapGuides = emptyList()
    }

    val runAfterUnsavedDiscardConfirmation = { actionKey: String, action: () -> Unit ->
        if (hasUnsavedLayoutChanges) {
            if (pendingUnsavedDiscardActionKey == actionKey) {
                discardUnsavedLayoutEdit()
                action()
            } else {
                pendingUnsavedDiscardActionKey = actionKey
                unsavedWarningPulse++
                triggerVibration(55)
            }
        } else {
            pendingUnsavedDiscardActionKey = null
            action()
        }
    }

    val guardedCloseGamepad = {
        runAfterUnsavedDiscardConfirmation("close_gamepad") {
            onClose()
        }
    }

    val selectLayoutProfile = { profile: GamepadLayoutProfile ->
        val selectProfile = {
            selectedLayoutId = profile.id
            sharedPrefs.edit { putString(GAMEPAD_ACTIVE_LAYOUT_PREF, profile.id) }
            isEditMode = false
            editBaselineValues = null
            editBaselineControlInstances = null
            pendingUnsavedDiscardActionKey = null
            showLayoutMenu = true
            deleteTargetId = null
            multiSelectedLayoutIds = emptyList()
            layoutRevision++
            triggerVibration(20)
        }
        if (profile.id == activeLayout.id) {
            deleteTargetId = null
        } else {
            runAfterUnsavedDiscardConfirmation("select_${profile.id}") {
                selectProfile()
            }
        }
    }

    val createLayoutProfile = { configId: String ->
        runAfterUnsavedDiscardConfirmation("create_$configId") {
            val ids = sharedPrefs.getCustomProfileIds(configId)
            val id = "gamepad_layout_${configId}_${System.currentTimeMillis()}"
            val baseName = if (configId == PS5_CONFIG_ID) "PS Layout" else "Xbox Layout"
            val name = "$baseName ${ids.size + 1}"
            sharedPrefs.saveCustomProfileIds(configId, ids + id)
            sharedPrefs.edit {
                putString(profileNameKey(id), name)
                putString(GAMEPAD_ACTIVE_LAYOUT_PREF, id)
                putString(
                    gamepadLayoutSettingsKey(id),
                    gamepadLayoutSettingsToJson(defaultGamepadLayoutSettings()).toString()
                )
            }
            selectedLayoutId = id
            renameTargetId = id
            renameText = name
            isEditMode = false
            editBaselineValues = null
            editBaselineControlInstances = null
            pendingUnsavedDiscardActionKey = null
            showLayoutMenu = true
            deleteTargetId = null
            multiSelectedLayoutIds = emptyList()
            layoutRevision++
            triggerVibration(25)
        }
    }

    val copyActiveLayoutProfile = {
        val sourceProfile = activeLayout
        val ids = sharedPrefs.getCustomProfileIds(sourceProfile.configId)
        val id = "gamepad_layout_${sourceProfile.configId}_${System.currentTimeMillis()}"
        val existingNames = sharedPrefs.loadLayoutProfiles(sourceProfile.configId).map { it.name }.toSet()
        val name = copiedLayoutName(sourceProfile.name, existingNames)
        val savedSourceValues = currentLayoutValues()
        val savedSourceControlInstances = controlInstances
        if (isEditMode && canEditLayout) {
            sharedPrefs.saveLayoutValues(layoutStorageKey, savedSourceValues)
            sharedPrefs.saveGamepadControlInstances(layoutStorageKey, savedSourceControlInstances)
            editBaselineValues = savedSourceValues
            editBaselineControlInstances = savedSourceControlInstances
            pendingUnsavedDiscardActionKey = null
        }
        val sourceValues = if (sourceProfile.id == activeLayout.id) {
            savedSourceValues
        } else {
            sharedPrefs.loadLayoutValues(sourceProfile.storageKey, sourceProfile.configId)
        }
        val sourceControlInstances = if (sourceProfile.id == activeLayout.id) {
            savedSourceControlInstances
        } else {
            sharedPrefs.loadGamepadControlInstances(sourceProfile.storageKey, sourceProfile.configId)
        }
        val sourceSettings = sharedPrefs.loadGamepadLayoutSettings(sourceProfile.storageKey)
        val insertIndex = if (sourceProfile.isDefault) {
            0
        } else {
            (ids.indexOf(sourceProfile.id) + 1).coerceIn(0, ids.size)
        }
        val nextIds = ids.toMutableList().apply { add(insertIndex, id) }
        sharedPrefs.edit {
            putString(profileIdsKey(sourceProfile.configId), nextIds.distinct().joinToString("|"))
            putString(profileNameKey(id), name)
            sourceValues.forEach { (suffix, value) -> putFloat("${id}_$suffix", value) }
            putString(
                gamepadControlInstancesKey(id),
                gamepadControlInstancesToJson(sourceControlInstances).toString()
            )
            putString(
                gamepadLayoutSettingsKey(id),
                gamepadLayoutSettingsToJson(sourceSettings).toString()
            )
        }
        showLayoutMenu = true
        deleteTargetId = null
        multiSelectedLayoutIds = emptyList()
        layoutRevision++
        triggerVibration(25)
    }

    val moveActiveLayoutProfile = { visualDirection: Int ->
        if (!activeLayout.isDefault) {
            val ids = sharedPrefs.getCustomProfileIds(activeLayout.configId).toMutableList()
            val index = ids.indexOf(activeLayout.id)
            val targetIndex = index + visualDirection
            if (index >= 0 && targetIndex in ids.indices) {
                val targetId = ids[targetIndex]
                ids[targetIndex] = ids[index]
                ids[index] = targetId
                sharedPrefs.saveCustomProfileIds(activeLayout.configId, ids)
                layoutRevision++
                triggerVibration(18)
            }
        }
    }

    val requestRenameLayout = { profile: GamepadLayoutProfile ->
        if (!profile.isDefault) {
            showLayoutMenu = true
            deleteTargetId = null
            multiSelectedLayoutIds = emptyList()
            renameTargetId = profile.id
            renameText = profile.name
            triggerVibration(15)
        }
    }

    val confirmRenameLayout = {
        val targetId = renameTargetId
        val trimmedName = renameText.trim()
        if (targetId != null && trimmedName.isNotEmpty()) {
            sharedPrefs.edit { putString(profileNameKey(targetId), trimmedName) }
            layoutRevision++
        }
        renameTargetId = null
        renameText = ""
        showLayoutMenu = true
    }

    val deleteLayoutProfiles = { profiles: List<GamepadLayoutProfile> ->
        val targets = profiles.filterNot { it.isDefault }
        if (targets.isNotEmpty()) {
            targets
                .groupBy { it.configId }
                .forEach { (configId, configProfiles) ->
                    val removedIds = configProfiles.map { it.id }.toSet()
                    val remainingIds = sharedPrefs.getCustomProfileIds(configId)
                        .filterNot { it in removedIds }
                    sharedPrefs.saveCustomProfileIds(configId, remainingIds)
                }
            sharedPrefs.edit {
                targets.forEach { profile ->
                    remove(profileNameKey(profile.id))
                    layoutPreferenceSuffixes.forEach { suffix ->
                        remove("${profile.storageKey}_$suffix")
                    }
                    remove(gamepadControlInstancesKey(profile.storageKey))
                    remove(gamepadLayoutSettingsKey(profile.storageKey))
                }
            }
            val selectedDeletedProfile = targets.firstOrNull { it.id == selectedLayoutId }
            if (selectedDeletedProfile != null) {
                val fallbackLayoutId = defaultLayoutIdFor(selectedDeletedProfile.configId)
                selectedLayoutId = fallbackLayoutId
                sharedPrefs.edit { putString(GAMEPAD_ACTIVE_LAYOUT_PREF, fallbackLayoutId) }
                isEditMode = false
                editBaselineValues = null
                editBaselineControlInstances = null
            }
            deleteTargetId = null
            multiSelectedLayoutIds = emptyList()
            layoutRevision++
            triggerVibration(30)
        }
    }

    val deleteLayoutProfile = { profile: GamepadLayoutProfile ->
        deleteLayoutProfiles(listOf(profile))
    }

    val requestDeleteLayout = { profile: GamepadLayoutProfile ->
        if (!profile.isDefault) {
            multiSelectedLayoutIds = emptyList()
            confirmingSelectedDelete = false
            deleteTargetId = profile.id
            showLayoutMenu = true
            triggerVibration(15)
        }
    }

    val startLayoutMultiSelect = { profile: GamepadLayoutProfile ->
        if (!profile.isDefault && !isEditMode) {
            deleteTargetId = null
            confirmingSelectedDelete = false
            renameTargetId = null
            renameText = ""
            showLayoutMenu = true
            multiSelectedLayoutIds = if (profile.id in multiSelectedLayoutIds) {
                multiSelectedLayoutIds
            } else {
                multiSelectedLayoutIds + profile.id
            }
            triggerVibration(25)
        }
    }

    val toggleLayoutMultiSelect = { profile: GamepadLayoutProfile ->
        if (!profile.isDefault && !isEditMode) {
            val nextSelection = if (profile.id in multiSelectedLayoutIds) {
                multiSelectedLayoutIds.filterNot { it == profile.id }
            } else {
                multiSelectedLayoutIds + profile.id
            }
            multiSelectedLayoutIds = nextSelection
            deleteTargetId = null
            confirmingSelectedDelete = false
            triggerVibration(12)
        }
    }

    val deleteMultiSelectedLayouts = {
        val selectedIds = multiSelectedLayoutIds.toSet()
        val profilesToDelete = (xboxLayoutProfiles + psLayoutProfiles)
            .filter { it.id in selectedIds }
        deleteLayoutProfiles(profilesToDelete)
    }

    var pendingLayoutExportJson by remember { mutableStateOf("") }
    val layoutExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(GAMEPAD_LAYOUT_EXPORT_MIME_TYPE)
    ) { uri ->
        if (uri != null && pendingLayoutExportJson.isNotBlank()) {
            runCatching {
                val outputStream = context.contentResolver.openOutputStream(uri)
                    ?: error("Unable to open layout export file.")
                outputStream.bufferedWriter().use { writer ->
                    writer.write(pendingLayoutExportJson)
                }
            }.onSuccess {
                triggerVibration(25)
            }
        }
        pendingLayoutExportJson = ""
    }
    val applyImportedLayoutProfiles: (List<GamepadLayoutProfile>) -> Unit = { importedProfiles ->
        if (importedProfiles.isNotEmpty()) {
            val activeImportedProfile = importedProfiles.last()
            selectedLayoutId = activeImportedProfile.id
            sharedPrefs.edit { putString(GAMEPAD_ACTIVE_LAYOUT_PREF, activeImportedProfile.id) }
            isEditMode = false
            editBaselineValues = null
            editBaselineControlInstances = null
            deleteTargetId = null
            multiSelectedLayoutIds = emptyList()
            renameTargetId = null
            renameText = ""
            showLayoutMenu = true
            layoutRevision++
            triggerVibration(25)
        }
    }
    val layoutImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val importedProfiles = runCatching {
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: error("Unable to open layout import file.")
                val json = inputStream.bufferedReader().use { reader -> reader.readText() }
                sharedPrefs.importLayoutProfilesFromJson(json)
            }.getOrElse {
                emptyList()
            }
            applyImportedLayoutProfiles(importedProfiles)
        }
    }

    val selectedLayoutProfilesForTransfer = {
        val allProfiles = xboxLayoutProfiles + psLayoutProfiles
        val selectedIds = multiSelectedLayoutIds.toSet()
        if (selectedIds.isEmpty()) {
            listOf(activeLayout)
        } else {
            allProfiles.filter { it.id in selectedIds }
        }
    }

    val exportLayoutProfiles = {
        val exportProfiles = selectedLayoutProfilesForTransfer()
        if (exportProfiles.isNotEmpty()) {
            pendingLayoutExportJson = sharedPrefs.exportLayoutProfilesToJson(exportProfiles)
            layoutExportLauncher.launch(gamepadLayoutExportFileName(exportProfiles))
            triggerVibration(15)
        }
    }

    val shareLayoutProfiles = {
        val shareProfiles = selectedLayoutProfilesForTransfer()
        if (shareProfiles.isNotEmpty()) {
            runCatching {
                val shareDirectory = File(context.cacheDir, "shared_layouts")
                check(shareDirectory.exists() || shareDirectory.mkdirs()) {
                    "Unable to create layout share directory."
                }
                val shareFile = File(
                    shareDirectory,
                    gamepadLayoutExportFileName(shareProfiles)
                )
                shareFile.writeText(sharedPrefs.exportLayoutProfilesToJson(shareProfiles))
                val sendIntent = gamepadLayoutShareIntent(context, shareFile)
                val chooserIntent = Intent.createChooser(
                    sendIntent,
                    if (isSimplifiedChineseEnabled) "发送布局" else "Share layouts"
                )
                if (context !is Activity) {
                    chooserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(chooserIntent)
            }.onSuccess {
                triggerVibration(15)
            }
        }
    }

    val copyLayoutProfilesToClipboard = {
        val copyProfiles = selectedLayoutProfilesForTransfer()
        if (copyProfiles.isNotEmpty()) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(
                ClipData.newPlainText(
                    gamepadLayoutExportFileName(copyProfiles),
                    sharedPrefs.exportLayoutProfilesToJson(copyProfiles)
                )
            )
            if (clipboard != null) triggerVibration(15)
        }
    }

    val importLayoutProfiles = {
        runAfterUnsavedDiscardConfirmation("import_layout_file") {
            layoutImportLauncher.launch(arrayOf(GAMEPAD_LAYOUT_EXPORT_MIME_TYPE, "text/*", "*/*"))
            triggerVibration(15)
        }
    }
    val pasteLayoutProfiles: (String) -> Boolean = { pastedText ->
        val importedProfiles = runCatching {
            if (pastedText.isBlank()) {
                emptyList()
            } else {
                sharedPrefs.importLayoutProfilesFromJson(pastedText)
            }
        }.getOrElse {
            emptyList()
        }
        if (importedProfiles.isNotEmpty()) {
            applyImportedLayoutProfiles(importedProfiles)
            true
        } else {
            false
        }
    }
    val requestPasteLayoutProfiles = {
        runAfterUnsavedDiscardConfirmation("import_layout_text") {
            layoutPasteText = ""
            showLayoutPasteError = false
            showLayoutPastePanel = true
            triggerVibration(15)
        }
    }

    val connectedDevNow by btManager.connectedDevice.collectAsState()
    val bondedDevices by btManager.bondedDevices.collectAsState()
    val gamepadSessionGeneration by btManager.gamepadSessionGeneration.collectAsState()
    val gamepadReportRate by btManager.gamepadReportRate.collectAsState()
    val isConnected = connectedDevNow != null
    val deviceName = connectedDevNow?.name ?: "No Host"

    LaunchedEffect(connectedDevNow?.address) {
        connectedDevNow?.address?.let { address ->
            sharedPrefs.edit { putString(GAMEPAD_LAST_CONNECTED_DEVICE_PREF, address) }
        }
    }

    val reconnectLastDevice = {
        val activeDevice = btManager.connectedDevice.value ?: connectedDevNow
        val activeAddress = activeDevice?.address
        val lastAddress = activeAddress
            ?: sharedPrefs.getString(GAMEPAD_LAST_CONNECTED_DEVICE_PREF, null)
            ?: bluetoothPrefs.getString(BLUETOOTH_LAST_CONNECTED_DEVICE_PREF, null)

        if (!activeAddress.isNullOrBlank()) {
            sharedPrefs.edit { putString(GAMEPAD_LAST_CONNECTED_DEVICE_PREF, activeAddress) }
        }

        if (lastAddress.isNullOrBlank()) {
            triggerVibration(10)
        } else {
            btManager.checkBluetoothCapabilities()
            btManager.updateBondedDevices()
            val currentBondedDevices = btManager.bondedDevices.value.ifEmpty { bondedDevices }
            val bluetoothAdapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            val targetDevice = activeDevice
                ?: currentBondedDevices.firstOrNull { it.address == lastAddress }
                ?: runCatching { bluetoothAdapter?.getRemoteDevice(lastAddress) }.getOrNull()
            if (targetDevice != null) {
                btManager.connectDevice(targetDevice)
                triggerVibration(25)
            } else {
                triggerVibration(10)
            }
        }
    }

    var buttonMask by remember { mutableIntStateOf(0) }
    var leftStickX by remember { mutableFloatStateOf(0f) }
    var leftStickY by remember { mutableFloatStateOf(0f) }
    var rightStickX by remember { mutableFloatStateOf(0f) }
    var rightStickY by remember { mutableFloatStateOf(0f) }
    var leftTouchStickX by remember { mutableFloatStateOf(0f) }
    var leftTouchStickY by remember { mutableFloatStateOf(0f) }
    var rightTouchStickX by remember { mutableFloatStateOf(0f) }
    var rightTouchStickY by remember { mutableFloatStateOf(0f) }
    var gyroscopeStickX by remember { mutableFloatStateOf(0f) }
    var gyroscopeStickY by remember { mutableFloatStateOf(0f) }
    val digitalInputAggregator = remember { GamepadDigitalInputAggregator() }
    val leftAnalogSources = remember { AveragedGamepadAnalogSources() }
    val rightAnalogSources = remember { AveragedGamepadAnalogSources() }
    var isAutomatedInputTestRunning by remember { mutableStateOf(false) }
    var automatedInputTestElapsedMs by remember { mutableLongStateOf(0L) }
    var automatedInputTestJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    LaunchedEffect(gamepadSessionGeneration) {
        automatedInputTestJob?.cancel()
        automatedInputTestElapsedMs = 0L
        digitalInputAggregator.reset()
        leftAnalogSources.clear()
        rightAnalogSources.clear()
        buttonMask = 0
        leftStickX = 0f
        leftStickY = 0f
        rightStickX = 0f
        rightStickY = 0f
        leftTouchStickX = 0f
        leftTouchStickY = 0f
        rightTouchStickX = 0f
        rightTouchStickY = 0f
        gyroscopeStickX = 0f
        gyroscopeStickY = 0f
    }
    val currentGamepadState = {
        LegacyGamepadStateAdapter.fromRouteOne(
            buttonMask,
            leftStickX,
            leftStickY,
            rightStickX,
            rightStickY
        )
    }

    val transmitGamepadState = { changeKind: GamepadStateChangeKind ->
        if (!isAutomatedInputTestRunning) {
            btManager.submitGamepadState(currentGamepadState(), changeKind)
        }
    }

    val startAutomatedInputTest = {
        if (isConnected && automatedInputTestJob?.isActive != true) {
            isAutomatedInputTestRunning = true
            automatedInputTestElapsedMs = 0L
            dismissLayoutMenu()
            automatedInputTestJob = scope.launch {
                try {
                    withContext(Dispatchers.Default) {
                        val startedAtNanos = SystemClock.elapsedRealtimeNanos()
                        var nextSampleAtNanos = startedAtNanos
                        var lastProgressUpdateMs = -GAMEPAD_TEST_PROGRESS_UPDATE_INTERVAL_MS
                        val intervalNanos =
                            GamepadAutomatedTestTimeline.outputSampleIntervalMs(gamepadReportRate) *
                                1_000_000L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val nowNanos = SystemClock.elapsedRealtimeNanos()
                            val elapsedNanos = (nowNanos - startedAtNanos).coerceAtLeast(0L)
                            val elapsedMs = (elapsedNanos / 1_000_000L).coerceAtMost(
                                GamepadAutomatedTestTimeline.TOTAL_DURATION_MS
                            )
                            if (
                                elapsedMs - lastProgressUpdateMs >= GAMEPAD_TEST_PROGRESS_UPDATE_INTERVAL_MS ||
                                GamepadAutomatedTestTimeline.isComplete(elapsedNanos)
                            ) {
                                withContext(Dispatchers.Main.immediate) {
                                    automatedInputTestElapsedMs = elapsedMs
                                }
                                lastProgressUpdateMs = elapsedMs
                            }
                            val frame = GamepadAutomatedTestTimeline.frameAt(elapsedNanos)
                            btManager.submitGamepadState(frame.state, frame.changeKind)
                            if (GamepadAutomatedTestTimeline.isComplete(elapsedNanos)) break

                            nextSampleAtNanos += intervalNanos
                            val afterSubmitNanos = SystemClock.elapsedRealtimeNanos()
                            if (nextSampleAtNanos <= afterSubmitNanos) {
                                val missedIntervals =
                                    (afterSubmitNanos - nextSampleAtNanos) / intervalNanos + 1L
                                nextSampleAtNanos += missedIntervals * intervalNanos
                            }
                            awaitGamepadTestDeadline(nextSampleAtNanos)
                        }
                    }
                } finally {
                    btManager.submitGamepadState(
                        GamepadState.Neutral,
                        GamepadStateChangeKind.DIGITAL
                    )
                    isAutomatedInputTestRunning = false
                    automatedInputTestJob = null
                }
            }
        }
    }

    fun syncButtonMask() {
        val nextMask = digitalInputAggregator.currentMask()
        if (nextMask != buttonMask) {
            val dpadChanged = ((nextMask xor buttonMask) and (0x0F shl 12)) != 0
            buttonMask = nextMask
            transmitGamepadState(
                if (dpadChanged) GamepadStateChangeKind.DPAD else GamepadStateChangeKind.DIGITAL
            )
        }
    }

    fun pressButtonSource(sourceId: String, bitIndex: Int, playPressHaptic: Boolean) {
        if (digitalInputAggregator.press(sourceId, bitIndex)) {
            syncButtonMask()
            if (playPressHaptic) triggerGamepadDirectPressHaptic()
        }
    }
    val pressButton = { sourceId: String, bitIndex: Int ->
        pressButtonSource(sourceId, bitIndex, true)
    }
    val releaseButton = { sourceId: String, bitIndex: Int ->
        if (digitalInputAggregator.release(sourceId, bitIndex)) {
            syncButtonMask()
        }
    }
    val setLatchedButton = { bitIndex: Int, enabled: Boolean ->
        digitalInputAggregator.setLatched(bitIndex, enabled)
        syncButtonMask()
        triggerGamepadDirectPressHaptic()
    }
    val setDpadSourceMask = { sourceId: String, mask: Int ->
        digitalInputAggregator.setDpadSource(sourceId, mask)
        syncButtonMask()
    }

    fun syncAnalogSources(
        sources: AveragedGamepadAnalogSources,
        isLeft: Boolean,
        changeKind: GamepadStateChangeKind = GamepadStateChangeKind.ANALOG
    ) {
        val (rawX, rawY) = sources.current()
        val (x, y) = if (isFullStickOutputEnabled) {
            gamepadFullMagnitudeInput(rawX, rawY)
        } else {
            rawX to rawY
        }
        if (isLeft) {
            leftTouchStickX = x
            leftTouchStickY = y
        } else {
            rightTouchStickX = x
            rightTouchStickY = y
        }
        val leftGyroX = if (isGamepadGyroscopeMappedToRightStick) 0f else gyroscopeStickX
        val leftGyroY = if (isGamepadGyroscopeMappedToRightStick) 0f else gyroscopeStickY
        val rightGyroX = if (isGamepadGyroscopeMappedToRightStick) gyroscopeStickX else 0f
        val rightGyroY = if (isGamepadGyroscopeMappedToRightStick) gyroscopeStickY else 0f
        combineGamepadStickAndGyroscope(
            leftTouchStickX,
            leftTouchStickY,
            leftGyroX,
            leftGyroY
        ).also { (combinedX, combinedY) ->
            leftStickX = combinedX
            leftStickY = combinedY
        }
        combineGamepadStickAndGyroscope(
            rightTouchStickX,
            rightTouchStickY,
            rightGyroX,
            rightGyroY
        ).also { (combinedX, combinedY) ->
            rightStickX = combinedX
            rightStickY = combinedY
        }
        transmitGamepadState(changeKind)
    }

    fun syncGyroscopeStickOutput(x: Float, y: Float) {
        gyroscopeStickX = x
        gyroscopeStickY = y
        syncAnalogSources(leftAnalogSources, true, GamepadStateChangeKind.GYROSCOPE)
        syncAnalogSources(rightAnalogSources, false, GamepadStateChangeKind.GYROSCOPE)
    }

    val toggleGamepadGyroscope: () -> Boolean = {
        if (!isGamepadGyroscopeAvailable) {
            false
        } else {
            val enabled = !isGamepadGyroscopeEnabled
            isGamepadGyroscopeEnabled = enabled
            sharedPrefs.edit { putBoolean(GAMEPAD_GYROSCOPE_ENABLED_PREF, enabled) }
            sharedPrefs.updateGamepadLayoutSettings(layoutStorageKey) {
                it.copy(gyroscopeEnabled = enabled)
            }
            if (!enabled) syncGyroscopeStickOutput(0f, 0f)
            true
        }
    }

    val setAnalogSourceActive = { sourceId: String, isLeft: Boolean, active: Boolean ->
        val sources = if (isLeft) leftAnalogSources else rightAnalogSources
        if (active) {
            sources.activate(sourceId)
        } else {
            sources.deactivate(sourceId)
        }
        syncAnalogSources(sources, isLeft)
    }
    val moveAnalogSource = { sourceId: String, isLeft: Boolean, x: Float, y: Float ->
        val sources = if (isLeft) leftAnalogSources else rightAnalogSources
        sources.move(sourceId, x, y)
        syncAnalogSources(sources, isLeft)
    }
    LaunchedEffect(controlInstances) {
        digitalInputAggregator.clearTransientSources()
        leftAnalogSources.clear()
        rightAnalogSources.clear()
        leftTouchStickX = 0f
        leftTouchStickY = 0f
        rightTouchStickX = 0f
        rightTouchStickY = 0f
        gyroscopeStickX = 0f
        gyroscopeStickY = 0f
        leftStickX = 0f
        leftStickY = 0f
        rightStickX = 0f
        rightStickY = 0f
        if (controlInstances.none { it.type == GamepadControlType.L3 }) {
            digitalInputAggregator.setLatched(10, false)
        }
        if (controlInstances.none { it.type == GamepadControlType.R3 }) {
            digitalInputAggregator.setLatched(11, false)
        }
        syncButtonMask()
    }
    val currentGyroscopeMappingSettings by rememberUpdatedState(
        GamepadGyroMappingSettings(
            sensitivity = gamepadGyroscopeSensitivity,
            invertHorizontal = isGamepadGyroscopeHorizontalInverted,
            invertVertical = isGamepadGyroscopeVerticalInverted,
            jitterSuppression = gamepadGyroscopeJitterSuppression
        )
    )
    val gyroscopeProcessor = remember { GamepadGyroscopeProcessor() }
    val tiltProcessor = remember { GamepadTiltProcessor() }
    LaunchedEffect(layoutStorageKey) {
        val settings = sharedPrefs.loadGamepadLayoutSettings(layoutStorageKey)
        sharedPrefs.saveGamepadLayoutSettings(layoutStorageKey, settings)
        sharedPrefs.edit {
            putBoolean(GAMEPAD_L3_R3_TOGGLE_MODE_PREF, settings.l3R3ToggleMode)
            putBoolean(GAMEPAD_STICK_CLICK_ENABLED_PREF, settings.stickClickEnabled)
            putBoolean(
                GAMEPAD_FULL_STICK_OUTPUT_ENABLED_PREF,
                settings.fullStickOutputEnabled
            )
            putBoolean(GAMEPAD_TOUCH_ASSIST_ENABLED_PREF, settings.touchAssistEnabled)
            putBoolean(GAMEPAD_GYROSCOPE_ENABLED_PREF, settings.gyroscopeEnabled)
            putInt(GAMEPAD_GYROSCOPE_MODE_PREF, settings.gyroscopeMode.preferenceValue)
            putBoolean(
                GAMEPAD_GYROSCOPE_RIGHT_STICK_PREF,
                settings.gyroscopeMappedToRightStick
            )
            putFloat(GAMEPAD_GYROSCOPE_SENSITIVITY_PREF, settings.gyroscopeSensitivity)
            putBoolean(
                GAMEPAD_GYROSCOPE_INVERT_HORIZONTAL_PREF,
                settings.gyroscopeInvertHorizontal
            )
            putBoolean(
                GAMEPAD_GYROSCOPE_INVERT_VERTICAL_PREF,
                settings.gyroscopeInvertVertical
            )
            putInt(
                GAMEPAD_GYROSCOPE_JITTER_SUPPRESSION_PREF,
                settings.gyroscopeJitterSuppression.preferenceValue
            )
        }
        digitalInputAggregator.clearMapping(10)
        digitalInputAggregator.clearMapping(11)
        syncButtonMask()
        tiltProcessor.clearCalibration()
        syncGyroscopeStickOutput(0f, 0f)
    }
    DisposableEffect(
        sensorManager,
        activeGamepadMotionSensor,
        gamepadGyroscopeMode,
        isGamepadGyroscopeEnabled,
        isLayoutModalOpen,
        isEditMode
    ) {
        if (
            sensorManager == null || activeGamepadMotionSensor == null ||
            !isGamepadGyroscopeEnabled || isLayoutModalOpen || isEditMode
        ) {
            gyroscopeProcessor.reset()
            tiltProcessor.resetOutput()
            syncGyroscopeStickOutput(0f, 0f)
            onDispose { }
        } else {
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    if (event.sensor.type != activeGamepadMotionSensor.type) return
                    btManager.recordGamepadGyroscopeSample()
                    val (x, y) = when (gamepadGyroscopeMode) {
                        GamepadGyroscopeMode.ANGULAR_VELOCITY -> gyroscopeProcessor.process(
                            deviceX = event.values[0],
                            deviceY = event.values[1],
                            timestampNanos = event.timestamp,
                            screenRotation = view.display?.rotation ?: Surface.ROTATION_0,
                            settings = currentGyroscopeMappingSettings
                        )
                        GamepadGyroscopeMode.TILT -> tiltProcessor.process(
                            rotationVector = event.values,
                            timestampNanos = event.timestamp,
                            screenRotation = view.display?.rotation ?: Surface.ROTATION_0,
                            settings = currentGyroscopeMappingSettings
                        )
                    }
                    syncGyroscopeStickOutput(x, y)
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            sensorManager.registerListener(listener, activeGamepadMotionSensor, 10_000)
            onDispose {
                sensorManager.unregisterListener(listener)
                gyroscopeProcessor.reset()
                tiltProcessor.resetOutput()
                syncGyroscopeStickOutput(0f, 0f)
            }
        }
    }
    val touchAssistController = remember { GamepadTouchAssistController() }
    SideEffect {
        touchAssistController.enabled =
            isTouchAssistEnabled && !isEditMode && !isLayoutModalOpen
        touchAssistController.onPress = { sourceId, target ->
            target.onPressAction?.invoke()
            target.mappingIds.forEach { mappingId ->
                pressButtonSource(sourceId, mappingId, false)
            }
            if (isVibrationEnabled) {
                vibrateGamepadHaptic(context, GamepadHaptic.AssistedPress)
            }
        }
        touchAssistController.onRelease = { sourceId, target ->
            target.mappingIds.forEach { mappingId ->
                releaseButton(sourceId, mappingId)
            }
            if (isVibrationEnabled) {
                vibrateGamepadReleaseHaptic(view, context)
            }
        }
    }

    fun hasInitialControl(type: GamepadControlType): Boolean =
        controlInstances.any { it.type == type && it.isInitial }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF28282A))
            .navigationBarsPadding()
            .onSizeChanged { gamepadRootSize = it }
            .motionEventSpy { motionEvent ->
                when (motionEvent.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        if (gamepadReportRate.usesEnhancedPipeline) {
                            view.requestUnbufferedDispatch(motionEvent)
                        }
                        btManager.recordGamepadTouchSamples(1)
                    }
                    MotionEvent.ACTION_POINTER_DOWN -> {
                        btManager.recordGamepadTouchSamples(1)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        btManager.recordGamepadTouchSamples(1 + motionEvent.historySize)
                    }
                }
            }
            .gamepadTouchAssistRouter(touchAssistController)
            .pointerInput(confirmingControlDeleteInstanceId) {
                if (confirmingControlDeleteInstanceId != null) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Final)
                            val hasUnconsumedDown = event.changes.any {
                                it.changedToDownIgnoreConsumed() && !it.isConsumed
                            }
                            if (hasUnconsumedDown) {
                                confirmingControlDeleteInstanceId = null
                            }
                        }
                    }
                }
            }
            .testTag("gamepad_view_root")
    ) {
        if (isAutomatedInputTestRunning) {
            GamepadAutomatedTestProgressOverlay(
                elapsedMs = automatedInputTestElapsedMs,
                totalMs = GamepadAutomatedTestTimeline.TOTAL_DURATION_MS,
                isSimplifiedChineseEnabled = isSimplifiedChineseEnabled,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp)
                    .zIndex(30f)
            )
        }
        CompositionLocalProvider(
            LocalGamepadSnapController provides snapController,
            LocalGamepadTouchAssistController provides touchAssistController,
            LocalGamepadControlDeleteController provides controlDeleteController
        ) {
            Column(modifier = Modifier.fillMaxSize()) {

            // ── Main Controls Body ──
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                val stickpadViewportScale = gamepadStickpadViewportScale(
                    viewportWidthDp = maxWidth.value,
                    viewportHeightDp = maxHeight.value
                )

                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                if (config.leftStickAboveDpad) {
                    // ── XBOX-STYLE OFFSET LAYOUT ──
                    // Left column: Left Stick (top), Dpad (bottom)
                    Column(
                        modifier = Modifier.weight(0.3f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        EditableGamepadAnalogStick(
                            isEditMode = isEditMode,
                            showStick = hasInitialControl(GamepadControlType.LEFT_STICK),
                            showStickButton = hasInitialControl(GamepadControlType.L3),
                            stickButtonLabel = "L3",
                            stickButtonMappingId = 10,
                            isClicked = (buttonMask and (1 shl 10)) != 0,
                            isHeld = (buttonMask and (1 shl 10)) != 0,
                            isStickButtonToggleMode = isL3R3ToggleMode,
                            stickOffsetX = leftStickOffsetX,
                            stickOffsetY = leftStickOffsetY,
                            stickScale = leftStickScale,
                            onStickOffsetChange = { x, y -> leftStickOffsetX = x; leftStickOffsetY = y; saveLayoutPref("${layoutStorageKey}_left_stick_x", x); saveLayoutPref("${layoutStorageKey}_left_stick_y", y) },
                            onStickScaleChange = { s -> leftStickScale = s; saveLayoutPref("${layoutStorageKey}_left_stick_scale", s) },
                            stickButtonOffsetX = leftStickButtonOffsetX,
                            stickButtonOffsetY = leftStickButtonOffsetY,
                            stickButtonScale = leftStickButtonScale,
                            onStickButtonOffsetChange = { x, y -> leftStickButtonOffsetX = x; leftStickButtonOffsetY = y; saveLayoutPref("${layoutStorageKey}_l3_x", x); saveLayoutPref("${layoutStorageKey}_l3_y", y) },
                            onStickButtonScaleChange = { s -> leftStickButtonScale = s; saveLayoutPref("${layoutStorageKey}_l3_scale", s) },
                            onStickActiveChange = { active ->
                                setAnalogSourceActive(initialGamepadControlId(GamepadControlType.LEFT_STICK), true, active)
                            },
                            onMove = { x, y ->
                                moveAnalogSource(initialGamepadControlId(GamepadControlType.LEFT_STICK), true, x, y)
                            },
                            onTouchPressHaptic = triggerTouchAssistStickPressHaptic,
                            onTouchReleaseHaptic = triggerTouchAssistStickReleaseHaptic,
                            onStickClick = {
                                if (isStickClickEnabled) {
                                    val sourceId = "${initialGamepadControlId(GamepadControlType.LEFT_STICK)}_click"
                                    scope.launch {
                                        pressButton(sourceId, 10)
                                        delay(100L.milliseconds)
                                        releaseButton(sourceId, 10)
                                    }
                                }
                            },
                            onToggleHold = { hold ->
                                if (isL3R3ToggleMode) {
                                    setLatchedButton(10, hold)
                                } else if (hold) {
                                    pressButton(initialGamepadControlId(GamepadControlType.L3), 10)
                                } else {
                                    releaseButton(initialGamepadControlId(GamepadControlType.L3), 10)
                                }
                            }
                        )
                        Spacer(Modifier.height(PRIMARY_GAMEPAD_CONTROL_SPACING_DP.dp))
                        if (hasInitialControl(GamepadControlType.DPAD)) EditableComponentWrapper(
                            controlInstanceId = initialGamepadControlId(GamepadControlType.DPAD),
                            isEditMode = isEditMode,
                            offsetX = dpadOffsetX,
                            offsetY = dpadOffsetY,
                            scale = dpadScale,
                            onOffsetChange = { x, y -> dpadOffsetX = x; dpadOffsetY = y; saveLayoutPref("${layoutStorageKey}_dpad_x", x); saveLayoutPref("${layoutStorageKey}_dpad_y", y) },
                            onScaleChange = { s -> dpadScale = s; saveLayoutPref("${layoutStorageKey}_dpad_scale", s) },
                            editFrameInset = GamepadControlType.DPAD.editFrameInset()
                        ) {
                            GamepadDpad(
                                isXboxStyle = true,
                                onDpadChange = { mask ->
                                    setDpadSourceMask(initialGamepadControlId(GamepadControlType.DPAD), mask)
                                    if (mask != 0) triggerGamepadDirectPressHaptic()
                                }
                            )
                        } else {
                            Spacer(Modifier.size(PRIMARY_GAMEPAD_CONTROL_SIZE_DP.dp))
                        }
                    }

                    // Center column: Logo, Center Buttons, Triggers/Bumpers
                    Column(
                        modifier = Modifier.weight(0.4f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Triggers/Bumpers vertically stacked at the top
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.95f)
                                .height(SHOULDER_STACK_LAYOUT_HEIGHT_DP.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .wrapContentHeight(align = Alignment.Top, unbounded = true),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                            // Left shoulder controls use fixed centers so resizing either one cannot reflow the other.
                            GamepadShoulderPair {
                                if (hasInitialControl(GamepadControlType.LEFT_TRIGGER)) EditableComponentWrapper(
                                    controlInstanceId = initialGamepadControlId(GamepadControlType.LEFT_TRIGGER),
                                    isEditMode = isEditMode,
                                    offsetX = leftTriggerOffsetX,
                                    offsetY = leftTriggerOffsetY,
                                    scale = 1f,
                                    onOffsetChange = { x, y -> leftTriggerOffsetX = x; leftTriggerOffsetY = y; saveLayoutPref("${layoutStorageKey}_left_trigger_x", x); saveLayoutPref("${layoutStorageKey}_left_trigger_y", y) },
                                    onScaleChange = {},
                                    allowUniformScale = false,
                                    onResizeDragDp = resizeLeftTrigger
                                ) {
                                    GamepadTriggerButton(
                                        config.leftTrigger,
                                        true,
                                        { pressButton(initialGamepadControlId(GamepadControlType.LEFT_TRIGGER), it) },
                                        { releaseButton(initialGamepadControlId(GamepadControlType.LEFT_TRIGGER), it) },
                                        widthScale = leftTriggerScale * leftTriggerWidthScale,
                                        heightScale = leftTriggerScale * leftTriggerHeightScale
                                    )
                                } else {
                                    Spacer(Modifier.width(120.dp).height(48.dp))
                                }
                                if (hasInitialControl(GamepadControlType.LEFT_BUMPER)) EditableComponentWrapper(
                                    controlInstanceId = initialGamepadControlId(GamepadControlType.LEFT_BUMPER),
                                    isEditMode = isEditMode,
                                    offsetX = leftBumperOffsetX,
                                    offsetY = leftBumperOffsetY,
                                    scale = 1f,
                                    onOffsetChange = { x, y -> leftBumperOffsetX = x; leftBumperOffsetY = y; saveLayoutPref("${layoutStorageKey}_left_bumper_x", x); saveLayoutPref("${layoutStorageKey}_left_bumper_y", y) },
                                    onScaleChange = {},
                                    allowUniformScale = false,
                                    onResizeDragDp = resizeLeftBumper
                                ) {
                                    GamepadBumperButton(
                                        config.leftBumper,
                                        true,
                                        { pressButton(initialGamepadControlId(GamepadControlType.LEFT_BUMPER), it) },
                                        { releaseButton(initialGamepadControlId(GamepadControlType.LEFT_BUMPER), it) },
                                        widthScale = leftBumperScale * leftBumperWidthScale,
                                        heightScale = leftBumperScale * leftBumperHeightScale
                                    )
                                } else {
                                    Spacer(Modifier.width(120.dp).height(34.dp))
                                }
                            }
                            // Right shoulder controls use the same independent fixed-center layout.
                            GamepadShoulderPair {
                                if (hasInitialControl(GamepadControlType.RIGHT_TRIGGER)) EditableComponentWrapper(
                                    controlInstanceId = initialGamepadControlId(GamepadControlType.RIGHT_TRIGGER),
                                    isEditMode = isEditMode,
                                    offsetX = rightTriggerOffsetX,
                                    offsetY = rightTriggerOffsetY,
                                    scale = 1f,
                                    onOffsetChange = { x, y -> rightTriggerOffsetX = x; rightTriggerOffsetY = y; saveLayoutPref("${layoutStorageKey}_right_trigger_x", x); saveLayoutPref("${layoutStorageKey}_right_trigger_y", y) },
                                    onScaleChange = {},
                                    allowUniformScale = false,
                                    onResizeDragDp = resizeRightTrigger
                                ) {
                                    GamepadTriggerButton(
                                        config.rightTrigger,
                                        false,
                                        { pressButton(initialGamepadControlId(GamepadControlType.RIGHT_TRIGGER), it) },
                                        { releaseButton(initialGamepadControlId(GamepadControlType.RIGHT_TRIGGER), it) },
                                        widthScale = rightTriggerScale * rightTriggerWidthScale,
                                        heightScale = rightTriggerScale * rightTriggerHeightScale
                                    )
                                } else {
                                    Spacer(Modifier.width(120.dp).height(48.dp))
                                }
                                if (hasInitialControl(GamepadControlType.RIGHT_BUMPER)) EditableComponentWrapper(
                                    controlInstanceId = initialGamepadControlId(GamepadControlType.RIGHT_BUMPER),
                                    isEditMode = isEditMode,
                                    offsetX = rightBumperOffsetX,
                                    offsetY = rightBumperOffsetY,
                                    scale = 1f,
                                    onOffsetChange = { x, y -> rightBumperOffsetX = x; rightBumperOffsetY = y; saveLayoutPref("${layoutStorageKey}_right_bumper_x", x); saveLayoutPref("${layoutStorageKey}_right_bumper_y", y) },
                                    onScaleChange = {},
                                    allowUniformScale = false,
                                    onResizeDragDp = resizeRightBumper
                                ) {
                                    GamepadBumperButton(
                                        config.rightBumper,
                                        false,
                                        { pressButton(initialGamepadControlId(GamepadControlType.RIGHT_BUMPER), it) },
                                        { releaseButton(initialGamepadControlId(GamepadControlType.RIGHT_BUMPER), it) },
                                        widthScale = rightBumperScale * rightBumperWidthScale,
                                        heightScale = rightBumperScale * rightBumperHeightScale
                                    )
                                } else {
                                    Spacer(Modifier.width(120.dp).height(34.dp))
                                }
                            }
                        }
                        }

                        Spacer(Modifier.height(CENTER_CLUSTER_VERTICAL_SPACING_DP.dp))

                        // Large Xbox Guide button
                        if (hasInitialControl(GamepadControlType.GUIDE)) EditableComponentWrapper(
                            controlInstanceId = initialGamepadControlId(GamepadControlType.GUIDE),
                            isEditMode = isEditMode,
                            offsetX = guideOffsetX,
                            offsetY = guideOffsetY,
                            scale = guideScale,
                            onOffsetChange = { x, y -> guideOffsetX = x; guideOffsetY = y; saveLayoutPref("${layoutStorageKey}_guide_x", x); saveLayoutPref("${layoutStorageKey}_guide_y", y) },
                            onScaleChange = { s -> guideScale = s; saveLayoutPref("${layoutStorageKey}_guide_scale", s) }
                        ) {
                            XboxLogoGuideButton(
                                config.guideButton,
                                { pressButton(initialGamepadControlId(GamepadControlType.GUIDE), it) },
                                { releaseButton(initialGamepadControlId(GamepadControlType.GUIDE), it) }
                            )
                        } else {
                            Spacer(Modifier.size(50.dp))
                        }

                        Spacer(Modifier.height(CENTER_CLUSTER_VERTICAL_SPACING_DP.dp))

                        // Center buttons (Select, Share, Start) flanking the area below Guide
                        Row(
                            modifier = Modifier.fillMaxWidth(0.75f),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (hasInitialControl(GamepadControlType.SELECT)) EditableComponentWrapper(
                                controlInstanceId = initialGamepadControlId(GamepadControlType.SELECT),
                                isEditMode = isEditMode,
                                offsetX = selectOffsetX,
                                offsetY = selectOffsetY,
                                scale = selectScale,
                                onOffsetChange = { x, y -> selectOffsetX = x; selectOffsetY = y; saveLayoutPref("${layoutStorageKey}_select_x", x); saveLayoutPref("${layoutStorageKey}_select_y", y) },
                                onScaleChange = { s -> selectScale = s; saveLayoutPref("${layoutStorageKey}_select_scale", s) }
                            ) {
                                GamepadCenterButton(
                                    config.selectButton,
                                    { pressButton(initialGamepadControlId(GamepadControlType.SELECT), it) },
                                    { releaseButton(initialGamepadControlId(GamepadControlType.SELECT), it) }
                                )
                            } else {
                                Spacer(Modifier.size(32.dp))
                            }
                            if (hasInitialControl(GamepadControlType.SHARE)) EditableComponentWrapper(
                                controlInstanceId = initialGamepadControlId(GamepadControlType.SHARE),
                                isEditMode = isEditMode,
                                offsetX = shareOffsetX,
                                offsetY = shareOffsetY,
                                scale = shareScale,
                                onOffsetChange = { x, y -> shareOffsetX = x; shareOffsetY = y; saveLayoutPref("${layoutStorageKey}_share_x", x); saveLayoutPref("${layoutStorageKey}_share_y", y) },
                                onScaleChange = { s -> shareScale = s; saveLayoutPref("${layoutStorageKey}_share_scale", s) }
                            ) {
                                GamepadCenterButton(
                                    config.shareButton,
                                    { pressButton(initialGamepadControlId(GamepadControlType.SHARE), it) },
                                    { releaseButton(initialGamepadControlId(GamepadControlType.SHARE), it) }
                                )
                            } else {
                                Spacer(Modifier.size(32.dp))
                            }
                            if (hasInitialControl(GamepadControlType.START)) EditableComponentWrapper(
                                controlInstanceId = initialGamepadControlId(GamepadControlType.START),
                                isEditMode = isEditMode,
                                offsetX = startOffsetX,
                                offsetY = startOffsetY,
                                scale = startScale,
                                onOffsetChange = { x, y -> startOffsetX = x; startOffsetY = y; saveLayoutPref("${layoutStorageKey}_start_x", x); saveLayoutPref("${layoutStorageKey}_start_y", y) },
                                onScaleChange = { s -> startScale = s; saveLayoutPref("${layoutStorageKey}_start_scale", s) }
                            ) {
                                GamepadCenterButton(
                                    config.startButton,
                                    { pressButton(initialGamepadControlId(GamepadControlType.START), it) },
                                    { releaseButton(initialGamepadControlId(GamepadControlType.START), it) }
                                )
                            } else {
                                Spacer(Modifier.size(32.dp))
                            }
                        }
                    }

                    // Right column: Face Buttons (top), Right Stick (bottom)
                    Column(
                        modifier = Modifier.weight(0.3f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (hasInitialControl(GamepadControlType.FACE_BUTTONS)) EditableComponentWrapper(
                            controlInstanceId = initialGamepadControlId(GamepadControlType.FACE_BUTTONS),
                            isEditMode = isEditMode,
                            offsetX = faceButtonsOffsetX,
                            offsetY = faceButtonsOffsetY,
                            scale = faceButtonsScale,
                            onOffsetChange = { x, y -> faceButtonsOffsetX = x; faceButtonsOffsetY = y; saveLayoutPref("${layoutStorageKey}_face_buttons_x", x); saveLayoutPref("${layoutStorageKey}_face_buttons_y", y) },
                            onScaleChange = { s -> faceButtonsScale = s; saveLayoutPref("${layoutStorageKey}_face_buttons_scale", s) },
                            editFrameInset = GamepadControlType.FACE_BUTTONS.editFrameInset()
                        ) {
                            FaceButtonsDiamond(
                                config = config,
                                isXboxStyle = true,
                                onPress = { pressButton(initialGamepadControlId(GamepadControlType.FACE_BUTTONS), it) },
                                onRelease = { releaseButton(initialGamepadControlId(GamepadControlType.FACE_BUTTONS), it) }
                            )
                        } else {
                            Spacer(Modifier.size(PRIMARY_GAMEPAD_CONTROL_SIZE_DP.dp))
                        }
                        Spacer(Modifier.height(PRIMARY_GAMEPAD_CONTROL_SPACING_DP.dp))
                        EditableGamepadAnalogStick(
                            isEditMode = isEditMode,
                            showStick = hasInitialControl(GamepadControlType.RIGHT_STICK),
                            showStickButton = hasInitialControl(GamepadControlType.R3),
                            stickButtonLabel = "R3",
                            stickButtonMappingId = 11,
                            isClicked = (buttonMask and (1 shl 11)) != 0,
                            isHeld = (buttonMask and (1 shl 11)) != 0,
                            isStickButtonToggleMode = isL3R3ToggleMode,
                            stickOffsetX = rightStickOffsetX,
                            stickOffsetY = rightStickOffsetY,
                            stickScale = rightStickScale,
                            onStickOffsetChange = { x, y -> rightStickOffsetX = x; rightStickOffsetY = y; saveLayoutPref("${layoutStorageKey}_right_stick_x", x); saveLayoutPref("${layoutStorageKey}_right_stick_y", y) },
                            onStickScaleChange = { s -> rightStickScale = s; saveLayoutPref("${layoutStorageKey}_right_stick_scale", s) },
                            stickButtonOffsetX = rightStickButtonOffsetX,
                            stickButtonOffsetY = rightStickButtonOffsetY,
                            stickButtonScale = rightStickButtonScale,
                            onStickButtonOffsetChange = { x, y -> rightStickButtonOffsetX = x; rightStickButtonOffsetY = y; saveLayoutPref("${layoutStorageKey}_r3_x", x); saveLayoutPref("${layoutStorageKey}_r3_y", y) },
                            onStickButtonScaleChange = { s -> rightStickButtonScale = s; saveLayoutPref("${layoutStorageKey}_r3_scale", s) },
                            onStickActiveChange = { active ->
                                setAnalogSourceActive(initialGamepadControlId(GamepadControlType.RIGHT_STICK), false, active)
                            },
                            onMove = { x, y ->
                                moveAnalogSource(initialGamepadControlId(GamepadControlType.RIGHT_STICK), false, x, y)
                            },
                            onTouchPressHaptic = triggerTouchAssistStickPressHaptic,
                            onTouchReleaseHaptic = triggerTouchAssistStickReleaseHaptic,
                            onStickClick = {
                                if (isStickClickEnabled) {
                                    val sourceId = "${initialGamepadControlId(GamepadControlType.RIGHT_STICK)}_click"
                                    scope.launch {
                                        pressButton(sourceId, 11)
                                        delay(100L.milliseconds)
                                        releaseButton(sourceId, 11)
                                    }
                                }
                            },
                            onToggleHold = { hold ->
                                if (isL3R3ToggleMode) {
                                    setLatchedButton(11, hold)
                                } else if (hold) {
                                    pressButton(initialGamepadControlId(GamepadControlType.R3), 11)
                                } else {
                                    releaseButton(initialGamepadControlId(GamepadControlType.R3), 11)
                                }
                            }
                        )
                    }
                } else {
                    // ── PLAYSTATION-STYLE SYMMETRICAL LAYOUT ──
                    // Left Column: split Dpad (top), Left Stick (bottom)
                    Column(
                        modifier = Modifier.weight(0.3f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (hasInitialControl(GamepadControlType.DPAD)) EditableComponentWrapper(
                            controlInstanceId = initialGamepadControlId(GamepadControlType.DPAD),
                            isEditMode = isEditMode,
                            offsetX = dpadOffsetX,
                            offsetY = dpadOffsetY,
                            scale = dpadScale,
                            onOffsetChange = { x, y -> dpadOffsetX = x; dpadOffsetY = y; saveLayoutPref("${layoutStorageKey}_dpad_x", x); saveLayoutPref("${layoutStorageKey}_dpad_y", y) },
                            onScaleChange = { s -> dpadScale = s; saveLayoutPref("${layoutStorageKey}_dpad_scale", s) },
                            editFrameInset = GamepadControlType.DPAD.editFrameInset()
                        ) {
                            GamepadDpad(
                                isXboxStyle = false,
                                onDpadChange = { mask ->
                                    setDpadSourceMask(initialGamepadControlId(GamepadControlType.DPAD), mask)
                                    if (mask != 0) triggerGamepadDirectPressHaptic()
                                }
                            )
                        } else {
                            Spacer(Modifier.size(PRIMARY_GAMEPAD_CONTROL_SIZE_DP.dp))
                        }
                        Spacer(Modifier.height(PRIMARY_GAMEPAD_CONTROL_SPACING_DP.dp))
                        EditableGamepadAnalogStick(
                            isEditMode = isEditMode,
                            showStick = hasInitialControl(GamepadControlType.LEFT_STICK),
                            showStickButton = hasInitialControl(GamepadControlType.L3),
                            stickButtonLabel = "L3",
                            stickButtonMappingId = 10,
                            isClicked = (buttonMask and (1 shl 10)) != 0,
                            isHeld = (buttonMask and (1 shl 10)) != 0,
                            isStickButtonToggleMode = isL3R3ToggleMode,
                            stickOffsetX = leftStickOffsetX,
                            stickOffsetY = leftStickOffsetY,
                            stickScale = leftStickScale,
                            onStickOffsetChange = { x, y -> leftStickOffsetX = x; leftStickOffsetY = y; saveLayoutPref("${layoutStorageKey}_left_stick_x", x); saveLayoutPref("${layoutStorageKey}_left_stick_y", y) },
                            onStickScaleChange = { s -> leftStickScale = s; saveLayoutPref("${layoutStorageKey}_left_stick_scale", s) },
                            stickButtonOffsetX = leftStickButtonOffsetX,
                            stickButtonOffsetY = leftStickButtonOffsetY,
                            stickButtonScale = leftStickButtonScale,
                            onStickButtonOffsetChange = { x, y -> leftStickButtonOffsetX = x; leftStickButtonOffsetY = y; saveLayoutPref("${layoutStorageKey}_l3_x", x); saveLayoutPref("${layoutStorageKey}_l3_y", y) },
                            onStickButtonScaleChange = { s -> leftStickButtonScale = s; saveLayoutPref("${layoutStorageKey}_l3_scale", s) },
                            onStickActiveChange = { active ->
                                setAnalogSourceActive(initialGamepadControlId(GamepadControlType.LEFT_STICK), true, active)
                            },
                            onMove = { x, y ->
                                moveAnalogSource(initialGamepadControlId(GamepadControlType.LEFT_STICK), true, x, y)
                            },
                            onTouchPressHaptic = triggerTouchAssistStickPressHaptic,
                            onTouchReleaseHaptic = triggerTouchAssistStickReleaseHaptic,
                            onStickClick = {
                                if (isStickClickEnabled) {
                                    val sourceId = "${initialGamepadControlId(GamepadControlType.LEFT_STICK)}_click"
                                    scope.launch {
                                        pressButton(sourceId, 10)
                                        delay(100L.milliseconds)
                                        releaseButton(sourceId, 10)
                                    }
                                }
                            },
                            onToggleHold = { hold ->
                                if (isL3R3ToggleMode) {
                                    setLatchedButton(10, hold)
                                } else if (hold) {
                                    pressButton(initialGamepadControlId(GamepadControlType.L3), 10)
                                } else {
                                    releaseButton(initialGamepadControlId(GamepadControlType.L3), 10)
                                }
                            }
                        )
                    }

                    // Center Column: PS Button + L1/L2 R1/R2, Right Stick/Left Stick spacing
                    Column(
                        modifier = Modifier.weight(0.4f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Bumper/Trigger stacks
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.95f)
                                .height(SHOULDER_STACK_LAYOUT_HEIGHT_DP.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .wrapContentHeight(align = Alignment.Top, unbounded = true),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.Top
                            ) {
                            GamepadShoulderPair {
                                if (hasInitialControl(GamepadControlType.LEFT_TRIGGER)) EditableComponentWrapper(
                                    controlInstanceId = initialGamepadControlId(GamepadControlType.LEFT_TRIGGER),
                                    isEditMode = isEditMode,
                                    offsetX = leftTriggerOffsetX,
                                    offsetY = leftTriggerOffsetY,
                                    scale = 1f,
                                    onOffsetChange = { x, y -> leftTriggerOffsetX = x; leftTriggerOffsetY = y; saveLayoutPref("${layoutStorageKey}_left_trigger_x", x); saveLayoutPref("${layoutStorageKey}_left_trigger_y", y) },
                                    onScaleChange = {},
                                    allowUniformScale = false,
                                    onResizeDragDp = resizeLeftTrigger
                                ) {
                                    GamepadTriggerButton(
                                        config.leftTrigger,
                                        true,
                                        { pressButton(initialGamepadControlId(GamepadControlType.LEFT_TRIGGER), it) },
                                        { releaseButton(initialGamepadControlId(GamepadControlType.LEFT_TRIGGER), it) },
                                        widthScale = leftTriggerScale * leftTriggerWidthScale,
                                        heightScale = leftTriggerScale * leftTriggerHeightScale
                                    )
                                } else {
                                    Spacer(Modifier.width(120.dp).height(48.dp))
                                }
                                if (hasInitialControl(GamepadControlType.LEFT_BUMPER)) EditableComponentWrapper(
                                    controlInstanceId = initialGamepadControlId(GamepadControlType.LEFT_BUMPER),
                                    isEditMode = isEditMode,
                                    offsetX = leftBumperOffsetX,
                                    offsetY = leftBumperOffsetY,
                                    scale = 1f,
                                    onOffsetChange = { x, y -> leftBumperOffsetX = x; leftBumperOffsetY = y; saveLayoutPref("${layoutStorageKey}_left_bumper_x", x); saveLayoutPref("${layoutStorageKey}_left_bumper_y", y) },
                                    onScaleChange = {},
                                    allowUniformScale = false,
                                    onResizeDragDp = resizeLeftBumper
                                ) {
                                    GamepadBumperButton(
                                        config.leftBumper,
                                        true,
                                        { pressButton(initialGamepadControlId(GamepadControlType.LEFT_BUMPER), it) },
                                        { releaseButton(initialGamepadControlId(GamepadControlType.LEFT_BUMPER), it) },
                                        widthScale = leftBumperScale * leftBumperWidthScale,
                                        heightScale = leftBumperScale * leftBumperHeightScale
                                    )
                                } else {
                                    Spacer(Modifier.width(120.dp).height(34.dp))
                                }
                            }

                            GamepadShoulderPair {
                                if (hasInitialControl(GamepadControlType.RIGHT_TRIGGER)) EditableComponentWrapper(
                                    controlInstanceId = initialGamepadControlId(GamepadControlType.RIGHT_TRIGGER),
                                    isEditMode = isEditMode,
                                    offsetX = rightTriggerOffsetX,
                                    offsetY = rightTriggerOffsetY,
                                    scale = 1f,
                                    onOffsetChange = { x, y -> rightTriggerOffsetX = x; rightTriggerOffsetY = y; saveLayoutPref("${layoutStorageKey}_right_trigger_x", x); saveLayoutPref("${layoutStorageKey}_right_trigger_y", y) },
                                    onScaleChange = {},
                                    allowUniformScale = false,
                                    onResizeDragDp = resizeRightTrigger
                                ) {
                                    GamepadTriggerButton(
                                        config.rightTrigger,
                                        false,
                                        { pressButton(initialGamepadControlId(GamepadControlType.RIGHT_TRIGGER), it) },
                                        { releaseButton(initialGamepadControlId(GamepadControlType.RIGHT_TRIGGER), it) },
                                        widthScale = rightTriggerScale * rightTriggerWidthScale,
                                        heightScale = rightTriggerScale * rightTriggerHeightScale
                                    )
                                } else {
                                    Spacer(Modifier.width(120.dp).height(48.dp))
                                }
                                if (hasInitialControl(GamepadControlType.RIGHT_BUMPER)) EditableComponentWrapper(
                                    controlInstanceId = initialGamepadControlId(GamepadControlType.RIGHT_BUMPER),
                                    isEditMode = isEditMode,
                                    offsetX = rightBumperOffsetX,
                                    offsetY = rightBumperOffsetY,
                                    scale = 1f,
                                    onOffsetChange = { x, y -> rightBumperOffsetX = x; rightBumperOffsetY = y; saveLayoutPref("${layoutStorageKey}_right_bumper_x", x); saveLayoutPref("${layoutStorageKey}_right_bumper_y", y) },
                                    onScaleChange = {},
                                    allowUniformScale = false,
                                    onResizeDragDp = resizeRightBumper
                                ) {
                                    GamepadBumperButton(
                                        config.rightBumper,
                                        false,
                                        { pressButton(initialGamepadControlId(GamepadControlType.RIGHT_BUMPER), it) },
                                        { releaseButton(initialGamepadControlId(GamepadControlType.RIGHT_BUMPER), it) },
                                        widthScale = rightBumperScale * rightBumperWidthScale,
                                        heightScale = rightBumperScale * rightBumperHeightScale
                                    )
                                } else {
                                    Spacer(Modifier.width(120.dp).height(34.dp))
                                }
                            }
                        }
                        }

                        Spacer(Modifier.height(CENTER_CLUSTER_VERTICAL_SPACING_DP.dp))

                        // PlayStation Guide button
                        if (hasInitialControl(GamepadControlType.GUIDE)) EditableComponentWrapper(
                            controlInstanceId = initialGamepadControlId(GamepadControlType.GUIDE),
                            isEditMode = isEditMode,
                            offsetX = guideOffsetX,
                            offsetY = guideOffsetY,
                            scale = guideScale,
                            onOffsetChange = { x, y -> guideOffsetX = x; guideOffsetY = y; saveLayoutPref("${layoutStorageKey}_guide_x", x); saveLayoutPref("${layoutStorageKey}_guide_y", y) },
                            onScaleChange = { s -> guideScale = s; saveLayoutPref("${layoutStorageKey}_guide_scale", s) }
                        ) {
                            PlayStationLogoButton(
                                config.guideButton,
                                { pressButton(initialGamepadControlId(GamepadControlType.GUIDE), it) },
                                { releaseButton(initialGamepadControlId(GamepadControlType.GUIDE), it) }
                            )
                        } else {
                            Spacer(Modifier.size(50.dp))
                        }

                        Spacer(Modifier.height(CENTER_CLUSTER_VERTICAL_SPACING_DP.dp))

                        // Center buttons (Select, Share, Start) flanking the area below Guide
                        Row(
                            modifier = Modifier.fillMaxWidth(0.75f),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (hasInitialControl(GamepadControlType.SELECT)) EditableComponentWrapper(
                                controlInstanceId = initialGamepadControlId(GamepadControlType.SELECT),
                                isEditMode = isEditMode,
                                offsetX = selectOffsetX,
                                offsetY = selectOffsetY,
                                scale = selectScale,
                                onOffsetChange = { x, y -> selectOffsetX = x; selectOffsetY = y; saveLayoutPref("${layoutStorageKey}_select_x", x); saveLayoutPref("${layoutStorageKey}_select_y", y) },
                                onScaleChange = { s -> selectScale = s; saveLayoutPref("${layoutStorageKey}_select_scale", s) }
                            ) {
                                GamepadCenterButton(
                                    config.selectButton,
                                    { pressButton(initialGamepadControlId(GamepadControlType.SELECT), it) },
                                    { releaseButton(initialGamepadControlId(GamepadControlType.SELECT), it) }
                                )
                            } else {
                                Spacer(Modifier.size(32.dp))
                            }
                            if (hasInitialControl(GamepadControlType.SHARE)) EditableComponentWrapper(
                                controlInstanceId = initialGamepadControlId(GamepadControlType.SHARE),
                                isEditMode = isEditMode,
                                offsetX = shareOffsetX,
                                offsetY = shareOffsetY,
                                scale = shareScale,
                                onOffsetChange = { x, y -> shareOffsetX = x; shareOffsetY = y; saveLayoutPref("${layoutStorageKey}_share_x", x); saveLayoutPref("${layoutStorageKey}_share_y", y) },
                                onScaleChange = { s -> shareScale = s; saveLayoutPref("${layoutStorageKey}_share_scale", s) }
                            ) {
                                GamepadCenterButton(
                                    config.shareButton,
                                    { pressButton(initialGamepadControlId(GamepadControlType.SHARE), it) },
                                    { releaseButton(initialGamepadControlId(GamepadControlType.SHARE), it) }
                                )
                            } else {
                                Spacer(Modifier.size(32.dp))
                            }
                            if (hasInitialControl(GamepadControlType.START)) EditableComponentWrapper(
                                controlInstanceId = initialGamepadControlId(GamepadControlType.START),
                                isEditMode = isEditMode,
                                offsetX = startOffsetX,
                                offsetY = startOffsetY,
                                scale = startScale,
                                onOffsetChange = { x, y -> startOffsetX = x; startOffsetY = y; saveLayoutPref("${layoutStorageKey}_start_x", x); saveLayoutPref("${layoutStorageKey}_start_y", y) },
                                onScaleChange = { s -> startScale = s; saveLayoutPref("${layoutStorageKey}_start_scale", s) }
                            ) {
                                GamepadCenterButton(
                                    config.startButton,
                                    { pressButton(initialGamepadControlId(GamepadControlType.START), it) },
                                    { releaseButton(initialGamepadControlId(GamepadControlType.START), it) }
                                )
                            } else {
                                Spacer(Modifier.size(32.dp))
                            }
                        }
                    }

                    // Right Column: Face Buttons (top), Right Stick (bottom)
                    Column(
                        modifier = Modifier.weight(0.3f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (hasInitialControl(GamepadControlType.FACE_BUTTONS)) EditableComponentWrapper(
                            controlInstanceId = initialGamepadControlId(GamepadControlType.FACE_BUTTONS),
                            isEditMode = isEditMode,
                            offsetX = faceButtonsOffsetX,
                            offsetY = faceButtonsOffsetY,
                            scale = faceButtonsScale,
                            onOffsetChange = { x, y -> faceButtonsOffsetX = x; faceButtonsOffsetY = y; saveLayoutPref("${layoutStorageKey}_face_buttons_x", x); saveLayoutPref("${layoutStorageKey}_face_buttons_y", y) },
                            onScaleChange = { s -> faceButtonsScale = s; saveLayoutPref("${layoutStorageKey}_face_buttons_scale", s) },
                            editFrameInset = GamepadControlType.FACE_BUTTONS.editFrameInset()
                        ) {
                            FaceButtonsDiamond(
                                config = config,
                                isXboxStyle = false,
                                onPress = { pressButton(initialGamepadControlId(GamepadControlType.FACE_BUTTONS), it) },
                                onRelease = { releaseButton(initialGamepadControlId(GamepadControlType.FACE_BUTTONS), it) }
                            )
                        } else {
                            Spacer(Modifier.size(PRIMARY_GAMEPAD_CONTROL_SIZE_DP.dp))
                        }
                        Spacer(Modifier.height(PRIMARY_GAMEPAD_CONTROL_SPACING_DP.dp))
                        EditableGamepadAnalogStick(
                            isEditMode = isEditMode,
                            showStick = hasInitialControl(GamepadControlType.RIGHT_STICK),
                            showStickButton = hasInitialControl(GamepadControlType.R3),
                            stickButtonLabel = "R3",
                            stickButtonMappingId = 11,
                            isClicked = (buttonMask and (1 shl 11)) != 0,
                            isHeld = (buttonMask and (1 shl 11)) != 0,
                            isStickButtonToggleMode = isL3R3ToggleMode,
                            stickOffsetX = rightStickOffsetX,
                            stickOffsetY = rightStickOffsetY,
                            stickScale = rightStickScale,
                            onStickOffsetChange = { x, y -> rightStickOffsetX = x; rightStickOffsetY = y; saveLayoutPref("${layoutStorageKey}_right_stick_x", x); saveLayoutPref("${layoutStorageKey}_right_stick_y", y) },
                            onStickScaleChange = { s -> rightStickScale = s; saveLayoutPref("${layoutStorageKey}_right_stick_scale", s) },
                            stickButtonOffsetX = rightStickButtonOffsetX,
                            stickButtonOffsetY = rightStickButtonOffsetY,
                            stickButtonScale = rightStickButtonScale,
                            onStickButtonOffsetChange = { x, y -> rightStickButtonOffsetX = x; rightStickButtonOffsetY = y; saveLayoutPref("${layoutStorageKey}_r3_x", x); saveLayoutPref("${layoutStorageKey}_r3_y", y) },
                            onStickButtonScaleChange = { s -> rightStickButtonScale = s; saveLayoutPref("${layoutStorageKey}_r3_scale", s) },
                            onStickActiveChange = { active ->
                                setAnalogSourceActive(initialGamepadControlId(GamepadControlType.RIGHT_STICK), false, active)
                            },
                            onMove = { x, y ->
                                moveAnalogSource(initialGamepadControlId(GamepadControlType.RIGHT_STICK), false, x, y)
                            },
                            onTouchPressHaptic = triggerTouchAssistStickPressHaptic,
                            onTouchReleaseHaptic = triggerTouchAssistStickReleaseHaptic,
                            onStickClick = {
                                if (isStickClickEnabled) {
                                    val sourceId = "${initialGamepadControlId(GamepadControlType.RIGHT_STICK)}_click"
                                    scope.launch {
                                        pressButton(sourceId, 11)
                                        delay(100L.milliseconds)
                                        releaseButton(sourceId, 11)
                                    }
                                }
                            },
                            onToggleHold = { hold ->
                                if (isL3R3ToggleMode) {
                                    setLatchedButton(11, hold)
                                } else if (hold) {
                                    pressButton(initialGamepadControlId(GamepadControlType.R3), 11)
                                } else {
                                    releaseButton(initialGamepadControlId(GamepadControlType.R3), 11)
                                }
                            }
                        )
                    }
                }
            }
            controlInstances
                .asSequence()
                .filterNot { it.isInitial }
                .sortedBy { it.creationOrder }
                .forEach { instance ->
                    key(instance.id) {
                        AdditionalGamepadControlInstance(
                            instance = instance,
                            config = config,
                            stickpadViewportScale = stickpadViewportScale,
                            isEditMode = isEditMode,
                            buttonMask = buttonMask,
                            isL3R3ToggleMode = isL3R3ToggleMode,
                            isStickClickEnabled = isStickClickEnabled,
                            isGyroscopeAvailable = isGamepadGyroscopeAvailable,
                            isGyroscopeEnabled = isGamepadGyroscopeEnabled,
                            onUpdate = updateControlInstance,
                            onPress = pressButton,
                            onRelease = releaseButton,
                            onSetLatched = setLatchedButton,
                            onDpadChange = setDpadSourceMask,
                            onAnalogActiveChange = setAnalogSourceActive,
                            onAnalogMove = moveAnalogSource,
                            onTouchPressHaptic = triggerTouchAssistStickPressHaptic,
                            onTouchReleaseHaptic = triggerTouchAssistStickReleaseHaptic,
                            onPressHaptic = triggerGamepadDirectPressHaptic,
                            onToggleGyroscope = toggleGamepadGyroscope
                        )
                    }
                }
            }
        }

        if (isEditMode && snapGuides.isNotEmpty() && !showLayoutMenu && renameTargetId == null) {
            GamepadSnapGuideOverlay(
                guides = snapGuides,
                modifier = Modifier
                    .matchParentSize()
                    .zIndex(4f)
            )
        }
        if (showLayoutMenu || renameTargetId != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(10f)
            ) {
                if (showLayoutMenu) {
                    GamepadLayoutMenu(
                        xboxProfiles = xboxLayoutProfiles,
                        psProfiles = psLayoutProfiles,
                        selectedLayoutId = activeLayout.id,
                        isEditing = true,
                        confirmingDeleteLayoutId = deleteTargetId,
                        confirmingSelectedDelete = confirmingSelectedDelete,
                        multiSelectedLayoutIds = multiSelectedLayoutIds,
                        isVibrationEnabled = isVibrationEnabled,
                        isL3R3ToggleMode = isL3R3ToggleMode,
                        isStickClickEnabled = isStickClickEnabled,
                        isFullStickOutputEnabled = isFullStickOutputEnabled,
                        isTouchAssistEnabled = isTouchAssistEnabled,
                        gamepadReportRate = gamepadReportRate,
                        isGyroscopeAvailable = isGamepadGyroscopeAvailable,
                        isGyroscopeEnabled = isGamepadGyroscopeEnabled,
                        gyroscopeMode = gamepadGyroscopeMode,
                        isGyroscopeMappedToRightStick = isGamepadGyroscopeMappedToRightStick,
                        gyroscopeSensitivity = gamepadGyroscopeSensitivity,
                        isGyroscopeHorizontalInverted = isGamepadGyroscopeHorizontalInverted,
                        isGyroscopeVerticalInverted = isGamepadGyroscopeVerticalInverted,
                        gyroscopeJitterSuppression = gamepadGyroscopeJitterSuppression,
                        isSimplifiedChineseEnabled = isSimplifiedChineseEnabled,
                        showFeatureListPage = showFeatureListPage,
                        showGyroscopeMappingPage = showGyroscopeMappingPage,
                        showBlankListPage = showBlankListPage,
                        showSpecialControlListPage = showSpecialControlListPage,
                        isLayoutEditMode = isEditMode,
                        selectedControlType = selectedControlType,
                        selectedControlCount = selectedControlCount,
                        isSnapAlignmentEnabled = snapAlignmentEnabled,
                        canUndoLayoutEdit = canUndoLayoutEdit,
                        canRedoLayoutEdit = canRedoLayoutEdit,
                        canEditActiveLayout = canEditLayout,
                        hasUnsavedLayoutChanges = hasUnsavedLayoutChanges,
                        showUnsavedSaveWarning = hasUnsavedLayoutChanges && pendingUnsavedDiscardActionKey != null,
                        unsavedWarningPulse = unsavedWarningPulse,
                        isConnected = isConnected,
                        deviceName = deviceName,
                        isAutomatedInputTestRunning = isAutomatedInputTestRunning,
                        activeConfigId = activeLayout.configId,
                        canMoveActiveLayoutUp = canMoveActiveLayoutUp,
                        canMoveActiveLayoutDown = canMoveActiveLayoutDown,
                        onClearDeleteConfirm = {
                            deleteTargetId = null
                            confirmingSelectedDelete = false
                        },
                        onExitMultiSelect = {
                            multiSelectedLayoutIds = emptyList()
                            deleteTargetId = null
                            confirmingSelectedDelete = false
                        },
                        onClearControlSelection = {
                            selectedControlTypeId = null
                        },
                        onSelectControlType = { type ->
                            selectedControlTypeId = type.storageId
                            triggerVibration(12)
                        },
                        onAddControl = addSelectedControlInstance,
                        onRemoveControl = removeSelectedControlInstance,
                        onToggleVibration = toggleVibration,
                        onRotateScreen = {
                            rotateGamepadScreen(context)?.let { orientation ->
                                tiltProcessor.clearCalibration()
                                val reverseLandscape =
                                    orientation == ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                                sharedPrefs.edit {
                                    putBoolean(
                                        GAMEPAD_REVERSE_LANDSCAPE_ENABLED_PREF,
                                        reverseLandscape
                                    )
                                }
                                triggerVibration(18)
                            }
                        },
                        onToggleLanguage = {
                            val enabled = !isSimplifiedChineseEnabled
                            isSimplifiedChineseEnabled = enabled
                            sharedPrefs.edit {
                                putBoolean(GAMEPAD_SIMPLIFIED_CHINESE_ENABLED_PREF, enabled)
                            }
                            triggerVibration(15)
                        },
                        onToggleL3R3Mode = {
                            digitalInputAggregator.clearMapping(10)
                            digitalInputAggregator.clearMapping(11)
                            syncButtonMask()
                            val enabled = !isL3R3ToggleMode
                            isL3R3ToggleMode = enabled
                            sharedPrefs.edit { putBoolean(GAMEPAD_L3_R3_TOGGLE_MODE_PREF, enabled) }
                            sharedPrefs.updateGamepadLayoutSettings(layoutStorageKey) {
                                it.copy(l3R3ToggleMode = enabled)
                            }
                            triggerVibration(15)
                        },
                        onToggleStickClick = {
                            val enabled = !isStickClickEnabled
                            isStickClickEnabled = enabled
                            sharedPrefs.edit { putBoolean(GAMEPAD_STICK_CLICK_ENABLED_PREF, enabled) }
                            sharedPrefs.updateGamepadLayoutSettings(layoutStorageKey) {
                                it.copy(stickClickEnabled = enabled)
                            }
                            triggerVibration(15)
                        },
                        onToggleFullStickOutput = {
                            val enabled = !isFullStickOutputEnabled
                            isFullStickOutputEnabled = enabled
                            sharedPrefs.edit {
                                putBoolean(GAMEPAD_FULL_STICK_OUTPUT_ENABLED_PREF, enabled)
                            }
                            sharedPrefs.updateGamepadLayoutSettings(layoutStorageKey) {
                                it.copy(fullStickOutputEnabled = enabled)
                            }
                            syncAnalogSources(leftAnalogSources, true)
                            syncAnalogSources(rightAnalogSources, false)
                            triggerVibration(15)
                        },
                        onToggleTouchAssist = {
                            val enabled = !isTouchAssistEnabled
                            isTouchAssistEnabled = enabled
                            sharedPrefs.edit { putBoolean(GAMEPAD_TOUCH_ASSIST_ENABLED_PREF, enabled) }
                            sharedPrefs.updateGamepadLayoutSettings(layoutStorageKey) {
                                it.copy(touchAssistEnabled = enabled)
                            }
                            triggerVibration(15)
                        },
                        onGamepadReportRateChange = { rate ->
                            btManager.setGamepadReportRate(rate)
                            triggerVibration(15)
                        },
                        onToggleGyroscope = {
                            if (toggleGamepadGyroscope()) {
                                triggerVibration(15)
                            }
                        },
                        onToggleGyroscopeMode = {
                            showGyroscopeCalibration = false
                            tiltProcessor.clearCalibration()
                            gamepadGyroscopeMode = when (gamepadGyroscopeMode) {
                                GamepadGyroscopeMode.ANGULAR_VELOCITY -> GamepadGyroscopeMode.TILT
                                GamepadGyroscopeMode.TILT -> GamepadGyroscopeMode.ANGULAR_VELOCITY
                            }
                            sharedPrefs.edit {
                                putInt(
                                    GAMEPAD_GYROSCOPE_MODE_PREF,
                                    gamepadGyroscopeMode.preferenceValue
                                )
                            }
                            sharedPrefs.updateGamepadLayoutSettings(layoutStorageKey) {
                                it.copy(gyroscopeMode = gamepadGyroscopeMode)
                            }
                            syncGyroscopeStickOutput(0f, 0f)
                            triggerVibration(15)
                        },
                        onCalibrateGyroscope = {
                            if (
                                gamepadGyroscopeMode == GamepadGyroscopeMode.TILT &&
                                gamepadTiltSensor != null
                            ) {
                                syncGyroscopeStickOutput(0f, 0f)
                                showGyroscopeCalibration = true
                                triggerVibration(15)
                            }
                        },
                        onToggleGyroscopeStick = {
                            isGamepadGyroscopeMappedToRightStick =
                                !isGamepadGyroscopeMappedToRightStick
                            sharedPrefs.edit {
                                putBoolean(
                                    GAMEPAD_GYROSCOPE_RIGHT_STICK_PREF,
                                    isGamepadGyroscopeMappedToRightStick
                                )
                            }
                            sharedPrefs.updateGamepadLayoutSettings(layoutStorageKey) {
                                it.copy(
                                    gyroscopeMappedToRightStick =
                                        isGamepadGyroscopeMappedToRightStick
                                )
                            }
                            syncGyroscopeStickOutput(gyroscopeStickX, gyroscopeStickY)
                            triggerVibration(15)
                        },
                        onGyroscopeSensitivityChange = { sensitivity ->
                            gamepadGyroscopeSensitivity = sensitivity.coerceIn(
                                GAMEPAD_GYRO_MIN_SENSITIVITY,
                                GAMEPAD_GYRO_MAX_SENSITIVITY
                            )
                            sharedPrefs.edit {
                                putFloat(
                                    GAMEPAD_GYROSCOPE_SENSITIVITY_PREF,
                                    gamepadGyroscopeSensitivity
                                )
                            }
                            sharedPrefs.updateGamepadLayoutSettings(layoutStorageKey) {
                                it.copy(gyroscopeSensitivity = gamepadGyroscopeSensitivity)
                            }
                        },
                        onToggleGyroscopeHorizontalInversion = {
                            isGamepadGyroscopeHorizontalInverted =
                                !isGamepadGyroscopeHorizontalInverted
                            sharedPrefs.edit {
                                putBoolean(
                                    GAMEPAD_GYROSCOPE_INVERT_HORIZONTAL_PREF,
                                    isGamepadGyroscopeHorizontalInverted
                                )
                            }
                            sharedPrefs.updateGamepadLayoutSettings(layoutStorageKey) {
                                it.copy(
                                    gyroscopeInvertHorizontal =
                                        isGamepadGyroscopeHorizontalInverted
                                )
                            }
                            triggerVibration(15)
                        },
                        onToggleGyroscopeVerticalInversion = {
                            isGamepadGyroscopeVerticalInverted =
                                !isGamepadGyroscopeVerticalInverted
                            sharedPrefs.edit {
                                putBoolean(
                                    GAMEPAD_GYROSCOPE_INVERT_VERTICAL_PREF,
                                    isGamepadGyroscopeVerticalInverted
                                )
                            }
                            sharedPrefs.updateGamepadLayoutSettings(layoutStorageKey) {
                                it.copy(
                                    gyroscopeInvertVertical =
                                        isGamepadGyroscopeVerticalInverted
                                )
                            }
                            triggerVibration(15)
                        },
                        onGyroscopeJitterSuppressionChange = { suppression ->
                            gamepadGyroscopeJitterSuppression = suppression
                            sharedPrefs.edit {
                                putInt(
                                    GAMEPAD_GYROSCOPE_JITTER_SUPPRESSION_PREF,
                                    suppression.preferenceValue
                                )
                            }
                            sharedPrefs.updateGamepadLayoutSettings(layoutStorageKey) {
                                it.copy(gyroscopeJitterSuppression = suppression)
                            }
                        },
                        onToggleFeatureListPage = {
                            showFeatureListPage = !showFeatureListPage
                            if (showFeatureListPage) {
                                showGyroscopeMappingPage = false
                                showBlankListPage = false
                            }
                            selectedControlTypeId = null
                            triggerVibration(15)
                        },
                        onToggleGyroscopeMappingPage = {
                            showGyroscopeMappingPage = !showGyroscopeMappingPage
                            if (showGyroscopeMappingPage) {
                                showFeatureListPage = false
                                showBlankListPage = false
                            }
                            selectedControlTypeId = null
                            triggerVibration(15)
                        },
                        onToggleBlankListPage = {
                            showBlankListPage = !showBlankListPage
                            if (showBlankListPage) {
                                showFeatureListPage = false
                                showGyroscopeMappingPage = false
                            }
                            selectedControlTypeId = null
                            triggerVibration(15)
                        },
                        onToggleSpecialControlListPage = {
                            showSpecialControlListPage = !showSpecialControlListPage
                            showFeatureListPage = false
                            showGyroscopeMappingPage = false
                            showBlankListPage = false
                            selectedControlTypeId = null
                            triggerVibration(15)
                        },
                        onToggleLayoutEdit = {
                            toggleLayoutEdit()
                        },
                        onResetLayoutEdit = resetLayoutEdit,
                        onUndoLayoutEdit = undoLayoutEdit,
                        onRedoLayoutEdit = redoLayoutEdit,
                        onToggleSnapAlignment = {
                            val enabled = !snapAlignmentEnabled
                            snapAlignmentEnabled = enabled
                            sharedPrefs.edit { putBoolean(GAMEPAD_SNAP_ALIGNMENT_ENABLED_PREF, enabled) }
                            snapGuides = emptyList()
                            triggerVibration(18)
                        },
                        onReconnectLastDevice = reconnectLastDevice,
                        onStartAutomatedInputTest = startAutomatedInputTest,
                        onClose = guardedCloseGamepad,
                        onDismiss = {
                            dismissLayoutMenu()
                        },
                        onSelect = selectLayoutProfile,
                        onCreate = createLayoutProfile,
                        onRename = requestRenameLayout,
                        onDeleteRequest = requestDeleteLayout,
                        onDeleteConfirm = deleteLayoutProfile,
                        onStartMultiSelect = startLayoutMultiSelect,
                        onToggleMultiSelect = toggleLayoutMultiSelect,
                        onRequestDeleteSelected = {
                            if (multiSelectedLayoutIds.isNotEmpty()) {
                                deleteTargetId = null
                                confirmingSelectedDelete = true
                                triggerVibration(15)
                            }
                        },
                        onDeleteSelected = {
                            confirmingSelectedDelete = false
                            deleteMultiSelectedLayouts()
                        },
                        onCopyActiveLayout = copyActiveLayoutProfile,
                        onMoveActiveLayoutUp = { moveActiveLayoutProfile(1) },
                        onMoveActiveLayoutDown = { moveActiveLayoutProfile(-1) },
                        onImportLayouts = importLayoutProfiles,
                        onPasteLayouts = requestPasteLayoutProfiles,
                        onExportLayouts = exportLayoutProfiles,
                        onShareLayouts = shareLayoutProfiles,
                        onCopyLayouts = copyLayoutProfilesToClipboard
                    )
                }

                if (renameTargetId != null) {
                    GamepadRenameLayoutPanel(
                        name = renameText,
                        onNameChange = { renameText = it },
                        onDismiss = {
                            renameTargetId = null
                            renameText = ""
                            showLayoutMenu = true
                        },
                        onSave = { confirmRenameLayout() }
                    )
                }

                if (showLayoutPastePanel) {
                    GamepadLayoutPastePanel(
                        text = layoutPasteText,
                        showError = showLayoutPasteError,
                        isSimplifiedChineseEnabled = isSimplifiedChineseEnabled,
                        onTextChange = { text ->
                            layoutPasteText = text
                            showLayoutPasteError = false
                        },
                        onDismiss = {
                            showLayoutPastePanel = false
                            layoutPasteText = ""
                            showLayoutPasteError = false
                        },
                        onImport = {
                            if (pasteLayoutProfiles(layoutPasteText)) {
                                showLayoutPastePanel = false
                                layoutPasteText = ""
                                showLayoutPasteError = false
                            } else {
                                showLayoutPasteError = true
                                triggerVibration(45)
                            }
                        }
                    )
                }

                if (showGyroscopeCalibration) {
                    GamepadGyroscopeCalibrationPanel(
                        sensorManager = sensorManager,
                        tiltSensor = gamepadTiltSensor,
                        isSimplifiedChineseEnabled = isSimplifiedChineseEnabled,
                        onCalibrate = { rotationVector ->
                            val calibrated = tiltProcessor.calibrate(rotationVector)
                            if (calibrated) {
                                syncGyroscopeStickOutput(0f, 0f)
                                triggerVibration(45)
                            }
                            calibrated
                        },
                        onDismiss = {
                            showGyroscopeCalibration = false
                        }
                    )
                }

            }
        }
    }
}

// ── Sub-Components ──

}

fun Modifier.gamepadButtonTouch(
    onPress: () -> Unit,
    onRelease: () -> Unit,
    onPressedStateChange: (Boolean) -> Unit
): Modifier = composed {
    val touchAssistEnabled = LocalGamepadTouchAssistController.current?.enabled == true
    if (touchAssistEnabled) {
        this
    } else {
        this
            .gamepadSharedPointerInput()
            .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val targetPointerId = down.id
                onPressedStateChange(true)
                onPress()
                down.consume()
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == targetPointerId } ?: break
                        if (!change.pressed) break
                        change.consume()
                    }
                } finally {
                    onPressedStateChange(false)
                    onRelease()
                }
            }
        }
    }
}

@Composable
private fun BoxScope.AdditionalGamepadControlInstance(
    instance: GamepadControlInstance,
    config: ConsoleConfig,
    stickpadViewportScale: Float,
    isEditMode: Boolean,
    buttonMask: Int,
    isL3R3ToggleMode: Boolean,
    isStickClickEnabled: Boolean,
    isGyroscopeAvailable: Boolean,
    isGyroscopeEnabled: Boolean,
    onUpdate: (GamepadControlInstance) -> Unit,
    onPress: (String, Int) -> Unit,
    onRelease: (String, Int) -> Unit,
    onSetLatched: (Int, Boolean) -> Unit,
    onDpadChange: (String, Int) -> Unit,
    onAnalogActiveChange: (String, Boolean, Boolean) -> Unit,
    onAnalogMove: (String, Boolean, Float, Float) -> Unit,
    onTouchPressHaptic: () -> Unit,
    onTouchReleaseHaptic: () -> Unit,
    onPressHaptic: () -> Unit,
    onToggleGyroscope: () -> Boolean
) {
    val scope = rememberCoroutineScope()
    val independentResizeSpec = instance.type.independentResizeSpec()
    val isStickpad = instance.type == GamepadControlType.LEFT_STICKPAD ||
        instance.type == GamepadControlType.RIGHT_STICKPAD
    val responsiveScale = if (isStickpad) stickpadViewportScale else 1f
    val effectiveWidthScale = instance.scale * instance.widthScale
    val effectiveHeightScale = instance.scale * instance.heightScale
    val renderedWidthScale = effectiveWidthScale * responsiveScale
    val renderedHeightScale = effectiveHeightScale * responsiveScale
    EditableComponentWrapper(
        controlInstanceId = instance.id,
        isEditMode = isEditMode,
        offsetX = instance.offsetX * responsiveScale,
        offsetY = instance.offsetY * responsiveScale,
        scale = if (independentResizeSpec == null) instance.scale else 1f,
        onOffsetChange = { x, y ->
            onUpdate(
                instance.copy(
                    offsetX = x / responsiveScale,
                    offsetY = y / responsiveScale
                )
            )
        },
        onScaleChange = { scale ->
            if (independentResizeSpec == null) onUpdate(instance.copy(scale = scale))
        },
        onTransformChange = { x, y, scale ->
            if (independentResizeSpec == null) {
                onUpdate(instance.copy(offsetX = x, offsetY = y, scale = scale))
            } else {
                onUpdate(
                    instance.copy(
                        offsetX = x / responsiveScale,
                        offsetY = y / responsiveScale
                    )
                )
            }
        },
        allowUniformScale = independentResizeSpec == null,
        editFrameInset = instance.type.editFrameInset(),
        onResizeDragDp = independentResizeSpec?.let { resizeSpec ->
            { dragAmountXDp, dragAmountYDp ->
                val resized = gamepadIndependentResizeTransform(
                    currentOffsetX = instance.offsetX,
                    currentOffsetY = instance.offsetY,
                    currentWidthScale = effectiveWidthScale,
                    currentHeightScale = effectiveHeightScale,
                    dragAmountXDp = dragAmountXDp / responsiveScale,
                    dragAmountYDp = dragAmountYDp / responsiveScale,
                    baseWidthDp = resizeSpec.baseWidthDp,
                    baseHeightDp = resizeSpec.baseHeightDp,
                    minimumAxisScale = resizeSpec.minimumAxisScale,
                    maximumAxisScale = resizeSpec.maximumAxisScale
                )
                onUpdate(
                    instance.copy(
                        offsetX = resized.offsetX,
                        offsetY = resized.offsetY,
                        scale = 1f,
                        widthScale = resized.widthScale,
                        heightScale = resized.heightScale
                    )
                )
            }
        },
        modifier = Modifier
            .align(Alignment.Center)
            .zIndex(2f)
    ) {
        when (instance.type) {
            GamepadControlType.LEFT_STICKPAD,
            GamepadControlType.RIGHT_STICKPAD -> {
                val isLeft = instance.type == GamepadControlType.LEFT_STICKPAD
                val mappingId = if (isLeft) 10 else 11
                GamepadStickpad(
                    isLeft = isLeft,
                    isEditMode = isEditMode,
                    isHeld = (buttonMask and (1 shl mappingId)) != 0,
                    modifier = Modifier
                        .width((STICKPAD_BASE_WIDTH_DP * renderedWidthScale).dp)
                        .height((STICKPAD_BASE_HEIGHT_DP * renderedHeightScale).dp),
                    onActiveChange = { active ->
                        onAnalogActiveChange(instance.id, isLeft, active)
                    },
                    onMove = { x, y -> onAnalogMove(instance.id, isLeft, x, y) },
                    onTouchPressHaptic = onTouchPressHaptic,
                    onTouchReleaseHaptic = onTouchReleaseHaptic,
                    onStickClick = {
                        if (isStickClickEnabled) {
                            val clickSourceId = "${instance.id}_click"
                            scope.launch {
                                onPress(clickSourceId, mappingId)
                                delay(100L.milliseconds)
                                onRelease(clickSourceId, mappingId)
                            }
                        }
                    }
                )
            }
            GamepadControlType.LEFT_STICK,
            GamepadControlType.RIGHT_STICK -> {
                val isLeft = instance.type == GamepadControlType.LEFT_STICK
                val mappingId = if (isLeft) 10 else 11
                GamepadAnalogStick(
                    isClicked = (buttonMask and (1 shl mappingId)) != 0,
                    isHeld = (buttonMask and (1 shl mappingId)) != 0,
                    editLabel = if (isEditMode) if (isLeft) "L" else "R" else null,
                    onActiveChange = { active ->
                        onAnalogActiveChange(instance.id, isLeft, active)
                    },
                    onMove = { x, y -> onAnalogMove(instance.id, isLeft, x, y) },
                    onTouchPressHaptic = onTouchPressHaptic,
                    onTouchReleaseHaptic = onTouchReleaseHaptic,
                    onStickClick = {
                        if (isStickClickEnabled) {
                            val clickSourceId = "${instance.id}_click"
                            scope.launch {
                                onPress(clickSourceId, mappingId)
                                delay(100L.milliseconds)
                                onRelease(clickSourceId, mappingId)
                            }
                        }
                    }
                )
            }
            GamepadControlType.L3,
            GamepadControlType.R3 -> {
                val mappingId = if (instance.type == GamepadControlType.L3) 10 else 11
                GamepadStickButton(
                    label = if (mappingId == 10) "L3" else "R3",
                    mappingId = mappingId,
                    isHeld = (buttonMask and (1 shl mappingId)) != 0,
                    isToggleMode = isL3R3ToggleMode,
                    onToggle = { held ->
                        if (isL3R3ToggleMode) {
                            onSetLatched(mappingId, held)
                        } else if (held) {
                            onPress(instance.id, mappingId)
                        } else {
                            onRelease(instance.id, mappingId)
                        }
                    }
                )
            }
            GamepadControlType.LEFT_BUMPER -> GamepadBumperButton(
                config.leftBumper,
                true,
                { onPress(instance.id, it) },
                { onRelease(instance.id, it) },
                widthScale = effectiveWidthScale,
                heightScale = effectiveHeightScale
            )
            GamepadControlType.RIGHT_BUMPER -> GamepadBumperButton(
                config.rightBumper,
                false,
                { onPress(instance.id, it) },
                { onRelease(instance.id, it) },
                widthScale = effectiveWidthScale,
                heightScale = effectiveHeightScale
            )
            GamepadControlType.LEFT_TRIGGER -> GamepadTriggerButton(
                config.leftTrigger,
                true,
                { onPress(instance.id, it) },
                { onRelease(instance.id, it) },
                widthScale = effectiveWidthScale,
                heightScale = effectiveHeightScale
            )
            GamepadControlType.RIGHT_TRIGGER -> GamepadTriggerButton(
                config.rightTrigger,
                false,
                { onPress(instance.id, it) },
                { onRelease(instance.id, it) },
                widthScale = effectiveWidthScale,
                heightScale = effectiveHeightScale
            )
            GamepadControlType.DPAD -> GamepadDpad(
                isXboxStyle = config.id == XBOX_CONFIG_ID,
                onDpadChange = { mask ->
                    onDpadChange(instance.id, mask)
                    if (mask != 0) onPressHaptic()
                }
            )
            GamepadControlType.FACE_BUTTONS -> FaceButtonsDiamond(
                config = config,
                isXboxStyle = config.id == XBOX_CONFIG_ID,
                onPress = { onPress(instance.id, it) },
                onRelease = { onRelease(instance.id, it) }
            )
            GamepadControlType.FACE_BOTTOM,
            GamepadControlType.FACE_RIGHT,
            GamepadControlType.FACE_LEFT,
            GamepadControlType.FACE_TOP -> GamepadFaceButton(
                button = config.faceButtonFor(instance.type),
                isXboxStyle = config.id == XBOX_CONFIG_ID,
                onPress = { onPress(instance.id, it) },
                onRelease = { onRelease(instance.id, it) }
            )
            GamepadControlType.DPAD_UP,
            GamepadControlType.DPAD_DOWN,
            GamepadControlType.DPAD_LEFT,
            GamepadControlType.DPAD_RIGHT -> GamepadDpadDirectionButton(
                directionMask = instance.type.dpadDirectionMask(),
                onDpadChange = { mask ->
                    onDpadChange(instance.id, mask)
                    if (mask != 0) onPressHaptic()
                }
            )
            GamepadControlType.SELECT -> GamepadCenterButton(
                config.selectButton,
                { onPress(instance.id, it) },
                { onRelease(instance.id, it) }
            )
            GamepadControlType.START -> GamepadCenterButton(
                config.startButton,
                { onPress(instance.id, it) },
                { onRelease(instance.id, it) }
            )
            GamepadControlType.GUIDE -> {
                if (config.id == XBOX_CONFIG_ID) {
                    XboxLogoGuideButton(
                        config.guideButton,
                        { onPress(instance.id, it) },
                        { onRelease(instance.id, it) }
                    )
                } else {
                    PlayStationLogoButton(
                        config.guideButton,
                        { onPress(instance.id, it) },
                        { onRelease(instance.id, it) }
                    )
                }
            }
            GamepadControlType.SHARE -> GamepadCenterButton(
                config.shareButton,
                { onPress(instance.id, it) },
                { onRelease(instance.id, it) }
            )
            GamepadControlType.GYROSCOPE_TOGGLE -> GamepadGyroscopeToggleButton(
                isAvailable = isGyroscopeAvailable,
                isEnabled = isGyroscopeEnabled,
                isEditMode = isEditMode,
                onToggle = onToggleGyroscope,
                onPressHaptic = onPressHaptic
            )
        }
    }
}

private fun hideGamepadSystemBars(context: Context) {
    val activity = context as? Activity ?: return
    val window = activity.window ?: return
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.hide(
                android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars()
            )
            window.insetsController?.systemBarsBehavior =
                android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
        }
    } catch (_: Exception) {
    }
}

internal fun gamepadLandscapeOrientation(isReverse: Boolean): Int =
    if (isReverse) {
        ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
    } else {
        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    }

private fun rotateGamepadScreen(context: Context): Int? {
    val activity = context as? Activity ?: return null
    val nextOrientation = gamepadLandscapeOrientation(
        isReverse = activity.requestedOrientation !=
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
    )
    return runCatching {
        activity.requestedOrientation = nextOrientation
        nextOrientation
    }.getOrNull()
}

@SuppressLint("MissingPermission")
private fun vibrateGamepadHaptic(context: Context, haptic: GamepadHaptic) {
    @Suppress("DEPRECATION")
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    } ?: return
    if (!vibrator.hasVibrator()) return

    val usesFirmFeedback = haptic != GamepadHaptic.AssistedPress
    if (usesFirmFeedback) {
        vibrator.cancel()
    }

    val effect = when {
        usesFirmFeedback && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
            val primitiveId = VibrationEffect.Composition.PRIMITIVE_CLICK
            val primitiveSupported = vibrator.arePrimitivesSupported(primitiveId).firstOrNull() == true
            if (primitiveSupported) {
                VibrationEffect.startComposition()
                    .addPrimitive(primitiveId, GAMEPAD_PRESS_RELEASE_PRIMITIVE_SCALE)
                    .compose()
            } else {
                null
            }
        }
        else -> null
    } ?: run {
        val duration = when (haptic) {
            GamepadHaptic.AssistedPress -> GAMEPAD_ASSISTED_PRESS_DURATION_MS
            GamepadHaptic.DirectPress,
            GamepadHaptic.Release -> GAMEPAD_PRESS_RELEASE_FALLBACK_DURATION_MS
        }
        val amplitude = if (vibrator.hasAmplitudeControl()) {
            when (haptic) {
                GamepadHaptic.AssistedPress -> GAMEPAD_ASSISTED_PRESS_AMPLITUDE
                GamepadHaptic.DirectPress,
                GamepadHaptic.Release -> GAMEPAD_PRESS_RELEASE_FALLBACK_AMPLITUDE
            }
        } else {
            VibrationEffect.DEFAULT_AMPLITUDE
        }
        VibrationEffect.createOneShot(duration, amplitude)
    }
    vibrator.vibrate(effect)
}

private fun vibrateGamepadReleaseHaptic(view: View, context: Context) {
    val semanticFeedbackPerformed =
        view.performHapticFeedback(HapticFeedbackConstants.TEXT_HANDLE_MOVE)
    if (!semanticFeedbackPerformed) {
        vibrateGamepadHaptic(context, GamepadHaptic.Release)
    }
}

internal fun gamepadMenuShouldDismissOnDoubleTap(
    tapPositionInRoot: Offset,
    menuBoundsInRoot: Rect
): Boolean = menuBoundsInRoot.width > 0f &&
    menuBoundsInRoot.height > 0f &&
    !menuBoundsInRoot.contains(tapPositionInRoot)

@Composable
private fun GamepadLayoutMenu(
    xboxProfiles: List<GamepadLayoutProfile>,
    psProfiles: List<GamepadLayoutProfile>,
    selectedLayoutId: String,
    isEditing: Boolean,
    confirmingDeleteLayoutId: String?,
    confirmingSelectedDelete: Boolean,
    multiSelectedLayoutIds: List<String>,
    isVibrationEnabled: Boolean,
    isL3R3ToggleMode: Boolean,
    isStickClickEnabled: Boolean,
    isFullStickOutputEnabled: Boolean,
    isTouchAssistEnabled: Boolean,
    gamepadReportRate: GamepadReportRate,
    isGyroscopeAvailable: Boolean,
    isGyroscopeEnabled: Boolean,
    gyroscopeMode: GamepadGyroscopeMode,
    isGyroscopeMappedToRightStick: Boolean,
    gyroscopeSensitivity: Float,
    isGyroscopeHorizontalInverted: Boolean,
    isGyroscopeVerticalInverted: Boolean,
    gyroscopeJitterSuppression: GamepadGyroJitterSuppression,
    isSimplifiedChineseEnabled: Boolean,
    showFeatureListPage: Boolean,
    showGyroscopeMappingPage: Boolean,
    showBlankListPage: Boolean,
    showSpecialControlListPage: Boolean,
    isLayoutEditMode: Boolean,
    selectedControlType: GamepadControlType?,
    selectedControlCount: Int?,
    isSnapAlignmentEnabled: Boolean,
    canUndoLayoutEdit: Boolean,
    canRedoLayoutEdit: Boolean,
    canEditActiveLayout: Boolean,
    hasUnsavedLayoutChanges: Boolean,
    showUnsavedSaveWarning: Boolean,
    unsavedWarningPulse: Int,
    isConnected: Boolean,
    deviceName: String,
    isAutomatedInputTestRunning: Boolean,
    activeConfigId: String,
    canMoveActiveLayoutUp: Boolean,
    canMoveActiveLayoutDown: Boolean,
    onClearDeleteConfirm: () -> Unit,
    onExitMultiSelect: () -> Unit,
    onClearControlSelection: () -> Unit,
    onSelectControlType: (GamepadControlType) -> Unit,
    onAddControl: () -> Unit,
    onRemoveControl: () -> Unit,
    onToggleVibration: () -> Unit,
    onRotateScreen: () -> Unit,
    onToggleLanguage: () -> Unit,
    onToggleL3R3Mode: () -> Unit,
    onToggleStickClick: () -> Unit,
    onToggleFullStickOutput: () -> Unit,
    onToggleTouchAssist: () -> Unit,
    onGamepadReportRateChange: (GamepadReportRate) -> Unit,
    onToggleGyroscope: () -> Unit,
    onToggleGyroscopeMode: () -> Unit,
    onCalibrateGyroscope: () -> Unit,
    onToggleGyroscopeStick: () -> Unit,
    onGyroscopeSensitivityChange: (Float) -> Unit,
    onToggleGyroscopeHorizontalInversion: () -> Unit,
    onToggleGyroscopeVerticalInversion: () -> Unit,
    onGyroscopeJitterSuppressionChange: (GamepadGyroJitterSuppression) -> Unit,
    onToggleFeatureListPage: () -> Unit,
    onToggleGyroscopeMappingPage: () -> Unit,
    onToggleBlankListPage: () -> Unit,
    onToggleSpecialControlListPage: () -> Unit,
    onToggleLayoutEdit: () -> Unit,
    onResetLayoutEdit: () -> Unit,
    onUndoLayoutEdit: () -> Unit,
    onRedoLayoutEdit: () -> Unit,
    onToggleSnapAlignment: () -> Unit,
    onReconnectLastDevice: () -> Unit,
    onStartAutomatedInputTest: () -> Unit,
    onClose: () -> Unit,
    onDismiss: () -> Unit,
    onSelect: (GamepadLayoutProfile) -> Unit,
    onCreate: (String) -> Unit,
    onRename: (GamepadLayoutProfile) -> Unit,
    onDeleteRequest: (GamepadLayoutProfile) -> Unit,
    onDeleteConfirm: (GamepadLayoutProfile) -> Unit,
    onStartMultiSelect: (GamepadLayoutProfile) -> Unit,
    onToggleMultiSelect: (GamepadLayoutProfile) -> Unit,
    onRequestDeleteSelected: () -> Unit,
    onDeleteSelected: () -> Unit,
    onCopyActiveLayout: () -> Unit,
    onMoveActiveLayoutUp: () -> Unit,
    onMoveActiveLayoutDown: () -> Unit,
    onImportLayouts: () -> Unit,
    onPasteLayouts: () -> Unit,
    onExportLayouts: () -> Unit,
    onShareLayouts: () -> Unit,
    onCopyLayouts: () -> Unit
) {
    val configuration = LocalConfiguration.current
    val d = (configuration.screenWidthDp.dp * 0.008f).coerceIn(8.dp, 18.dp)
    val menuEdgePadding = d
    val menuInnerPadding = d
    val menuShape = RoundedCornerShape((d * 1.15f).coerceIn(14.dp, 20.dp))
    val baseControlSize = 28.dp
    val baseLayoutRowHeight = 34.dp
    val dividerHeight = 0.5.dp
    val layoutRowSpacing = d * 0.25f
    val isMultiSelectMode = multiSelectedLayoutIds.isNotEmpty()
    var showImportActions by rememberSaveable { mutableStateOf(false) }
    var showExportActions by rememberSaveable { mutableStateOf(false) }
    var backdropOriginInRoot by remember { mutableStateOf(Offset.Zero) }
    var menuBoundsInRoot by remember { mutableStateOf(Rect.Zero) }
    var featureEntryCenterXInRoot by remember { mutableFloatStateOf(Float.NaN) }
    var gyroscopeEntryCenterXInRoot by remember { mutableFloatStateOf(Float.NaN) }
    var editEntryCenterXInRoot by remember { mutableFloatStateOf(Float.NaN) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(10f)
            .background(LayoutMenuBackdropColor)
            .onGloballyPositioned { coordinates ->
                backdropOriginInRoot = coordinates.positionInRoot()
            }
            .pointerInput(
                confirmingDeleteLayoutId,
                confirmingSelectedDelete,
                isMultiSelectMode,
                isLayoutEditMode,
                selectedControlType,
                backdropOriginInRoot,
                menuBoundsInRoot
            ) {
                detectTapGestures(
                    onTap = {
                        if (confirmingDeleteLayoutId != null || confirmingSelectedDelete) {
                            onClearDeleteConfirm()
                        } else if (isMultiSelectMode) {
                            onExitMultiSelect()
                        } else if (isLayoutEditMode && selectedControlType != null) {
                            onClearControlSelection()
                        }
                    },
                    onDoubleTap = { tapPosition ->
                        val tapPositionInRoot = Offset(
                            x = tapPosition.x + backdropOriginInRoot.x,
                            y = tapPosition.y + backdropOriginInRoot.y
                        )
                        if (gamepadMenuShouldDismissOnDoubleTap(tapPositionInRoot, menuBoundsInRoot)) {
                            onDismiss()
                        }
                    }
                )
            },
        contentAlignment = Alignment.BottomCenter
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(menuEdgePadding),
            contentAlignment = Alignment.BottomCenter
        ) {
            val menuWidth = (configuration.screenWidthDp.dp * 0.8f).coerceAtMost(maxWidth)
            fun layoutContentHeight(profileCount: Int, rowHeight: androidx.compose.ui.unit.Dp) =
                rowHeight * profileCount.toFloat() + layoutRowSpacing * (profileCount - 1).coerceAtLeast(0).toFloat()

            val maxMenuControlScale = 2f
            val availableMenuHeight = configuration.screenHeightDp.dp - menuEdgePadding * 2f
            val contentWidth = (menuWidth - menuInnerPadding * 2f).coerceAtLeast(0.dp)
            val columnWidth = ((contentWidth - d) / 2f).coerceAtLeast(0.dp)

            fun scaleLimit(available: Dp, fixed: Dp, scalable: Dp): Float {
                val room = (available - fixed).value
                val base = scalable.value
                return if (room <= 0f || base <= 0f) {
                    1f
                } else {
                    (room / base).coerceIn(1f, maxMenuControlScale)
                }
            }

            val bottomRowsScaleLimit = scaleLimit(
                available = configuration.screenHeightDp.dp * 0.5f,
                fixed = dividerHeight + d * 2f,
                scalable = baseControlSize * 2f
            )
            val minimumListMenuScaleLimit = scaleLimit(
                available = availableMenuHeight,
                fixed = menuInnerPadding * 2f + dividerHeight + d * 3f,
                scalable = baseLayoutRowHeight + baseControlSize * 2f
            )
            val baseHeaderTitleWidth = 42.dp
            val headerActiveWithoutTrailingScaleLimit = scaleLimit(
                available = columnWidth,
                fixed = d * 4f,
                scalable = baseHeaderTitleWidth + baseControlSize * 4f
            )
            val headerInactiveWithoutTrailingScaleLimit = scaleLimit(
                available = columnWidth,
                fixed = d,
                scalable = baseHeaderTitleWidth + baseControlSize
            )
            val headerRightInactiveWithTransferScaleLimit = scaleLimit(
                available = columnWidth,
                fixed = d * 3f,
                scalable = baseHeaderTitleWidth + baseControlSize * 3f
            )
            val headerRightActiveWithTransferScaleLimit = scaleLimit(
                available = columnWidth,
                fixed = d * 6f,
                scalable = baseHeaderTitleWidth + baseControlSize * 6f
            )
            val headerRightMultiSelectWithTransferScaleLimit = scaleLimit(
                available = columnWidth,
                fixed = d * 4f,
                scalable = baseHeaderTitleWidth + 92.dp + baseControlSize * 3f
            )
            val headerScaleLimit = minOf(
                headerActiveWithoutTrailingScaleLimit,
                headerInactiveWithoutTrailingScaleLimit,
                headerRightInactiveWithTransferScaleLimit,
                headerRightActiveWithTransferScaleLimit,
                headerRightMultiSelectWithTransferScaleLimit
            )
            val normalControlRowScaleLimit = scaleLimit(
                available = contentWidth,
                fixed = d * 6f,
                scalable = baseControlSize * 5f + 276.dp
            )
            val editControlRowScaleLimit = scaleLimit(
                available = contentWidth,
                fixed = d * 2f,
                scalable = baseControlSize * 3f + 180.dp
            )
            val editControlHeaderScaleLimit = scaleLimit(
                available = contentWidth,
                fixed = d * 6f,
                scalable = baseControlSize * 8f
            )
            val controlRowScaleLimit = minOf(
                normalControlRowScaleLimit,
                editControlRowScaleLimit,
                editControlHeaderScaleLimit
            )
            val menuControlScale = minOf(
                maxMenuControlScale,
                bottomRowsScaleLimit,
                minimumListMenuScaleLimit,
                headerScaleLimit,
                controlRowScaleLimit
            ).coerceIn(1f, maxMenuControlScale)
            val controlRowHeight = baseControlSize * menuControlScale
            val headerRowHeight = baseControlSize * menuControlScale
            val layoutRowHeight = baseLayoutRowHeight * menuControlScale
            val scaledFixedMenuHeight = menuInnerPadding * 2f + controlRowHeight + headerRowHeight + dividerHeight + d * 3f
            val listMaxHeight = (availableMenuHeight - scaledFixedMenuHeight).coerceAtLeast(layoutRowHeight)
            val tallestContentHeight = maxOf(
                layoutContentHeight(xboxProfiles.size, layoutRowHeight),
                layoutContentHeight(psProfiles.size, layoutRowHeight)
            )
            val featureCount = 4
            val featureRowCount = (featureCount + 1) / 2
            val gyroscopeSettingCount = 4
            val gyroscopeSettingRowCount = (gyroscopeSettingCount + 1) / 2
            val blankPlaceholderCount = 2
            val blankRowCount = (blankPlaceholderCount + 1) / 2
            val controlTypeColumnCount = 4
            val controlTypeRowCount =
                (standardGamepadControlTypes.size + controlTypeColumnCount - 1) / controlTypeColumnCount
            val specialControlTypeRowCount =
                (specialGamepadControlTypes.size + controlTypeColumnCount - 1) / controlTypeColumnCount
            val menuPage = when {
                showFeatureListPage -> GamepadLayoutMenuPage.FEATURES
                showGyroscopeMappingPage -> GamepadLayoutMenuPage.GYROSCOPE
                isLayoutEditMode && showSpecialControlListPage ->
                    GamepadLayoutMenuPage.SPECIAL_CONTROLS
                isLayoutEditMode -> GamepadLayoutMenuPage.CONTROLS
                showBlankListPage -> GamepadLayoutMenuPage.BLANK
                else -> GamepadLayoutMenuPage.LAYOUTS
            }
            LaunchedEffect(menuPage) {
                if (menuPage != GamepadLayoutMenuPage.LAYOUTS) {
                    showImportActions = false
                    showExportActions = false
                }
            }
            val activeContentHeight = when (menuPage) {
                GamepadLayoutMenuPage.FEATURES ->
                    layoutContentHeight(featureRowCount, layoutRowHeight)
                GamepadLayoutMenuPage.GYROSCOPE ->
                    layoutContentHeight(gyroscopeSettingRowCount, layoutRowHeight)
                GamepadLayoutMenuPage.SPECIAL_CONTROLS ->
                    layoutContentHeight(specialControlTypeRowCount, layoutRowHeight)
                GamepadLayoutMenuPage.CONTROLS ->
                    layoutContentHeight(controlTypeRowCount, layoutRowHeight)
                GamepadLayoutMenuPage.BLANK ->
                    layoutContentHeight(blankRowCount, layoutRowHeight)
                GamepadLayoutMenuPage.LAYOUTS -> tallestContentHeight
            }
            val listHeight = activeContentHeight
                .coerceIn(layoutRowHeight, listMaxHeight)
            Box(
                modifier = Modifier
                    .width(menuWidth)
                    .onGloballyPositioned { coordinates ->
                        val position = coordinates.positionInRoot()
                        menuBoundsInRoot = Rect(
                            left = position.x,
                            top = position.y,
                            right = position.x + coordinates.size.width,
                            bottom = position.y + coordinates.size.height
                        )
                    }
                    .clip(menuShape)
                    .background(LayoutMenuSurfaceColor)
                    .border(1.dp, Color.White.copy(alpha = 0.12f), menuShape)
                    .padding(menuInnerPadding)
            ) {
                CompositionLocalProvider(
                    LocalGamepadMenuControlScale provides menuControlScale,
                    LocalGamepadSimplifiedChineseEnabled provides isSimplifiedChineseEnabled
                ) {
                Column {
                    AnimatedContent(
                        targetState = menuPage,
                        transitionSpec = {
                            fadeIn(
                                tween(
                                    durationMillis = GAMEPAD_MOTION_STATE_MS,
                                    delayMillis = 45,
                                    easing = LinearEasing
                                )
                            ) togetherWith fadeOut(
                                tween(durationMillis = 75, easing = LinearEasing)
                            )
                        },
                        label = "layoutMenuPageContent"
                    ) { animatedPage ->
                    when (animatedPage) {
                        GamepadLayoutMenuPage.CONTROLS,
                        GamepadLayoutMenuPage.SPECIAL_CONTROLS -> {
                        GamepadControlListGrid(
                            configId = activeConfigId,
                            controlTypes = if (
                                animatedPage == GamepadLayoutMenuPage.SPECIAL_CONTROLS
                            ) {
                                specialGamepadControlTypes
                            } else {
                                standardGamepadControlTypes
                            },
                            selectedType = selectedControlType,
                            listHeight = listHeight,
                            rowHeight = layoutRowHeight,
                            rowSpacing = layoutRowSpacing,
                            columnSpacing = d,
                            onSelect = onSelectControlType
                        )
                        }
                        GamepadLayoutMenuPage.FEATURES -> {
                        GamepadFeatureListGrid(
                            isL3R3ToggleMode = isL3R3ToggleMode,
                            isStickClickEnabled = isStickClickEnabled,
                            isFullStickOutputEnabled = isFullStickOutputEnabled,
                            isTouchAssistEnabled = isTouchAssistEnabled,
                            isSimplifiedChineseEnabled = isSimplifiedChineseEnabled,
                            listHeight = listHeight,
                            rowHeight = layoutRowHeight,
                            rowSpacing = layoutRowSpacing,
                            columnSpacing = d,
                            onToggleL3R3Mode = onToggleL3R3Mode,
                            onToggleStickClick = onToggleStickClick,
                            onToggleFullStickOutput = onToggleFullStickOutput,
                            onToggleTouchAssist = onToggleTouchAssist
                        )
                        }
                        GamepadLayoutMenuPage.GYROSCOPE -> {
                        GamepadGyroscopeMappingGrid(
                            isGyroscopeAvailable = isGyroscopeAvailable,
                            isGyroscopeEnabled = isGyroscopeEnabled,
                            gyroscopeMode = gyroscopeMode,
                            isMappedToRightStick = isGyroscopeMappedToRightStick,
                            isHorizontalInverted = isGyroscopeHorizontalInverted,
                            isVerticalInverted = isGyroscopeVerticalInverted,
                            isSimplifiedChineseEnabled = isSimplifiedChineseEnabled,
                            listHeight = listHeight,
                            rowHeight = layoutRowHeight,
                            rowSpacing = layoutRowSpacing,
                            columnSpacing = d,
                            onToggleGyroscope = onToggleGyroscope,
                            onToggleGyroscopeMode = onToggleGyroscopeMode,
                            onCalibrateGyroscope = onCalibrateGyroscope,
                            onToggleMappedStick = onToggleGyroscopeStick,
                            onToggleHorizontalInversion =
                                onToggleGyroscopeHorizontalInversion,
                            onToggleVerticalInversion = onToggleGyroscopeVerticalInversion
                        )
                        }
                        GamepadLayoutMenuPage.BLANK -> {
                        GamepadListPageGrid(
                            placeholderCount = blankPlaceholderCount,
                            listHeight = listHeight,
                            rowHeight = layoutRowHeight,
                            rowSpacing = layoutRowSpacing,
                            columnSpacing = d
                        )
                        }
                        GamepadLayoutMenuPage.LAYOUTS -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(d)
                        ) {
                            GamepadLayoutColumn(
                                profiles = xboxProfiles,
                                selectedLayoutId = selectedLayoutId,
                                isEditing = isEditing,
                                confirmingDeleteLayoutId = confirmingDeleteLayoutId,
                                multiSelectedLayoutIds = multiSelectedLayoutIds,
                                isMultiSelectMode = isMultiSelectMode,
                                canStartMultiSelect = !isLayoutEditMode,
                                listHeight = listHeight,
                                rowHeight = layoutRowHeight,
                                rowSpacing = layoutRowSpacing,
                                onSelect = onSelect,
                                onRename = onRename,
                                onDeleteRequest = onDeleteRequest,
                                onDeleteConfirm = onDeleteConfirm,
                                onStartMultiSelect = onStartMultiSelect,
                                onToggleMultiSelect = onToggleMultiSelect,
                                modifier = Modifier.weight(1f)
                            )
                            GamepadLayoutColumn(
                                profiles = psProfiles,
                                selectedLayoutId = selectedLayoutId,
                                isEditing = isEditing,
                                confirmingDeleteLayoutId = confirmingDeleteLayoutId,
                                multiSelectedLayoutIds = multiSelectedLayoutIds,
                                isMultiSelectMode = isMultiSelectMode,
                                canStartMultiSelect = !isLayoutEditMode,
                                listHeight = listHeight,
                                rowHeight = layoutRowHeight,
                                rowSpacing = layoutRowSpacing,
                                onSelect = onSelect,
                                onRename = onRename,
                                onDeleteRequest = onDeleteRequest,
                                onDeleteConfirm = onDeleteConfirm,
                                onStartMultiSelect = onStartMultiSelect,
                                onToggleMultiSelect = onToggleMultiSelect,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        }
                    }
                    }

                    Spacer(Modifier.height(d))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(headerRowHeight)
                    ) {
                        AnimatedContent(
                            targetState = menuPage,
                            modifier = Modifier.matchParentSize(),
                            transitionSpec = {
                                fadeIn(
                                    tween(
                                        durationMillis = GAMEPAD_MOTION_STATE_MS,
                                        delayMillis = 45,
                                        easing = LinearEasing
                                    )
                                ) togetherWith fadeOut(
                                    tween(durationMillis = 75, easing = LinearEasing)
                                )
                            },
                            label = "layoutMenuPageHeader"
                        ) { animatedPage ->
                        Box(modifier = Modifier.fillMaxSize()) {
                        when (animatedPage) {
                            GamepadLayoutMenuPage.CONTROLS,
                            GamepadLayoutMenuPage.SPECIAL_CONTROLS -> {
                            GamepadControlListHeader(
                                selectedCount = selectedControlCount,
                                canRemove = (selectedControlCount ?: 0) > 0,
                                canAdd = selectedControlCount != null &&
                                    selectedControlCount < GAMEPAD_CONTROL_INSTANCE_LIMIT,
                                isSnapAlignmentEnabled = isSnapAlignmentEnabled,
                                canUndoLayoutEdit = canUndoLayoutEdit,
                                canRedoLayoutEdit = canRedoLayoutEdit,
                                hasUnsavedLayoutChanges = hasUnsavedLayoutChanges,
                                showSpecialControlListPage =
                                    animatedPage == GamepadLayoutMenuPage.SPECIAL_CONTROLS,
                                buttonSpacing = d,
                                onRemove = onRemoveControl,
                                onAdd = onAddControl,
                                onToggleSnapAlignment = onToggleSnapAlignment,
                                onToggleSpecialControlListPage = onToggleSpecialControlListPage,
                                onResetLayoutEdit = onResetLayoutEdit,
                                onUndoLayoutEdit = onUndoLayoutEdit,
                                onRedoLayoutEdit = onRedoLayoutEdit
                            )
                            }
                            GamepadLayoutMenuPage.FEATURES -> {
                            GamepadFeatureControlsHeader(
                                isVibrationEnabled = isVibrationEnabled,
                                isAutomatedInputTestRunning = isAutomatedInputTestRunning,
                                isConnected = isConnected,
                                gamepadReportRate = gamepadReportRate,
                                isSimplifiedChineseEnabled = isSimplifiedChineseEnabled,
                                buttonSpacing = d,
                                sliderWidth = columnWidth,
                                controlHeight = headerRowHeight,
                                onToggleLanguage = onToggleLanguage,
                                onRotateScreen = onRotateScreen,
                                onToggleVibration = onToggleVibration,
                                onStartAutomatedInputTest = onStartAutomatedInputTest,
                                onGamepadReportRateChange = onGamepadReportRateChange,
                                modifier = Modifier.matchParentSize()
                            )
                            }
                            GamepadLayoutMenuPage.GYROSCOPE -> {
                            GamepadGyroscopeSliderHeader(
                                sensitivity = gyroscopeSensitivity,
                                jitterSuppression = gyroscopeJitterSuppression,
                                isSimplifiedChineseEnabled = isSimplifiedChineseEnabled,
                                controlWidth = columnWidth,
                                controlHeight = headerRowHeight,
                                onSensitivityChange = onGyroscopeSensitivityChange,
                                onJitterSuppressionChange =
                                    onGyroscopeJitterSuppressionChange,
                                modifier = Modifier.matchParentSize()
                            )
                            }
                            GamepadLayoutMenuPage.BLANK -> {
                            Row(
                                modifier = Modifier.align(Alignment.CenterStart),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(d)
                            ) {
                                GamepadListPageHeaderButton()
                                GamepadListPageHeaderButton()
                            }
                            }
                            GamepadLayoutMenuPage.LAYOUTS -> {
                        Row(
                            modifier = Modifier.matchParentSize(),
                            horizontalArrangement = Arrangement.spacedBy(d),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            GamepadLayoutHeader(
                                title = "Xbox",
                                onCreate = { onCreate(XBOX_CONFIG_ID) },
                                showProfileActions = !isMultiSelectMode && activeConfigId == XBOX_CONFIG_ID,
                                canMoveUp = canMoveActiveLayoutUp,
                                canMoveDown = canMoveActiveLayoutDown,
                                onCopy = onCopyActiveLayout,
                                onMoveUp = onMoveActiveLayoutUp,
                                onMoveDown = onMoveActiveLayoutDown,
                                buttonSpacing = d,
                                modifier = Modifier.weight(1f)
                            )
                            GamepadLayoutHeader(
                                title = "PS5",
                                onCreate = { onCreate(PS5_CONFIG_ID) },
                                showProfileActions = !isMultiSelectMode && activeConfigId == PS5_CONFIG_ID,
                                canMoveUp = canMoveActiveLayoutUp,
                                canMoveDown = canMoveActiveLayoutDown,
                                onCopy = onCopyActiveLayout,
                                onMoveUp = onMoveActiveLayoutUp,
                                onMoveDown = onMoveActiveLayoutDown,
                                buttonSpacing = d,
                                modifier = Modifier.weight(1f)
                            )
                        }
                            }
                        }
                        }
                        }
                        if (menuPage == GamepadLayoutMenuPage.LAYOUTS) {
                            Row(
                                modifier = Modifier.align(Alignment.CenterEnd),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(d)
                            ) {
                            if (isMultiSelectMode) {
                                GamepadSelectedLayoutsDeleteButton(
                                    count = multiSelectedLayoutIds.size,
                                    isConfirmingDelete = confirmingSelectedDelete,
                                    onClick = onRequestDeleteSelected,
                                    onConfirmDelete = onDeleteSelected
                                )
                            }
                            Box {
                                GamepadLayoutTransferButton(
                                    isImport = true,
                                    onClick = {
                                        showExportActions = false
                                        showImportActions = true
                                    }
                                )
                                GamepadLayoutTransferDropdownMenu(
                                    expanded = showImportActions,
                                    onDismissRequest = { showImportActions = false },
                                    shape = menuShape,
                                    dividerHeight = dividerHeight,
                                    items = listOf(
                                        GamepadLayoutTransferMenuItem(
                                            text = if (isSimplifiedChineseEnabled) {
                                                "从本地导入"
                                            } else {
                                                "Import from device"
                                            },
                                            icon = Icons.Default.FolderOpen,
                                            onClick = {
                                                showImportActions = false
                                                onImportLayouts()
                                            }
                                        ),
                                        GamepadLayoutTransferMenuItem(
                                            text = if (isSimplifiedChineseEnabled) {
                                                "粘贴文本"
                                            } else {
                                                "Paste text"
                                            },
                                            icon = Icons.Default.ContentPaste,
                                            onClick = {
                                                showImportActions = false
                                                onPasteLayouts()
                                            }
                                        )
                                    )
                                )
                            }
                            Box {
                                GamepadLayoutTransferButton(
                                    isImport = false,
                                    onClick = {
                                        showImportActions = false
                                        showExportActions = true
                                    }
                                )
                                GamepadLayoutTransferDropdownMenu(
                                    expanded = showExportActions,
                                    onDismissRequest = { showExportActions = false },
                                    shape = menuShape,
                                    dividerHeight = dividerHeight,
                                    items = listOf(
                                        GamepadLayoutTransferMenuItem(
                                            text = if (isSimplifiedChineseEnabled) {
                                                "保存到本地"
                                            } else {
                                                "Save locally"
                                            },
                                            icon = Icons.Default.SaveAlt,
                                            onClick = {
                                                showExportActions = false
                                                onExportLayouts()
                                            }
                                        ),
                                        GamepadLayoutTransferMenuItem(
                                            text = if (isSimplifiedChineseEnabled) {
                                                "直接发送"
                                            } else {
                                                "Share"
                                            },
                                            icon = Icons.AutoMirrored.Filled.Send,
                                            onClick = {
                                                showExportActions = false
                                                onShareLayouts()
                                            }
                                        ),
                                        GamepadLayoutTransferMenuItem(
                                            text = if (isSimplifiedChineseEnabled) {
                                                "复制布局文本"
                                            } else {
                                                "Copy layout text"
                                            },
                                            icon = Icons.Default.ContentCopy,
                                            onClick = {
                                                showExportActions = false
                                                onCopyLayouts()
                                            }
                                        )
                                    )
                                )
                            }
                            }
                        }
                    }

                    Spacer(Modifier.height(d))

                    GamepadMenuPageIndicator(
                        targetCenterXInRoot = when (menuPage) {
                            GamepadLayoutMenuPage.FEATURES ->
                                featureEntryCenterXInRoot.takeIf { it.isFinite() }
                            GamepadLayoutMenuPage.GYROSCOPE ->
                                gyroscopeEntryCenterXInRoot.takeIf { it.isFinite() }
                            GamepadLayoutMenuPage.CONTROLS,
                            GamepadLayoutMenuPage.SPECIAL_CONTROLS ->
                                editEntryCenterXInRoot.takeIf { it.isFinite() }
                            else -> null
                        },
                        editMarkerCenterXInRoot = editEntryCenterXInRoot.takeIf {
                            isLayoutEditMode && it.isFinite()
                        },
                        dividerHeight = dividerHeight,
                        indicatorAreaHeight = d
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(controlRowHeight)
                    ) {
                        Row(
                            modifier = Modifier.align(Alignment.CenterStart),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(d)
                        ) {
                            GamepadMenuHomeButton(onClick = onClose)
                            GamepadMenuIconButton(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Feature list page",
                                selected = showFeatureListPage,
                                onClick = onToggleFeatureListPage,
                                modifier = Modifier.onGloballyPositioned { coordinates ->
                                    val position = coordinates.positionInRoot()
                                    featureEntryCenterXInRoot =
                                        position.x + coordinates.size.width / 2f
                                }
                            )
                            GamepadMenuIconButton(
                                imageVector = Icons.Default._3dRotation,
                                contentDescription = if (isSimplifiedChineseEnabled) {
                                    "陀螺仪映射"
                                } else {
                                    "Gyroscope mapping"
                                },
                                selected = showGyroscopeMappingPage,
                                onClick = onToggleGyroscopeMappingPage,
                                modifier = Modifier
                                    .testTag("layout_menu_gyroscope_mapping")
                                    .onGloballyPositioned { coordinates ->
                                        val position = coordinates.positionInRoot()
                                        gyroscopeEntryCenterXInRoot =
                                            position.x + coordinates.size.width / 2f
                                    }
                            )
                        }
                        GamepadConnectionStatus(
                            isConnected = isConnected,
                            deviceName = deviceName,
                            isSimplifiedChineseEnabled = isSimplifiedChineseEnabled,
                            onReconnect = onReconnectLastDevice,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .zIndex(1f)
                        )
                        Row(
                            modifier = Modifier.align(Alignment.CenterEnd),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(d)
                        ) {
                            if (isLayoutEditMode) {
                                GamepadLayoutEditButton(
                                    isEditMode = true,
                                    enabled = canEditActiveLayout,
                                    isSimplifiedChineseEnabled = isSimplifiedChineseEnabled,
                                    showUnsavedWarning = showUnsavedSaveWarning,
                                    unsavedWarningPulse = unsavedWarningPulse,
                                    onClick = onToggleLayoutEdit,
                                    modifier = Modifier.onGloballyPositioned { coordinates ->
                                        val position = coordinates.positionInRoot()
                                        editEntryCenterXInRoot =
                                            position.x + coordinates.size.width / 2f
                                    },
                                    testTag = "layout_menu_edit_layout_btn"
                                )
                                GamepadMenuIconButton(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close layouts",
                                    onClick = onDismiss
                                )
                            } else {
                                if (SHOW_BLANK_LAYOUT_TEMPLATE_ENTRY) {
                                    GamepadMenuIconButton(
                                        imageVector = Icons.Default.ViewModule,
                                        contentDescription = "Blank list page",
                                        selected = showBlankListPage,
                                        onClick = onToggleBlankListPage
                                    )
                                }
                                GamepadLayoutEditButton(
                                    isEditMode = false,
                                    enabled = canEditActiveLayout,
                                    isSimplifiedChineseEnabled = isSimplifiedChineseEnabled,
                                    showUnsavedWarning = showUnsavedSaveWarning,
                                    unsavedWarningPulse = unsavedWarningPulse,
                                    onClick = onToggleLayoutEdit,
                                    modifier = Modifier.onGloballyPositioned { coordinates ->
                                        val position = coordinates.positionInRoot()
                                        editEntryCenterXInRoot =
                                            position.x + coordinates.size.width / 2f
                                    },
                                    testTag = "layout_menu_edit_layout_btn"
                                )
                                GamepadMenuIconButton(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close layouts",
                                    onClick = onDismiss
                                )
                            }
                        }
                    }
                }
                }
            }
}
}
}

@Composable
private fun GamepadMenuPageIndicator(
    targetCenterXInRoot: Float?,
    editMarkerCenterXInRoot: Float?,
    dividerHeight: Dp,
    indicatorAreaHeight: Dp,
    modifier: Modifier = Modifier
) {
    val controlScale = LocalGamepadMenuControlScale.current
    var indicatorOriginXInRoot by remember { mutableFloatStateOf(Float.NaN) }
    var hasLocatedTarget by remember { mutableStateOf(false) }
    val resolvedTargetCenterX = targetCenterXInRoot?.takeIf {
        it.isFinite() && indicatorOriginXInRoot.isFinite()
    }?.minus(indicatorOriginXInRoot)
    val resolvedEditMarkerCenterX = editMarkerCenterXInRoot?.takeIf {
        it.isFinite() && indicatorOriginXInRoot.isFinite()
    }?.minus(indicatorOriginXInRoot)
    val indicatorCenterX = remember { Animatable(0f) }
    LaunchedEffect(resolvedTargetCenterX) {
        resolvedTargetCenterX?.let { targetCenterX ->
            if (hasLocatedTarget) {
                indicatorCenterX.animateTo(
                    targetValue = targetCenterX,
                    animationSpec = tween(
                        durationMillis = 180,
                        easing = GamepadStandardEasing
                    )
                )
            } else {
                indicatorCenterX.snapTo(targetCenterX)
                hasLocatedTarget = true
            }
        }
    }
    val indicatorProgress by animateFloatAsState(
        targetValue = if (resolvedTargetCenterX == null) 0f else 1f,
        animationSpec = tween(
            durationMillis = if (resolvedTargetCenterX == null) {
                GAMEPAD_MOTION_EXIT_MS
            } else {
                160
            },
            easing = if (resolvedTargetCenterX == null) {
                GamepadEmphasizedAccelerateEasing
            } else {
                GamepadEmphasizedDecelerateEasing
            }
        ),
        label = "layoutMenuPageIndicatorProgress"
    )
    val editMarkerProgress by animateFloatAsState(
        targetValue = if (resolvedEditMarkerCenterX == null) 0f else 1f,
        animationSpec = tween(
            durationMillis = if (resolvedEditMarkerCenterX == null) {
                GAMEPAD_MOTION_EXIT_MS
            } else {
                GAMEPAD_MOTION_STATE_MS
            },
            easing = if (resolvedEditMarkerCenterX == null) {
                GamepadEmphasizedAccelerateEasing
            } else {
                GamepadEmphasizedDecelerateEasing
            }
        ),
        label = "layoutMenuEditMarkerProgress"
    )
    val triangleWidth = 10.dp * controlScale
    val triangleHeight = (6.dp * controlScale).coerceAtMost(indicatorAreaHeight)
    val indicatorColor = Color.White.copy(alpha = 0.55f)
    val editMarkerColor = Color.White.copy(alpha = 0.2f)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(dividerHeight + indicatorAreaHeight)
            .testTag("layout_menu_page_indicator")
            .onGloballyPositioned { coordinates ->
                val originX = coordinates.positionInRoot().x
                if (indicatorOriginXInRoot != originX) {
                    indicatorOriginXInRoot = originX
                }
            }
    ) {
        val lineHeightPx = ceil(dividerHeight.toPx()).coerceAtLeast(1f)
        val triangleWidthPx = triangleWidth.toPx()
        val triangleHeightPx = triangleHeight.toPx()
        resolvedEditMarkerCenterX?.let { markerCenterX ->
            val activeTargetIsEditMarker = resolvedTargetCenterX?.let { targetCenterX ->
                abs(targetCenterX - markerCenterX) <= 0.5f
            } == true
            val markerProgress = gamepadEditMarkerVisibleProgress(
                markerProgress = editMarkerProgress,
                activeTargetIsMarker = activeTargetIsEditMarker,
                activeCenterX = indicatorCenterX.value,
                markerCenterX = markerCenterX,
                triangleWidthPx = triangleWidthPx
            )
            if (markerProgress > 0f) {
                val markerHalfBase = triangleWidthPx * markerProgress / 2f
                val markerTipY = (
                    lineHeightPx + triangleHeightPx * markerProgress
                ).coerceAtMost(size.height)
                val editMarkerPath = androidx.compose.ui.graphics.Path().apply {
                    moveTo(markerCenterX - markerHalfBase, lineHeightPx)
                    lineTo(markerCenterX + markerHalfBase, lineHeightPx)
                    lineTo(markerCenterX, markerTipY)
                    close()
                }
                drawPath(path = editMarkerPath, color = editMarkerColor)
            }
        }
        val indicatorPath = androidx.compose.ui.graphics.Path().apply {
            moveTo(0f, 0f)
            lineTo(size.width, 0f)
            lineTo(size.width, lineHeightPx)
            lineTo(0f, lineHeightPx)
            close()
            if (indicatorProgress > 0f) {
                val centerX = indicatorCenterX.value
                val halfBase = triangleWidthPx * indicatorProgress / 2f
                val tipY = (
                    lineHeightPx + triangleHeightPx * indicatorProgress
                ).coerceAtMost(size.height)
                moveTo(centerX - halfBase, 0f)
                lineTo(centerX + halfBase, 0f)
                lineTo(centerX, tipY)
                close()
            }
        }
        drawPath(path = indicatorPath, color = indicatorColor)
    }
}

internal fun gamepadEditMarkerVisibleProgress(
    markerProgress: Float,
    activeTargetIsMarker: Boolean,
    activeCenterX: Float,
    markerCenterX: Float,
    triangleWidthPx: Float
): Float {
    val safeMarkerProgress = markerProgress.coerceIn(0f, 1f)
    if (!activeTargetIsMarker) return safeMarkerProgress
    if (
        !activeCenterX.isFinite() || !markerCenterX.isFinite() ||
        !triangleWidthPx.isFinite() || triangleWidthPx <= 0f
    ) {
        return 0f
    }
    val arrivalProgress = (abs(activeCenterX - markerCenterX) / triangleWidthPx).coerceIn(0f, 1f)
    return safeMarkerProgress * arrivalProgress
}

@Composable
private fun GamepadAutomatedTestProgressOverlay(
    elapsedMs: Long,
    totalMs: Long,
    isSimplifiedChineseEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    val safeTotalMs = totalMs.coerceAtLeast(1L)
    val progress = (elapsedMs.toFloat() / safeTotalMs.toFloat()).coerceIn(0f, 1f)
    Surface(
        modifier = modifier.widthIn(min = 280.dp, max = 380.dp),
        shape = RoundedCornerShape(14.dp),
        color = Color(0xEE18181A),
        tonalElevation = 8.dp,
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text(
                text = if (isSimplifiedChineseEnabled) "性能测试" else "Performance Test",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleSmall
            )
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(7.dp)
                    .clip(RoundedCornerShape(50)),
                color = Color(0xFF64B5F6),
                trackColor = Color.White.copy(alpha = 0.20f)
            )
            Text(
                text = buildString {
                    append(if (isSimplifiedChineseEnabled) "进行时间 / 总时间  " else "Elapsed / Total  ")
                    append(String.format(java.util.Locale.US, "%.2f s / %.2f s", elapsedMs / 1000.0, totalMs / 1000.0))
                },
                color = Color.White.copy(alpha = 0.92f),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun GamepadConnectionStatus(
    isConnected: Boolean,
    deviceName: String,
    isSimplifiedChineseEnabled: Boolean,
    onReconnect: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val controlScale = LocalGamepadMenuControlScale.current
    val controlSize = gamepadMenuControlSize()
    val controlCorner = gamepadMenuControlCorner()
    val currentOnReconnect by rememberUpdatedState(onReconnect)
    Row(
        modifier = modifier
            .height(controlSize)
            .gamepadPressScale(enabled = onReconnect != null)
            .clip(controlCorner)
            .then(
                if (onReconnect != null) {
                    Modifier.pointerInput(Unit) {
                        detectTapGestures(onTap = { currentOnReconnect?.invoke() })
                    }
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 8.dp * controlScale)
            .testTag("gamepad_connection_status"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp * controlScale)
    ) {
        Box(
            Modifier
                .size(6.dp * controlScale)
                .clip(CircleShape)
                .background(if (isConnected) Color(0xFF39FF14) else Color(0xFFFF9800))
        )
        Text(
            text = if (
                isSimplifiedChineseEnabled && !isConnected && deviceName == "No Host"
            ) {
                "无主机"
            } else {
                deviceName
            },
            color = Color.White,
            fontSize = gamepadMenuTextSize(11f),
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.SansSerif,
            maxLines = 1
        )
        Text(
            text = if (isSimplifiedChineseEnabled) {
                if (isConnected) "[已连接]" else "[离线]"
            } else {
                if (isConnected) "[connected]" else "[offline]"
            },
            color = Color.White.copy(alpha = 0.5f),
            fontSize = gamepadMenuTextSize(9f),
            fontFamily = FontFamily.SansSerif,
            maxLines = 1
        )
    }
}

@Composable
private fun gamepadMenuControlSize() = 28.dp * LocalGamepadMenuControlScale.current

@Composable
private fun gamepadMenuControlCorner() = RoundedCornerShape(8.dp * LocalGamepadMenuControlScale.current)

@Composable
private fun gamepadMenuIconSize(baseDp: Float) = (baseDp * LocalGamepadMenuControlScale.current).dp

@Composable
private fun gamepadMenuTextSize(baseSp: Float) = (baseSp * LocalGamepadMenuControlScale.current).sp

@Composable
private fun GamepadMenuHomeButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val controlSize = gamepadMenuControlSize()
    val controlCorner = gamepadMenuControlCorner()
    Box(
        modifier = modifier
            .size(controlSize)
            .gamepadPressScale()
            .clip(controlCorner)
            .background(LayoutMenuButtonColor)
            .clickable { onClick() }
            .testTag("layout_menu_exit_gamepad_btn"),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Home,
            contentDescription = "Exit gamepad",
            tint = Color.White,
            modifier = Modifier.size(gamepadMenuIconSize(15f))
        )
    }
}

@Composable
private fun GamepadMenuVibrationButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val controlSize = gamepadMenuControlSize()
    val controlCorner = gamepadMenuControlCorner()
    Box(
        modifier = modifier
            .size(controlSize)
            .gamepadPressScale()
            .clip(controlCorner)
            .background(if (enabled) LayoutMenuButtonColor else LayoutMenuButtonDisabledColor)
            .clickable { onClick() }
            .testTag("layout_menu_vibration_toggle"),
        contentAlignment = Alignment.Center
    ) {
        if (enabled) {
            Icon(
                imageVector = Icons.Default.Vibration,
                contentDescription = "Haptics",
                tint = Color.White,
                modifier = Modifier.size(gamepadMenuIconSize(13f))
            )
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_vibration_off),
                contentDescription = "Haptics",
                tint = Color.White,
                modifier = Modifier.size(gamepadMenuIconSize(13f))
            )
        }
    }
}

@Composable
private fun GamepadLayoutSnapButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val controlSize = gamepadMenuControlSize()
    val controlCorner = gamepadMenuControlCorner()
    Box(
        modifier = modifier
            .size(controlSize)
            .gamepadPressScale()
            .clip(controlCorner)
            .background(if (enabled) LayoutMenuButtonSelectedColor else LayoutMenuButtonDisabledColor)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.GridOn,
                contentDescription = "Alignment guides",
            tint = if (enabled) GamepadSnapGuideYellow else Color.White.copy(alpha = 0.42f),
            modifier = Modifier.size(gamepadMenuIconSize(15f))
        )
    }
}

@Composable
private fun GamepadLayoutResetButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String = "reset_layout_saved_btn"
) {
    val controlSize = gamepadMenuControlSize()
    val controlCorner = gamepadMenuControlCorner()
    Box(
        modifier = modifier
            .size(controlSize)
            .gamepadPressScale(enabled = enabled)
            .clip(controlCorner)
            .background(if (enabled) Color(0xFFB43A3A) else LayoutMenuButtonDisabledColor)
            .clickable(enabled = enabled) { onClick() }
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Refresh,
            contentDescription = "Reset layout",
            tint = Color.White.copy(alpha = if (enabled) 1f else 0.45f),
            modifier = Modifier.size(gamepadMenuIconSize(14f))
        )
    }
}

@Composable
private fun GamepadLayoutEditButton(
    isEditMode: Boolean,
    enabled: Boolean,
    isSimplifiedChineseEnabled: Boolean,
    showUnsavedWarning: Boolean = false,
    unsavedWarningPulse: Int = 0,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String = "edit_layout_toggle_btn"
) {
    val controlSize = gamepadMenuControlSize()
    val controlCorner = gamepadMenuControlCorner()
    val controlScale = LocalGamepadMenuControlScale.current
    val warningTransition = rememberInfiniteTransition(label = "layoutUnsavedWarning$unsavedWarningPulse")
    val warningProgress by warningTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 180, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "layoutUnsavedWarningProgress"
    )
    if (isEditMode) {
        val doneBackgroundColor = when {
            !enabled -> LayoutMenuButtonDisabledColor
            showUnsavedWarning -> lerp(Color(0xFF2E7D32), Color(0xFFFFC107), warningProgress)
            else -> Color(0xFF2E7D32)
        }
        Box(
            modifier = modifier
                .size(controlSize)
                .gamepadPressScale(enabled = enabled)
                .clip(controlCorner)
                .background(doneBackgroundColor)
                .clickable(enabled = enabled) { onClick() }
                .testTag(testTag),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (enabled) Icons.Default.Check else Icons.Default.Lock,
                contentDescription = when {
                    isSimplifiedChineseEnabled && showUnsavedWarning -> "布局有未保存的修改"
                    isSimplifiedChineseEnabled -> "完成"
                    showUnsavedWarning -> "Unsaved layout changes"
                    else -> "Done"
                },
                tint = Color.White.copy(alpha = if (enabled) 1f else 0.48f),
                modifier = Modifier.size(gamepadMenuIconSize(15f))
            )
        }
        return
    }

    Row(
        modifier = modifier
            .height(controlSize)
            .gamepadPressScale(enabled = enabled)
            .clip(controlCorner)
            .background(if (enabled) LayoutMenuButtonColor else LayoutMenuButtonDisabledColor)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 8.dp * controlScale)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            text = when {
                isSimplifiedChineseEnabled && !enabled -> "已锁定"
                isSimplifiedChineseEnabled && isEditMode -> "完成"
                isSimplifiedChineseEnabled -> "编辑布局"
                !enabled -> "Locked"
                isEditMode -> "Done"
                else -> "Edit Layout"
            },
            color = Color.White.copy(alpha = if (enabled) 1f else 0.48f),
            fontSize = gamepadMenuTextSize(9f),
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun GamepadLayoutHeader(
    title: String,
    onCreate: () -> Unit,
    showProfileActions: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onCopy: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    buttonSpacing: androidx.compose.ui.unit.Dp,
    onDismiss: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val controlSize = gamepadMenuControlSize()
    Box(
        modifier = modifier.height(controlSize)
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(buttonSpacing)
        ) {
            Text(
                text = title,
                color = Color.White.copy(alpha = 0.78f),
                fontSize = gamepadMenuTextSize(11f),
                fontWeight = FontWeight.Bold
            )
            GamepadLayoutNewButton(onClick = onCreate)
            GamepadLayoutProfileActions(
                visible = showProfileActions,
                canMoveUp = canMoveUp,
                canMoveDown = canMoveDown,
                buttonSpacing = buttonSpacing,
                onCopy = onCopy,
                onMoveUp = onMoveUp,
                onMoveDown = onMoveDown
            )
        }
        if (onDismiss != null) {
            GamepadMenuIconButton(
                imageVector = Icons.Default.Close,
                contentDescription = "Close layouts",
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }
    }
}

@Composable
private fun GamepadListPageHeaderButton(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(gamepadMenuControlSize())
            .clip(gamepadMenuControlCorner())
            .background(LayoutMenuButtonColor)
    )
}

private data class GamepadFeatureSwitchOption(
    val id: String,
    val title: String,
    val status: String?,
    val checked: Boolean,
    val isBinarySwitch: Boolean,
    val enabled: Boolean = true,
    val actionLabel: String? = null,
    val actionEnabled: Boolean = true,
    val onAction: (() -> Unit)? = null,
    val onToggle: () -> Unit
)

internal enum class GamepadControlType(val storageId: String) {
    LEFT_STICK("left_stick"),
    RIGHT_STICK("right_stick"),
    L3("l3"),
    R3("r3"),
    LEFT_BUMPER("left_bumper"),
    RIGHT_BUMPER("right_bumper"),
    LEFT_TRIGGER("left_trigger"),
    RIGHT_TRIGGER("right_trigger"),
    DPAD("dpad"),
    FACE_BUTTONS("face_buttons"),
    SELECT("select"),
    START("start"),
    GUIDE("guide"),
    SHARE("share"),
    LEFT_STICKPAD("left_stickpad"),
    RIGHT_STICKPAD("right_stickpad"),
    FACE_BOTTOM("face_bottom"),
    FACE_RIGHT("face_right"),
    FACE_LEFT("face_left"),
    FACE_TOP("face_top"),
    DPAD_UP("dpad_up"),
    DPAD_DOWN("dpad_down"),
    DPAD_LEFT("dpad_left"),
    DPAD_RIGHT("dpad_right"),
    GYROSCOPE_TOGGLE("gyroscope_toggle");

    companion object {
        fun fromStorageId(storageId: String): GamepadControlType? = when (storageId) {
            "left_region_stick" -> LEFT_STICKPAD
            "right_region_stick" -> RIGHT_STICKPAD
            else -> entries.firstOrNull { it.storageId == storageId }
        }
    }
}

@Composable
private fun GamepadLayoutProfileActions(
    visible: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    buttonSpacing: Dp,
    onCopy: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(
            tween(durationMillis = 140, easing = GamepadEmphasizedDecelerateEasing)
        ) + scaleIn(
            animationSpec = tween(
                durationMillis = 140,
                easing = GamepadEmphasizedDecelerateEasing
            ),
            initialScale = 0.92f
        ) + slideInHorizontally(
            animationSpec = tween(
                durationMillis = 140,
                easing = GamepadEmphasizedDecelerateEasing
            ),
            initialOffsetX = { fullWidth -> -(fullWidth * 0.04f).roundToInt() }
        ),
        exit = fadeOut(
            tween(
                durationMillis = GAMEPAD_MOTION_EXIT_MS,
                easing = GamepadEmphasizedAccelerateEasing
            )
        ) + scaleOut(
            animationSpec = tween(
                durationMillis = GAMEPAD_MOTION_EXIT_MS,
                easing = GamepadEmphasizedAccelerateEasing
            ),
            targetScale = 0.96f
        ) + slideOutHorizontally(
            animationSpec = tween(
                durationMillis = GAMEPAD_MOTION_EXIT_MS,
                easing = GamepadEmphasizedAccelerateEasing
            ),
            targetOffsetX = { fullWidth -> -(fullWidth * 0.03f).roundToInt() }
        )
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(buttonSpacing)) {
            GamepadMenuIconButton(
                imageVector = Icons.Default.ContentCopy,
                contentDescription = "Copy current layout",
                onClick = onCopy
            )
            GamepadMenuIconButton(
                imageVector = Icons.Default.KeyboardArrowUp,
                contentDescription = "Move layout up",
                enabled = canMoveUp,
                onClick = onMoveUp
            )
            GamepadMenuIconButton(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = "Move layout down",
                enabled = canMoveDown,
                onClick = onMoveDown
            )
        }
    }
}

private data class GamepadIndependentResizeSpec(
    val baseWidthDp: Float,
    val baseHeightDp: Float,
    val minimumAxisScale: Float = GAMEPAD_MIN_EDIT_SCALE,
    val maximumAxisScale: Float = GAMEPAD_MAX_EDIT_SCALE
)

internal fun gamepadStickpadViewportScale(
    viewportWidthDp: Float,
    viewportHeightDp: Float,
    referenceWidthDp: Float = STICKPAD_REFERENCE_CANVAS_WIDTH_DP,
    referenceHeightDp: Float = STICKPAD_REFERENCE_CANVAS_HEIGHT_DP
): Float {
    if (
        !viewportWidthDp.isFinite() || !viewportHeightDp.isFinite() ||
        !referenceWidthDp.isFinite() || !referenceHeightDp.isFinite() ||
        viewportWidthDp <= 0f || viewportHeightDp <= 0f ||
        referenceWidthDp <= 0f || referenceHeightDp <= 0f
    ) {
        return 1f
    }
    return minOf(
        viewportWidthDp / referenceWidthDp,
        viewportHeightDp / referenceHeightDp,
        1f
    ).coerceAtLeast(0.1f)
}

private fun GamepadControlType.independentResizeSpec(): GamepadIndependentResizeSpec? = when (this) {
    GamepadControlType.LEFT_STICKPAD,
    GamepadControlType.RIGHT_STICKPAD -> GamepadIndependentResizeSpec(
        baseWidthDp = STICKPAD_BASE_WIDTH_DP,
        baseHeightDp = STICKPAD_BASE_HEIGHT_DP
    )
    GamepadControlType.LEFT_BUMPER,
    GamepadControlType.RIGHT_BUMPER -> GamepadIndependentResizeSpec(
        baseWidthDp = SHOULDER_BUTTON_BASE_WIDTH_DP,
        baseHeightDp = BUMPER_BUTTON_BASE_HEIGHT_DP,
        minimumAxisScale = SHOULDER_MIN_RESIZE_AXIS_SCALE,
        maximumAxisScale = SHOULDER_MAX_RESIZE_AXIS_SCALE
    )
    GamepadControlType.LEFT_TRIGGER,
    GamepadControlType.RIGHT_TRIGGER -> GamepadIndependentResizeSpec(
        baseWidthDp = SHOULDER_BUTTON_BASE_WIDTH_DP,
        baseHeightDp = TRIGGER_BUTTON_BASE_HEIGHT_DP,
        minimumAxisScale = SHOULDER_MIN_RESIZE_AXIS_SCALE,
        maximumAxisScale = SHOULDER_MAX_RESIZE_AXIS_SCALE
    )
    else -> null
}

private fun GamepadControlType.editFrameInset(): Dp = when (this) {
    GamepadControlType.LEFT_STICK,
    GamepadControlType.RIGHT_STICK -> ANALOG_STICK_EDIT_FRAME_INSET_DP.dp
    GamepadControlType.FACE_BUTTONS -> FACE_BUTTONS_EDIT_FRAME_INSET_DP.dp
    GamepadControlType.DPAD -> DPAD_EDIT_FRAME_INSET_DP.dp
    else -> 0.dp
}

internal val standardGamepadControlTypes = listOf(
    GamepadControlType.LEFT_STICK,
    GamepadControlType.RIGHT_STICK,
    GamepadControlType.L3,
    GamepadControlType.R3,
    GamepadControlType.LEFT_BUMPER,
    GamepadControlType.RIGHT_BUMPER,
    GamepadControlType.LEFT_TRIGGER,
    GamepadControlType.RIGHT_TRIGGER,
    GamepadControlType.DPAD,
    GamepadControlType.FACE_BUTTONS,
    GamepadControlType.SELECT,
    GamepadControlType.START,
    GamepadControlType.GUIDE,
    GamepadControlType.SHARE
)

internal val specialGamepadControlTypes = listOf(
    GamepadControlType.FACE_BOTTOM,
    GamepadControlType.FACE_RIGHT,
    GamepadControlType.FACE_LEFT,
    GamepadControlType.FACE_TOP,
    GamepadControlType.DPAD_UP,
    GamepadControlType.DPAD_DOWN,
    GamepadControlType.DPAD_LEFT,
    GamepadControlType.DPAD_RIGHT,
    GamepadControlType.LEFT_STICKPAD,
    GamepadControlType.RIGHT_STICKPAD,
    GamepadControlType.GYROSCOPE_TOGGLE
)

private fun ConsoleConfig.faceButtonFor(type: GamepadControlType): ButtonDef = when (type) {
    GamepadControlType.FACE_BOTTOM -> faceBottom
    GamepadControlType.FACE_RIGHT -> faceRight
    GamepadControlType.FACE_LEFT -> faceLeft
    GamepadControlType.FACE_TOP -> faceTop
    else -> error("Not an individual face button type: $type")
}

internal fun GamepadControlType.dpadDirectionMask(): Int = when (this) {
    GamepadControlType.DPAD_UP -> 1
    GamepadControlType.DPAD_DOWN -> 2
    GamepadControlType.DPAD_LEFT -> 4
    GamepadControlType.DPAD_RIGHT -> 8
    else -> 0
}

internal data class GamepadControlInstance(
    val id: String,
    val type: GamepadControlType,
    val creationOrder: Long,
    val isInitial: Boolean,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val scale: Float = 1f,
    val widthScale: Float = 1f,
    val heightScale: Float = 1f
)

private data class GamepadLayoutEditSnapshot(
    val layoutValues: Map<String, Float>,
    val controlInstances: List<GamepadControlInstance>
)

private fun initialGamepadControlId(type: GamepadControlType) = "initial_${type.storageId}"

internal fun defaultGamepadControlInstances(): List<GamepadControlInstance> =
    standardGamepadControlTypes.mapIndexed { index, type ->
        GamepadControlInstance(
            id = initialGamepadControlId(type),
            type = type,
            creationOrder = index.toLong(),
            isInitial = true
        )
    }

private fun gamepadControlInstancesKey(storageKey: String) =
    "${storageKey}_$GAMEPAD_CONTROL_INSTANCES_SUFFIX"

internal fun gamepadControlInstancesToJson(instances: List<GamepadControlInstance>): JSONArray =
    JSONArray().apply {
        instances.forEach { instance ->
            put(
                JSONObject()
                    .put("id", instance.id)
                    .put("type", instance.type.storageId)
                    .put("creationOrder", instance.creationOrder)
                    .put("isInitial", instance.isInitial)
                    .put("x", instance.offsetX.toDouble())
                    .put("y", instance.offsetY.toDouble())
                    .put("scale", instance.scale.toDouble())
                    .put("widthScale", instance.widthScale.toDouble())
                    .put("heightScale", instance.heightScale.toDouble())
            )
        }
    }

internal fun parseGamepadControlInstances(
    array: JSONArray?
): List<GamepadControlInstance> {
    if (array == null) return defaultGamepadControlInstances()
    val parsed = mutableListOf<GamepadControlInstance>()
    val seenIds = mutableSetOf<String>()
    val counts = mutableMapOf<GamepadControlType, Int>()
    for (index in 0 until array.length()) {
        val item = array.optJSONObject(index) ?: continue
        val type = GamepadControlType.fromStorageId(item.optString("type")) ?: continue
        val count = counts[type] ?: 0
        if (count >= GAMEPAD_CONTROL_INSTANCE_LIMIT) continue
        val isInitial = item.optBoolean("isInitial", false)
        val fallbackId = if (isInitial) {
            initialGamepadControlId(type)
        } else {
            "${type.storageId}_${item.optLong("creationOrder", index.toLong())}_$index"
        }
        if (isInitial && parsed.any { it.type == type && it.isInitial }) continue
        val id = if (isInitial) {
            initialGamepadControlId(type)
        } else {
            item.optString("id", fallbackId).ifBlank { fallbackId }
        }
        if (!seenIds.add(id)) continue
        val resizeSpec = type.independentResizeSpec()
        val minimumWidthScale = resizeSpec?.let { spec ->
            minOf(spec.baseWidthDp, spec.baseHeightDp) * spec.minimumAxisScale /
                spec.baseWidthDp
        } ?: GAMEPAD_MIN_EDIT_SCALE
        val minimumHeightScale = resizeSpec?.let { spec ->
            minOf(spec.baseWidthDp, spec.baseHeightDp) * spec.minimumAxisScale /
                spec.baseHeightDp
        } ?: GAMEPAD_MIN_EDIT_SCALE
        val maximumAxisScale = resizeSpec?.maximumAxisScale ?: GAMEPAD_MAX_EDIT_SCALE
        parsed += GamepadControlInstance(
            id = id,
            type = type,
            creationOrder = item.optLong("creationOrder", index.toLong()),
            isInitial = isInitial,
            offsetX = item.optDouble("x", 0.0).toFloat(),
            offsetY = item.optDouble("y", 0.0).toFloat(),
            scale = item.optDouble("scale", 1.0).toFloat()
                .coerceIn(GAMEPAD_MIN_EDIT_SCALE, GAMEPAD_MAX_EDIT_SCALE),
            widthScale = item.optDouble("widthScale", 1.0).toFloat()
                .coerceIn(
                    minimumWidthScale,
                    maximumAxisScale
                ),
            heightScale = item.optDouble("heightScale", 1.0).toFloat()
                .coerceIn(
                    minimumHeightScale,
                    maximumAxisScale
                )
        )
        counts[type] = count + 1
    }
    return parsed.sortedBy { it.creationOrder }
}

internal fun addGamepadControlInstance(
    instances: List<GamepadControlInstance>,
    type: GamepadControlType,
    id: String
): List<GamepadControlInstance> {
    if (instances.count { it.type == type } >= GAMEPAD_CONTROL_INSTANCE_LIMIT) return instances
    val nextOrder = (instances.maxOfOrNull { it.creationOrder } ?: -1L) + 1L
    return instances + GamepadControlInstance(
        id = id,
        type = type,
        creationOrder = nextOrder,
        isInitial = false,
        offsetY = GAMEPAD_NEW_CONTROL_VERTICAL_OFFSET_DP
    )
}

internal fun removeNewestGamepadControlInstance(
    instances: List<GamepadControlInstance>,
    type: GamepadControlType
): List<GamepadControlInstance> {
    val newest = instances
        .filter { it.type == type }
        .maxByOrNull { it.creationOrder }
        ?: return instances
    return instances.filterNot { it.id == newest.id }
}

internal fun removeGamepadControlInstance(
    instances: List<GamepadControlInstance>,
    instanceId: String
): List<GamepadControlInstance> {
    if (instances.none { it.id == instanceId }) return instances
    return instances.filterNot { it.id == instanceId }
}

@Composable
private fun GamepadControlListHeader(
    selectedCount: Int?,
    canRemove: Boolean,
    canAdd: Boolean,
    isSnapAlignmentEnabled: Boolean,
    canUndoLayoutEdit: Boolean,
    canRedoLayoutEdit: Boolean,
    hasUnsavedLayoutChanges: Boolean,
    showSpecialControlListPage: Boolean,
    buttonSpacing: Dp,
    onRemove: () -> Unit,
    onAdd: () -> Unit,
    onToggleSnapAlignment: () -> Unit,
    onToggleSpecialControlListPage: () -> Unit,
    onResetLayoutEdit: () -> Unit,
    onUndoLayoutEdit: () -> Unit,
    onRedoLayoutEdit: () -> Unit
) {
    val isSimplifiedChineseEnabled = LocalGamepadSimplifiedChineseEnabled.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(gamepadMenuControlSize())
    ) {
        Row(
            modifier = Modifier.align(Alignment.CenterStart),
            horizontalArrangement = Arrangement.spacedBy(buttonSpacing),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GamepadLayoutSnapButton(
                enabled = isSnapAlignmentEnabled,
                onClick = onToggleSnapAlignment
            )
            GamepadMenuIconButton(
                imageVector = Icons.Default.Extension,
                contentDescription = if (isSimplifiedChineseEnabled) {
                    "特殊按键"
                } else {
                    "Special controls"
                },
                selected = showSpecialControlListPage,
                onClick = onToggleSpecialControlListPage,
                modifier = Modifier.testTag("layout_special_controls_btn")
            )
        }
        Row(
            modifier = Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(buttonSpacing),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GamepadMenuIconButton(
                imageVector = Icons.Default.Remove,
                contentDescription = if (isSimplifiedChineseEnabled) {
                    "删除最新按键"
                } else {
                    "Remove newest control"
                },
                enabled = canRemove,
                onClick = onRemove,
                modifier = Modifier.testTag("layout_control_remove")
            )
            Box(
                modifier = Modifier
                    .size(gamepadMenuControlSize())
                    .testTag("layout_control_count"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = selectedCount?.toString() ?: "/",
                    color = Color.White.copy(alpha = if (selectedCount == null) 0.45f else 1f),
                    fontSize = gamepadMenuTextSize(10f),
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }
            GamepadMenuIconButton(
                imageVector = Icons.Default.Add,
                contentDescription = if (isSimplifiedChineseEnabled) {
                    "添加按键"
                } else {
                    "Add control"
                },
                enabled = canAdd,
                onClick = onAdd,
                modifier = Modifier.testTag("layout_control_add")
            )
        }
        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            horizontalArrangement = Arrangement.spacedBy(buttonSpacing),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GamepadLayoutResetButton(
                enabled = hasUnsavedLayoutChanges,
                onClick = onResetLayoutEdit,
                testTag = "layout_menu_reset_saved_btn"
            )
            GamepadMenuIconButton(
                imageVector = Icons.AutoMirrored.Filled.Undo,
                contentDescription = if (isSimplifiedChineseEnabled) {
                    "撤销布局编辑"
                } else {
                    "Undo layout edit"
                },
                enabled = canUndoLayoutEdit,
                onClick = onUndoLayoutEdit,
                modifier = Modifier.testTag("layout_menu_undo_btn")
            )
            GamepadMenuIconButton(
                imageVector = Icons.AutoMirrored.Filled.Redo,
                contentDescription = if (isSimplifiedChineseEnabled) {
                    "重做布局编辑"
                } else {
                    "Redo layout edit"
                },
                enabled = canRedoLayoutEdit,
                onClick = onRedoLayoutEdit,
                modifier = Modifier.testTag("layout_menu_redo_btn")
            )
        }
    }
}

@Composable
private fun GamepadControlListGrid(
    configId: String,
    controlTypes: List<GamepadControlType>,
    selectedType: GamepadControlType?,
    listHeight: Dp,
    rowHeight: Dp,
    rowSpacing: Dp,
    columnSpacing: Dp,
    onSelect: (GamepadControlType) -> Unit,
    modifier: Modifier = Modifier
) {
    val config = CONSOLES.first { it.id == configId }
    val controlScale = LocalGamepadMenuControlScale.current
    val rowShape = RoundedCornerShape(8.dp * controlScale)
    val types = controlTypes
    val columnCount = 4
    val rowCount = (types.size + columnCount - 1) / columnCount
    val contentHeight =
        rowHeight * rowCount.toFloat() + rowSpacing * (rowCount - 1).coerceAtLeast(0).toFloat()
    val showScrollHint = contentHeight > listHeight

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(listHeight)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            reverseLayout = true,
            verticalArrangement = Arrangement.spacedBy(rowSpacing, Alignment.Bottom)
        ) {
            items(List(rowCount) { it }, key = { it }) { rowIndex ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(columnSpacing)
                ) {
                    repeat(columnCount) { columnIndex ->
                        val type = types.getOrNull(rowIndex * columnCount + columnIndex)
                        if (type == null) {
                            Spacer(Modifier.weight(1f).height(rowHeight))
                        } else {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(rowHeight)
                                    .gamepadPressScale()
                                    .clip(rowShape)
                                    .background(LayoutMenuLayoutRowColor)
                                    .then(
                                        if (type == selectedType) {
                                            Modifier.border(1.dp, Color(0xFF42A5F5), rowShape)
                                        } else {
                                            Modifier
                                        }
                                    )
                                    .clickable { onSelect(type) }
                                    .testTag("layout_control_type_${type.storageId}"),
                                contentAlignment = Alignment.Center
                            ) {
                                GamepadControlTypePreview(type = type, config = config)
                            }
                        }
                    }
                }
            }
        }
        if (showScrollHint) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(28.dp * controlScale)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(LayoutMenuSurfaceColor, Color.Transparent)
                        )
                    )
            )
        }
    }
}

@Composable
private fun GamepadControlTypePreview(
    type: GamepadControlType,
    config: ConsoleConfig
) {
    val isSimplifiedChineseEnabled = LocalGamepadSimplifiedChineseEnabled.current
    val label = when (type) {
        GamepadControlType.LEFT_STICK ->
            if (isSimplifiedChineseEnabled) "左摇杆" else "Left Stick"
        GamepadControlType.RIGHT_STICK ->
            if (isSimplifiedChineseEnabled) "右摇杆" else "Right Stick"
        GamepadControlType.L3 -> "L3"
        GamepadControlType.R3 -> "R3"
        GamepadControlType.LEFT_BUMPER -> config.leftBumper.label
        GamepadControlType.RIGHT_BUMPER -> config.rightBumper.label
        GamepadControlType.LEFT_TRIGGER -> config.leftTrigger.label
        GamepadControlType.RIGHT_TRIGGER -> config.rightTrigger.label
        GamepadControlType.DPAD ->
            if (isSimplifiedChineseEnabled) "方向键" else "D-pad"
        GamepadControlType.LEFT_STICKPAD ->
            if (isSimplifiedChineseEnabled) "左触控摇杆" else "Left Stickpad"
        GamepadControlType.RIGHT_STICKPAD ->
            if (isSimplifiedChineseEnabled) "右触控摇杆" else "Right Stickpad"
        GamepadControlType.GYROSCOPE_TOGGLE ->
            if (isSimplifiedChineseEnabled) "陀螺仪开关" else "Gyroscope Toggle"
        else -> null
    }
    if (label != null) {
        Text(
            text = label,
            color = Color.White,
            fontSize = gamepadMenuTextSize(7.5f),
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
        return
    }

    when (type) {
        GamepadControlType.FACE_BUTTONS -> GamepadFaceButtonsPreview(config.id == XBOX_CONFIG_ID)
        GamepadControlType.FACE_BOTTOM,
        GamepadControlType.FACE_RIGHT,
        GamepadControlType.FACE_LEFT,
        GamepadControlType.FACE_TOP -> GamepadSingleFaceButtonPreview(
            type = type,
            config = config
        )
        GamepadControlType.DPAD_UP,
        GamepadControlType.DPAD_DOWN,
        GamepadControlType.DPAD_LEFT,
        GamepadControlType.DPAD_RIGHT -> GamepadDpadDirectionPreview(
            directionMask = type.dpadDirectionMask()
        )
        GamepadControlType.SELECT -> GamepadSelectIconPreview()
        GamepadControlType.START -> GamepadStartIconPreview()
        GamepadControlType.GUIDE -> Icon(
            imageVector = Icons.Default.SportsEsports,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.82f),
            modifier = Modifier.size(gamepadMenuIconSize(17f))
        )
        GamepadControlType.SHARE -> Icon(
            imageVector = Icons.Default.IosShare,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.82f),
            modifier = Modifier.size(gamepadMenuIconSize(17f))
        )
        else -> Unit
    }
}

@Composable
private fun GamepadFaceButtonsPreview(isXbox: Boolean) {
    val colors = if (isXbox) {
        listOf(Color(0xFF66BB6A), Color(0xFFEF5350), Color(0xFF42A5F5), Color(0xFFFFCA28))
    } else {
        listOf(Color(0xFF4DB6AC), Color(0xFFEF5350), Color(0xFF5C6BC0), Color(0xFFEC407A))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(4) { index ->
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF333336))
                    .border(0.7.dp, Color.White.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                GamepadFaceButtonGlyph(
                    index = index,
                    isXbox = isXbox,
                    color = colors[index]
                )
            }
        }
    }
}

private fun gamepadFaceGlyphIndex(type: GamepadControlType, isXbox: Boolean): Int = when (type) {
    GamepadControlType.FACE_BOTTOM -> if (isXbox) 0 else 2
    GamepadControlType.FACE_RIGHT -> 1
    GamepadControlType.FACE_LEFT -> if (isXbox) 2 else 3
    GamepadControlType.FACE_TOP -> if (isXbox) 3 else 0
    else -> 0
}

@Composable
private fun GamepadSingleFaceButtonPreview(
    type: GamepadControlType,
    config: ConsoleConfig
) {
    val isXbox = config.id == XBOX_CONFIG_ID
    val button = config.faceButtonFor(type)
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(Color(0xFF333336))
            .border(0.7.dp, Color.White.copy(alpha = 0.12f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        GamepadFaceButtonGlyph(
            index = gamepadFaceGlyphIndex(type, isXbox),
            isXbox = isXbox,
            color = button.color
        )
    }
}

internal fun dpadPentagonRotation(directionMask: Int): Float = when (directionMask) {
    1 -> 180f
    2 -> 0f
    4 -> 90f
    8 -> -90f
    else -> 180f
}

private fun dpadPentagonVertices(
    size: androidx.compose.ui.geometry.Size,
    directionMask: Int,
    edgeInset: Float
): List<Offset> {
    val isVertical = directionMask == 1 || directionMask == 2
    val canonicalWidth = if (isVertical) size.width else size.height
    val canonicalHeight = if (isVertical) size.height else size.width
    val halfWidth = canonicalWidth / 2f
    val halfHeight = canonicalHeight / 2f
    val shoulderY = -halfHeight + canonicalHeight * 0.38f
    val canonical = listOf(
        Offset(0f, -halfHeight + edgeInset),
        Offset(halfWidth - edgeInset, shoulderY),
        Offset(halfWidth - edgeInset, halfHeight - edgeInset),
        Offset(-halfWidth + edgeInset, halfHeight - edgeInset),
        Offset(-halfWidth + edgeInset, shoulderY)
    )
    val radians = Math.toRadians(dpadPentagonRotation(directionMask).toDouble())
    val cosine = kotlin.math.cos(radians).toFloat()
    val sine = kotlin.math.sin(radians).toFloat()
    return canonical.map { point ->
        Offset(
            x = size.width / 2f + point.x * cosine - point.y * sine,
            y = size.height / 2f + point.x * sine + point.y * cosine
        )
    }
}

private fun roundedGamepadPolygonPath(
    vertices: List<Offset>,
    cornerCut: Float
): androidx.compose.ui.graphics.Path {
    fun Offset.toward(other: Offset, distance: Float): Offset {
        val dx = other.x - x
        val dy = other.y - y
        val length = sqrt(dx * dx + dy * dy)
        if (length <= 0f) return this
        val safeDistance = distance.coerceAtMost(length / 2f)
        return Offset(x + dx / length * safeDistance, y + dy / length * safeDistance)
    }

    val before = vertices.indices.map { index ->
        vertices[index].toward(vertices[(index - 1 + vertices.size) % vertices.size], cornerCut)
    }
    val after = vertices.indices.map { index ->
        vertices[index].toward(vertices[(index + 1) % vertices.size], cornerCut)
    }
    return androidx.compose.ui.graphics.Path().apply {
        moveTo(after[0].x, after[0].y)
        for (index in 1 until vertices.size) {
            lineTo(before[index].x, before[index].y)
            quadraticTo(
                vertices[index].x,
                vertices[index].y,
                after[index].x,
                after[index].y
            )
        }
        lineTo(before[0].x, before[0].y)
        quadraticTo(vertices[0].x, vertices[0].y, after[0].x, after[0].y)
        close()
    }
}

private fun dpadDirectionMarkerPath(
    size: androidx.compose.ui.geometry.Size,
    directionMask: Int
): androidx.compose.ui.graphics.Path {
    val centerX = size.width / 2f
    val centerY = size.height / 2f
    val along = if (directionMask == 1 || directionMask == 2) size.height else size.width
    val across = if (directionMask == 1 || directionMask == 2) size.width else size.height
    val outerDistance = along * 0.30f
    val markerLength = along * 0.10f
    val markerHalfWidth = across * 0.09f
    return androidx.compose.ui.graphics.Path().apply {
        when (directionMask) {
            1 -> {
                moveTo(centerX, centerY - outerDistance)
                lineTo(centerX - markerHalfWidth, centerY - outerDistance + markerLength)
                lineTo(centerX + markerHalfWidth, centerY - outerDistance + markerLength)
            }
            2 -> {
                moveTo(centerX, centerY + outerDistance)
                lineTo(centerX - markerHalfWidth, centerY + outerDistance - markerLength)
                lineTo(centerX + markerHalfWidth, centerY + outerDistance - markerLength)
            }
            4 -> {
                moveTo(centerX - outerDistance, centerY)
                lineTo(centerX - outerDistance + markerLength, centerY - markerHalfWidth)
                lineTo(centerX - outerDistance + markerLength, centerY + markerHalfWidth)
            }
            else -> {
                moveTo(centerX + outerDistance, centerY)
                lineTo(centerX + outerDistance - markerLength, centerY - markerHalfWidth)
                lineTo(centerX + outerDistance - markerLength, centerY + markerHalfWidth)
            }
        }
        close()
    }
}

@Composable
private fun GamepadDpadDirectionPreview(directionMask: Int) {
    val isVertical = directionMask == 1 || directionMask == 2
    Canvas(
        modifier = Modifier
            .width(gamepadMenuIconSize(if (isVertical) 16f else 24f))
            .height(gamepadMenuIconSize(if (isVertical) 24f else 16f))
    ) {
        val path = roundedGamepadPolygonPath(
            vertices = dpadPentagonVertices(size, directionMask, 2.4.dp.toPx()),
            cornerCut = 1.7.dp.toPx()
        )
        val outerStroke = Stroke(
            width = 4.2.dp.toPx(),
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
            join = androidx.compose.ui.graphics.StrokeJoin.Round
        )
        val wellStroke = Stroke(
            width = 3.2.dp.toPx(),
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
            join = androidx.compose.ui.graphics.StrokeJoin.Round
        )
        drawPath(path, Color.Black.copy(alpha = 0.55f), style = outerStroke)
        drawPath(path, Color(0xFF1B1B1C), style = wellStroke)
        drawPath(
            path = path,
            brush = Brush.verticalGradient(
                colors = listOf(Color(0xFF4A4A4E), Color(0xFF2D2D30))
            )
        )
        drawPath(
            path = path,
            color = Color.White.copy(alpha = 0.22f),
            style = Stroke(width = 0.65.dp.toPx())
        )
        drawPath(
            path = dpadDirectionMarkerPath(size, directionMask),
            color = Color.White.copy(alpha = 0.34f)
        )
    }
}

@Composable
private fun GamepadFaceButtonGlyph(
    index: Int,
    isXbox: Boolean,
    color: Color
) {
    Canvas(modifier = Modifier.size(10.dp)) {
        val strokeWidth = 1.35.dp.toPx()
        val stroke = Stroke(
            width = strokeWidth,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
            join = androidx.compose.ui.graphics.StrokeJoin.Round
        )
        val w = size.width
        val h = size.height
        if (!isXbox) {
            when (index) {
                0 -> drawPath(
                    path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(w * 0.50f, h * 0.14f)
                        lineTo(w * 0.88f, h * 0.84f)
                        lineTo(w * 0.12f, h * 0.84f)
                        close()
                    },
                    color = color,
                    style = stroke
                )
                1 -> drawCircle(color = color, radius = w * 0.36f, style = stroke)
                2 -> {
                    drawLine(color, Offset(w * 0.20f, h * 0.20f), Offset(w * 0.80f, h * 0.80f), strokeWidth)
                    drawLine(color, Offset(w * 0.80f, h * 0.20f), Offset(w * 0.20f, h * 0.80f), strokeWidth)
                }
                else -> drawRect(
                    color = color,
                    topLeft = Offset(w * 0.18f, h * 0.18f),
                    size = androidx.compose.ui.geometry.Size(w * 0.64f, h * 0.64f),
                    style = stroke
                )
            }
            return@Canvas
        }

        when (index) {
            0 -> {
                drawLine(color, Offset(w * 0.14f, h * 0.84f), Offset(w * 0.50f, h * 0.14f), strokeWidth)
                drawLine(color, Offset(w * 0.50f, h * 0.14f), Offset(w * 0.86f, h * 0.84f), strokeWidth)
                drawLine(color, Offset(w * 0.30f, h * 0.59f), Offset(w * 0.70f, h * 0.59f), strokeWidth)
            }
            1 -> drawPath(
                path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(w * 0.28f, h * 0.14f)
                    lineTo(w * 0.28f, h * 0.86f)
                    moveTo(w * 0.28f, h * 0.14f)
                    cubicTo(w * 0.80f, h * 0.14f, w * 0.80f, h * 0.50f, w * 0.28f, h * 0.50f)
                    moveTo(w * 0.28f, h * 0.50f)
                    cubicTo(w * 0.84f, h * 0.50f, w * 0.84f, h * 0.86f, w * 0.28f, h * 0.86f)
                },
                color = color,
                style = stroke
            )
            2 -> {
                drawLine(color, Offset(w * 0.18f, h * 0.16f), Offset(w * 0.82f, h * 0.84f), strokeWidth)
                drawLine(color, Offset(w * 0.82f, h * 0.16f), Offset(w * 0.18f, h * 0.84f), strokeWidth)
            }
            else -> {
                drawLine(color, Offset(w * 0.16f, h * 0.16f), Offset(w * 0.50f, h * 0.50f), strokeWidth)
                drawLine(color, Offset(w * 0.84f, h * 0.16f), Offset(w * 0.50f, h * 0.50f), strokeWidth)
                drawLine(color, Offset(w * 0.50f, h * 0.50f), Offset(w * 0.50f, h * 0.86f), strokeWidth)
            }
        }
    }
}

@Composable
private fun GamepadSelectIconPreview() {
    Canvas(modifier = Modifier.size(gamepadMenuIconSize(17f))) {
        val strokeWidth = 1.6.dp.toPx()
        drawRect(
            color = Color.White.copy(alpha = 0.82f),
            topLeft = Offset(size.width * 0.26f, 0f),
            size = androidx.compose.ui.geometry.Size(size.width * 0.72f, size.height * 0.72f),
            style = Stroke(strokeWidth)
        )
        drawRect(
            color = Color.White.copy(alpha = 0.82f),
            topLeft = Offset(0f, size.height * 0.28f),
            size = androidx.compose.ui.geometry.Size(size.width * 0.72f, size.height * 0.72f),
            style = Stroke(strokeWidth)
        )
    }
}

@Composable
private fun GamepadStartIconPreview() {
    Column(
        modifier = Modifier.size(gamepadMenuIconSize(17f)),
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        repeat(3) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.8.dp)
                    .background(Color.White.copy(alpha = 0.82f))
            )
        }
    }
}

@Composable
private fun GamepadFeatureListGrid(
    isL3R3ToggleMode: Boolean,
    isStickClickEnabled: Boolean,
    isFullStickOutputEnabled: Boolean,
    isTouchAssistEnabled: Boolean,
    isSimplifiedChineseEnabled: Boolean,
    listHeight: Dp,
    rowHeight: Dp,
    rowSpacing: Dp,
    columnSpacing: Dp,
    onToggleL3R3Mode: () -> Unit,
    onToggleStickClick: () -> Unit,
    onToggleFullStickOutput: () -> Unit,
    onToggleTouchAssist: () -> Unit,
    modifier: Modifier = Modifier
) {
    val options = listOf(
        GamepadFeatureSwitchOption(
            id = "l3_r3_trigger_mode",
            title = if (isSimplifiedChineseEnabled) "L3/R3 触发模式" else "L3/R3 Trigger Mode",
            status = if (isSimplifiedChineseEnabled) {
                if (isL3R3ToggleMode) "切换" else "按住"
            } else {
                if (isL3R3ToggleMode) "Toggle" else "Hold"
            },
            checked = isL3R3ToggleMode,
            isBinarySwitch = false,
            onToggle = onToggleL3R3Mode
        ),
        GamepadFeatureSwitchOption(
            id = "stick_click_l3_r3",
            title = if (isSimplifiedChineseEnabled) {
                "点击摇杆触发 L3/R3"
            } else {
                "Stick Click L3/R3"
            },
            status = null,
            checked = isStickClickEnabled,
            isBinarySwitch = true,
            onToggle = onToggleStickClick
        ),
        GamepadFeatureSwitchOption(
            id = "full_stick_output",
            title = if (isSimplifiedChineseEnabled) "摇杆满幅模式" else "Full Stick Mode",
            status = null,
            checked = isFullStickOutputEnabled,
            isBinarySwitch = true,
            onToggle = onToggleFullStickOutput
        ),
        GamepadFeatureSwitchOption(
            id = "touch_assist",
            title = if (isSimplifiedChineseEnabled) "触摸辅助" else "Touch Assist",
            status = null,
            checked = isTouchAssistEnabled,
            isBinarySwitch = true,
            onToggle = onToggleTouchAssist
        )
    )
    val controlScale = LocalGamepadMenuControlScale.current
    val itemCount = options.size
    val rowCount = (itemCount + 1) / 2
    val contentHeight =
        rowHeight * rowCount.toFloat() + rowSpacing * (rowCount - 1).coerceAtLeast(0).toFloat()
    val showScrollHint = contentHeight > listHeight

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(listHeight)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            reverseLayout = true,
            verticalArrangement = Arrangement.spacedBy(rowSpacing, Alignment.Bottom)
        ) {
            items(List(rowCount) { it }, key = { it }) { rowIndex ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(columnSpacing)
                ) {
                    repeat(2) { columnIndex ->
                        val itemIndex = rowIndex * 2 + columnIndex
                        val option = options.getOrNull(itemIndex)
                        if (option != null) {
                            GamepadFeatureSwitchRow(
                                option = option,
                                rowHeight = rowHeight,
                                modifier = Modifier.weight(1f)
                            )
                        } else {
                            Spacer(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(rowHeight)
                            )
                        }
                    }
                }
            }
        }
        if (showScrollHint) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(28.dp * controlScale)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                LayoutMenuSurfaceColor,
                                Color.Transparent
                            )
                        )
                    )
            )
        }
    }
}

@Composable
private fun GamepadFeatureSwitchRow(
    option: GamepadFeatureSwitchOption,
    rowHeight: Dp,
    modifier: Modifier = Modifier
) {
    val controlScale = LocalGamepadMenuControlScale.current
    val rowShape = RoundedCornerShape(8.dp * controlScale)
    val trackHeight = 24.dp * controlScale
    val outerInset = ((rowHeight - trackHeight) / 2f).coerceAtLeast(0.dp)

    Row(
        modifier = modifier
            .height(rowHeight)
            .clip(rowShape)
            .background(LayoutMenuLayoutRowColor)
            .padding(start = 10.dp * controlScale, end = outerInset),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = option.title,
            color = Color.White.copy(alpha = if (option.enabled) 1f else 0.42f),
            fontSize = gamepadMenuTextSize(11f),
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = if (option.actionLabel == null) Modifier.weight(1f) else Modifier
        )
        option.actionLabel?.let { label ->
            Spacer(Modifier.width(7.dp * controlScale))
            GamepadFeatureInlineActionButton(
                label = label,
                enabled = option.actionEnabled,
                onClick = { option.onAction?.invoke() },
                modifier = Modifier.testTag("feature_action_${option.id}")
            )
            Spacer(Modifier.weight(1f))
        }
        option.status?.let { status ->
            Spacer(Modifier.width(5.dp * controlScale))
            Text(
                text = "[$status]",
                color = Color.White.copy(alpha = 0.62f),
                fontSize = gamepadMenuTextSize(8f),
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false
            )
        }
        Spacer(Modifier.width(5.dp * controlScale))
        GamepadFeatureSwitch(
            checked = option.checked,
            isBinarySwitch = option.isBinarySwitch,
            enabled = option.enabled,
            onClick = option.onToggle,
            modifier = Modifier.testTag("feature_switch_${option.id}")
        )
    }
}

@Composable
private fun GamepadFeatureInlineActionButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val controlScale = LocalGamepadMenuControlScale.current
    Box(
        modifier = modifier
            .height(22.dp * controlScale)
            .gamepadPressScale(enabled = enabled)
            .clip(RoundedCornerShape(6.dp * controlScale))
            .background(
                if (enabled) Color(0xFF456A8D) else LayoutMenuButtonDisabledColor
            )
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 8.dp * controlScale),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = if (enabled) 1f else 0.38f),
            fontSize = gamepadMenuTextSize(9f),
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun GamepadFeatureSwitch(
    checked: Boolean,
    isBinarySwitch: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val controlScale = LocalGamepadMenuControlScale.current
    val trackWidth = 40.dp * controlScale
    val trackHeight = 24.dp * controlScale
    val thumbWidth = 20.dp * controlScale
    val thumbHeight = 20.dp * controlScale
    val innerInset = 2.dp * controlScale
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) trackWidth - thumbWidth - innerInset else innerInset,
        animationSpec = tween(durationMillis = 120, easing = FastOutSlowInEasing),
        label = "featureSwitchOffset"
    )
    val trackColor = when {
        !enabled -> LayoutMenuButtonDisabledColor
        !isBinarySwitch -> LayoutMenuButtonColor
        checked -> Color(0xFF2E7D32)
        else -> LayoutMenuButtonDisabledColor
    }

    Box(
        modifier = modifier
            .width(trackWidth)
            .height(trackHeight)
            .gamepadPressScale(enabled = enabled)
            .clip(RoundedCornerShape(6.dp * controlScale))
            .background(trackColor)
            .clickable(enabled = enabled) { onClick() }
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = thumbOffset)
                .width(thumbWidth)
                .height(thumbHeight)
                .clip(RoundedCornerShape(4.dp * controlScale))
                .background(Color.White.copy(alpha = if (enabled) 1f else 0.38f))
        )
    }
}

@Composable
private fun GamepadGyroscopeMappingGrid(
    isGyroscopeAvailable: Boolean,
    isGyroscopeEnabled: Boolean,
    gyroscopeMode: GamepadGyroscopeMode,
    isMappedToRightStick: Boolean,
    isHorizontalInverted: Boolean,
    isVerticalInverted: Boolean,
    isSimplifiedChineseEnabled: Boolean,
    listHeight: Dp,
    rowHeight: Dp,
    rowSpacing: Dp,
    columnSpacing: Dp,
    onToggleGyroscope: () -> Unit,
    onToggleGyroscopeMode: () -> Unit,
    onCalibrateGyroscope: () -> Unit,
    onToggleMappedStick: () -> Unit,
    onToggleHorizontalInversion: () -> Unit,
    onToggleVerticalInversion: () -> Unit,
    modifier: Modifier = Modifier
) {
    val masterOption = GamepadFeatureSwitchOption(
        id = "gyroscope_master",
        title = if (isSimplifiedChineseEnabled) "陀螺仪总开关" else "Gyroscope",
        status = if (isGyroscopeAvailable) {
            null
        } else if (isSimplifiedChineseEnabled) {
            "不可用"
        } else {
            "Unavailable"
        },
        checked = isGyroscopeEnabled && isGyroscopeAvailable,
        isBinarySwitch = true,
        enabled = isGyroscopeAvailable,
        onToggle = onToggleGyroscope
    )
    val modeOption = GamepadFeatureSwitchOption(
        id = "gyroscope_mode",
        title = if (isSimplifiedChineseEnabled) "模式" else "Mode",
        status = if (isSimplifiedChineseEnabled) {
            if (gyroscopeMode == GamepadGyroscopeMode.TILT) "赛车" else "视角"
        } else {
            if (gyroscopeMode == GamepadGyroscopeMode.TILT) "Racing" else "View"
        },
        checked = gyroscopeMode == GamepadGyroscopeMode.TILT,
        isBinarySwitch = false,
        actionLabel = if (gyroscopeMode == GamepadGyroscopeMode.TILT) {
            if (isSimplifiedChineseEnabled) "校准" else "Calibrate"
        } else {
            null
        },
        actionEnabled = isGyroscopeAvailable,
        onAction = onCalibrateGyroscope,
        onToggle = onToggleGyroscopeMode
    )
    val mappedStickOption = GamepadFeatureSwitchOption(
        id = "gyroscope_stick",
        title = if (isSimplifiedChineseEnabled) {
            "映射成左/右摇杆"
        } else {
            "Map to left/right stick"
        },
        status = if (isSimplifiedChineseEnabled) {
            if (isMappedToRightStick) "右摇杆" else "左摇杆"
        } else {
            if (isMappedToRightStick) "Right" else "Left"
        },
        checked = isMappedToRightStick,
        isBinarySwitch = false,
        onToggle = onToggleMappedStick
    )
    val horizontalInversionOption = GamepadFeatureSwitchOption(
        id = "gyroscope_invert_horizontal",
        title = if (isSimplifiedChineseEnabled) "反转水平" else "Invert horizontal",
        status = null,
        checked = isHorizontalInverted,
        isBinarySwitch = true,
        onToggle = onToggleHorizontalInversion
    )
    val verticalInversionOption = GamepadFeatureSwitchOption(
        id = "gyroscope_invert_vertical",
        title = if (isSimplifiedChineseEnabled) "反转垂直" else "Invert vertical",
        status = null,
        checked = isVerticalInverted,
        isBinarySwitch = true,
        onToggle = onToggleVerticalInversion
    )
    val rowCount = 2
    val contentHeight =
        rowHeight * rowCount.toFloat() + rowSpacing * (rowCount - 1).coerceAtLeast(0).toFloat()
    val showScrollHint = contentHeight > listHeight
    val controlScale = LocalGamepadMenuControlScale.current

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(listHeight)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            reverseLayout = true,
            verticalArrangement = Arrangement.spacedBy(rowSpacing, Alignment.Bottom)
        ) {
            items(List(rowCount) { it }, key = { it }) { rowIndex ->
                if (rowIndex == 0) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(columnSpacing)
                    ) {
                        GamepadFeatureSwitchRow(
                            option = masterOption,
                            rowHeight = rowHeight,
                            modifier = Modifier.weight(1f)
                        )
                        GamepadFeatureSwitchRow(
                            option = modeOption,
                            rowHeight = rowHeight,
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(columnSpacing)
                    ) {
                        GamepadFeatureSwitchRow(
                            option = mappedStickOption,
                            rowHeight = rowHeight,
                            modifier = Modifier.weight(1f)
                        )
                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(columnSpacing)
                        ) {
                            GamepadFeatureSwitchRow(
                                option = horizontalInversionOption,
                                rowHeight = rowHeight,
                                modifier = Modifier.weight(1f)
                            )
                            GamepadFeatureSwitchRow(
                                option = verticalInversionOption,
                                rowHeight = rowHeight,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
        if (showScrollHint) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(28.dp * controlScale)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(LayoutMenuSurfaceColor, Color.Transparent)
                        )
                    )
            )
        }
    }
}

private fun gyroscopeSuppressionLabel(
    suppression: GamepadGyroJitterSuppression,
    isSimplifiedChineseEnabled: Boolean
): String = if (isSimplifiedChineseEnabled) {
    when (suppression) {
        GamepadGyroJitterSuppression.NONE -> "无"
        GamepadGyroJitterSuppression.LOW -> "低"
        GamepadGyroJitterSuppression.MEDIUM -> "中"
        GamepadGyroJitterSuppression.HIGH -> "高"
    }
} else {
    when (suppression) {
        GamepadGyroJitterSuppression.NONE -> "Off"
        GamepadGyroJitterSuppression.LOW -> "Low"
        GamepadGyroJitterSuppression.MEDIUM -> "Medium"
        GamepadGyroJitterSuppression.HIGH -> "High"
    }
}

@Composable
private fun GamepadFeatureControlsHeader(
    isVibrationEnabled: Boolean,
    isAutomatedInputTestRunning: Boolean,
    isConnected: Boolean,
    gamepadReportRate: GamepadReportRate,
    isSimplifiedChineseEnabled: Boolean,
    buttonSpacing: Dp,
    sliderWidth: Dp,
    controlHeight: Dp,
    onToggleLanguage: () -> Unit,
    onRotateScreen: () -> Unit,
    onToggleVibration: () -> Unit,
    onStartAutomatedInputTest: () -> Unit,
    onGamepadReportRateChange: (GamepadReportRate) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        Row(
            modifier = Modifier.align(Alignment.CenterStart),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(buttonSpacing)
        ) {
            GamepadMenuIconButton(
                imageVector = Icons.Default.Translate,
                contentDescription = if (isSimplifiedChineseEnabled) {
                    "切换为英语"
                } else {
                    "Switch to Simplified Chinese"
                },
                onClick = onToggleLanguage
            )
            GamepadMenuIconButton(
                imageVector = Icons.Default.ScreenRotation,
                contentDescription = if (isSimplifiedChineseEnabled) {
                    "旋转屏幕"
                } else {
                    "Rotate screen"
                },
                onClick = onRotateScreen,
                modifier = Modifier.testTag("layout_menu_rotate_screen")
            )
            GamepadMenuVibrationButton(
                enabled = isVibrationEnabled,
                onClick = onToggleVibration
            )
            GamepadMenuIconButton(
                imageVector = Icons.Default.Science,
                contentDescription = if (isSimplifiedChineseEnabled) {
                    "性能测试"
                } else {
                    "Performance Test"
                },
                selected = isAutomatedInputTestRunning,
                enabled = isConnected && !isAutomatedInputTestRunning,
                onClick = onStartAutomatedInputTest,
                modifier = Modifier.testTag("gamepad_automated_test_btn")
            )
        }
        GamepadGyroscopeInlineSlider(
            title = if (isSimplifiedChineseEnabled) "目标回报率" else "Target report rate",
            value = gamepadReportRate.sliderPosition,
            valueRange = 0f..2f,
            steps = 1,
            valueText = { value ->
                "${GamepadReportRate.fromSliderPosition(value).hz} Hz"
            },
            onValueChange = { value ->
                val selected = GamepadReportRate.fromSliderPosition(value)
                if (selected != gamepadReportRate) {
                    onGamepadReportRateChange(selected)
                }
            },
            testTag = "gamepad_report_rate",
            controlHeight = controlHeight,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(sliderWidth)
        )
    }
}

@Composable
private fun GamepadGyroscopeSliderHeader(
    sensitivity: Float,
    jitterSuppression: GamepadGyroJitterSuppression,
    isSimplifiedChineseEnabled: Boolean,
    controlWidth: Dp,
    controlHeight: Dp,
    onSensitivityChange: (Float) -> Unit,
    onJitterSuppressionChange: (GamepadGyroJitterSuppression) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        GamepadGyroscopeInlineSlider(
            title = if (isSimplifiedChineseEnabled) "灵敏度" else "Sensitivity",
            value = sensitivity,
            valueRange = GAMEPAD_GYRO_MIN_SENSITIVITY..GAMEPAD_GYRO_MAX_SENSITIVITY,
            steps = 28,
            valueText = { value ->
                String.format(java.util.Locale.US, "%.1f", value)
            },
            onValueChange = onSensitivityChange,
            testTag = "gyroscope_sensitivity",
            controlHeight = controlHeight,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .width(controlWidth)
        )
        GamepadGyroscopeInlineSlider(
            title = if (isSimplifiedChineseEnabled) "抖动抑制" else "Jitter suppression",
            value = jitterSuppression.preferenceValue.toFloat(),
            valueRange = 0f..3f,
            steps = 2,
            valueText = { value ->
                gyroscopeSuppressionLabel(
                    GamepadGyroJitterSuppression.fromPreference(
                        value.roundToInt().coerceIn(0, 3)
                    ),
                    isSimplifiedChineseEnabled
                )
            },
            onValueChange = { value ->
                onJitterSuppressionChange(
                    GamepadGyroJitterSuppression.fromPreference(
                        value.roundToInt().coerceIn(0, 3)
                    )
                )
            },
            testTag = "gyroscope_jitter_suppression",
            controlHeight = controlHeight,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(controlWidth)
        )
    }
}

@Composable
private fun GamepadGyroscopeInlineSlider(
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    valueText: (Float) -> String,
    onValueChange: (Float) -> Unit,
    testTag: String,
    controlHeight: Dp,
    modifier: Modifier = Modifier
) {
    val controlScale = LocalGamepadMenuControlScale.current
    val view = LocalView.current
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    var targetValue by remember { mutableFloatStateOf(value) }
    var isDragging by remember { mutableStateOf(false) }
    var pendingCommit by remember { mutableStateOf(false) }
    val displayedValue by animateFloatAsState(
        targetValue = targetValue,
        animationSpec = if (isDragging) {
            snap()
        } else {
            spring(dampingRatio = 0.9f, stiffness = 700f)
        },
        finishedListener = { settledValue ->
            if (pendingCommit) {
                pendingCommit = false
                currentOnValueChange(settledValue)
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
        },
        label = "${testTag}SettlingValue"
    )

    LaunchedEffect(value) {
        if (!isDragging && !pendingCommit) {
            targetValue = value.coerceIn(valueRange)
        }
    }

    Row(
        modifier = modifier
            .height(controlHeight)
            .padding(horizontal = 2.dp * controlScale),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            color = Color.White,
            fontSize = gamepadMenuTextSize(11f),
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.width(6.dp * controlScale))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(controlHeight)
        ) {
            Canvas(
                modifier = Modifier
                    .matchParentSize()
                    .padding(horizontal = 10.dp * controlScale)
            ) {
                val selectableValueCount = steps + 2
                repeat(selectableValueCount) { index ->
                    val fraction = if (selectableValueCount <= 1) {
                        0f
                    } else {
                        index.toFloat() / (selectableValueCount - 1).toFloat()
                    }
                    val selectedFraction =
                        ((displayedValue - valueRange.start) /
                            (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f)
                    val tickColor = if (fraction <= selectedFraction) {
                        Color.White.copy(alpha = 0.48f)
                    } else {
                        Color.White.copy(alpha = 0.22f)
                    }
                    drawLine(
                        color = tickColor,
                        start = Offset(size.width * fraction, size.height / 2f - 3.dp.toPx()),
                        end = Offset(size.width * fraction, size.height / 2f + 3.dp.toPx()),
                        strokeWidth = 1.dp.toPx()
                    )
                }
            }
            Slider(
                value = displayedValue,
                onValueChange = { draggedValue ->
                    pendingCommit = false
                    isDragging = true
                    targetValue = draggedValue
                },
                onValueChangeFinished = {
                    val snappedValue = snapGamepadSliderValue(
                        value = targetValue,
                        rangeStart = valueRange.start,
                        rangeEnd = valueRange.endInclusive,
                        steps = steps
                    )
                    isDragging = false
                    targetValue = snappedValue
                    if (abs(displayedValue - snappedValue) <= 0.0001f) {
                        pendingCommit = false
                        currentOnValueChange(snappedValue)
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    } else {
                        pendingCommit = true
                    }
                },
                valueRange = valueRange,
                steps = 0,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color(0xFF5E9E63),
                    inactiveTrackColor = LayoutMenuButtonDisabledColor,
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent
                ),
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(testTag)
            )
        }
        Spacer(Modifier.width(5.dp * controlScale))
        Text(
            text = valueText(displayedValue),
            color = Color.White.copy(alpha = 0.72f),
            fontSize = gamepadMenuTextSize(8f),
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(40.dp * controlScale)
        )
        Spacer(Modifier.width(3.dp * controlScale))
    }
}

internal fun snapGamepadSliderValue(
    value: Float,
    rangeStart: Float,
    rangeEnd: Float,
    steps: Int
): Float {
    if (rangeEnd <= rangeStart) return rangeStart
    val intervalCount = steps.coerceAtLeast(0) + 1
    val intervalSize = (rangeEnd - rangeStart) / intervalCount.toFloat()
    val intervalIndex = ((value - rangeStart) / intervalSize)
        .roundToInt()
        .coerceIn(0, intervalCount)
    return rangeStart + intervalSize * intervalIndex.toFloat()
}

@Composable
private fun GamepadListPageGrid(
    placeholderCount: Int,
    listHeight: Dp,
    rowHeight: Dp,
    rowSpacing: Dp,
    columnSpacing: Dp,
    modifier: Modifier = Modifier
) {
    val controlScale = LocalGamepadMenuControlScale.current
    val rowShape = RoundedCornerShape(8.dp * controlScale)
    val rowCount = (placeholderCount + 1) / 2
    val contentHeight =
        rowHeight * rowCount.toFloat() + rowSpacing * (rowCount - 1).coerceAtLeast(0).toFloat()
    val showScrollHint = contentHeight > listHeight

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(listHeight)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            reverseLayout = true,
            verticalArrangement = Arrangement.spacedBy(rowSpacing, Alignment.Bottom)
        ) {
            items(List(rowCount) { it }, key = { it }) { rowIndex ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(columnSpacing)
                ) {
                    repeat(2) { columnIndex ->
                        val placeholderIndex = rowIndex * 2 + columnIndex
                        if (placeholderIndex < placeholderCount) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(rowHeight)
                                    .clip(rowShape)
                                    .background(LayoutMenuLayoutRowColor)
                            )
                        } else {
                            Spacer(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(rowHeight)
                            )
                        }
                    }
                }
            }
        }
        if (showScrollHint) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(28.dp * controlScale)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                LayoutMenuSurfaceColor,
                                Color.Transparent
                            )
                        )
                    )
            )
        }
    }
}

@Composable
private fun GamepadLayoutColumn(
    profiles: List<GamepadLayoutProfile>,
    selectedLayoutId: String,
    isEditing: Boolean,
    confirmingDeleteLayoutId: String?,
    multiSelectedLayoutIds: List<String>,
    isMultiSelectMode: Boolean,
    canStartMultiSelect: Boolean,
    listHeight: androidx.compose.ui.unit.Dp,
    rowHeight: androidx.compose.ui.unit.Dp,
    rowSpacing: androidx.compose.ui.unit.Dp,
    onSelect: (GamepadLayoutProfile) -> Unit,
    onRename: (GamepadLayoutProfile) -> Unit,
    onDeleteRequest: (GamepadLayoutProfile) -> Unit,
    onDeleteConfirm: (GamepadLayoutProfile) -> Unit,
    onStartMultiSelect: (GamepadLayoutProfile) -> Unit,
    onToggleMultiSelect: (GamepadLayoutProfile) -> Unit,
    modifier: Modifier = Modifier
) {
    val contentHeight =
        rowHeight * profiles.size.toFloat() + rowSpacing * (profiles.size - 1).coerceAtLeast(0).toFloat()
    val showScrollHint = contentHeight > listHeight
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(rowSpacing)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(listHeight)
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(),
                reverseLayout = true,
                verticalArrangement = Arrangement.spacedBy(rowSpacing, Alignment.Bottom)
            ) {
                items(profiles, key = { it.id }) { profile ->
                    GamepadLayoutProfileRow(
                        profile = profile,
                        selected = profile.id == selectedLayoutId,
                        isEditing = isEditing,
                        isConfirmingDelete = profile.id == confirmingDeleteLayoutId,
                        isMultiSelectMode = isMultiSelectMode,
                        multiSelected = profile.id in multiSelectedLayoutIds,
                        canStartMultiSelect = canStartMultiSelect,
                        rowHeight = rowHeight,
                        onSelect = {
                            if (isMultiSelectMode && canStartMultiSelect) {
                                onToggleMultiSelect(profile)
                            } else {
                                onSelect(profile)
                            }
                        },
                        onLongPress = { onStartMultiSelect(profile) },
                        onRename = { onRename(profile) },
                        onDeleteRequest = { onDeleteRequest(profile) },
                        onDeleteConfirm = { onDeleteConfirm(profile) },
                        modifier = Modifier.animateItem(
                            fadeInSpec = tween(
                                durationMillis = 180,
                                easing = GamepadEmphasizedDecelerateEasing
                            ),
                            placementSpec = spring(
                                dampingRatio = 0.9f,
                                stiffness = 700f
                            ),
                            fadeOutSpec = tween(
                                durationMillis = GAMEPAD_MOTION_EXIT_MS,
                                easing = GamepadEmphasizedAccelerateEasing
                            )
                        )
                    )
                }
            }
            if (showScrollHint) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(28.dp * LocalGamepadMenuControlScale.current)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    LayoutMenuSurfaceColor,
                                    Color.Transparent
                                )
                            )
                        )
                )
            }
        }
    }
}

@Composable
private fun GamepadLayoutNewButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val controlSize = gamepadMenuControlSize()
    val controlCorner = gamepadMenuControlCorner()
    Box(
        modifier = modifier
            .size(controlSize)
            .gamepadPressScale()
            .clip(controlCorner)
            .background(LayoutMenuButtonColor)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Add,
            contentDescription = "New layout",
            tint = Color.White,
            modifier = Modifier.size(gamepadMenuIconSize(14f))
        )
    }
}

@Composable
private fun GamepadMenuIconButton(
    imageVector: ImageVector,
    contentDescription: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val controlSize = gamepadMenuControlSize()
    val controlCorner = gamepadMenuControlCorner()
    val containerColor by animateColorAsState(
        targetValue = when {
            !enabled -> LayoutMenuButtonDisabledColor
            selected -> Color.White
            else -> LayoutMenuButtonColor
        },
        animationSpec = tween(
            durationMillis = GAMEPAD_MOTION_QUICK_MS,
            easing = GamepadStandardEasing
        ),
        label = "menuIconButtonContainerColor"
    )
    val iconColor by animateColorAsState(
        targetValue = when {
            !enabled -> Color.White.copy(alpha = 0.42f)
            selected -> LayoutMenuButtonSelectedColor
            else -> Color.White
        },
        animationSpec = tween(
            durationMillis = GAMEPAD_MOTION_QUICK_MS,
            easing = GamepadStandardEasing
        ),
        label = "menuIconButtonIconColor"
    )
    Box(
        modifier = modifier
            .size(controlSize)
            .gamepadPressScale(enabled = enabled)
            .clip(controlCorner)
            .background(containerColor)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = iconColor,
            modifier = Modifier.size(gamepadMenuIconSize(15f))
        )
    }
}

@Composable
private fun GamepadSelectedLayoutsDeleteButton(
    count: Int,
    isConfirmingDelete: Boolean,
    onClick: () -> Unit,
    onConfirmDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val controlSize = gamepadMenuControlSize()
    val controlCorner = gamepadMenuControlCorner()
    val controlScale = LocalGamepadMenuControlScale.current
    val isSimplifiedChineseEnabled = LocalGamepadSimplifiedChineseEnabled.current
    val buttonWidth = 92.dp * controlScale
    if (isConfirmingDelete) {
        GamepadInlineDeleteConfirmButton(
            modifier = modifier
                .width(buttonWidth)
                .height(controlSize),
            onConfirmDelete = onConfirmDelete
        )
        return
    }

    Box(
        modifier = modifier
            .width(buttonWidth)
            .height(controlSize)
            .gamepadPressScale(enabled = count > 0)
            .clip(controlCorner)
            .background(Color(0xFFD32F2F))
            .clickable(enabled = count > 0) { onClick() }
            .testTag("layout_menu_delete_selected_btn"),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp * controlScale)
        ) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = if (isSimplifiedChineseEnabled) {
                    "删除选中的布局"
                } else {
                    "Delete selected layouts"
                },
                tint = Color.White,
                modifier = Modifier.size(gamepadMenuIconSize(12f))
            )
            Text(
                text = if (isSimplifiedChineseEnabled) {
                    if (count > 1) "删除 $count" else "删除"
                } else {
                    if (count > 1) "Delete $count" else "Delete"
                },
                color = Color.White,
                fontSize = gamepadMenuTextSize(9f),
                fontWeight = FontWeight.Bold
            )
        }
    }
}

private data class GamepadLayoutTransferMenuItem(
    val text: String,
    val icon: ImageVector,
    val onClick: () -> Unit
)

@Composable
private fun GamepadLayoutTransferDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    shape: RoundedCornerShape,
    dividerHeight: Dp,
    items: List<GamepadLayoutTransferMenuItem>
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        shape = shape,
        containerColor = LayoutMenuSurfaceColor,
        tonalElevation = 0.dp,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
    ) {
        items.forEachIndexed { index, item ->
            DropdownMenuItem(
                text = {
                    Text(
                        text = item.text,
                        color = Color.White,
                        fontSize = gamepadMenuTextSize(9f),
                        fontWeight = FontWeight.Medium
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(gamepadMenuIconSize(14f))
                    )
                },
                onClick = item.onClick
            )
            if (index < items.lastIndex) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(dividerHeight)
                        .background(Color.White.copy(alpha = 0.55f))
                )
            }
        }
    }
}

@Composable
private fun GamepadLayoutTransferButton(
    isImport: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val controlSize = gamepadMenuControlSize()
    val controlCorner = gamepadMenuControlCorner()
    val isSimplifiedChineseEnabled = LocalGamepadSimplifiedChineseEnabled.current
    Box(
        modifier = modifier
            .size(controlSize)
            .gamepadPressScale()
            .clip(controlCorner)
            .background(LayoutMenuButtonColor)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (isImport) {
            Icon(
                imageVector = Icons.Default.MoveToInbox,
                contentDescription = if (isSimplifiedChineseEnabled) {
                    "导入布局"
                } else {
                    "Import layouts"
                },
                tint = Color.White,
                modifier = Modifier.size(gamepadMenuIconSize(15f))
            )
        } else {
            Icon(
                imageVector = Icons.Default.IosShare,
                contentDescription = if (isSimplifiedChineseEnabled) {
                    "导出布局"
                } else {
                    "Export layouts"
                },
                tint = Color.White,
                modifier = Modifier.size(gamepadMenuIconSize(14f))
            )
        }
    }
}

@Composable
private fun GamepadRenameLayoutPanel(
    name: String,
    onNameChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    val canSave = name.trim().isNotEmpty()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(20f)
            .background(LayoutDialogBackdropColor)
            .pointerInput(Unit) {
                detectTapGestures { }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 300.dp, max = 420.dp)
                .padding(18.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(LayoutDialogSurfaceColor)
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "Rename layout",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )

            OutlinedTextField(
                value = name,
                onValueChange = onNameChange,
                singleLine = true,
                label = { Text("Layout name") },
                modifier = Modifier.fillMaxWidth(),
                textStyle = TextStyle(
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color.White.copy(alpha = 0.62f),
                    unfocusedBorderColor = Color.White.copy(alpha = 0.32f),
                    focusedLabelColor = Color.White.copy(alpha = 0.78f),
                    unfocusedLabelColor = Color.White.copy(alpha = 0.55f),
                    cursorColor = Color.White,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent
                )
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                GamepadModalTextButton(
                    label = "Cancel",
                    onClick = onDismiss
                )
                Spacer(Modifier.width(8.dp))
                GamepadModalTextButton(
                    label = "Save",
                    enabled = canSave,
                    onClick = onSave
                )
            }
        }
    }
}

@Composable
private fun GamepadLayoutPastePanel(
    text: String,
    showError: Boolean,
    isSimplifiedChineseEnabled: Boolean,
    onTextChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onImport: () -> Unit
) {
    val canImport = text.isNotBlank()
    val panelShape = RoundedCornerShape(16.dp)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(20f)
            .background(LayoutDialogBackdropColor)
            .pointerInput(Unit) {
                detectTapGestures { }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 320.dp, max = 560.dp)
                .padding(18.dp)
                .clip(panelShape)
                .background(LayoutDialogSurfaceColor)
                .border(1.dp, Color.White.copy(alpha = 0.12f), panelShape)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = if (isSimplifiedChineseEnabled) {
                    "粘贴布局文本"
                } else {
                    "Paste layout text"
                },
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = if (isSimplifiedChineseEnabled) {
                    "请在下方文本框中粘贴完整的布局文本。"
                } else {
                    "Paste the complete layout text into the field below."
                },
                color = Color.White.copy(alpha = 0.66f),
                fontSize = 12.sp
            )
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                label = {
                    Text(if (isSimplifiedChineseEnabled) "布局文本" else "Layout text")
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 220.dp),
                minLines = 5,
                maxLines = 9,
                isError = showError,
                supportingText = if (showError) {
                    {
                        Text(
                            if (isSimplifiedChineseEnabled) {
                                "无法识别布局文本，请检查内容是否完整。"
                            } else {
                                "The layout text is invalid or incomplete."
                            }
                        )
                    }
                } else {
                    null
                },
                textStyle = TextStyle(
                    color = Color.White,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color.White.copy(alpha = 0.62f),
                    unfocusedBorderColor = Color.White.copy(alpha = 0.32f),
                    focusedLabelColor = Color.White.copy(alpha = 0.78f),
                    unfocusedLabelColor = Color.White.copy(alpha = 0.55f),
                    cursorColor = Color.White,
                    errorBorderColor = Color(0xFFFF6B6B),
                    errorLabelColor = Color(0xFFFF8A8A),
                    errorSupportingTextColor = Color(0xFFFF8A8A),
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    errorContainerColor = Color.Transparent
                )
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                GamepadModalTextButton(
                    label = if (isSimplifiedChineseEnabled) "取消" else "Cancel",
                    onClick = onDismiss
                )
                Spacer(Modifier.width(8.dp))
                GamepadModalTextButton(
                    label = if (isSimplifiedChineseEnabled) "导入" else "Import",
                    enabled = canImport,
                    onClick = onImport
                )
            }
        }
    }
}

@Composable
private fun GamepadModalTextButton(
    label: String,
    enabled: Boolean = true,
    containerColor: Color? = null,
    contentColor: Color? = null,
    onClick: () -> Unit
) {
    val resolvedContainerColor = containerColor
        ?: Color.White.copy(alpha = if (enabled) 0.16f else 0.07f)
    val resolvedContentColor = contentColor
        ?: Color.White.copy(alpha = if (enabled) 0.92f else 0.38f)
    Box(
        modifier = Modifier
            .height(32.dp)
            .gamepadPressScale(enabled = enabled)
            .clip(RoundedCornerShape(8.dp))
            .background(resolvedContainerColor)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = resolvedContentColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

private enum class GamepadGyroscopeCalibrationPhase {
    WAITING,
    COUNTDOWN,
    SUCCESS,
    ERROR
}

@Composable
private fun GamepadGyroscopeCalibrationPanel(
    sensorManager: SensorManager?,
    tiltSensor: Sensor?,
    isSimplifiedChineseEnabled: Boolean,
    onCalibrate: (FloatArray) -> Boolean,
    onDismiss: () -> Unit
) {
    var latestRotationVector by remember { mutableStateOf<FloatArray?>(null) }
    var isSensorRegistered by remember(sensorManager, tiltSensor) { mutableStateOf(false) }
    var phase by remember(sensorManager, tiltSensor) {
        mutableStateOf(
            if (sensorManager == null || tiltSensor == null) {
                GamepadGyroscopeCalibrationPhase.ERROR
            } else {
                GamepadGyroscopeCalibrationPhase.WAITING
            }
        )
    }
    val countdownProgress = remember { Animatable(0f) }
    var retryGeneration by remember { mutableIntStateOf(0) }
    val currentOnCalibrate by rememberUpdatedState(onCalibrate)
    val currentOnDismiss by rememberUpdatedState(onDismiss)

    DisposableEffect(sensorManager, tiltSensor) {
        if (sensorManager == null || tiltSensor == null) {
            onDispose { }
        } else {
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    if (event.sensor.type == tiltSensor.type) {
                        latestRotationVector = event.values.copyOf()
                    }
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            isSensorRegistered = sensorManager.registerListener(listener, tiltSensor, 10_000)
            if (!isSensorRegistered) {
                phase = GamepadGyroscopeCalibrationPhase.ERROR
            }
            onDispose {
                sensorManager.unregisterListener(listener)
            }
        }
    }

    LaunchedEffect(isSensorRegistered, retryGeneration) {
        if (!isSensorRegistered) return@LaunchedEffect
        latestRotationVector = null
        phase = GamepadGyroscopeCalibrationPhase.COUNTDOWN
        countdownProgress.snapTo(0f)
        countdownProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 3_000, easing = LinearEasing)
        )
        val calibrated = latestRotationVector?.let(currentOnCalibrate) == true
        if (calibrated) {
            phase = GamepadGyroscopeCalibrationPhase.SUCCESS
            delay(900L.milliseconds)
            currentOnDismiss()
        } else {
            phase = GamepadGyroscopeCalibrationPhase.ERROR
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(40f)
            .background(LayoutDialogBackdropColor)
            .pointerInput(Unit) { detectTapGestures { } }
            .testTag("gyroscope_calibration_panel"),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 320.dp, max = 440.dp)
                .padding(18.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(LayoutDialogSurfaceColor)
                .border(1.dp, Color(0xFF64B5F6).copy(alpha = 0.34f), RoundedCornerShape(16.dp))
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = if (isSimplifiedChineseEnabled) "赛车模式校准" else "Racing mode calibration",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = if (isSimplifiedChineseEnabled) {
                    "把手机保持在希望作为摇杆中心的位置，倒计时结束前请勿移动。"
                } else {
                    "Hold the phone at the position you want to use as stick center and keep it still."
                },
                color = Color.White.copy(alpha = 0.76f),
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )

            Box(
                modifier = Modifier.height(68.dp),
                contentAlignment = Alignment.Center
            ) {
                when (phase) {
                    GamepadGyroscopeCalibrationPhase.WAITING -> CircularProgressIndicator(
                        modifier = Modifier.size(34.dp),
                        color = Color(0xFF64B5F6),
                        strokeWidth = 3.dp
                    )
                    GamepadGyroscopeCalibrationPhase.COUNTDOWN -> Text(
                        text = ceil((1f - countdownProgress.value) * 3f)
                            .toInt()
                            .coerceIn(1, 3)
                            .toString(),
                        color = Color.White,
                        fontSize = 48.sp,
                        fontWeight = FontWeight.Bold
                    )
                    GamepadGyroscopeCalibrationPhase.SUCCESS -> Text(
                        text = "✓",
                        color = Color(0xFF66BB6A),
                        fontSize = 48.sp,
                        fontWeight = FontWeight.Bold
                    )
                    GamepadGyroscopeCalibrationPhase.ERROR -> Text(
                        text = "!",
                        color = Color(0xFFFFB74D),
                        fontSize = 48.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (phase == GamepadGyroscopeCalibrationPhase.COUNTDOWN) {
                LinearProgressIndicator(
                    progress = { countdownProgress.value },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(50))
                        .testTag("gyroscope_calibration_progress"),
                    color = Color(0xFF64B5F6),
                    trackColor = Color.White.copy(alpha = 0.14f)
                )
            }

            Text(
                text = when (phase) {
                    GamepadGyroscopeCalibrationPhase.WAITING ->
                        if (isSimplifiedChineseEnabled) "正在连接姿态传感器…" else "Connecting to motion sensor…"
                    GamepadGyroscopeCalibrationPhase.COUNTDOWN ->
                        if (isSimplifiedChineseEnabled) "正在采集中心姿态" else "Capturing center position"
                    GamepadGyroscopeCalibrationPhase.SUCCESS ->
                        if (isSimplifiedChineseEnabled) "校准完成" else "Calibration complete"
                    GamepadGyroscopeCalibrationPhase.ERROR ->
                        if (isSimplifiedChineseEnabled) {
                            "没有收到有效的姿态数据，请重试。"
                        } else {
                            "No valid motion data was received. Please try again."
                        }
                },
                color = Color.White.copy(alpha = 0.74f),
                fontSize = 11.sp,
                textAlign = TextAlign.Center
            )

            if (phase != GamepadGyroscopeCalibrationPhase.SUCCESS) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GamepadModalTextButton(
                        label = if (isSimplifiedChineseEnabled) "取消" else "Cancel",
                        onClick = onDismiss
                    )
                    if (phase == GamepadGyroscopeCalibrationPhase.ERROR && isSensorRegistered) {
                        Spacer(Modifier.width(8.dp))
                        GamepadModalTextButton(
                            label = if (isSimplifiedChineseEnabled) "重试" else "Retry",
                            containerColor = Color(0xFF456A8D),
                            onClick = { retryGeneration += 1 }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GamepadDeleteLayoutPanel(
    profile: GamepadLayoutProfile,
    onDismiss: () -> Unit,
    onDelete: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(20f)
            .background(LayoutDialogBackdropColor)
            .pointerInput(Unit) {
                detectTapGestures { }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 300.dp, max = 380.dp)
                .padding(18.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(LayoutDeleteDialogSurfaceColor)
                .border(1.dp, Color(0xFFEF5350).copy(alpha = 0.32f), RoundedCornerShape(16.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "删除布局",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "确定删除 ${profile.name}？",
                color = Color.White.copy(alpha = 0.78f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                GamepadModalTextButton(
                    label = "取消",
                    onClick = onDismiss
                )
                Spacer(Modifier.width(8.dp))
                GamepadModalTextButton(
                    label = "删除",
                    containerColor = Color(0xFFB3261E).copy(alpha = 0.82f),
                    contentColor = Color.White,
                    onClick = onDelete
                )
            }
        }
    }
}

@Composable
private fun GamepadLayoutProfileRow(
    profile: GamepadLayoutProfile,
    selected: Boolean,
    isEditing: Boolean,
    isConfirmingDelete: Boolean,
    isMultiSelectMode: Boolean,
    multiSelected: Boolean,
    canStartMultiSelect: Boolean,
    rowHeight: androidx.compose.ui.unit.Dp,
    onSelect: () -> Unit,
    onLongPress: () -> Unit,
    onRename: () -> Unit,
    onDeleteRequest: () -> Unit,
    onDeleteConfirm: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (isConfirmingDelete && !profile.isDefault) {
        GamepadInlineDeleteConfirmButton(
            modifier = modifier
                .fillMaxWidth()
                .height(rowHeight),
            onConfirmDelete = onDeleteConfirm
        )
        return
    }

    val controlScale = LocalGamepadMenuControlScale.current
    val rowShape = RoundedCornerShape(8.dp * controlScale)
    val rowHorizontalPadding = 10.dp * controlScale
    val rowHorizontalPaddingPx = with(LocalDensity.current) { rowHorizontalPadding.toPx() }
    var selectionOrigin by remember(profile.id) { mutableStateOf(Offset.Unspecified) }
    var hasObservedSelectionState by remember(profile.id) { mutableStateOf(false) }
    val selectionReveal = remember(profile.id) { Animatable(if (selected) 1f else 0f) }
    val selectionAlpha = remember(profile.id) { Animatable(if (selected) 1f else 0f) }
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentOnLongPress by rememberUpdatedState(onLongPress)

    LaunchedEffect(selected) {
        if (!hasObservedSelectionState) {
            hasObservedSelectionState = true
            return@LaunchedEffect
        }
        if (selected) {
            selectionAlpha.snapTo(1f)
            selectionReveal.snapTo(0f)
            selectionReveal.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = 300,
                    easing = GamepadEmphasizedDecelerateEasing
                )
            )
        } else {
            selectionReveal.snapTo(1f)
            selectionAlpha.animateTo(
                targetValue = 0f,
                animationSpec = tween(
                    durationMillis = 220,
                    easing = GamepadStandardEasing
                )
            )
            selectionReveal.snapTo(0f)
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(rowHeight)
            .clip(rowShape)
            .background(LayoutMenuLayoutRowColor)
            .then(
                if (multiSelected) {
                    Modifier.border(1.dp, Color(0xFF42A5F5), rowShape)
                } else {
                    Modifier
                }
            )
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            if (selectionAlpha.value <= 0f || selectionReveal.value <= 0f) return@Canvas
            val origin = if (selectionOrigin.x.isFinite() && selectionOrigin.y.isFinite()) {
                Offset(
                    x = selectionOrigin.x.coerceIn(0f, size.width),
                    y = selectionOrigin.y.coerceIn(0f, size.height)
                )
            } else {
                center
            }
            val leftTopRadius = sqrt(origin.x * origin.x + origin.y * origin.y)
            val rightTopRadius = sqrt(
                (size.width - origin.x) * (size.width - origin.x) + origin.y * origin.y
            )
            val leftBottomRadius = sqrt(
                origin.x * origin.x + (size.height - origin.y) * (size.height - origin.y)
            )
            val rightBottomRadius = sqrt(
                (size.width - origin.x) * (size.width - origin.x) +
                    (size.height - origin.y) * (size.height - origin.y)
            )
            drawCircle(
                color = LayoutMenuLayoutRowSelectedColor.copy(alpha = selectionAlpha.value),
                radius = maxOf(
                    leftTopRadius,
                    rightTopRadius,
                    leftBottomRadius,
                    rightBottomRadius
                ) * selectionReveal.value,
                center = origin
            )
        }

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = rowHorizontalPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp * controlScale)
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .pointerInput(profile.id, profile.isDefault, isMultiSelectMode, canStartMultiSelect) {
                        detectTapGestures(
                            onTap = { tapOffset ->
                                selectionOrigin = Offset(
                                    x = tapOffset.x + rowHorizontalPaddingPx,
                                    y = tapOffset.y
                                )
                                currentOnSelect()
                            },
                            onLongPress = {
                                if (!profile.isDefault && canStartMultiSelect) {
                                    currentOnLongPress()
                                }
                            }
                        )
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp * controlScale)
            ) {
                GamepadLayoutSelectedCheck(
                    selected = selected,
                    size = 13.dp * controlScale
                )
                Text(
                    text = profile.name,
                    color = Color.White,
                    fontSize = gamepadMenuTextSize(11f),
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (profile.isDefault) {
                    Text(
                        text = "LOCKED",
                        color = Color.White.copy(alpha = 0.45f),
                        fontSize = gamepadMenuTextSize(8f),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            if (!profile.isDefault && isEditing && !isMultiSelectMode) {
                GamepadLayoutActionButton(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "Rename layout",
                    onClick = onRename
                )
                GamepadLayoutActionButton(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete layout",
                    tint = Color(0xFFFF8A80),
                    onClick = onDeleteRequest
                )
            }
        }
    }
}

@Composable
private fun GamepadLayoutSelectedCheck(selected: Boolean, size: Dp) {
    Box(
        modifier = Modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        AnimatedVisibility(
            visible = selected,
            enter = fadeIn(
                tween(durationMillis = 190, easing = GamepadEmphasizedDecelerateEasing)
            ) + scaleIn(
                animationSpec = tween(
                    durationMillis = 190,
                    easing = GamepadEmphasizedDecelerateEasing
                ),
                initialScale = 0.72f
            ),
            exit = fadeOut(
                tween(durationMillis = 140, easing = GamepadEmphasizedAccelerateEasing)
            ) + scaleOut(
                animationSpec = tween(
                    durationMillis = 140,
                    easing = GamepadEmphasizedAccelerateEasing
                ),
                targetScale = 0.96f
            )
        ) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Selected",
                tint = Color.White,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun GamepadInlineDeleteConfirmButton(
    modifier: Modifier = Modifier
        .width(58.dp)
        .height(26.dp),
    onConfirmDelete: () -> Unit
) {
    val progress = remember { Animatable(0f) }
    val animationScope = rememberCoroutineScope()
    val currentOnConfirmDelete by rememberUpdatedState(onConfirmDelete)
    val controlScale = LocalGamepadMenuControlScale.current
    val isSimplifiedChineseEnabled = LocalGamepadSimplifiedChineseEnabled.current
    val shape = RoundedCornerShape(8.dp * controlScale)

    Box(
        modifier = modifier
            .gamepadPressScale()
            .clip(shape)
            .background(Color.White)
            .border(1.dp, Color(0xFFD32F2F), shape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val holdJob = animationScope.launch {
                        progress.snapTo(0f)
                        progress.animateTo(
                            targetValue = 1f,
                            animationSpec = tween(
                                durationMillis = 1000,
                                easing = LinearEasing
                            )
                        )
                        currentOnConfirmDelete()
                    }
                    waitForUpOrCancellation()
                    if (holdJob.isActive) {
                        holdJob.cancel()
                        animationScope.launch {
                            progress.animateTo(
                                targetValue = 0f,
                                animationSpec = tween(durationMillis = 140)
                            )
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .fillMaxWidth(progress.value.coerceIn(0f, 1f))
                .background(Color(0xFF7F0000).copy(alpha = 0.28f))
        )
        Text(
            text = if (isSimplifiedChineseEnabled) "长按删除" else "Hold to delete",
            color = Color(0xFFD32F2F),
            fontSize = gamepadMenuTextSize(9f),
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

@Composable
private fun GamepadLayoutActionButton(
    imageVector: ImageVector,
    contentDescription: String,
    tint: Color = Color.White,
    onClick: () -> Unit
) {
    val controlScale = LocalGamepadMenuControlScale.current
    Box(
        modifier = Modifier
            .size(26.dp * controlScale)
            .gamepadPressScale()
            .clip(RoundedCornerShape(7.dp * controlScale))
            .background(LayoutMenuButtonColor)
            .pointerInput(onClick) {
                detectTapGestures { onClick() }
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(13.dp * controlScale)
        )
    }
}

internal fun gamepadShoulderControlTopLeft(
    pairWidthPx: Int,
    controlWidthPx: Int,
    controlHeightPx: Int,
    density: Float,
    isTrigger: Boolean
): IntOffset {
    if (
        pairWidthPx <= 0 || controlWidthPx < 0 || controlHeightPx < 0 ||
        density <= 0f
    ) {
        return IntOffset.Zero
    }
    val verticalCenterDp = if (isTrigger) {
        TRIGGER_BUTTON_BASE_HEIGHT_DP / 2f
    } else {
        TRIGGER_BUTTON_BASE_HEIGHT_DP + SHOULDER_BUTTON_VERTICAL_SPACING_DP +
            BUMPER_BUTTON_BASE_HEIGHT_DP / 2f
    }
    return IntOffset(
        x = (pairWidthPx / 2f - controlWidthPx / 2f).roundToInt(),
        y = (verticalCenterDp * density - controlHeightPx / 2f).roundToInt()
    )
}

@Composable
private fun GamepadShoulderPair(
    content: @Composable () -> Unit
) {
    Layout(
        content = content
    ) { measurables, constraints ->
        check(measurables.size == 2) { "A shoulder pair must contain trigger and bumper" }
        val childConstraints = constraints.copy(
            minWidth = 0,
            maxWidth = Constraints.Infinity,
            minHeight = 0,
            maxHeight = Constraints.Infinity
        )
        val placeables = measurables.map { measurable ->
            measurable.measure(childConstraints)
        }
        val pairWidthPx = (SHOULDER_BUTTON_BASE_WIDTH_DP * density)
            .roundToInt()
            .coerceIn(constraints.minWidth, constraints.maxWidth)
        val pairHeightPx = (SHOULDER_STACK_LAYOUT_HEIGHT_DP * density)
            .roundToInt()
            .coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(pairWidthPx, pairHeightPx) {
            placeables.forEachIndexed { index, placeable ->
                val topLeft = gamepadShoulderControlTopLeft(
                    pairWidthPx = pairWidthPx,
                    controlWidthPx = placeable.width,
                    controlHeightPx = placeable.height,
                    density = density,
                    isTrigger = index == 0
                )
                placeable.place(topLeft.x, topLeft.y)
            }
        }
    }
}

@Composable
private fun GamepadBumperButton(
    button: ButtonDef,
    isLeft: Boolean,
    onPress: (Int) -> Unit,
    onRelease: (Int) -> Unit,
    widthScale: Float = 1f,
    heightScale: Float = 1f
) {
    val touchAssistTargetId = remember { Any() }
    var directlyPressed by remember { mutableStateOf(false) }
    val isPressed = directlyPressed ||
        (LocalGamepadTouchAssistController.current?.isAssistedPressed(touchAssistTargetId) == true)
    val pressOffsetY by animateDpAsState(
        targetValue = if (isPressed) (-0.6).dp else 0.dp,
        animationSpec = gamepadPressSpring(isPressed),
        label = "bumperPress"
    )
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) GAMEPAD_CONTROL_PRESSED_SCALE else 1.0f,
        animationSpec = gamepadPressSpring(isPressed),
        label = "bumperScale"
    )

    val outerShape = if (isLeft) {
        RoundedCornerShape(topStart = 14.dp, topEnd = 6.dp, bottomStart = 6.dp, bottomEnd = 10.dp)
    } else {
        RoundedCornerShape(topStart = 6.dp, topEnd = 14.dp, bottomStart = 10.dp, bottomEnd = 6.dp)
    }
    
    val innerShape = if (isLeft) {
        RoundedCornerShape(topStart = 12.dp, topEnd = 4.dp, bottomStart = 4.dp, bottomEnd = 8.dp)
    } else {
        RoundedCornerShape(topStart = 4.dp, topEnd = 12.dp, bottomStart = 8.dp, bottomEnd = 4.dp)
    }

    // Outer well container
    Box(
        modifier = Modifier
            .width((SHOULDER_BUTTON_BASE_WIDTH_DP * widthScale).dp)
            .height((BUMPER_BUTTON_BASE_HEIGHT_DP * heightScale).dp)
            .gamepadTouchAssistTarget(button.mappingId, touchAssistTargetId)
            .background(Color(0xFF1B1B1C), outerShape)
            .border(1.2.dp, Color.Black.copy(alpha = 0.5f), outerShape),
        contentAlignment = Alignment.Center
    ) {
        // Inner button cap
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(2.dp) // Well padding
                .offset(y = pressOffsetY)
                .graphicsLayer {
                    scaleX = pressScale
                    scaleY = pressScale
                }
                .clip(innerShape)
                .background(
                    Brush.verticalGradient(
                        colors = if (isPressed) {
                            listOf(Color(0xFF202022), Color(0xFF19191B))
                        } else {
                            listOf(Color(0xFF38383B), Color(0xFF2D2D30))
                        }
                    )
                )
                .border(
                    0.8.dp,
                    Brush.verticalGradient(
                        colors = listOf(Color.White.copy(alpha = 0.15f), Color.Transparent)
                    ),
                    innerShape
                )
                .gamepadButtonTouch(
                    onPress = { onPress(button.mappingId) },
                    onRelease = { onRelease(button.mappingId) },
                    onPressedStateChange = { directlyPressed = it }
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = button.label,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.SansSerif
            )
        }
    }
}

@Composable
private fun GamepadTriggerButton(
    button: ButtonDef,
    isLeft: Boolean,
    onPress: (Int) -> Unit,
    onRelease: (Int) -> Unit,
    widthScale: Float = 1f,
    heightScale: Float = 1f
) {
    val touchAssistTargetId = remember { Any() }
    var directlyPressed by remember { mutableStateOf(false) }
    val isPressed = directlyPressed ||
        (LocalGamepadTouchAssistController.current?.isAssistedPressed(touchAssistTargetId) == true)
    val pressOffsetY by animateDpAsState(
        targetValue = if (isPressed) 0.6.dp else 0.dp,
        animationSpec = gamepadPressSpring(isPressed),
        label = "triggerPress"
    )
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) GAMEPAD_CONTROL_PRESSED_SCALE else 1.0f,
        animationSpec = gamepadPressSpring(isPressed),
        label = "triggerScale"
    )

    val outerShape = if (isLeft) {
        RoundedCornerShape(topStart = 10.dp, topEnd = 6.dp, bottomStart = 14.dp, bottomEnd = 8.dp)
    } else {
        RoundedCornerShape(topStart = 6.dp, topEnd = 10.dp, bottomStart = 8.dp, bottomEnd = 14.dp)
    }

    val innerShape = if (isLeft) {
        RoundedCornerShape(topStart = 8.dp, topEnd = 4.dp, bottomStart = 12.dp, bottomEnd = 6.dp)
    } else {
        RoundedCornerShape(topStart = 4.dp, topEnd = 8.dp, bottomStart = 6.dp, bottomEnd = 12.dp)
    }

    // Outer well container
    Box(
        modifier = Modifier
            .width((SHOULDER_BUTTON_BASE_WIDTH_DP * widthScale).dp)
            .height((TRIGGER_BUTTON_BASE_HEIGHT_DP * heightScale).dp)
            .gamepadTouchAssistTarget(button.mappingId, touchAssistTargetId)
            .background(Color(0xFF1B1B1C), outerShape)
            .border(1.2.dp, Color.Black.copy(alpha = 0.5f), outerShape),
        contentAlignment = Alignment.Center
    ) {
        // Inner button cap
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(2.dp) // Well padding
                .offset(y = pressOffsetY)
                .graphicsLayer {
                    scaleX = pressScale
                    scaleY = pressScale
                }
                .clip(innerShape)
                .background(
                    Brush.verticalGradient(
                        colors = if (isPressed) {
                            listOf(Color(0xFF202022), Color(0xFF19191B))
                        } else {
                            listOf(Color(0xFF38383B), Color(0xFF2D2D30))
                        }
                    )
                )
                .border(
                    0.8.dp,
                    Brush.verticalGradient(
                        colors = listOf(Color.White.copy(alpha = 0.15f), Color.Transparent)
                    ),
                    innerShape
                )
                .gamepadButtonTouch(
                    onPress = { onPress(button.mappingId) },
                    onRelease = { onRelease(button.mappingId) },
                    onPressedStateChange = { directlyPressed = it }
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = button.label,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.SansSerif
            )
        }
    }
}

private fun Color.gpDarker(factor: Float = 0.22f): Color {
    return Color(
        red = (this.red * (1f - factor)).coerceIn(0f, 1f),
        green = (this.green * (1f - factor)).coerceIn(0f, 1f),
        blue = (this.blue * (1f - factor)).coerceIn(0f, 1f),
        alpha = this.alpha
    )
}

private fun Color.gpLighter(factor: Float = 0.25f): Color {
    return Color(
        red = (this.red + (1f - this.red) * factor).coerceIn(0f, 1f),
        green = (this.green + (1f - this.green) * factor).coerceIn(0f, 1f),
        blue = (this.blue + (1f - this.blue) * factor).coerceIn(0f, 1f),
        alpha = this.alpha
    )
}

@Composable
private fun GamepadFaceButton(
    button: ButtonDef,
    isXboxStyle: Boolean,
    onPress: (Int) -> Unit,
    onRelease: (Int) -> Unit,
    modifier: Modifier = Modifier,
    externalIsPressed: Boolean = false
) {
    val touchAssistTargetId = remember { Any() }
    var internalIsPressed by remember { mutableStateOf(false) }
    val isPressed = internalIsPressed || externalIsPressed ||
        (LocalGamepadTouchAssistController.current?.isAssistedPressed(touchAssistTargetId) == true)

    val pressOffsetY by animateDpAsState(
        targetValue = if (isPressed) 0.6.dp else 0.dp,
        animationSpec = gamepadPressSpring(isPressed),
        label = "pressOffset"
    )
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) GAMEPAD_CONTROL_PRESSED_SCALE else 1.0f,
        animationSpec = gamepadPressSpring(isPressed),
        label = "pressScale"
    )

    // Base color of the disk itself (dark gray, themed to the background #28282A)
    val baseColor = Color(0xFF333336)
    
    // Label/Symbol color (A, B, X, Y or △, ◯, ✕, ☐)
    val labelColor = button.color

    // Gap clearance reduced to 1.5.dp (well size 48.dp, cap size 45.dp)
    Box(
        modifier = modifier
            .size(48.dp)
            .gamepadTouchAssistTarget(button.mappingId, touchAssistTargetId),
        contentAlignment = Alignment.Center
    ) {
        // 1. Button Well (physical hole in casing with shadow)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(Color(0xFF1B1B1C))
                .border(0.8.dp, Color.Black.copy(alpha = 0.25f), CircleShape)
        )

        // 2. Button Cap (presses down into the well)
        Box(
            modifier = Modifier
                .size(45.dp)
                .offset(y = pressOffsetY)
                .graphicsLayer {
                    scaleX = pressScale
                    scaleY = pressScale
                }
                .clip(CircleShape)
                .gamepadButtonTouch(
                    onPress = { onPress(button.mappingId) },
                    onRelease = { onRelease(button.mappingId) },
                    onPressedStateChange = { internalIsPressed = it }
                ),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val r = size.width / 2f

                val maxOffset = 0.6.dp.toPx()
                val edgeOffset = if (isPressed) 0.3f.dp.toPx() else 1.8f.dp.toPx()
                val faceOffset = if (isPressed) 0f else maxOffset

                // Calculate radii to fit perfectly within the clip boundaries
                val edgeR = r - edgeOffset / 2f - 0.5.dp.toPx()
                val faceR = r - maxOffset - 0.5.dp.toPx()

                // Draw 3D side edge of the disk (darker than the face)
                drawCircle(
                    color = Color(0xFF1F1F21),
                    radius = edgeR,
                    center = Offset(r, r + edgeOffset / 2f)
                )

                // Top face of the disk (themed to the background, soft vertical gradient)
                val faceColor = if (isPressed) Color(0xFF232325) else baseColor
                drawCircle(
                    brush = Brush.verticalGradient(
                        colors = listOf(faceColor.gpLighter(0.08f), faceColor.gpDarker(0.12f))
                    ),
                    radius = faceR,
                    center = Offset(r, r - faceOffset)
                )

                // Flat face bevel edge highlight
                drawCircle(
                    color = Color.White.copy(alpha = if (isPressed) 0.05f else 0.15f),
                    radius = faceR - 0.5.dp.toPx(),
                    center = Offset(r, r - faceOffset),
                    style = Stroke(width = 0.8.dp.toPx())
                )

                // Bezel shadow
                drawCircle(
                    color = Color.Black.copy(alpha = if (isPressed) 0.10f else 0.25f),
                    radius = faceR,
                    center = Offset(r, r - faceOffset),
                    style = Stroke(width = 0.8.dp.toPx())
                )
            }

            // Offset the content slightly to match the unpressed/pressed 3D displacement
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .offset(y = if (isPressed) 0.dp else (-0.6).dp),
                contentAlignment = Alignment.Center
            ) {
                if (isXboxStyle) {
                    Text(
                        text = button.label,
                        color = labelColor,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.SansSerif,
                        textAlign = TextAlign.Center,
                        style = androidx.compose.ui.text.TextStyle(
                            platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)
                        )
                    )
                } else {
                    Canvas(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val strokeW = 2.8.dp.toPx()
                        val radius = 6.5.dp.toPx()
                        
                        when (button.label) {
                            "△" -> {
                                val path = androidx.compose.ui.graphics.Path().apply {
                                    moveTo(cx, cy - radius)
                                    lineTo(cx + radius, cy + radius * 0.5f)
                                    lineTo(cx - radius, cy + radius * 0.5f)
                                    close()
                                }
                                drawPath(path, labelColor, style = Stroke(width = strokeW))
                            }
                            "◯" -> {
                                drawCircle(labelColor, radius = radius, center = Offset(cx, cy), style = Stroke(width = strokeW))
                            }
                            "✕" -> {
                                drawLine(labelColor, start = Offset(cx - radius, cy - radius), end = Offset(cx + radius, cy + radius), strokeWidth = strokeW)
                                drawLine(labelColor, start = Offset(cx + radius, cy - radius), end = Offset(cx - radius, cy + radius), strokeWidth = strokeW)
                            }
                            "☐" -> {
                                val side = radius * 1.5f
                                drawRect(
                                    labelColor,
                                    topLeft = Offset(cx - side/2f, cy - side/2f),
                                    size = androidx.compose.ui.geometry.Size(side, side),
                                    style = Stroke(width = strokeW)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FaceButtonsDiamond(
    config: ConsoleConfig,
    @Suppress("UNUSED_PARAMETER") isXboxStyle: Boolean,
    onPress: (Int) -> Unit,
    onRelease: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = 40.dp
    val density = LocalDensity.current.density
    val touchAssistEnabled = LocalGamepadTouchAssistController.current?.enabled == true

    // Track active pressed button mapping IDs via proximity multi-touch
    val activePressedButtons = remember { mutableStateListOf<Int>() }

    val updateProximityPresses = { pointerPositions: List<Offset>, containerSizePx: Float ->
        val centerPx = containerSizePx / 2f
        val spacingPx = 40f * density
        val touchRadiusPx = 35f * density // Proximity radius covering multi-button thumb presses

        val topCenter = Offset(centerPx, centerPx - spacingPx)
        val rightCenter = Offset(centerPx + spacingPx, centerPx)
        val bottomCenter = Offset(centerPx, centerPx + spacingPx)
        val leftCenter = Offset(centerPx - spacingPx, centerPx)

        val buttonCenters = listOf(
            config.faceTop.mappingId to topCenter,
            config.faceRight.mappingId to rightCenter,
            config.faceBottom.mappingId to bottomCenter,
            config.faceLeft.mappingId to leftCenter
        )

        val newlyActive = mutableSetOf<Int>()
        for (pos in pointerPositions) {
            for ((mappingId, btnCenter) in buttonCenters) {
                val dx = pos.x - btnCenter.x
                val dy = pos.y - btnCenter.y
                val dist = sqrt(dx * dx + dy * dy)
                if (dist <= touchRadiusPx) {
                    newlyActive.add(mappingId)
                }
            }
        }

        // Press newly touched buttons
        for (mappingId in newlyActive) {
            if (!activePressedButtons.contains(mappingId)) {
                activePressedButtons.add(mappingId)
                onPress(mappingId)
            }
        }

        // Release buttons no longer under any active finger
        val toRemove = mutableListOf<Int>()
        for (mappingId in activePressedButtons) {
            if (!newlyActive.contains(mappingId)) {
                toRemove.add(mappingId)
                onRelease(mappingId)
            }
        }
        activePressedButtons.removeAll(toRemove.toSet())
    }

    Box(
        modifier = modifier
            .size(PRIMARY_GAMEPAD_CONTROL_SIZE_DP.dp)
            .then(
                if (touchAssistEnabled) {
                    Modifier
                } else {
                    Modifier.pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val activePositions = mutableMapOf<PointerId, Offset>()
                            activePositions[down.id] = down.position
                            updateProximityPresses(activePositions.values.toList(), size.width.toFloat())

                            while (true) {
                                val event = awaitPointerEvent()
                                for (change in event.changes) {
                                    if (change.pressed) {
                                        activePositions[change.id] = change.position
                                    } else {
                                        activePositions.remove(change.id)
                                    }
                                }
                                if (activePositions.isEmpty()) {
                                    for (id in activePressedButtons.toList()) {
                                        onRelease(id)
                                    }
                                    activePressedButtons.clear()
                                    break
                                }
                                updateProximityPresses(activePositions.values.toList(), size.width.toFloat())
                            }
                        }
                    }
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        GamepadFaceButton(config.faceTop, isXboxStyle, onPress, onRelease, Modifier.offset(y = -spacing), externalIsPressed = activePressedButtons.contains(config.faceTop.mappingId))
        GamepadFaceButton(config.faceRight, isXboxStyle, onPress, onRelease, Modifier.offset(x = spacing), externalIsPressed = activePressedButtons.contains(config.faceRight.mappingId))
        GamepadFaceButton(config.faceBottom, isXboxStyle, onPress, onRelease, Modifier.offset(y = spacing), externalIsPressed = activePressedButtons.contains(config.faceBottom.mappingId))
        GamepadFaceButton(config.faceLeft, isXboxStyle, onPress, onRelease, Modifier.offset(x = -spacing), externalIsPressed = activePressedButtons.contains(config.faceLeft.mappingId))
    }
}

@Composable
private fun GamepadGyroscopeToggleButton(
    isAvailable: Boolean,
    isEnabled: Boolean,
    isEditMode: Boolean,
    onToggle: () -> Boolean,
    onPressHaptic: () -> Unit
) {
    val touchAssistTargetId = remember { Any() }
    var directlyPressed by remember { mutableStateOf(false) }
    val isPressed = directlyPressed ||
        (LocalGamepadTouchAssistController.current?.isAssistedPressed(touchAssistTargetId) == true)
    val pressOffsetY by animateDpAsState(
        targetValue = when {
            isPressed -> 1.dp
            isEnabled -> 0.6.dp
            else -> 0.dp
        },
        animationSpec = gamepadPressSpring(isPressed),
        label = "gyroscopeTogglePress"
    )
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) GAMEPAD_CONTROL_PRESSED_SCALE else 1f,
        animationSpec = gamepadPressSpring(isPressed),
        label = "gyroscopeToggleScale"
    )
    val interactionEnabled = isAvailable && !isEditMode

    Box(
        modifier = Modifier
            .size(48.dp)
            .then(
                if (interactionEnabled) {
                    Modifier.gamepadTouchAssistActionTarget(touchAssistTargetId) {
                        onToggle()
                    }
                } else {
                    Modifier
                }
            )
            .clip(CircleShape)
            .background(Color(0xFF1B1B1C))
            .border(
                width = 1.dp,
                color = Color.Black.copy(alpha = 0.35f),
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .offset(y = pressOffsetY)
                .graphicsLayer {
                    scaleX = pressScale
                    scaleY = pressScale
                }
                .clip(CircleShape)
                .background(
                    Brush.verticalGradient(
                        colors = when {
                            !isAvailable -> listOf(Color(0xFF303033), Color(0xFF252527))
                            isEnabled -> listOf(Color(0xFF3A3A3E), Color(0xFF29292C))
                            else -> listOf(Color(0xFF3B3B3E), Color(0xFF2C2C2F))
                        }
                    )
                )
                .border(
                    width = 0.8.dp,
                    color = Color.White.copy(alpha = if (isPressed) 0.06f else 0.14f),
                    shape = CircleShape
                )
                .then(
                    if (interactionEnabled) {
                        Modifier.gamepadButtonTouch(
                            onPress = {
                                if (onToggle()) onPressHaptic()
                            },
                            onRelease = {},
                            onPressedStateChange = { directlyPressed = it }
                        )
                    } else {
                        Modifier
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier.size(31.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isEnabled) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawCircle(
                            color = Color.White.copy(alpha = 0.92f),
                            radius = size.minDimension / 2f - 0.8.dp.toPx(),
                            style = Stroke(width = 0.55.dp.toPx())
                        )
                    }
                }
                Icon(
                    imageVector = Icons.Default._3dRotation,
                    contentDescription = null,
                    tint = if (isAvailable) {
                        Color.White.copy(alpha = 0.8f)
                    } else {
                        Color.White.copy(alpha = 0.28f)
                    },
                    modifier = Modifier.size(21.dp)
                )
            }
        }
    }
}

@Composable
private fun GamepadCenterButton(
    button: ButtonDef,
    onPress: (Int) -> Unit,
    onRelease: (Int) -> Unit
) {
    val touchAssistTargetId = remember { Any() }
    var directlyPressed by remember { mutableStateOf(false) }
    val isPressed = directlyPressed ||
        (LocalGamepadTouchAssistController.current?.isAssistedPressed(touchAssistTargetId) == true)
    val pressOffsetY by animateDpAsState(
        targetValue = if (isPressed) 0.6.dp else 0.dp,
        animationSpec = gamepadPressSpring(isPressed),
        label = "centerBtnPress"
    )
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) GAMEPAD_CONTROL_PRESSED_SCALE else 1.0f,
        animationSpec = gamepadPressSpring(isPressed),
        label = "centerBtnScale"
    )

    // Well size 32.dp, cap size 29.dp (1.5.dp gap)
    Box(
        modifier = Modifier
            .size(32.dp)
            .gamepadTouchAssistTarget(button.mappingId, touchAssistTargetId),
        contentAlignment = Alignment.Center
    ) {
        // Button well
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(Color(0xFF1B1B1C))
                .border(0.8.dp, Color.Black.copy(alpha = 0.25f), CircleShape)
        )

        // Button Cap (Flat Disk)
        Box(
            modifier = Modifier
                .size(29.dp)
                .offset(y = pressOffsetY)
                .graphicsLayer {
                    scaleX = pressScale
                    scaleY = pressScale
                }
                .clip(CircleShape)
                .gamepadButtonTouch(
                    onPress = { onPress(button.mappingId) },
                    onRelease = { onRelease(button.mappingId) },
                    onPressedStateChange = { directlyPressed = it }
                ),
            contentAlignment = Alignment.Center
        ) {
            val baseColor = Color(0xFF333336)
            Canvas(modifier = Modifier.fillMaxSize()) {
                val r = size.width / 2f

                val maxOffset = 0.6.dp.toPx()
                val edgeOffset = if (isPressed) 0.3f.dp.toPx() else 1.5f.dp.toPx()
                val faceOffset = if (isPressed) 0f else maxOffset

                // Calculate radii to fit perfectly within the clip boundaries
                val edgeR = r - edgeOffset / 2f - 0.5.dp.toPx()
                val faceR = r - maxOffset - 0.5.dp.toPx()

                // 3D side edge
                drawCircle(
                    color = Color(0xFF1F1F21),
                    radius = edgeR,
                    center = Offset(r, r + edgeOffset / 2f)
                )

                // Top face
                val faceColor = if (isPressed) Color(0xFF232325) else baseColor
                drawCircle(
                    brush = Brush.verticalGradient(
                        colors = listOf(faceColor.gpLighter(0.08f), faceColor.gpDarker(0.12f))
                    ),
                    radius = faceR,
                    center = Offset(r, r - faceOffset)
                )

                // Bevel highlight (vertical gradient brush for better shading)
                drawCircle(
                    brush = Brush.verticalGradient(
                        colors = if (isPressed) {
                            listOf(Color.White.copy(alpha = 0.05f), Color.Transparent)
                        } else {
                            listOf(Color.White.copy(alpha = 0.15f), Color.Transparent)
                        }
                    ),
                    radius = faceR - 0.5.dp.toPx(),
                    center = Offset(r, r - faceOffset),
                    style = Stroke(width = 0.8.dp.toPx())
                )
            }

            // Offset the icon content to match the 3D displacement
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .offset(y = if (isPressed) 0.dp else (-0.6).dp),
                contentAlignment = Alignment.Center
            ) {
                if (button.label == "VIEW" || button.label == "CREATE") {
                    Canvas(modifier = Modifier.size(11.dp)) {
                        val w = size.width
                        val h = size.height
                        val strokeW = 1.5.dp.toPx()
                        
                        // Back window
                        drawRect(
                            color = Color.White.copy(alpha = 0.75f),
                            topLeft = Offset(w * 0.25f, 0f),
                            size = androidx.compose.ui.geometry.Size(w * 0.75f, h * 0.75f),
                            style = Stroke(strokeW)
                        )
                        // Front window
                        drawRect(
                            color = Color.White.copy(alpha = 0.75f),
                            topLeft = Offset(0f, h * 0.25f),
                            size = androidx.compose.ui.geometry.Size(w * 0.75f, h * 0.75f),
                            style = Stroke(strokeW)
                        )
                    }
                } else if (button.label == "SHARE") {
                    Icon(
                        imageVector = Icons.Default.IosShare,
                        contentDescription = "Share",
                        tint = Color.White.copy(alpha = 0.75f),
                        modifier = Modifier.size(11.dp)
                    )
                } else {
                    Column(
                        modifier = Modifier.size(10.dp),
                        verticalArrangement = Arrangement.SpaceBetween,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        repeat(3) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(1.6.dp)
                                    .background(Color.White.copy(alpha = 0.75f))
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GamepadShareButton(
    onPress: (Int) -> Unit,
    onRelease: (Int) -> Unit
) {
    var directlyPressed by remember { mutableStateOf(false) }
    val isPressed = directlyPressed
    val pressOffsetY by animateDpAsState(
        targetValue = if (isPressed) 0.6.dp else 0.dp,
        animationSpec = gamepadPressSpring(isPressed),
        label = "shareBtnPress"
    )
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) GAMEPAD_CONTROL_PRESSED_SCALE else 1.0f,
        animationSpec = gamepadPressSpring(isPressed),
        label = "shareBtnScale"
    )

    Box(
        modifier = Modifier
            .width(38.dp)
            .height(28.dp),
        contentAlignment = Alignment.Center
    ) {
        val outerShape = RoundedCornerShape(8.dp)
        val innerShape = RoundedCornerShape(6.dp)
        
        // Button well
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF1B1B1C), outerShape)
                .border(1.2.dp, Color.Black.copy(alpha = 0.5f), outerShape)
        )

        // Button Cap (Flat Disk 3D) - Gap clearance reduced to 1.5.dp
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(1.5.dp) // Gap clearance
                .offset(y = pressOffsetY)
                .graphicsLayer {
                    scaleX = pressScale
                    scaleY = pressScale
                }
                .clip(innerShape)
                .gamepadButtonTouch(
                    onPress = { onPress(18) },
                    onRelease = { onRelease(18) },
                    onPressedStateChange = { directlyPressed = it }
                ),
            contentAlignment = Alignment.Center
        ) {
            val baseColor = Color(0xFF333336)
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                
                val maxOffset = 0.6.dp.toPx()
                val edgeOffset = if (isPressed) 0.3f.dp.toPx() else 1.5f.dp.toPx()
                val faceOffset = if (isPressed) maxOffset else 0f

                // 3D side edge
                drawRoundRect(
                    color = Color(0xFF1F1F21),
                    topLeft = Offset(0f, edgeOffset),
                    size = androidx.compose.ui.geometry.Size(w, h - edgeOffset),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx(), 6.dp.toPx())
                )

                // Top face
                val faceColor = if (isPressed) Color(0xFF232325) else baseColor
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(faceColor.gpLighter(0.08f), faceColor.gpDarker(0.12f))
                    ),
                    topLeft = Offset(0f, faceOffset),
                    size = androidx.compose.ui.geometry.Size(w, h - maxOffset),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx(), 6.dp.toPx())
                )

                // Bevel highlight
                drawRoundRect(
                    color = Color.White.copy(alpha = if (isPressed) 0.05f else 0.15f),
                    topLeft = Offset(0.5.dp.toPx(), faceOffset + 0.5.dp.toPx()),
                    size = androidx.compose.ui.geometry.Size(w - 1.dp.toPx(), h - maxOffset - 1.dp.toPx()),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(5.5.dp.toPx(), 5.5.dp.toPx()),
                    style = Stroke(width = 0.8.dp.toPx())
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .offset(y = if (isPressed) 0.6.dp else 0.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.IosShare,
                    contentDescription = "Share",
                    tint = Color.White.copy(alpha = 0.75f),
                    modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}

@Composable
private fun XboxLogoGuideButton(
    button: ButtonDef,
    onPress: (Int) -> Unit,
    onRelease: (Int) -> Unit
) {
    val touchAssistTargetId = remember { Any() }
    var directlyPressed by remember { mutableStateOf(false) }
    val isPressed = directlyPressed ||
        (LocalGamepadTouchAssistController.current?.isAssistedPressed(touchAssistTargetId) == true)
    val pressOffsetY by animateDpAsState(
        targetValue = if (isPressed) 0.6.dp else 0.dp,
        animationSpec = gamepadPressSpring(isPressed),
        label = "xboxLogoPress"
    )
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) GAMEPAD_CONTROL_PRESSED_SCALE else 1.0f,
        animationSpec = gamepadPressSpring(isPressed),
        label = "xboxLogoScale"
    )

    // Well size 50.dp, cap size 47.dp (1.5.dp gap)
    Box(
        modifier = Modifier
            .size(50.dp)
            .gamepadTouchAssistTarget(button.mappingId, touchAssistTargetId),
        contentAlignment = Alignment.Center
    ) {
        // 1. Button well
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(Color(0xFF1B1B1C))
                .border(0.8.dp, Color.Black.copy(alpha = 0.25f), CircleShape)
        )

        // 2. Button Cap (Flat Disk)
        Box(
            modifier = Modifier
                .size(47.dp)
                .offset(y = pressOffsetY)
                .graphicsLayer {
                    scaleX = pressScale
                    scaleY = pressScale
                }
                .clip(CircleShape)
                .gamepadButtonTouch(
                    onPress = { onPress(button.mappingId) },
                    onRelease = { onRelease(button.mappingId) },
                    onPressedStateChange = { directlyPressed = it }
                )
        ) {
            val baseColor = Color(0xFF333336)
            Canvas(modifier = Modifier.fillMaxSize()) {
                val r = size.width / 2f

                val maxOffset = 0.6.dp.toPx()
                val edgeOffset = if (isPressed) 0.3f.dp.toPx() else 1.8f.dp.toPx()
                val faceOffset = if (isPressed) 0f else maxOffset

                // Calculate radii to fit perfectly within the clip boundaries
                val edgeR = r - edgeOffset / 2f - 0.5.dp.toPx()
                val faceR = r - maxOffset - 0.5.dp.toPx()

                // 3D side edge
                drawCircle(
                    color = Color(0xFF1F1F21),
                    radius = edgeR,
                    center = Offset(r, r + edgeOffset / 2f)
                )

                // Top face
                val faceColor = if (isPressed) Color(0xFF232325) else baseColor
                drawCircle(
                    brush = Brush.verticalGradient(
                        colors = listOf(faceColor.gpLighter(0.08f), faceColor.gpDarker(0.12f))
                    ),
                    radius = faceR,
                    center = Offset(r, r - faceOffset)
                )

                // Bevel highlight border (vertical gradient brush for better shading)
                drawCircle(
                    brush = Brush.verticalGradient(
                        colors = if (isPressed) {
                            listOf(Color.White.copy(alpha = 0.05f), Color.Transparent)
                        } else {
                            listOf(Color.White.copy(alpha = 0.15f), Color.Transparent)
                        }
                    ),
                    radius = faceR - 0.5.dp.toPx(),
                    center = Offset(r, r - faceOffset),
                    style = Stroke(width = 0.8.dp.toPx())
                )
            }
            
            // Developer logo overlay centered on top face
            val faceOffset = if (isPressed) 0.dp else 0.6.dp
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .offset(y = -faceOffset),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_launcher_foreground),
                    contentDescription = "Logo",
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }
}

@Composable
private fun PlayStationLogoButton(
    button: ButtonDef,
    onPress: (Int) -> Unit,
    onRelease: (Int) -> Unit
) {
    val touchAssistTargetId = remember { Any() }
    var directlyPressed by remember { mutableStateOf(false) }
    val isPressed = directlyPressed ||
        (LocalGamepadTouchAssistController.current?.isAssistedPressed(touchAssistTargetId) == true)
    val pressOffsetY by animateDpAsState(
        targetValue = if (isPressed) 0.6.dp else 0.dp,
        animationSpec = gamepadPressSpring(isPressed),
        label = "psLogoPress"
    )
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) GAMEPAD_CONTROL_PRESSED_SCALE else 1.0f,
        animationSpec = gamepadPressSpring(isPressed),
        label = "psLogoScale"
    )

    // Well size 50.dp, cap size 47.dp (1.5.dp gap)
    Box(
        modifier = Modifier
            .size(50.dp)
            .gamepadTouchAssistTarget(button.mappingId, touchAssistTargetId),
        contentAlignment = Alignment.Center
    ) {
        // Button well
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(Color(0xFF1B1B1C))
                .border(0.8.dp, Color.Black.copy(alpha = 0.25f), CircleShape)
        )

        // Button Cap (Flat Disk)
        Box(
            modifier = Modifier
                .size(47.dp)
                .offset(y = pressOffsetY)
                .graphicsLayer {
                    scaleX = pressScale
                    scaleY = pressScale
                }
                .clip(CircleShape)
                .gamepadButtonTouch(
                    onPress = { onPress(button.mappingId) },
                    onRelease = { onRelease(button.mappingId) },
                    onPressedStateChange = { directlyPressed = it }
                )
        ) {
            val baseColor = Color(0xFF333336)
            Canvas(modifier = Modifier.fillMaxSize()) {
                val r = size.width / 2f

                val maxOffset = 0.6.dp.toPx()
                val edgeOffset = if (isPressed) 0.3f.dp.toPx() else 1.8f.dp.toPx()
                val faceOffset = if (isPressed) 0f else maxOffset

                // Calculate radii to fit perfectly within the clip boundaries
                val edgeR = r - edgeOffset / 2f - 0.5.dp.toPx()
                val faceR = r - maxOffset - 0.5.dp.toPx()

                // 3D side edge
                drawCircle(
                    color = Color(0xFF1F1F21),
                    radius = edgeR,
                    center = Offset(r, r + edgeOffset / 2f)
                )

                // Top face
                val faceColor = if (isPressed) Color(0xFF232325) else baseColor
                drawCircle(
                    brush = Brush.verticalGradient(
                        colors = listOf(faceColor.gpLighter(0.08f), faceColor.gpDarker(0.12f))
                    ),
                    radius = faceR,
                    center = Offset(r, r - faceOffset)
                )
                
                // Bevel highlight (vertical gradient brush for better shading)
                drawCircle(
                    brush = Brush.verticalGradient(
                        colors = if (isPressed) {
                            listOf(Color.White.copy(alpha = 0.05f), Color.Transparent)
                        } else {
                            listOf(Color.White.copy(alpha = 0.15f), Color.Transparent)
                        }
                    ),
                    radius = faceR - 0.5.dp.toPx(),
                    center = Offset(r, r - faceOffset),
                    style = Stroke(width = 0.8.dp.toPx())
                )
            }
            
            // Developer logo overlay centered on top face
            val faceOffset = if (isPressed) 0.dp else 0.6.dp
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .offset(y = -faceOffset),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_launcher_foreground),
                    contentDescription = "Logo",
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }
}



@Composable
private fun EditableGamepadAnalogStick(
    isEditMode: Boolean,
    showStick: Boolean = true,
    showStickButton: Boolean = true,
    stickButtonLabel: String,
    stickButtonMappingId: Int,
    isClicked: Boolean,
    isHeld: Boolean,
    isStickButtonToggleMode: Boolean,
    stickOffsetX: Float,
    stickOffsetY: Float,
    stickScale: Float,
    onStickOffsetChange: (Float, Float) -> Unit,
    onStickScaleChange: (Float) -> Unit,
    stickButtonOffsetX: Float,
    stickButtonOffsetY: Float,
    stickButtonScale: Float,
    onStickButtonOffsetChange: (Float, Float) -> Unit,
    onStickButtonScaleChange: (Float) -> Unit,
    onStickActiveChange: (Boolean) -> Unit,
    onMove: (Float, Float) -> Unit,
    onTouchPressHaptic: () -> Unit,
    onTouchReleaseHaptic: () -> Unit,
    onStickClick: () -> Unit,
    onToggleHold: (Boolean) -> Unit
) {
    val isLeft = stickButtonMappingId == 10
    val stickType = if (isLeft) GamepadControlType.LEFT_STICK else GamepadControlType.RIGHT_STICK
    val stickButtonType = if (isLeft) GamepadControlType.L3 else GamepadControlType.R3
    Box(
        modifier = Modifier.size(STICK_CONTROL_AREA_SIZE_DP.dp),
        contentAlignment = Alignment.Center
    ) {
        if (showStickButton) {
            EditableComponentWrapper(
                controlInstanceId = initialGamepadControlId(stickButtonType),
                isEditMode = isEditMode,
                offsetX = stickButtonOffsetX,
                offsetY = stickButtonOffsetY,
                scale = stickButtonScale,
                onOffsetChange = onStickButtonOffsetChange,
                onScaleChange = onStickButtonScaleChange,
                modifier = Modifier.align(Alignment.TopStart)
            ) {
                GamepadStickButton(
                    label = stickButtonLabel,
                    mappingId = stickButtonMappingId,
                    isHeld = isHeld,
                    isToggleMode = isStickButtonToggleMode,
                    onToggle = onToggleHold
                )
            }
        }

        if (showStick) {
            EditableComponentWrapper(
                controlInstanceId = initialGamepadControlId(stickType),
                isEditMode = isEditMode,
                offsetX = stickOffsetX,
                offsetY = stickOffsetY,
                scale = stickScale,
                onOffsetChange = onStickOffsetChange,
                onScaleChange = onStickScaleChange,
                editFrameInset = stickType.editFrameInset(),
                modifier = Modifier
                    .align(Alignment.Center)
                    .zIndex(1f)
            ) {
                GamepadAnalogStick(
                    isClicked = isClicked,
                    isHeld = isHeld,
                    editLabel = if (isEditMode) if (isLeft) "L" else "R" else null,
                    onActiveChange = onStickActiveChange,
                    onMove = onMove,
                    onTouchPressHaptic = onTouchPressHaptic,
                    onTouchReleaseHaptic = onTouchReleaseHaptic,
                    onStickClick = onStickClick
                )
            }
        }
    }
}

internal fun gamepadStickButtonTargetState(
    isToggleMode: Boolean,
    isHeld: Boolean,
    pressed: Boolean
): Boolean = if (isToggleMode) {
    if (pressed) !isHeld else isHeld
} else {
    pressed
}

@Composable
private fun GamepadStickButton(
    label: String,
    mappingId: Int,
    isHeld: Boolean,
    isToggleMode: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val touchAssistTargetId = remember { Any() }
    val touchAssistEnabled = LocalGamepadTouchAssistController.current?.enabled == true
    val currentOnToggle by rememberUpdatedState(onToggle)
    val currentIsHeld by rememberUpdatedState(isHeld)
    var directlyPressed by remember { mutableStateOf(false) }
    val assistedPressed =
        LocalGamepadTouchAssistController.current?.isAssistedPressed(touchAssistTargetId) == true
    val physicallyPressed = directlyPressed || assistedPressed
    val pressOffsetY by animateDpAsState(
        targetValue = when {
            physicallyPressed -> 1.dp
            isHeld -> 0.6.dp
            else -> 0.dp
        },
        animationSpec = gamepadPressSpring(physicallyPressed),
        label = "stickHoldPress"
    )
    val pressScale by animateFloatAsState(
        targetValue = if (physicallyPressed) GAMEPAD_CONTROL_PRESSED_SCALE else 1.0f,
        animationSpec = gamepadPressSpring(physicallyPressed),
        label = "stickHoldScale"
    )

    // Well size 30.dp, Cap size 26.dp (Hole / Inset shadow effect)
    Box(
        modifier = modifier
            .size(STICK_BUTTON_SIZE_DP.dp)
            .then(
                if (isToggleMode) {
                    Modifier.gamepadTouchAssistActionTarget(touchAssistTargetId) {
                        currentOnToggle(
                            gamepadStickButtonTargetState(
                                isToggleMode = true,
                                isHeld = currentIsHeld,
                                pressed = true
                            )
                        )
                    }
                } else {
                    Modifier.gamepadTouchAssistTarget(mappingId, touchAssistTargetId)
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        // 1. Dark Button Well (Hole Inset)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(Color(0xFF171718))
                .border(0.8.dp, Color.Black.copy(alpha = 0.5f), CircleShape)
        )

        // 2. 3D Circular Button Cap
        Box(
            modifier = Modifier
                .size(26.dp)
                .offset(y = pressOffsetY)
                .graphicsLayer {
                    scaleX = pressScale
                    scaleY = pressScale
                }
                .clip(CircleShape)
                .then(
                    if (touchAssistEnabled) {
                        Modifier
                    } else {
                        Modifier
                            .gamepadSharedPointerInput()
                            .pointerInput(isToggleMode) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val pointerId = down.id
                                    var releasedNormally = false
                                    directlyPressed = true
                                    if (!isToggleMode) {
                                        currentOnToggle(
                                            gamepadStickButtonTargetState(
                                                isToggleMode = false,
                                                isHeld = currentIsHeld,
                                                pressed = true
                                            )
                                        )
                                    }
                                    down.consume()
                                    try {
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            val change = event.changes.firstOrNull {
                                                it.id == pointerId
                                            } ?: break
                                            if (!change.pressed) {
                                                releasedNormally = true
                                                break
                                            }
                                            change.consume()
                                        }
                                    } finally {
                                        directlyPressed = false
                                        if (isToggleMode) {
                                            if (releasedNormally) {
                                                currentOnToggle(
                                                    gamepadStickButtonTargetState(
                                                        isToggleMode = true,
                                                        isHeld = currentIsHeld,
                                                        pressed = true
                                                    )
                                                )
                                            }
                                        } else {
                                            currentOnToggle(
                                                gamepadStickButtonTargetState(
                                                    isToggleMode = false,
                                                    isHeld = currentIsHeld,
                                                    pressed = false
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            val baseColor = if (isHeld) Color(0xFF3A3A3E) else Color(0xFF28282B)
            Canvas(modifier = Modifier.fillMaxSize()) {
                val r = size.width / 2f

                val maxOffset = 0.6.dp.toPx()
                val edgeOffset = if (isHeld) 0.2.dp.toPx() else 1.5.dp.toPx()
                val faceOffset = if (isHeld) 0f else maxOffset

                val edgeR = r - edgeOffset / 2f - 0.5.dp.toPx()
                val faceR = r - maxOffset - 0.5.dp.toPx()

                // 3D Side shadow edge
                drawCircle(
                    color = Color(0xFF161618),
                    radius = edgeR,
                    center = Offset(r, r + edgeOffset / 2f)
                )

                // Top face gradient
                drawCircle(
                    brush = Brush.verticalGradient(
                        colors = listOf(baseColor.gpLighter(0.10f), baseColor.gpDarker(0.12f))
                    ),
                    radius = faceR,
                    center = Offset(r, r - faceOffset)
                )

                // Top bevel highlight / active border
                drawCircle(
                    color = if (isHeld) Color(0xFFE5E5EA) else Color.White.copy(alpha = 0.15f),
                    radius = faceR - 0.5.dp.toPx(),
                    center = Offset(r, r - faceOffset),
                    style = Stroke(width = if (isHeld) 1.dp.toPx() else 0.8.dp.toPx())
                )
            }

            // Centered Text "L3" / "R3"
            Text(
                text = label,
                color = if (isHeld) Color(0xFFFFFFFF) else Color.White.copy(alpha = 0.70f),
                fontSize = 9.5.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = FontFamily.SansSerif,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun GamepadStickpad(
    isLeft: Boolean,
    isEditMode: Boolean,
    isHeld: Boolean,
    modifier: Modifier = Modifier,
    onActiveChange: (Boolean) -> Unit,
    onMove: (Float, Float) -> Unit,
    onTouchPressHaptic: () -> Unit,
    onTouchReleaseHaptic: () -> Unit,
    onStickClick: () -> Unit
) {
    var origin by remember { mutableStateOf<Offset?>(null) }
    var visualOffset by remember { mutableStateOf(Offset.Zero) }
    val currentOnActiveChange by rememberUpdatedState(onActiveChange)
    val currentOnMove by rememberUpdatedState(onMove)
    val currentOnTouchPressHaptic by rememberUpdatedState(onTouchPressHaptic)
    val currentOnTouchReleaseHaptic by rememberUpdatedState(onTouchReleaseHaptic)
    val currentIsHeld by rememberUpdatedState(isHeld)
    val currentOnStickClick by rememberUpdatedState(onStickClick)
    val stickpadShape = RoundedCornerShape(18.dp)

    Box(
        modifier = modifier
            .clip(stickpadShape)
            .gamepadTouchAssistExclusion()
            .then(
                if (isEditMode) {
                    Modifier
                } else {
                    Modifier.pointerInput(Unit) {
                        val maxInputRadius = 46.dp.toPx()
                        val maxVisualRadius = 22.dp.toPx()
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val pointerId = down.id
                            val downTime = System.currentTimeMillis()
                            var releasePosition: Offset? = null
                            origin = down.position
                            visualOffset = Offset.Zero
                            currentOnActiveChange(true)
                            currentOnMove(0f, 0f)
                            currentOnTouchPressHaptic()
                            down.consume()
                            try {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == pointerId }
                                        ?: break
                                    if (!change.pressed) {
                                        releasePosition = change.position
                                        break
                                    }
                                    val activeOrigin = origin ?: down.position
                                    val input = gamepadStickpadInput(
                                        origin = activeOrigin,
                                        position = change.position,
                                        maxInputRadius = maxInputRadius
                                    )
                                    val dx = change.position.x - activeOrigin.x
                                    val dy = change.position.y - activeOrigin.y
                                    val distance = sqrt(dx * dx + dy * dy)
                                    visualOffset = if (distance == 0f) {
                                        Offset.Zero
                                    } else {
                                        val visualDistance = distance.coerceAtMost(maxVisualRadius)
                                        Offset(
                                            x = dx / distance * visualDistance,
                                            y = dy / distance * visualDistance
                                        )
                                    }
                                    currentOnMove(input.first, input.second)
                                    change.consume()
                                }
                            } finally {
                                val upPosition = releasePosition
                                if (
                                    upPosition != null && !currentIsHeld &&
                                    isGamepadStickTap(
                                        downPosition = down.position,
                                        upPosition = upPosition,
                                        elapsedMillis = System.currentTimeMillis() - downTime
                                    )
                                ) {
                                    currentOnStickClick()
                                }
                                origin = null
                                visualOffset = Offset.Zero
                                currentOnMove(0f, 0f)
                                currentOnActiveChange(false)
                                currentOnTouchReleaseHaptic()
                            }
                        }
                    }
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val corner = 18.dp.toPx()
            drawRoundRect(
                brush = Brush.linearGradient(
                    colors = listOf(Color(0xCC34363A), Color(0xCC202124)),
                    start = Offset.Zero,
                    end = Offset(size.width, size.height)
                ),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner, corner)
            )
            drawRoundRect(
                color = Color.Black.copy(alpha = 0.58f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner, corner),
                style = Stroke(width = 1.2.dp.toPx())
            )
            drawRoundRect(
                color = Color.White.copy(alpha = 0.07f),
                topLeft = Offset(1.5.dp.toPx(), 1.5.dp.toPx()),
                size = androidx.compose.ui.geometry.Size(
                    width = (size.width - 3.dp.toPx()).coerceAtLeast(0f),
                    height = (size.height - 3.dp.toPx()).coerceAtLeast(0f)
                ),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                    (corner - 1.5.dp.toPx()).coerceAtLeast(0f)
                ),
                style = Stroke(width = 0.8.dp.toPx())
            )

            origin?.let { stickOrigin ->
                val baseRadius = 37.dp.toPx()
                val thumbRadius = 29.dp.toPx()
                val thumbCenter = stickOrigin + visualOffset
                drawCircle(
                    brush = Brush.linearGradient(
                        colors = listOf(Color(0xFF3B3B3E), Color(0xFF161718)),
                        start = stickOrigin - Offset(baseRadius, baseRadius),
                        end = stickOrigin + Offset(baseRadius, baseRadius)
                    ),
                    radius = baseRadius,
                    center = stickOrigin
                )
                drawCircle(
                    color = Color(0xFF101112),
                    radius = baseRadius - 3.dp.toPx(),
                    center = stickOrigin
                )
                drawCircle(
                    color = Color.Black.copy(alpha = 0.34f),
                    radius = thumbRadius + 3.dp.toPx(),
                    center = thumbCenter + Offset(0f, 2.dp.toPx())
                )
                drawCircle(
                    color = Color(0xFF151619),
                    radius = thumbRadius,
                    center = thumbCenter
                )
                drawCircle(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color(0xFF3A3B3F), Color(0xFF242529)),
                        startY = thumbCenter.y - thumbRadius,
                        endY = thumbCenter.y + thumbRadius
                    ),
                    radius = thumbRadius - 1.5.dp.toPx(),
                    center = thumbCenter
                )
                drawCircle(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color(0xFF1A1B1E), Color(0xFF292A2E)),
                        startY = thumbCenter.y - thumbRadius * 0.55f,
                        endY = thumbCenter.y + thumbRadius * 0.55f
                    ),
                    radius = thumbRadius * 0.57f,
                    center = thumbCenter
                )
                drawCircle(
                    color = Color.White.copy(alpha = 0.09f),
                    radius = thumbRadius - 2.dp.toPx(),
                    center = thumbCenter,
                    style = Stroke(width = 0.9.dp.toPx())
                )
            }
        }

        if (isEditMode) {
            Text(
                text = if (isLeft) "L" else "R",
                color = Color.White.copy(alpha = 0.25f),
                fontSize = 28.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center,
                style = TextStyle(
                    platformStyle = androidx.compose.ui.text.PlatformTextStyle(
                        includeFontPadding = false
                    )
                )
            )
        }
    }
}

@Composable
private fun GamepadAnalogStick(
    isClicked: Boolean,
    modifier: Modifier = Modifier,
    isHeld: Boolean = false,
    editLabel: String? = null,
    onActiveChange: (Boolean) -> Unit,
    onMove: (Float, Float) -> Unit,
    onTouchPressHaptic: () -> Unit,
    onTouchReleaseHaptic: () -> Unit,
    onStickClick: () -> Unit
) {
    var stickOffsetX by remember { mutableFloatStateOf(0f) }
    var stickOffsetY by remember { mutableFloatStateOf(0f) }
    var isTouchActive by remember { mutableStateOf(false) }

    val currentIsHeld by rememberUpdatedState(isHeld)
    val currentOnStickClick by rememberUpdatedState(onStickClick)
    val currentOnMove by rememberUpdatedState(onMove)
    val currentOnActiveChange by rememberUpdatedState(onActiveChange)
    val currentOnTouchPressHaptic by rememberUpdatedState(onTouchPressHaptic)
    val currentOnTouchReleaseHaptic by rememberUpdatedState(onTouchReleaseHaptic)

    val stickScale by animateFloatAsState(
        targetValue = if (isClicked || isHeld) 0.90f else 1.0f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "stickScale"
    )

    Box(
        modifier = modifier.size(STICK_CONTROL_AREA_SIZE_DP.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(ANALOG_STICK_WELL_SIZE_DP.dp)
                .gamepadTouchAssistExclusion()
                .pointerInput(Unit) {
                    val centerPx = size.width / 2f
                    val maxInputRadius = 32.dp.toPx()  // Wide linear touch response radius
                    val maxVisualRadius = 22.dp.toPx() // Clean visual cap travel boundary

                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        isTouchActive = true
                        currentOnActiveChange(true)
                        currentOnTouchPressHaptic()
                        val pointerId = down.id
                        val downTime = System.currentTimeMillis()

                        val updateStickOffset = { pos: Offset ->
                            val dx = pos.x - centerPx
                            val dy = pos.y - centerPx
                            val dist = sqrt(dx * dx + dy * dy)

                            if (dist == 0f) {
                                stickOffsetX = 0f
                                stickOffsetY = 0f
                                currentOnMove(0f, 0f)
                            } else {
                                val normDist = (dist / maxInputRadius).coerceIn(0f, 1f)
                                val normalizedX = (dx / dist) * normDist
                                val normalizedY = (dy / dist) * normDist

                                val visualDist = dist.coerceAtMost(maxVisualRadius)
                                stickOffsetX = (dx / dist) * visualDist
                                stickOffsetY = (dy / dist) * visualDist

                                currentOnMove(normalizedX, normalizedY)
                            }
                        }

                        updateStickOffset(down.position)

                        var releasedNormally = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == pointerId } ?: break

                            if (!change.pressed) {
                                isTouchActive = false
                                if (
                                    !currentIsHeld && isGamepadStickTap(
                                        downPosition = down.position,
                                        upPosition = change.position,
                                        elapsedMillis = System.currentTimeMillis() - downTime
                                    )
                                ) {
                                    currentOnStickClick()
                                }
                                stickOffsetX = 0f
                                stickOffsetY = 0f
                                currentOnActiveChange(false)
                                currentOnMove(0f, 0f)
                                currentOnTouchReleaseHaptic()
                                releasedNormally = true
                                break
                            }

                            change.consume()
                            updateStickOffset(change.position)
                        }
                        if (!releasedNormally) {
                            isTouchActive = false
                            stickOffsetX = 0f
                            stickOffsetY = 0f
                            currentOnActiveChange(false)
                            currentOnMove(0f, 0f)
                            currentOnTouchReleaseHaptic()
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
        // 1. Analog Stick Base Well (neumorphic molding & concentric rings)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width

            // Neumorphic outer lip molding (top-left highlight, bottom-right shadow)
            drawCircle(
                brush = Brush.linearGradient(
                    colors = listOf(Color(0xFF3B3B3E), Color(0xFF1B1B1C)),
                    start = Offset(0f, 0f),
                    end = Offset(w, w)
                ),
                radius = w / 2f + ANALOG_STICK_VISUAL_OVERDRAW_DP.dp.toPx()
            )
            drawCircle(
                color = Color(0xFF121213),
                radius = w / 2f + ANALOG_STICK_VISUAL_OVERDRAW_DP.dp.toPx(),
                style = Stroke(width = 0.8.dp.toPx())
            )
            
            // Recessed well background
            drawCircle(
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFF141416), Color(0xFF222224))
                ),
                radius = w / 2f - 1.dp.toPx()
            )
            
            // Soft inner shadow ring inside the well for depth
            drawCircle(
                color = Color.Black.copy(alpha = 0.45f),
                radius = w / 2f - 2.dp.toPx(),
                style = Stroke(width = 3.dp.toPx())
            )
        }

        // 2. Parallax 3D Shadow Layer (floats under the cap, shifts slightly less)
        Canvas(
            modifier = Modifier
                .offset { IntOffset((stickOffsetX * 0.6f).roundToInt(), (stickOffsetY * 0.6f).roundToInt()) }
                .size(82.dp)
        ) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.Black.copy(alpha = 0.6f), Color.Black.copy(alpha = 0.2f), Color.Transparent),
                    center = Offset(size.width / 2f, size.height / 2f),
                    radius = size.width / 2f
                ),
                radius = size.width / 2f
            )
        }
            
        // 3. Analog Stick Thumb (rendered on top, NOT clipped, with 3D tilt & press)
        Box(
            modifier = Modifier
                .offset { IntOffset(stickOffsetX.roundToInt(), stickOffsetY.roundToInt()) }
                .size(76.dp) // Large thumb cap (narrow gap to well)
                .clip(CircleShape)
                .graphicsLayer {
                    // Quick press/sink scaling when clicked (no shape distortion when moving)
                    scaleX = stickScale
                    scaleY = stickScale
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val cx = w / 2f
                val cy = w / 2f
                val r = w / 2f
                
                // Outer base edge (very dark ring)
                drawCircle(
                    color = Color(0xFF161719),
                    radius = r
                )
                
                // Outer ring highlight/shadow
                drawCircle(
                    color = Color.White.copy(alpha = 0.08f),
                    radius = r - 0.5.dp.toPx(),
                    style = Stroke(width = 1.dp.toPx())
                )
                
                // Shifting central elements for 3D parallax deflection and press look
                val pressShift = if (isClicked) 1.2.dp.toPx() else 0f
                val cupCx = cx + stickOffsetX * 0.12f
                val cupCy = cy + stickOffsetY * 0.12f + pressShift
                
                // Create paths for the knurled dome slope (between outer ring and inner cup)
                val pathOuter = androidx.compose.ui.graphics.Path().apply {
                    addOval(androidx.compose.ui.geometry.Rect(center = Offset(cx, cy), radius = r - 1.dp.toPx()))
                }
                val pathInner = androidx.compose.ui.graphics.Path().apply {
                    addOval(androidx.compose.ui.geometry.Rect(center = Offset(cupCx, cupCy), radius = r * 0.58f))
                }
                val ringPath = androidx.compose.ui.graphics.Path.combine(
                    androidx.compose.ui.graphics.PathOperation.Difference,
                    pathOuter,
                    pathInner
                )
                
                // Fill the dome slope with a soft vertical gradient for a flatter look
                drawPath(
                    path = ringPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(Color(0xFF38383B), Color(0xFF232325))
                    )
                )
                
                // Flatter recessed cup gradient (no radial concentration in center)
                val cupRadius = r * 0.58f
                val concaveBrush = Brush.verticalGradient(
                    colors = listOf(Color(0xFF1B1C1E), Color(0xFF292A2E))
                )
                
                // 3D Inner cup recessed background
                drawCircle(
                    brush = concaveBrush,
                    radius = cupRadius,
                    center = Offset(cupCx, cupCy)
                )
                
                // Bevel ring border (simple dark separator)
                drawCircle(
                    color = Color(0xFF141517),
                    radius = cupRadius,
                    center = Offset(cupCx, cupCy),
                    style = Stroke(width = 1.2.dp.toPx())
                )

                // Bevel highlight (simple soft outline ring)
                drawCircle(
                    color = Color.White.copy(alpha = 0.09f),
                    radius = cupRadius - 0.6.dp.toPx(),
                    center = Offset(cupCx, cupCy),
                    style = Stroke(width = 0.8.dp.toPx())
                )
            }
        }
        if (editLabel != null) {
            Text(
                text = editLabel,
                color = Color.White.copy(alpha = 0.25f),
                fontSize = 28.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center,
                style = TextStyle(
                    platformStyle = androidx.compose.ui.text.PlatformTextStyle(
                        includeFontPadding = false
                    )
                ),
                modifier = Modifier.zIndex(2f)
            )
        }
        }

    }
}

@Composable
private fun GamepadDpadDirectionButton(
    directionMask: Int,
    onDpadChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val touchAssistTargetId = remember { Any() }
    val mappingId = when (directionMask) {
        1 -> 12
        2 -> 13
        4 -> 14
        8 -> 15
        else -> 12
    }
    var directlyPressed by remember { mutableStateOf(false) }
    val isPressed = directlyPressed ||
        (LocalGamepadTouchAssistController.current?.isAssistedPressed(touchAssistTargetId) == true)
    val pressOffsetY by animateDpAsState(
        targetValue = if (isPressed) 1.2.dp else 0.dp,
        animationSpec = gamepadPressSpring(isPressed),
        label = "dpadDirectionOffset"
    )
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) GAMEPAD_CONTROL_PRESSED_SCALE else 1f,
        animationSpec = gamepadPressSpring(isPressed),
        label = "dpadDirectionScale"
    )
    val isVertical = directionMask == 1 || directionMask == 2

    Box(
        modifier = modifier
            .width(if (isVertical) 54.dp else 82.dp)
            .height(if (isVertical) 82.dp else 54.dp)
            .gamepadTouchAssistTarget(mappingId, touchAssistTargetId)
            .gamepadButtonTouch(
                onPress = { onDpadChange(directionMask) },
                onRelease = { onDpadChange(0) },
                onPressedStateChange = { directlyPressed = it }
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val path = roundedGamepadPolygonPath(
                vertices = dpadPentagonVertices(size, directionMask, 7.dp.toPx()),
                cornerCut = 5.dp.toPx()
            )
            drawPath(
                path = path,
                color = Color.Black.copy(alpha = 0.55f),
                style = Stroke(
                    width = 12.5.dp.toPx(),
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    join = androidx.compose.ui.graphics.StrokeJoin.Round
                )
            )
            drawPath(
                path = path,
                color = Color(0xFF1B1B1C),
                style = Stroke(
                    width = 10.dp.toPx(),
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    join = androidx.compose.ui.graphics.StrokeJoin.Round
                )
            )
            drawPath(path, Color(0xFF1B1B1C))
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .offset(y = pressOffsetY)
                .graphicsLayer {
                    scaleX = pressScale
                    scaleY = pressScale
                }
        ) {
            val bodyPath = roundedGamepadPolygonPath(
                vertices = dpadPentagonVertices(size, directionMask, 7.dp.toPx()),
                cornerCut = 5.dp.toPx()
            )
            drawPath(
                path = bodyPath,
                brush = Brush.verticalGradient(
                    colors = if (isPressed) {
                        listOf(Color(0xFF303033), Color(0xFF242426))
                    } else {
                        listOf(Color(0xFF414145), Color(0xFF2D2D30))
                    }
                )
            )
            drawPath(
                path = bodyPath,
                color = Color.White.copy(alpha = if (isPressed) 0.07f else 0.16f),
                style = Stroke(width = 0.9.dp.toPx())
            )
            drawPath(
                path = bodyPath,
                color = Color.Black.copy(alpha = 0.34f),
                style = Stroke(width = 1.1.dp.toPx())
            )
            drawPath(
                path = dpadDirectionMarkerPath(size, directionMask),
                color = Color.White.copy(alpha = if (isPressed) 0.42f else 0.22f)
            )
        }
    }
}

@Composable
private fun GamepadDpad(
    @Suppress("UNUSED_PARAMETER") isXboxStyle: Boolean,
    onDpadChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var activeDirection by remember { mutableIntStateOf(0) }
    val touchAssistTargetIds = remember { List(GamepadDpadTouchAssistMasks.size) { Any() } }
    val touchAssistController = LocalGamepadTouchAssistController.current
    val touchAssistEnabled = touchAssistController?.enabled == true
    val assistedDirection = GamepadDpadTouchAssistMasks.foldIndexed(0) { index, result, mask ->
        if (touchAssistController?.isAssistedPressed(touchAssistTargetIds[index]) == true) {
            result or mask
        } else {
            result
        }
    }
    val visualDirection = activeDirection or assistedDirection
    
    // Physical tilt based on pressed direction (pure pivot rotation)
    var targetRotX = 0f
    var targetRotY = 0f
    
    if (visualDirection and 1 != 0) targetRotX = 12f  // UP: depresses top, raises bottom
    if (visualDirection and 2 != 0) targetRotX = -12f // DOWN: depresses bottom, raises top
    if (visualDirection and 4 != 0) targetRotY = -12f // LEFT: depresses left, raises right
    if (visualDirection and 8 != 0) targetRotY = 12f  // RIGHT: depresses right, raises left
    
    val rotX by animateFloatAsState(targetValue = targetRotX, animationSpec = spring(stiffness = Spring.StiffnessHigh))
    val rotY by animateFloatAsState(targetValue = targetRotY, animationSpec = spring(stiffness = Spring.StiffnessHigh))

    Box(
        modifier = modifier
            .size(PRIMARY_GAMEPAD_CONTROL_SIZE_DP.dp)
            .gamepadTouchAssistDpadTargets(touchAssistTargetIds)
            .then(
                if (touchAssistEnabled) {
                    Modifier
                } else {
                    Modifier
                        .gamepadSharedPointerInput()
                        .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val pointerId = down.id
                            var currentBit = gamepadDpadMaskAt(
                                down.position.x,
                                down.position.y,
                                size.width.toFloat()
                            )
                            activeDirection = currentBit
                            onDpadChange(currentBit)

                            while (true) {
                                val event = awaitPointerEvent()
                                // A PointerEvent can contain changes from other fingers that are
                                // pressing nearby controls. Only the finger that started this D-pad
                                // gesture is allowed to update its direction.
                                val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                                if (!change.pressed) {
                                    activeDirection = 0
                                    onDpadChange(0)
                                    break
                                }
                                change.consume()
                                val newBit = gamepadDpadMaskAt(
                                    change.position.x,
                                    change.position.y,
                                    size.width.toFloat()
                                )
                                if (newBit != currentBit) {
                                    currentBit = newBit
                                    activeDirection = currentBit
                                    onDpadChange(currentBit)
                                }
                            }
                        }
                    }
                }
            )
    ) {
        // 1. Stationary Background Well (Casing hole remains static)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val barWWell = w * 0.36f
            val startOffWell = (w - barWWell) / 2f
            
            val path1 = androidx.compose.ui.graphics.Path().apply {
                addRoundRect(
                    androidx.compose.ui.geometry.RoundRect(
                        left = w * DPAD_WELL_OUTER_INSET_FRACTION,
                        top = startOffWell,
                        right = w * (1f - DPAD_WELL_OUTER_INSET_FRACTION),
                        bottom = startOffWell + barWWell,
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx())
                    )
                )
            }
            val path2 = androidx.compose.ui.graphics.Path().apply {
                addRoundRect(
                    androidx.compose.ui.geometry.RoundRect(
                        left = startOffWell,
                        top = w * DPAD_WELL_OUTER_INSET_FRACTION,
                        right = startOffWell + barWWell,
                        bottom = w * (1f - DPAD_WELL_OUTER_INSET_FRACTION),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx())
                    )
                )
            }
            val wellPath = androidx.compose.ui.graphics.Path.combine(
                androidx.compose.ui.graphics.PathOperation.Union,
                path1,
                path2
            )
            
            // Fill plus-shaped well
            drawPath(
                path = wellPath,
                color = Color(0xFF1B1B1C)
            )
            // Well border
            drawPath(
                path = wellPath,
                color = Color.Black.copy(alpha = 0.5f),
                style = Stroke(width = 1.2.dp.toPx())
            )
            // Inner shadow for depth
            drawPath(
                path = wellPath,
                color = Color.Black.copy(alpha = 0.2f),
                style = Stroke(width = 3.dp.toPx())
            )
        }

        // 2. Stationary Drop Shadow Layer (does NOT rotate in 3D, shifts dynamically opposite to tilt)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val barW = w * DPAD_BODY_BAR_WIDTH_FRACTION
            val startOff = (w - barW) / 2f
            
            val cross1 = androidx.compose.ui.graphics.Path().apply {
                addRoundRect(
                    androidx.compose.ui.geometry.RoundRect(
                        left = w * DPAD_BODY_OUTER_INSET_FRACTION,
                        top = startOff,
                        right = w * (1f - DPAD_BODY_OUTER_INSET_FRACTION),
                        bottom = startOff + barW,
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx())
                    )
                )
            }
            val cross2 = androidx.compose.ui.graphics.Path().apply {
                addRoundRect(
                    androidx.compose.ui.geometry.RoundRect(
                        left = startOff,
                        top = w * DPAD_BODY_OUTER_INSET_FRACTION,
                        right = startOff + barW,
                        bottom = w * (1f - DPAD_BODY_OUTER_INSET_FRACTION),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx())
                    )
                )
            }
            val crossPath = androidx.compose.ui.graphics.Path.combine(
                androidx.compose.ui.graphics.PathOperation.Union,
                cross1,
                cross2
            )
            
            // Dynamic D-pad drop shadow offset (shifts in opposite direction of tilt)
            var shadowOffsetX = 0f
            var shadowOffsetY = 1.5f.dp.toPx()
            
            if (visualDirection and 1 != 0) shadowOffsetY += 2f.dp.toPx()  // UP: shifts shadow down
            if (visualDirection and 2 != 0) shadowOffsetY -= 2f.dp.toPx()  // DOWN: shifts shadow up
            if (visualDirection and 4 != 0) shadowOffsetX += 2f.dp.toPx()  // LEFT: shifts shadow right
            if (visualDirection and 8 != 0) shadowOffsetX -= 2f.dp.toPx()  // RIGHT: shifts shadow left
            
            withTransform({
                translate(left = shadowOffsetX, top = shadowOffsetY)
            }) {
                drawPath(
                    path = crossPath,
                    color = Color.Black.copy(alpha = 0.4f)
                )
            }
        }

        // 3. Animated D-pad Cross Body (Applying rotation to inner cross only)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    rotationX = rotX
                    rotationY = rotY
                    cameraDistance = 8f * density
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val cx = w / 2f
                val cy = w / 2f
                
                val barW = w * DPAD_BODY_BAR_WIDTH_FRACTION
                val startOff = (w - barW) / 2f
                
                val cross1 = androidx.compose.ui.graphics.Path().apply {
                    addRoundRect(
                        androidx.compose.ui.geometry.RoundRect(
                            left = w * DPAD_BODY_OUTER_INSET_FRACTION,
                            top = startOff,
                            right = w * (1f - DPAD_BODY_OUTER_INSET_FRACTION),
                            bottom = startOff + barW,
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx())
                        )
                    )
                }
                val cross2 = androidx.compose.ui.graphics.Path().apply {
                    addRoundRect(
                        androidx.compose.ui.geometry.RoundRect(
                            left = startOff,
                            top = w * DPAD_BODY_OUTER_INSET_FRACTION,
                            right = startOff + barW,
                            bottom = w * (1f - DPAD_BODY_OUTER_INSET_FRACTION),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx())
                        )
                    )
                }
                val crossPath = androidx.compose.ui.graphics.Path.combine(
                    androidx.compose.ui.graphics.PathOperation.Union,
                    cross1,
                    cross2
                )

                // Draw cross body (matte vertical gradient remains static/unpressed color on tap)
                val bodyBrush = Brush.verticalGradient(
                    colors = listOf(Color(0xFF38383B), Color(0xFF2D2D30))
                )
                drawPath(
                    path = crossPath,
                    brush = bodyBrush
                )

                // Beveled highlight stroke remains static/unpressed on tap
                val highlightBrush = Brush.verticalGradient(
                    colors = listOf(Color.White.copy(alpha = 0.15f), Color.Transparent)
                )
                drawPath(
                    path = crossPath,
                    brush = highlightBrush,
                    style = Stroke(width = 0.8.dp.toPx())
                )
                // Outer dark border stroke remains static on tap
                drawPath(
                    path = crossPath,
                    color = Color.Black.copy(alpha = 0.35f),
                    style = Stroke(width = 1.2.dp.toPx())
                )

                // Central depressed dish (concave look)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFF1B1B1D), Color(0xFF2C2C30)),
                        center = Offset(cx, cy),
                        radius = barW / 1.5f
                    ),
                    radius = barW / 1.8f
                )
                // Soft outline for dish
                drawCircle(
                    color = Color.White.copy(alpha = 0.04f),
                    radius = barW / 1.8f,
                    style = Stroke(width = 0.8.dp.toPx())
                )

                // Elegant, thin directional markers/arrows (static subtle white/grey)
                val arrowColor = { directionBit: Int ->
                    if (visualDirection and directionBit != 0) Color.White.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.15f)
                }
                val arrowDist = w * 0.34f
                val arrowSize = w * 0.04f

                // Up Arrow
                val pathU = androidx.compose.ui.graphics.Path().apply {
                    moveTo(cx, cy - arrowDist)
                    lineTo(cx - arrowSize, cy - arrowDist + arrowSize * 1.1f)
                    lineTo(cx + arrowSize, cy - arrowDist + arrowSize * 1.1f)
                    close()
                }
                drawPath(pathU, arrowColor(1))

                // Down Arrow
                val pathD = androidx.compose.ui.graphics.Path().apply {
                    moveTo(cx, cy + arrowDist)
                    lineTo(cx - arrowSize, cy + arrowDist - arrowSize * 1.1f)
                    lineTo(cx + arrowSize, cy + arrowDist - arrowSize * 1.1f)
                    close()
                }
                drawPath(pathD, arrowColor(2))

                // Left Arrow
                val pathL = androidx.compose.ui.graphics.Path().apply {
                    moveTo(cx - arrowDist, cy)
                    lineTo(cx - arrowDist + arrowSize * 1.1f, cy - arrowSize)
                    lineTo(cx - arrowDist + arrowSize * 1.1f, cy + arrowSize)
                    close()
                }
                drawPath(pathL, arrowColor(4))

                // Right Arrow
                val pathR = androidx.compose.ui.graphics.Path().apply {
                    moveTo(cx + arrowDist, cy)
                    lineTo(cx + arrowDist - arrowSize * 1.1f, cy - arrowSize)
                    lineTo(cx + arrowDist - arrowSize * 1.1f, cy + arrowSize)
                    close()
                }
                drawPath(pathR, arrowColor(8))
            }
        }
    }
}

internal fun gamepadDpadMaskAt(x: Float, y: Float, totalSize: Float): Int {
    if (!x.isFinite() || !y.isFinite() || !totalSize.isFinite() || totalSize <= 0f) return 0
    if (x < 0f || x > totalSize || y < 0f || y > totalSize) return 0
    val center = totalSize / 2f
    val dx = x - center
    val dy = y - center
    val distance = sqrt(dx * dx + dy * dy)
    if (distance < totalSize * DPAD_CENTER_DEAD_ZONE_RADIUS_FRACTION) return 0

    val barWidth = totalSize * DPAD_BODY_BAR_WIDTH_FRACTION
    val innerStart = (totalSize - barWidth) / 2f
    val innerEnd = innerStart + barWidth
    val diagonalRadius = innerStart - totalSize * DPAD_BODY_OUTER_INSET_FRACTION
    val diagonalRadiusSquared = diagonalRadius * diagonalRadius

    fun insideQuarterCircle(centerX: Float, centerY: Float): Boolean {
        val circleDx = x - centerX
        val circleDy = y - centerY
        return circleDx * circleDx + circleDy * circleDy <= diagonalRadiusSquared
    }

    if (x <= innerStart && y <= innerStart && insideQuarterCircle(innerStart, innerStart)) {
        return 1 or 4
    }
    if (x >= innerEnd && y <= innerStart && insideQuarterCircle(innerEnd, innerStart)) {
        return 1 or 8
    }
    if (x <= innerStart && y >= innerEnd && insideQuarterCircle(innerStart, innerEnd)) {
        return 2 or 4
    }
    if (x >= innerEnd && y >= innerEnd && insideQuarterCircle(innerEnd, innerEnd)) {
        return 2 or 8
    }

    val outerStart = totalSize * DPAD_BODY_OUTER_INSET_FRACTION
    val outerEnd = totalSize - outerStart
    val insideHorizontalArm = x in outerStart..outerEnd && y in innerStart..innerEnd
    val insideVerticalArm = x in innerStart..innerEnd && y in outerStart..outerEnd
    if (!insideHorizontalArm && !insideVerticalArm) return 0

    return if (abs(dx) > abs(dy)) {
        if (dx > 0f) 8 else 4
    } else {
        if (dy > 0f) 2 else 1
    }
}

internal fun gamepadDpadMappingIds(directionMask: Int): List<Int> = buildList {
    if (directionMask and 1 != 0) add(12)
    if (directionMask and 2 != 0) add(13)
    if (directionMask and 4 != 0) add(14)
    if (directionMask and 8 != 0) add(15)
}

internal fun gamepadStickpadInput(
    origin: Offset,
    position: Offset,
    maxInputRadius: Float
): Pair<Float, Float> {
    if (maxInputRadius <= 0f) return 0f to 0f
    val dx = position.x - origin.x
    val dy = position.y - origin.y
    val distance = sqrt(dx * dx + dy * dy)
    if (distance == 0f) return 0f to 0f
    val normalizedDistance = (distance / maxInputRadius).coerceIn(0f, 1f)
    return (dx / distance) * normalizedDistance to (dy / distance) * normalizedDistance
}

internal fun isGamepadStickTap(
    downPosition: Offset,
    upPosition: Offset,
    elapsedMillis: Long,
    tapSlopPx: Float = 8f,
    maximumTapDurationMillis: Long = 250L
): Boolean {
    if (elapsedMillis < 0L || elapsedMillis >= maximumTapDurationMillis) return false
    val dx = upPosition.x - downPosition.x
    val dy = upPosition.y - downPosition.y
    return sqrt(dx * dx + dy * dy) < tapSlopPx
}

private data class GamepadAxisScales(
    val scaleX: Float,
    val scaleY: Float
)

private fun gamepadAxisScalesForDisplay(scale: Float): GamepadAxisScales {
    val safeScale = scale.coerceIn(GAMEPAD_MIN_EDIT_SCALE, GAMEPAD_MAX_EDIT_SCALE)
    return GamepadAxisScales(safeScale, safeScale)
}

internal fun gamepadOffsetForBottomRightResize(
    currentOffsetX: Float,
    currentOffsetY: Float,
    layoutWidthDp: Float,
    layoutHeightDp: Float,
    currentScale: Float,
    newScale: Float
): Pair<Float, Float> {
    val scaleDelta = newScale - currentScale
    return currentOffsetX + layoutWidthDp * scaleDelta / 2f to
        currentOffsetY + layoutHeightDp * scaleDelta / 2f
}

internal fun gamepadScaleDeltaForResizeDrag(
    dragAmountXpx: Float,
    dragAmountYpx: Float,
    layoutWidthDp: Float,
    layoutHeightDp: Float,
    density: Float
): Float {
    if (density <= 0f || layoutWidthDp <= 0f || layoutHeightDp <= 0f) return 0f
    val dragXdp = dragAmountXpx / density
    val dragYdp = dragAmountYpx / density
    val sizeSquared = layoutWidthDp * layoutWidthDp + layoutHeightDp * layoutHeightDp
    val projectedScaleDelta =
        (dragXdp * layoutWidthDp + dragYdp * layoutHeightDp) / sizeSquared
    return projectedScaleDelta * GAMEPAD_RESIZE_DRAG_SENSITIVITY
}

internal data class GamepadIndependentResizeTransform(
    val offsetX: Float,
    val offsetY: Float,
    val widthScale: Float,
    val heightScale: Float
)

internal fun gamepadIndependentResizeTransform(
    currentOffsetX: Float,
    currentOffsetY: Float,
    currentWidthScale: Float,
    currentHeightScale: Float,
    dragAmountXDp: Float,
    dragAmountYDp: Float,
    baseWidthDp: Float = STICKPAD_BASE_WIDTH_DP,
    baseHeightDp: Float = STICKPAD_BASE_HEIGHT_DP,
    minimumAxisScale: Float = GAMEPAD_MIN_EDIT_SCALE,
    maximumAxisScale: Float = GAMEPAD_MAX_EDIT_SCALE
): GamepadIndependentResizeTransform {
    if (
        baseWidthDp <= 0f || baseHeightDp <= 0f ||
        minimumAxisScale <= 0f || maximumAxisScale < minimumAxisScale
    ) {
        return GamepadIndependentResizeTransform(
            currentOffsetX,
            currentOffsetY,
            currentWidthScale,
            currentHeightScale
        )
    }
    val oldWidth = baseWidthDp * currentWidthScale
    val oldHeight = baseHeightDp * currentHeightScale
    val minimumAxisSizeDp = minOf(baseWidthDp, baseHeightDp) * minimumAxisScale
    val newWidthScale = (
        currentWidthScale + dragAmountXDp * GAMEPAD_RESIZE_DRAG_SENSITIVITY / baseWidthDp
    ).coerceIn(
        minimumAxisSizeDp / baseWidthDp,
        maximumAxisScale
    )
    val newHeightScale = (
        currentHeightScale + dragAmountYDp * GAMEPAD_RESIZE_DRAG_SENSITIVITY / baseHeightDp
    ).coerceIn(
        minimumAxisSizeDp / baseHeightDp,
        maximumAxisScale
    )
    val newWidth = baseWidthDp * newWidthScale
    val newHeight = baseHeightDp * newHeightScale
    return GamepadIndependentResizeTransform(
        offsetX = currentOffsetX + (newWidth - oldWidth) / 2f,
        offsetY = currentOffsetY + (newHeight - oldHeight) / 2f,
        widthScale = newWidthScale,
        heightScale = newHeightScale
    )
}

private data class GamepadIndependentResizeDragSnap(
    val dragAmountXDp: Float,
    val dragAmountYDp: Float,
    val guides: List<GamepadSnapGuide>
)

private fun calculateGamepadIndependentResizeDragSnap(
    componentKey: Any,
    dragAmountXDp: Float,
    dragAmountYDp: Float,
    density: Float,
    controller: GamepadSnapController?
): GamepadIndependentResizeDragSnap {
    if (
        controller == null || !controller.enabled || controller.rootSize == IntSize.Zero ||
        density <= 0f || GAMEPAD_RESIZE_DRAG_SENSITIVITY <= 0f ||
        GAMEPAD_SNAP_GUIDE_THRESHOLD_DP <= 0f
    ) {
        return GamepadIndependentResizeDragSnap(dragAmountXDp, dragAmountYDp, emptyList())
    }
    val currentRect = controller.componentBounds[componentKey]
        ?: return GamepadIndependentResizeDragSnap(dragAmountXDp, dragAmountYDp, emptyList())
    val otherRects = controller.componentBounds
        .filterKeys { it !== componentKey }
        .values
        .filter { it.width > 0f && it.height > 0f }
    val proposedRight = currentRect.right +
        dragAmountXDp * density * GAMEPAD_RESIZE_DRAG_SENSITIVITY
    val proposedBottom = currentRect.bottom +
        dragAmountYDp * density * GAMEPAD_RESIZE_DRAG_SENSITIVITY
    val proposedCenterX = (currentRect.left + proposedRight) / 2f
    val proposedCenterY = (currentRect.top + proposedBottom) / 2f
    val rootCenterX = controller.rootSize.width / 2f
    val rootCenterY = controller.rootSize.height / 2f
    val thresholdPx = GAMEPAD_SNAP_GUIDE_THRESHOLD_DP * density
    val xGuides = mutableListOf<GamepadSnapGuide>()
    val yGuides = mutableListOf<GamepadSnapGuide>()

    fun considerX(
        correctionPx: Float,
        distancePx: Float = abs(correctionPx),
        guides: List<GamepadSnapGuide>
    ) {
        if (distancePx <= thresholdPx) {
            guides.forEach { guide ->
                if (xGuides.none { it.color == guide.color && abs(it.positionPx - guide.positionPx) <= 0.5f }) {
                    xGuides += guide
                }
            }
        }
    }

    fun considerY(
        correctionPx: Float,
        distancePx: Float = abs(correctionPx),
        guides: List<GamepadSnapGuide>
    ) {
        if (distancePx <= thresholdPx) {
            guides.forEach { guide ->
                if (yGuides.none { it.color == guide.color && abs(it.positionPx - guide.positionPx) <= 0.5f }) {
                    yGuides += guide
                }
            }
        }
    }

    otherRects.forEach { other ->
        listOf(other.left, other.right).forEach { targetEdge ->
            considerX(
                correctionPx = targetEdge - proposedRight,
                guides = listOf(
                    GamepadSnapGuide(
                        GamepadSnapGuideOrientation.Vertical,
                        targetEdge,
                        GamepadSnapGuideYellow
                    )
                )
            )
        }
        listOf(other.top, other.bottom).forEach { targetEdge ->
            considerY(
                correctionPx = targetEdge - proposedBottom,
                guides = listOf(
                    GamepadSnapGuide(
                        GamepadSnapGuideOrientation.Horizontal,
                        targetEdge,
                        GamepadSnapGuideYellow
                    )
                )
            )
        }

        val otherCenterX = other.left + other.width / 2f
        val oppositeSides =
            (proposedCenterX < rootCenterX && otherCenterX > rootCenterX) ||
                (proposedCenterX > rootCenterX && otherCenterX < rootCenterX)
        if (oppositeSides) {
            val mirroredCenterX = rootCenterX * 2f - otherCenterX
            val centerDelta = mirroredCenterX - proposedCenterX
            considerX(
                correctionPx = centerDelta * 2f,
                distancePx = abs(centerDelta),
                guides = listOf(
                    GamepadSnapGuide(
                        GamepadSnapGuideOrientation.Vertical,
                        mirroredCenterX,
                        GamepadSnapGuideGreen
                    ),
                    GamepadSnapGuide(
                        GamepadSnapGuideOrientation.Vertical,
                        otherCenterX,
                        GamepadSnapGuideGreen
                    )
                )
            )
        }
    }

    val rootCenterDeltaX = rootCenterX - proposedCenterX
    considerX(
        correctionPx = rootCenterDeltaX * 2f,
        distancePx = abs(rootCenterDeltaX),
        guides = listOf(
            GamepadSnapGuide(
                GamepadSnapGuideOrientation.Vertical,
                rootCenterX,
                GamepadSnapGuideRed
            )
        )
    )
    val rootCenterDeltaY = rootCenterY - proposedCenterY
    considerY(
        correctionPx = rootCenterDeltaY * 2f,
        distancePx = abs(rootCenterDeltaY),
        guides = listOf(
            GamepadSnapGuide(
                GamepadSnapGuideOrientation.Horizontal,
                rootCenterY,
                GamepadSnapGuideRed
            )
        )
    )

    return GamepadIndependentResizeDragSnap(
        dragAmountXDp = dragAmountXDp,
        dragAmountYDp = dragAmountYDp,
        guides = xGuides + yGuides
    )
}

private fun calculateGamepadSnap(
    componentKey: Any,
    proposedOffsetX: Float,
    proposedOffsetY: Float,
    proposedScale: Float,
    currentOffsetX: Float,
    currentOffsetY: Float,
    currentScale: Float,
    density: Float,
    controller: GamepadSnapController?
): GamepadSnapResult {
    if (
        controller == null || !controller.enabled || controller.rootSize == IntSize.Zero ||
        density <= 0f || GAMEPAD_SNAP_GUIDE_THRESHOLD_DP <= 0f
    ) {
        return GamepadSnapResult(proposedOffsetX, proposedOffsetY, emptyList())
    }
    val currentRect = controller.componentBounds[componentKey]
        ?: return GamepadSnapResult(proposedOffsetX, proposedOffsetY, emptyList())
    val otherRects = controller.componentBounds
        .filterKeys { it !== componentKey }
        .values
        .filter { it.width > 0f && it.height > 0f }

    val scaleRatio = if (currentScale == 0f) 1f else proposedScale / currentScale
    val proposedCenterX = currentRect.left + currentRect.width / 2f + (proposedOffsetX - currentOffsetX) * density
    val proposedCenterY = currentRect.top + currentRect.height / 2f + (proposedOffsetY - currentOffsetY) * density
    val proposedWidth = currentRect.width * scaleRatio
    val proposedHeight = currentRect.height * scaleRatio
    val proposedRect = Rect(
        left = proposedCenterX - proposedWidth / 2f,
        top = proposedCenterY - proposedHeight / 2f,
        right = proposedCenterX + proposedWidth / 2f,
        bottom = proposedCenterY + proposedHeight / 2f
    )

    val rootCenterX = controller.rootSize.width / 2f
    val rootCenterY = controller.rootSize.height / 2f
    val thresholdPx = GAMEPAD_SNAP_GUIDE_THRESHOLD_DP * density
    val guidePositionTolerancePx = 0.5f
    var bestDxDistance = Float.POSITIVE_INFINITY
    val xGuideCandidates = mutableListOf<Pair<Float, List<GamepadSnapGuide>>>()
    var bestDyDistance = Float.POSITIVE_INFINITY
    val yGuideCandidates = mutableListOf<Pair<Float, List<GamepadSnapGuide>>>()

    fun MutableList<GamepadSnapGuide>.addDistinctGuide(guide: GamepadSnapGuide) {
        if (none {
                it.orientation == guide.orientation &&
                    it.color == guide.color &&
                    abs(it.positionPx - guide.positionPx) <= guidePositionTolerancePx
            }
        ) {
            add(guide)
        }
    }

    fun guidesForDisplay(
        candidates: List<Pair<Float, List<GamepadSnapGuide>>>,
        hasSnap: Boolean
    ): List<GamepadSnapGuide> {
        if (!hasSnap) return emptyList()
        val result = mutableListOf<GamepadSnapGuide>()
        candidates.forEach { (_, guides) ->
            guides.forEach { result.addDistinctGuide(it) }
        }
        return result
    }

    fun considerX(delta: Float, guides: List<GamepadSnapGuide>) {
        val distance = abs(delta)
        if (distance <= thresholdPx) {
            xGuideCandidates += delta to guides
            if (distance < bestDxDistance) {
                bestDxDistance = distance
            }
        }
    }

    fun considerY(delta: Float, guides: List<GamepadSnapGuide>) {
        val distance = abs(delta)
        if (distance <= thresholdPx) {
            yGuideCandidates += delta to guides
            if (distance < bestDyDistance) {
                bestDyDistance = distance
            }
        }
    }

    otherRects.forEach { other ->
        listOf(proposedRect.left, proposedRect.right).forEach { activeEdge ->
            listOf(other.left, other.right).forEach { targetEdge ->
                considerX(
                    targetEdge - activeEdge,
                    listOf(GamepadSnapGuide(GamepadSnapGuideOrientation.Vertical, targetEdge, GamepadSnapGuideYellow))
                )
            }
        }
        listOf(proposedRect.top, proposedRect.bottom).forEach { activeEdge ->
            listOf(other.top, other.bottom).forEach { targetEdge ->
                considerY(
                    targetEdge - activeEdge,
                    listOf(GamepadSnapGuide(GamepadSnapGuideOrientation.Horizontal, targetEdge, GamepadSnapGuideYellow))
                )
            }
        }

        val otherCenterX = other.left + other.width / 2f
        val oppositeSides =
            (proposedCenterX < rootCenterX && otherCenterX > rootCenterX) ||
                (proposedCenterX > rootCenterX && otherCenterX < rootCenterX)
        if (oppositeSides) {
            val mirroredCenterX = rootCenterX * 2f - otherCenterX
            considerX(
                mirroredCenterX - proposedCenterX,
                listOf(
                    GamepadSnapGuide(GamepadSnapGuideOrientation.Vertical, mirroredCenterX, GamepadSnapGuideGreen),
                    GamepadSnapGuide(GamepadSnapGuideOrientation.Vertical, otherCenterX, GamepadSnapGuideGreen)
                )
            )
        }
    }

    considerX(
        rootCenterX - proposedCenterX,
        listOf(GamepadSnapGuide(GamepadSnapGuideOrientation.Vertical, rootCenterX, GamepadSnapGuideRed))
    )
    considerY(
        rootCenterY - proposedCenterY,
        listOf(GamepadSnapGuide(GamepadSnapGuideOrientation.Horizontal, rootCenterY, GamepadSnapGuideRed))
    )

    val guides = guidesForDisplay(
        candidates = xGuideCandidates,
        hasSnap = bestDxDistance != Float.POSITIVE_INFINITY
    ) + guidesForDisplay(
        candidates = yGuideCandidates,
        hasSnap = bestDyDistance != Float.POSITIVE_INFINITY
    )
    return GamepadSnapResult(
        offsetX = proposedOffsetX,
        offsetY = proposedOffsetY,
        guides = guides
    )
}

@Composable
private fun GamepadSnapGuideOverlay(
    guides: List<GamepadSnapGuide>,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val dash = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 5.dp.toPx()), 0f)
        val stroke = 1.dp.toPx()
        guides.forEach { guide ->
            when (guide.orientation) {
                GamepadSnapGuideOrientation.Vertical -> {
                    drawLine(
                        color = guide.color,
                        start = Offset(guide.positionPx, 0f),
                        end = Offset(guide.positionPx, size.height),
                        strokeWidth = stroke,
                        pathEffect = dash
                    )
                }
                GamepadSnapGuideOrientation.Horizontal -> {
                    drawLine(
                        color = guide.color,
                        start = Offset(0f, guide.positionPx),
                        end = Offset(size.width, guide.positionPx),
                        strokeWidth = stroke,
                        pathEffect = dash
                    )
                }
            }
        }
    }
}

@Composable
private fun EditableComponentWrapper(
    controlInstanceId: String? = null,
    isEditMode: Boolean,
    offsetX: Float,
    offsetY: Float,
    scale: Float,
    onOffsetChange: (Float, Float) -> Unit,
    onScaleChange: (Float) -> Unit,
    onTransformChange: ((Float, Float, Float) -> Unit)? = null,
    allowUniformScale: Boolean = true,
    onResizeDragDp: ((Float, Float) -> Unit)? = null,
    editFrameInset: Dp = 0.dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current.density
    val frameInset = editFrameInset.coerceAtLeast(0.dp)
    val frameInsetPx = frameInset.value * density
    val snapController = LocalGamepadSnapController.current
    val deleteController = LocalGamepadControlDeleteController.current
    val selectedInstanceId = controlInstanceId?.takeIf {
        deleteController?.selectedInstanceId == it
    }
    val confirmingInstanceId = controlInstanceId?.takeIf {
        deleteController?.confirmingInstanceId == it
    }
    val isConfirmingDelete = confirmingInstanceId != null
    val componentKey = remember { Any() }
    
    val currentOffsetX by rememberUpdatedState(offsetX)
    val currentOffsetY by rememberUpdatedState(offsetY)
    val currentScale by rememberUpdatedState(scale)
    val currentOnOffsetChange by rememberUpdatedState(onOffsetChange)
    val currentOnScaleChange by rememberUpdatedState(onScaleChange)
    val currentOnTransformChange by rememberUpdatedState(onTransformChange)
    val currentOnResizeDragDp by rememberUpdatedState(onResizeDragDp)
    
    var layoutTopInWindowPx by remember { mutableFloatStateOf(0f) }
    var componentLayoutSizePx by remember { mutableStateOf(IntSize.Zero) }
    var rawGestureOffsetX by remember { mutableFloatStateOf(offsetX) }
    var rawGestureOffsetY by remember { mutableFloatStateOf(offsetY) }
    var lastTransformGestureTime by remember { mutableLongStateOf(0L) }
    val componentVisualSizePx = if (componentLayoutSizePx == IntSize.Zero) {
        IntSize.Zero
    } else {
        IntSize(
            width = (componentLayoutSizePx.width - frameInsetPx * 2f)
                .roundToInt()
                .coerceAtLeast(1),
            height = (componentLayoutSizePx.height - frameInsetPx * 2f)
                .roundToInt()
                .coerceAtLeast(1)
        )
    }
    val currentComponentVisualSizePx by rememberUpdatedState(componentVisualSizePx)
    val displayAxisScales = gamepadAxisScalesForDisplay(scale)
    val editHandleScaleX = 1f / displayAxisScales.scaleX
    val editHandleScaleY = 1f / displayAxisScales.scaleY

    DisposableEffect(componentKey, snapController) {
        onDispose {
            snapController?.componentBounds?.remove(componentKey)
        }
    }

    LaunchedEffect(isEditMode, offsetX, offsetY) {
        if (!isEditMode || System.currentTimeMillis() - lastTransformGestureTime > GAMEPAD_SNAP_GESTURE_RESET_MS) {
            rawGestureOffsetX = offsetX
            rawGestureOffsetY = offsetY
        }
    }
    
    Box(
        modifier = modifier
            .onGloballyPositioned { coordinates ->
                layoutTopInWindowPx = coordinates.positionInWindow().y
            }
            .offset {
                val layoutTopInWindow = layoutTopInWindowPx / density
                val constrainedOffsetY = if (layoutTopInWindow > 0) {
                    val minY = GAMEPAD_EDIT_TOP_MARGIN_DP - layoutTopInWindow
                    offsetY.coerceAtLeast(minY)
                } else {
                    offsetY
                }
                IntOffset((offsetX * density).roundToInt(), (constrainedOffsetY * density).roundToInt())
            }
            .graphicsLayer {
                scaleX = displayAxisScales.scaleX
                scaleY = displayAxisScales.scaleY
            }
            .onGloballyPositioned { coordinates ->
                componentLayoutSizePx = coordinates.size
                val insetX = frameInsetPx.coerceAtMost(coordinates.size.width / 2f)
                val insetY = frameInsetPx.coerceAtMost(coordinates.size.height / 2f)
                val topLeft = coordinates.localToRoot(Offset(insetX, insetY))
                val bottomRight = coordinates.localToRoot(
                    Offset(
                        coordinates.size.width - insetX,
                        coordinates.size.height - insetY
                    )
                )
                snapController?.componentBounds?.set(
                    componentKey,
                    Rect(
                        left = minOf(topLeft.x, bottomRight.x),
                        top = minOf(topLeft.y, bottomRight.y),
                        right = maxOf(topLeft.x, bottomRight.x),
                        bottom = maxOf(topLeft.y, bottomRight.y)
                    )
                )
            },
        contentAlignment = Alignment.Center
    ) {
        content()
        
        if (isEditMode) {
            // 1. Overlay container with border that intercepts gestures for moving and pinch zoom
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .padding(frameInset)
                    .border(
                        width = 1.2.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                        shape = RoundedCornerShape(8.dp)
                    )
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f), shape = RoundedCornerShape(8.dp))
                    .pointerInput(
                        deleteController?.selectedInstanceId,
                        deleteController?.confirmingInstanceId,
                        controlInstanceId
                    ) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            if (deleteController?.confirmingInstanceId != null) {
                                deleteController.onClear()
                            }
                            if (controlInstanceId != null) {
                                deleteController?.onSelect(controlInstanceId)
                            }
                        }
                    }
                    .pointerInput(snapController?.enabled, snapController?.rootSize) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val now = System.currentTimeMillis()
                            if (now - lastTransformGestureTime > GAMEPAD_SNAP_GESTURE_RESET_MS) {
                                rawGestureOffsetX = currentOffsetX
                                rawGestureOffsetY = currentOffsetY
                            }
                            lastTransformGestureTime = now

                            // 1. Update scale via pinch zoom
                            val newScale = if (allowUniformScale) {
                                (currentScale * zoom)
                                    .coerceIn(GAMEPAD_MIN_EDIT_SCALE, GAMEPAD_MAX_EDIT_SCALE)
                            } else {
                                currentScale
                            }
                            
                            // 2. Update offset with scale factor correction and top edge constraint
                            val minY = GAMEPAD_EDIT_TOP_MARGIN_DP - layoutTopInWindowPx / density
                            rawGestureOffsetX += (pan.x * currentScale) / density
                            rawGestureOffsetY = (rawGestureOffsetY + (pan.y * currentScale) / density).coerceAtLeast(minY)
                            val snap = calculateGamepadSnap(
                                componentKey = componentKey,
                                proposedOffsetX = rawGestureOffsetX,
                                proposedOffsetY = rawGestureOffsetY,
                                proposedScale = newScale,
                                currentOffsetX = currentOffsetX,
                                currentOffsetY = currentOffsetY,
                                currentScale = currentScale,
                                density = density,
                                controller = snapController
                            )
                            val continuousOffsetX = rawGestureOffsetX
                            val continuousOffsetY = rawGestureOffsetY.coerceAtLeast(minY)
                            rawGestureOffsetX = continuousOffsetX
                            rawGestureOffsetY = continuousOffsetY
                            val transformChange = currentOnTransformChange
                            if (transformChange != null) {
                                transformChange(continuousOffsetX, continuousOffsetY, newScale)
                            } else {
                                currentOnScaleChange(newScale)
                                currentOnOffsetChange(continuousOffsetX, continuousOffsetY)
                            }
                            snapController?.onGuidesChange(snap.guides)
                        }
                    }
            )

            if (selectedInstanceId != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset(x = frameInset - 6.dp, y = frameInset - 6.dp)
                        .size(22.dp)
                        .graphicsLayer {
                            scaleX = editHandleScaleX
                            scaleY = editHandleScaleY
                        }
                        .gamepadPressScale()
                        .clip(CircleShape)
                        .background(GamepadSnapGuideRed)
                        .clickable {
                            deleteController?.onRequest(selectedInstanceId)
                        }
                        .testTag("gamepad_control_delete_$selectedInstanceId"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete control",
                        tint = Color.White,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }

            if (selectedInstanceId != null) {
                // 2. Drag resize handle in bottom-right corner (Alternative scaling option)
                val resizeHandleModifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 6.dp - frameInset, y = 6.dp - frameInset)
                .size(22.dp)
                .graphicsLayer {
                    scaleX = editHandleScaleX
                    scaleY = editHandleScaleY
                }
                .clip(CircleShape)
                .then(
                    if (confirmingInstanceId != null) {
                        Modifier
                            .gamepadPressScale()
                            .background(Color.White)
                            .border(1.4.dp, GamepadSnapGuideRed, CircleShape)
                            .clickable {
                                deleteController?.onConfirm(confirmingInstanceId)
                            }
                            .testTag("gamepad_control_delete_confirm_$confirmingInstanceId")
                    } else {
                        Modifier
                                .background(MaterialTheme.colorScheme.primary)
                                .pointerInput(snapController?.enabled, snapController?.rootSize) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        val independentResize = currentOnResizeDragDp
                                        if (independentResize != null) {
                                            val resizeSnap = calculateGamepadIndependentResizeDragSnap(
                                                componentKey = componentKey,
                                                dragAmountXDp = dragAmount.x / density,
                                                dragAmountYDp = dragAmount.y / density,
                                                density = density,
                                                controller = snapController
                                            )
                                            independentResize(
                                                dragAmount.x / density,
                                                dragAmount.y / density
                                            )
                                            snapController?.onGuidesChange(resizeSnap.guides)
                                        } else {
                                            val layoutWidthDp = currentComponentVisualSizePx.width / density
                                            val layoutHeightDp = currentComponentVisualSizePx.height / density
                                            val deltaScale = gamepadScaleDeltaForResizeDrag(
                                                dragAmountXpx = dragAmount.x,
                                                dragAmountYpx = dragAmount.y,
                                                layoutWidthDp = layoutWidthDp,
                                                layoutHeightDp = layoutHeightDp,
                                                density = density
                                            )
                                            val newScale = (currentScale + deltaScale)
                                                .coerceIn(GAMEPAD_MIN_EDIT_SCALE, GAMEPAD_MAX_EDIT_SCALE)
                                            val minY = GAMEPAD_EDIT_TOP_MARGIN_DP - layoutTopInWindowPx / density
                                            val anchoredOffset = gamepadOffsetForBottomRightResize(
                                                currentOffsetX = currentOffsetX,
                                                currentOffsetY = currentOffsetY,
                                                layoutWidthDp = layoutWidthDp,
                                                layoutHeightDp = layoutHeightDp,
                                                currentScale = currentScale,
                                                newScale = newScale
                                            )
                                            val anchoredOffsetX = anchoredOffset.first
                                            val anchoredOffsetY = anchoredOffset.second.coerceAtLeast(minY)
                                            val snap = calculateGamepadSnap(
                                                componentKey = componentKey,
                                                proposedOffsetX = anchoredOffsetX,
                                                proposedOffsetY = anchoredOffsetY,
                                                proposedScale = newScale,
                                                currentOffsetX = currentOffsetX,
                                                currentOffsetY = currentOffsetY,
                                                currentScale = currentScale,
                                                density = density,
                                                controller = snapController
                                            )
                                            val continuousOffsetX = anchoredOffsetX
                                            val continuousOffsetY = anchoredOffsetY.coerceAtLeast(minY)
                                            val transformChange = currentOnTransformChange
                                            if (transformChange != null) {
                                                transformChange(continuousOffsetX, continuousOffsetY, newScale)
                                            } else {
                                                currentOnScaleChange(newScale)
                                                currentOnOffsetChange(continuousOffsetX, continuousOffsetY)
                                            }
                                            snapController?.onGuidesChange(snap.guides)
                                        }
                                    }
                                }
                    }
                )
                Box(
                    modifier = resizeHandleModifier,
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isConfirmingDelete) Icons.Default.Delete else Icons.Default.OpenInFull,
                        contentDescription = if (isConfirmingDelete) "Confirm delete control" else "Resize",
                        tint = if (isConfirmingDelete) GamepadSnapGuideRed else Color.White,
                        modifier = Modifier
                            .size(if (isConfirmingDelete) 12.dp else 11.dp)
                            .graphicsLayer {
                                rotationZ = if (isConfirmingDelete) 0f else 90f
                            }
                    )
                }
            }
        }
    }
}
