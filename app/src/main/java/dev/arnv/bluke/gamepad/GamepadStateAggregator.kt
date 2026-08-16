package dev.arnv.bluke.gamepad

import kotlin.math.sqrt

enum class GamepadStick {
    LEFT,
    RIGHT
}

enum class GamepadTrigger {
    LEFT,
    RIGHT
}

private data class StickValue(val x: Float, val y: Float)

/**
 * Pure Kotlin semantic input aggregator. It deliberately knows nothing about report ids,
 * descriptor bits, or output profiles.
 */
class GamepadStateAggregator {
    private val pressedButtonSources = mutableMapOf<GamepadButton, MutableSet<String>>()
    private val latchedButtons = mutableSetOf<GamepadButton>()
    private val dpadSources = mutableMapOf<String, DpadDirection>()
    private val leftStickSources = mutableMapOf<String, StickValue>()
    private val rightStickSources = mutableMapOf<String, StickValue>()
    private val leftTriggerSources = mutableMapOf<String, Float>()
    private val rightTriggerSources = mutableMapOf<String, Float>()
    private var gyroscopeTarget: GamepadStick? = null
    private var gyroscope = StickValue(0f, 0f)

    fun pressButton(sourceId: String, button: GamepadButton) {
        pressedButtonSources.getOrPut(button) { mutableSetOf() }.add(sourceId)
    }

    fun releaseButton(sourceId: String, button: GamepadButton) {
        val sources = pressedButtonSources[button] ?: return
        sources.remove(sourceId)
        if (sources.isEmpty()) pressedButtonSources.remove(button)
    }

    fun setLatched(button: GamepadButton, enabled: Boolean) {
        if (enabled) latchedButtons.add(button) else latchedButtons.remove(button)
    }

    fun setDpadSource(sourceId: String, direction: DpadDirection) {
        if (direction == DpadDirection.NEUTRAL) {
            dpadSources.remove(sourceId)
        } else {
            dpadSources[sourceId] = direction
        }
    }

    fun setStickSource(sourceId: String, stick: GamepadStick, x: Float, y: Float) {
        val target = if (stick == GamepadStick.LEFT) leftStickSources else rightStickSources
        target[sourceId] = StickValue(x.coerceIn(-1f, 1f), y.coerceIn(-1f, 1f))
    }

    fun clearStickSource(sourceId: String, stick: GamepadStick) {
        val target = if (stick == GamepadStick.LEFT) leftStickSources else rightStickSources
        target.remove(sourceId)
    }

    fun setTriggerSource(sourceId: String, trigger: GamepadTrigger, value: Float) {
        val target = if (trigger == GamepadTrigger.LEFT) leftTriggerSources else rightTriggerSources
        val clamped = value.coerceIn(0f, 1f)
        if (clamped == 0f) target.remove(sourceId) else target[sourceId] = clamped
    }

    fun setGyroscopeContribution(target: GamepadStick?, x: Float, y: Float) {
        gyroscopeTarget = target
        gyroscope = if (target == null) {
            StickValue(0f, 0f)
        } else {
            StickValue(x.coerceIn(-1f, 1f), y.coerceIn(-1f, 1f))
        }
    }

    fun clearTransientSources() {
        pressedButtonSources.clear()
        dpadSources.clear()
        leftStickSources.clear()
        rightStickSources.clear()
        leftTriggerSources.clear()
        rightTriggerSources.clear()
        setGyroscopeContribution(null, 0f, 0f)
    }

    fun reset() {
        clearTransientSources()
        latchedButtons.clear()
    }

    fun currentState(): GamepadState {
        val heldButtons = buildSet {
            addAll(latchedButtons)
            pressedButtonSources.forEach { (button, sources) ->
                if (sources.isNotEmpty()) add(button)
            }
        }
        val left = combinedStick(leftStickSources, GamepadStick.LEFT)
        val right = combinedStick(rightStickSources, GamepadStick.RIGHT)
        return GamepadState(
            buttons = heldButtons,
            dpad = aggregateDpad(),
            leftX = left.x,
            leftY = left.y,
            rightX = right.x,
            rightY = right.y,
            leftTrigger = leftTriggerSources.values.maxOrNull() ?: 0f,
            rightTrigger = rightTriggerSources.values.maxOrNull() ?: 0f
        )
    }

    private fun aggregateDpad(): DpadDirection {
        val horizontal = dpadSources.values.sumOf { it.horizontal }.coerceIn(-1, 1)
        val vertical = dpadSources.values.sumOf { it.vertical }.coerceIn(-1, 1)
        return DpadDirection.fromVector(horizontal, vertical)
    }

    private fun combinedStick(
        sources: Map<String, StickValue>,
        stick: GamepadStick
    ): StickValue {
        val touch = averageAnalogSources(sources.values)
        val gyro = if (gyroscopeTarget == stick) gyroscope else StickValue(0f, 0f)
        return StickValue(
            x = (touch.x + gyro.x).coerceIn(-1f, 1f),
            y = (touch.y + gyro.y).coerceIn(-1f, 1f)
        )
    }

    private fun averageAnalogSources(sources: Collection<StickValue>): StickValue {
        var directionX = 0f
        var directionY = 0f
        var magnitudeSum = 0f
        var inputCount = 0
        sources.forEach { source ->
            val magnitude = sqrt(source.x * source.x + source.y * source.y)
            if (magnitude > 0f) {
                directionX += source.x / magnitude
                directionY += source.y / magnitude
                magnitudeSum += magnitude
                inputCount++
            }
        }
        if (inputCount == 0) return StickValue(0f, 0f)
        val directionMagnitude = sqrt(directionX * directionX + directionY * directionY)
        if (directionMagnitude <= 0.000001f) return StickValue(0f, 0f)
        val averageMagnitude = magnitudeSum / inputCount
        return StickValue(
            x = directionX / directionMagnitude * averageMagnitude,
            y = directionY / directionMagnitude * averageMagnitude
        )
    }
}
