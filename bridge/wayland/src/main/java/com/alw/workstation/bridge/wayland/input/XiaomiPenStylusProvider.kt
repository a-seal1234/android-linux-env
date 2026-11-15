package com.alw.workstation.bridge.wayland.input

import android.view.MotionEvent
import kotlin.math.pow

/**
 * High-precision Xiaomi Smart Pen (1 & 2) hardware stylus provider.
 * Extends [DefaultMotionEventStylusProvider] with:
 * 1. Xiaomi Pen SDK vendor gamma pressure curve calibration (gamma = 0.85) for responsive light strokes.
 * 2. Z-height hover distance reading from AXIS_DISTANCE and AXIS_GENERIC_3.
 * 3. 6-axis IMU barrel roll / rotation parsing from AXIS_GENERIC_1 with orientation fallback.
 * 4. Xiaomi dual side-button event decoding (Writing button = PRIMARY, Screenshot button = SECONDARY).
 */
class XiaomiPenStylusProvider(
    private val pressureGamma: Float = 0.85f
) : DefaultMotionEventStylusProvider() {

    override fun parseMotionEvent(
        event: MotionEvent,
        surfaceWidth: Int,
        surfaceHeight: Int
    ): StylusFrameData {
        val baseFrame = super.parseMotionEvent(event, surfaceWidth, surfaceHeight)

        // 1. Xiaomi gamma pressure curve calibration
        val calibratedPressure = if (baseFrame.isDown && event.pressure > 0.001f) {
            val normalized = event.pressure.coerceIn(0f, 1f)
            val curved = normalized.pow(pressureGamma).coerceIn(0f, 1f)
            (curved * 65535f).toInt().coerceIn(0, 65535)
        } else {
            0
        }

        // 2. High-precision hover distance (AXIS_DISTANCE with AXIS_GENERIC_3 fallback)
        var hoverDist = baseFrame.distance
        if (hoverDist <= 0.001f) {
            val generic3 = event.getAxisValue(MotionEvent.AXIS_GENERIC_3)
            if (generic3 > 0.001f) {
                hoverDist = generic3.coerceAtLeast(0f)
            }
        }

        // 3. 6-axis IMU barrel roll / rotation parsing
        var rotationDeg = baseFrame.rotationDegrees
        if (rotationDeg == 0f) {
            val generic1 = event.getAxisValue(MotionEvent.AXIS_GENERIC_1)
            if (generic1 != 0f) {
                val rot = Math.toDegrees(generic1.toDouble()).toFloat()
                rotationDeg = ((rot % 360f) + 360f) % 360f
            }
        }

        // 4. Xiaomi Pen side button decoding
        var buttonMask = baseFrame.buttonMask
        val buttonState = event.buttonState

        // Lower button (Primary Stylus / Writing)
        if ((buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0 ||
            (buttonState and MotionEvent.BUTTON_SECONDARY) != 0) {
            buttonMask = buttonMask or TabletButtons.MASK_STYLUS
        }

        // Upper button (Secondary Stylus / Screenshot / Eraser toggle)
        if ((buttonState and MotionEvent.BUTTON_STYLUS_SECONDARY) != 0 ||
            (buttonState and MotionEvent.BUTTON_TERTIARY) != 0) {
            buttonMask = buttonMask or TabletButtons.MASK_STYLUS2
        }

        // Vendor axis 4 auxiliary button mask (if Xiaomi digitizer reports auxiliary flags)
        val generic4 = event.getAxisValue(MotionEvent.AXIS_GENERIC_4).toInt()
        if ((generic4 and 0x01) != 0) {
            buttonMask = buttonMask or TabletButtons.MASK_STYLUS
        }
        if ((generic4 and 0x02) != 0) {
            buttonMask = buttonMask or TabletButtons.MASK_STYLUS2
        }

        return baseFrame.copy(
            pressure = calibratedPressure,
            distance = hoverDist,
            rotationDegrees = rotationDeg,
            buttonMask = buttonMask
        )
    }
}
