package com.alw.workstation.bridge.wayland.input

import android.view.MotionEvent

/**
 * Tool types supported by the Wayland tablet-v2 protocol.
 */
enum class StylusToolType(val nativeId: Int) {
    PEN(1),
    ERASER(2),
    BRUSH(3);

    companion object {
        fun fromNativeId(id: Int): StylusToolType = entries.find { it.nativeId == id } ?: PEN
    }
}

/**
 * Standard Linux input event button codes for tablet tools.
 */
object TabletButtons {
    const val BTN_TOOL_PEN = 0x140
    const val BTN_TOOL_RUBBER = 0x141
    const val BTN_TOUCH = 0x14a
    const val BTN_STYLUS = 0x14b
    const val BTN_STYLUS2 = 0x14c
    const val BTN_STYLUS3 = 0x149

    const val MASK_PRIMARY = 1
    const val MASK_STYLUS = 2
    const val MASK_STYLUS2 = 4
}

/**
 * High-precision normalized frame representing a single stylus sample.
 */
data class StylusFrameData(
    val x: Float,
    val y: Float,
    val pressure: Int,            // 0 .. 65535
    val tiltXDeg: Float,          // -90.0 .. +90.0 degrees (+X = right)
    val tiltYDeg: Float,          // -90.0 .. +90.0 degrees (+Y = down)
    val distance: Float,          // Hover distance (0.0 .. 1.0 or mm)
    val rotationDegrees: Float,   // IMU barrel roll (0.0 .. 360.0 degrees)
    val buttonMask: Int,          // Bitmask of active buttons
    val toolType: StylusToolType,
    val isHovering: Boolean,
    val isDown: Boolean,
    val inProximity: Boolean
)

/**
 * Mathematical projection utilities for Android stylus spherical coordinates to Wayland planar angles.
 */
object StylusTiltMath {
    /**
     * Projects spherical altitude (tilt) and azimuth (orientation) to planar tilt angles.
     *
     * Android orientation is clockwise from top (-Y):
     *   phi = 0 is straight up (-Y)
     *   phi = +pi/2 is right (+X)
     *   phi = +-pi is down (+Y)
     *   phi = -pi/2 is left (-X)
     *
     * Wayland zwp_tablet_tool_v2 sign conventions:
     *   +X = tilted towards right
     *   +Y = tilted towards bottom (down)
     */
    fun computePlanarTilt(tiltRad: Double, orientationRad: Double): Pair<Float, Float> {
        val sinTilt = Math.sin(tiltRad)
        val sinOrientation = Math.sin(orientationRad)
        val cosOrientation = Math.cos(orientationRad)

        // tilt_x = arcsin(sin(tilt) * sin(orientation))
        val sinX = (sinTilt * sinOrientation).coerceIn(-1.0, 1.0)
        val tiltXDeg = Math.toDegrees(Math.asin(sinX)).toFloat().coerceIn(-90f, 90f)

        // tilt_y = arcsin(sin(tilt) * -cos(orientation))
        val sinY = (sinTilt * -cosOrientation).coerceIn(-1.0, 1.0)
        val tiltYDeg = Math.toDegrees(Math.asin(sinY)).toFloat().coerceIn(-90f, 90f)

        return Pair(tiltXDeg, tiltYDeg)
    }

    /**
     * Converts normalized pressure (0.0 .. 1.0) to Wayland tablet-v2 integer scale (0 .. 65535).
     */
    fun normalizePressure(pressure: Float): Int {
        return (pressure.coerceIn(0f, 1f) * 65535f).toInt().coerceIn(0, 65535)
    }
}

/**
 * Extensible provider interface allowing standard MotionEvents or custom vendor Pen SDK hooks
 * (such as Xiaomi Smart Pen 2 IMU barrel roll / vendor button events) to supply stylus frames.
 */
interface StylusDataProvider {
    fun parseMotionEvent(event: MotionEvent, surfaceWidth: Int = 0, surfaceHeight: Int = 0): StylusFrameData
}

/**
 * Default implementation extracting high-precision stylus parameters from standard Android MotionEvents.
 */
open class DefaultMotionEventStylusProvider : StylusDataProvider {

    override fun parseMotionEvent(
        event: MotionEvent,
        surfaceWidth: Int,
        surfaceHeight: Int
    ): StylusFrameData {
        val toolType = when (event.getToolType(0)) {
            MotionEvent.TOOL_TYPE_ERASER -> StylusToolType.ERASER
            else -> StylusToolType.PEN
        }

        val action = event.actionMasked
        val isHovering = action == MotionEvent.ACTION_HOVER_MOVE || action == MotionEvent.ACTION_HOVER_ENTER
        val inProximity = action != MotionEvent.ACTION_HOVER_EXIT && action != MotionEvent.ACTION_CANCEL
        val isDown = action == MotionEvent.ACTION_DOWN ||
                (action == MotionEvent.ACTION_MOVE && event.pressure > 0.001f)

        val tiltRad = event.getAxisValue(MotionEvent.AXIS_TILT).toDouble()
        val orientationRad = event.getAxisValue(MotionEvent.AXIS_ORIENTATION).toDouble()
        val (tiltXDeg, tiltYDeg) = StylusTiltMath.computePlanarTilt(tiltRad, orientationRad)

        val pressure = if (isDown) StylusTiltMath.normalizePressure(event.pressure) else 0
        val distance = event.getAxisValue(MotionEvent.AXIS_DISTANCE).coerceAtLeast(0f)

        // Barrel roll from AXIS_GENERIC_1 if supported by hardware digitizer
        val generic1 = event.getAxisValue(MotionEvent.AXIS_GENERIC_1)
        val rotationDeg = if (generic1 != 0f) {
            val rot = Math.toDegrees(generic1.toDouble()).toFloat()
            ((rot % 360f) + 360f) % 360f
        } else {
            0f
        }

        // Button state decoding
        val buttonState = event.buttonState
        var buttonMask = 0
        if (isDown) {
            buttonMask = buttonMask or TabletButtons.MASK_PRIMARY
        }
        if ((buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0 ||
            (buttonState and MotionEvent.BUTTON_SECONDARY) != 0) {
            buttonMask = buttonMask or TabletButtons.MASK_STYLUS
        }
        if ((buttonState and MotionEvent.BUTTON_STYLUS_SECONDARY) != 0 ||
            (buttonState and MotionEvent.BUTTON_TERTIARY) != 0) {
            buttonMask = buttonMask or TabletButtons.MASK_STYLUS2
        }

        return StylusFrameData(
            x = event.x,
            y = event.y,
            pressure = pressure,
            tiltXDeg = tiltXDeg,
            tiltYDeg = tiltYDeg,
            distance = distance,
            rotationDegrees = rotationDeg,
            buttonMask = buttonMask,
            toolType = toolType,
            isHovering = isHovering,
            isDown = isDown,
            inProximity = inProximity
        )
    }
}
