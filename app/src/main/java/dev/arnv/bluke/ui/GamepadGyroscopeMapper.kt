package dev.arnv.bluke.ui

import android.view.Surface
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.sqrt

internal const val GAMEPAD_GYRO_MIN_SENSITIVITY = 0.1f
internal const val GAMEPAD_GYRO_MAX_SENSITIVITY = 3f
internal const val GAMEPAD_GYRO_DEFAULT_SENSITIVITY = 0.5f
internal const val GAMEPAD_GYRO_DEFAULT_INVERT_HORIZONTAL = true
internal const val GAMEPAD_GYRO_DEFAULT_INVERT_VERTICAL = true

private const val GAMEPAD_GYRO_FULL_STICK_RADIANS_PER_SECOND = PI.toFloat()
private const val GAMEPAD_GYRO_FULL_STICK_TILT_RADIANS = PI.toFloat() / 8f

internal enum class GamepadGyroscopeMode(val preferenceValue: Int) {
    ANGULAR_VELOCITY(0),
    TILT(1);

    companion object {
        fun fromPreference(value: Int): GamepadGyroscopeMode =
            entries.firstOrNull { it.preferenceValue == value } ?: ANGULAR_VELOCITY
    }
}

internal enum class GamepadGyroJitterSuppression(
    val preferenceValue: Int,
    val smoothingTimeSeconds: Float
) {
    NONE(0, 0f),
    LOW(1, 0.012f),
    MEDIUM(2, 0.025f),
    HIGH(3, 0.05f);

    companion object {
        fun fromPreference(value: Int): GamepadGyroJitterSuppression =
            entries.firstOrNull { it.preferenceValue == value }
                ?: GAMEPAD_GYRO_DEFAULT_JITTER_SUPPRESSION
    }
}

internal val GAMEPAD_GYRO_DEFAULT_JITTER_SUPPRESSION = GamepadGyroJitterSuppression.MEDIUM

internal data class GamepadGyroMappingSettings(
    val sensitivity: Float = GAMEPAD_GYRO_DEFAULT_SENSITIVITY,
    val invertHorizontal: Boolean = GAMEPAD_GYRO_DEFAULT_INVERT_HORIZONTAL,
    val invertVertical: Boolean = GAMEPAD_GYRO_DEFAULT_INVERT_VERTICAL,
    val jitterSuppression: GamepadGyroJitterSuppression =
        GAMEPAD_GYRO_DEFAULT_JITTER_SUPPRESSION
)

/**
 * Converts Android's device-relative gyroscope axes to axes relative to the visible screen.
 * The returned pair is rotation around screen X followed by rotation around screen Y.
 */
internal fun remapGamepadGyroscopeToScreen(
    deviceX: Float,
    deviceY: Float,
    screenRotation: Int
): Pair<Float, Float> = when (screenRotation) {
    Surface.ROTATION_90 -> -deviceY to deviceX
    Surface.ROTATION_180 -> -deviceX to -deviceY
    Surface.ROTATION_270 -> deviceY to -deviceX
    else -> deviceX to deviceY
}

internal fun combineGamepadStickAndGyroscope(
    stickX: Float,
    stickY: Float,
    gyroX: Float,
    gyroY: Float
): Pair<Float, Float> =
    (stickX + gyroX).coerceIn(-1f, 1f) to
        (stickY + gyroY).coerceIn(-1f, 1f)

internal class GamepadGyroscopeProcessor {
    private var lastTimestampNanos = 0L
    private var filteredScreenX = 0f
    private var filteredScreenY = 0f

    fun process(
        deviceX: Float,
        deviceY: Float,
        timestampNanos: Long,
        screenRotation: Int,
        settings: GamepadGyroMappingSettings
    ): Pair<Float, Float> {
        val (screenX, screenY) = remapGamepadGyroscopeToScreen(
            deviceX = deviceX,
            deviceY = deviceY,
            screenRotation = screenRotation
        )
        val smoothingTime = settings.jitterSuppression.smoothingTimeSeconds
        val alpha = when {
            smoothingTime <= 0f || lastTimestampNanos == 0L -> 1f
            timestampNanos <= lastTimestampNanos -> 1f
            else -> {
                val elapsedSeconds =
                    ((timestampNanos - lastTimestampNanos) / 1_000_000_000f).coerceIn(0f, 0.1f)
                (1f - exp(-elapsedSeconds / smoothingTime)).coerceIn(0f, 1f)
            }
        }
        filteredScreenX += (screenX - filteredScreenX) * alpha
        filteredScreenY += (screenY - filteredScreenY) * alpha
        lastTimestampNanos = timestampNanos

        val sensitivity = settings.sensitivity.coerceIn(
            GAMEPAD_GYRO_MIN_SENSITIVITY,
            GAMEPAD_GYRO_MAX_SENSITIVITY
        )
        var outputX = filteredScreenY / GAMEPAD_GYRO_FULL_STICK_RADIANS_PER_SECOND * sensitivity
        var outputY = filteredScreenX / GAMEPAD_GYRO_FULL_STICK_RADIANS_PER_SECOND * sensitivity
        if (settings.invertHorizontal) outputX = -outputX
        if (settings.invertVertical) outputY = -outputY
        return outputX.coerceIn(-1f, 1f) to outputY.coerceIn(-1f, 1f)
    }

    fun reset() {
        lastTimestampNanos = 0L
        filteredScreenX = 0f
        filteredScreenY = 0f
    }
}

private data class GamepadOrientationQuaternion(
    val w: Float,
    val x: Float,
    val y: Float,
    val z: Float
) {
    fun conjugate() = GamepadOrientationQuaternion(w, -x, -y, -z)

    operator fun times(other: GamepadOrientationQuaternion) = GamepadOrientationQuaternion(
        w = w * other.w - x * other.x - y * other.y - z * other.z,
        x = w * other.x + x * other.w + y * other.z - z * other.y,
        y = w * other.y - x * other.z + y * other.w + z * other.x,
        z = w * other.z + x * other.y - y * other.x + z * other.w
    )

    fun normalized(): GamepadOrientationQuaternion? {
        val magnitude = sqrt(w * w + x * x + y * y + z * z)
        if (!magnitude.isFinite() || magnitude < 0.000001f) return null
        return GamepadOrientationQuaternion(
            w = w / magnitude,
            x = x / magnitude,
            y = y / magnitude,
            z = z / magnitude
        )
    }
}

private fun gamepadRotationVectorQuaternion(
    rotationVector: FloatArray
): GamepadOrientationQuaternion? {
    if (rotationVector.size < 3) return null
    val x = rotationVector[0]
    val y = rotationVector[1]
    val z = rotationVector[2]
    val w = if (rotationVector.size >= 4) {
        rotationVector[3]
    } else {
        sqrt((1f - x * x - y * y - z * z).coerceAtLeast(0f))
    }
    if (!w.isFinite() || !x.isFinite() || !y.isFinite() || !z.isFinite()) return null
    return GamepadOrientationQuaternion(w, x, y, z).normalized()
}

private fun GamepadOrientationQuaternion.shortestRotationVector(): Triple<Float, Float, Float> {
    val shortest = if (w < 0f) {
        GamepadOrientationQuaternion(-w, -x, -y, -z)
    } else {
        this
    }
    val vectorMagnitude = sqrt(
        shortest.x * shortest.x + shortest.y * shortest.y + shortest.z * shortest.z
    )
    if (vectorMagnitude < 0.000001f) return Triple(0f, 0f, 0f)
    val angle = 2f * atan2(vectorMagnitude, shortest.w.coerceAtLeast(0f))
    val scale = angle / vectorMagnitude
    return Triple(shortest.x * scale, shortest.y * scale, shortest.z * scale)
}

/**
 * Maps the device's displacement from the pose captured by the first sample to a stick position.
 * Unlike angular-velocity mode, the output remains displaced while the phone holds its tilt.
 */
internal class GamepadTiltProcessor {
    private var referenceOrientation: GamepadOrientationQuaternion? = null
    private var lastTimestampNanos = 0L
    private var filteredOutputX = 0f
    private var filteredOutputY = 0f

    fun calibrate(rotationVector: FloatArray): Boolean {
        val orientation = gamepadRotationVectorQuaternion(rotationVector) ?: return false
        referenceOrientation = orientation
        resetOutput()
        return true
    }

    fun process(
        rotationVector: FloatArray,
        timestampNanos: Long,
        screenRotation: Int,
        settings: GamepadGyroMappingSettings
    ): Pair<Float, Float> {
        val orientation = gamepadRotationVectorQuaternion(rotationVector) ?: return 0f to 0f
        val reference = referenceOrientation
        if (reference == null) {
            calibrate(rotationVector)
            lastTimestampNanos = timestampNanos
            return 0f to 0f
        }

        val relativeOrientation = (reference.conjugate() * orientation).normalized()
            ?: return 0f to 0f
        val (deviceTiltX, deviceTiltY, deviceTiltZ) =
            relativeOrientation.shortestRotationVector()
        val (screenTiltX, _) = remapGamepadGyroscopeToScreen(
            deviceX = deviceTiltX,
            deviceY = deviceTiltY,
            screenRotation = screenRotation
        )
        val sensitivity = settings.sensitivity.coerceIn(
            GAMEPAD_GYRO_MIN_SENSITIVITY,
            GAMEPAD_GYRO_MAX_SENSITIVITY
        )
        // Roll around the screen normal feels like a steering wheel; screen X supplies pitch.
        var targetX = deviceTiltZ / GAMEPAD_GYRO_FULL_STICK_TILT_RADIANS * sensitivity
        var targetY = screenTiltX / GAMEPAD_GYRO_FULL_STICK_TILT_RADIANS * sensitivity
        if (settings.invertHorizontal) targetX = -targetX
        if (settings.invertVertical) targetY = -targetY
        targetX = targetX.coerceIn(-1f, 1f)
        targetY = targetY.coerceIn(-1f, 1f)

        val smoothingTime = settings.jitterSuppression.smoothingTimeSeconds
        val alpha = when {
            smoothingTime <= 0f || lastTimestampNanos == 0L -> 1f
            timestampNanos <= lastTimestampNanos -> 1f
            else -> {
                val elapsedSeconds =
                    ((timestampNanos - lastTimestampNanos) / 1_000_000_000f).coerceIn(0f, 0.1f)
                (1f - exp(-elapsedSeconds / smoothingTime)).coerceIn(0f, 1f)
            }
        }
        filteredOutputX += (targetX - filteredOutputX) * alpha
        filteredOutputY += (targetY - filteredOutputY) * alpha
        lastTimestampNanos = timestampNanos
        return filteredOutputX.coerceIn(-1f, 1f) to filteredOutputY.coerceIn(-1f, 1f)
    }

    fun resetOutput() {
        lastTimestampNanos = 0L
        filteredOutputX = 0f
        filteredOutputY = 0f
    }

    fun clearCalibration() {
        referenceOrientation = null
        resetOutput()
    }

    fun reset() = clearCalibration()
}
