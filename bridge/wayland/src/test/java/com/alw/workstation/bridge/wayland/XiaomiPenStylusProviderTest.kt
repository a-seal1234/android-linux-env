package com.alw.workstation.bridge.wayland

import android.view.MotionEvent
import com.alw.workstation.bridge.wayland.input.StylusToolType
import com.alw.workstation.bridge.wayland.input.TabletButtons
import com.alw.workstation.bridge.wayland.input.XiaomiPenStylusProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import kotlin.math.pow

class XiaomiPenStylusProviderTest {

    private lateinit var provider: XiaomiPenStylusProvider
    private lateinit var mockEvent: MotionEvent

    @Before
    fun setUp() {
        provider = XiaomiPenStylusProvider(pressureGamma = 0.85f)
        mockEvent = mock(MotionEvent::class.java)
    }

    @Test
    fun testXiaomiGammaPressureCurveCalibration() {
        `when`(mockEvent.getToolType(0)).thenReturn(MotionEvent.TOOL_TYPE_STYLUS)
        `when`(mockEvent.actionMasked).thenReturn(MotionEvent.ACTION_DOWN)
        `when`(mockEvent.x).thenReturn(500f)
        `when`(mockEvent.y).thenReturn(800f)
        `when`(mockEvent.pressure).thenReturn(0.5f)
        `when`(mockEvent.getAxisValue(MotionEvent.AXIS_TILT)).thenReturn(0f)
        `when`(mockEvent.getAxisValue(MotionEvent.AXIS_ORIENTATION)).thenReturn(0f)
        `when`(mockEvent.getAxisValue(MotionEvent.AXIS_DISTANCE)).thenReturn(0f)

        val frame = provider.parseMotionEvent(mockEvent, 3200, 2136)

        assertTrue(frame.isDown)
        val expectedCalibrated = (0.5f.pow(0.85f) * 65535f).toInt()
        assertEquals(expectedCalibrated, frame.pressure)
    }

    @Test
    fun testXiaomiHoverDistanceWithGenericAxisFallback() {
        `when`(mockEvent.getToolType(0)).thenReturn(MotionEvent.TOOL_TYPE_STYLUS)
        `when`(mockEvent.actionMasked).thenReturn(MotionEvent.ACTION_HOVER_MOVE)
        `when`(mockEvent.x).thenReturn(300f)
        `when`(mockEvent.y).thenReturn(400f)
        `when`(mockEvent.pressure).thenReturn(0f)
        `when`(mockEvent.getAxisValue(MotionEvent.AXIS_DISTANCE)).thenReturn(0f)
        // Vendor AXIS_GENERIC_3 reports 0.35f hover distance
        `when`(mockEvent.getAxisValue(MotionEvent.AXIS_GENERIC_3)).thenReturn(0.35f)

        val frame = provider.parseMotionEvent(mockEvent, 3200, 2136)

        assertTrue(frame.isHovering)
        assertFalse(frame.isDown)
        assertEquals(0.35f, frame.distance, 0.001f)
    }

    @Test
    fun testXiaomiImuBarrelRollRotation() {
        `when`(mockEvent.getToolType(0)).thenReturn(MotionEvent.TOOL_TYPE_STYLUS)
        `when`(mockEvent.actionMasked).thenReturn(MotionEvent.ACTION_MOVE)
        `when`(mockEvent.x).thenReturn(100f)
        `when`(mockEvent.y).thenReturn(100f)
        `when`(mockEvent.pressure).thenReturn(0.6f)
        // Vendor AXIS_GENERIC_1 reports 1.57 radians (approx 90 deg)
        `when`(mockEvent.getAxisValue(MotionEvent.AXIS_GENERIC_1)).thenReturn(Math.PI.toFloat() / 2f)

        val frame = provider.parseMotionEvent(mockEvent, 3200, 2136)

        assertEquals(90.0f, frame.rotationDegrees, 0.1f)
    }

    @Test
    fun testXiaomiDualSideButtonsDecoding() {
        `when`(mockEvent.getToolType(0)).thenReturn(MotionEvent.TOOL_TYPE_STYLUS)
        `when`(mockEvent.actionMasked).thenReturn(MotionEvent.ACTION_DOWN)
        `when`(mockEvent.x).thenReturn(200f)
        `when`(mockEvent.y).thenReturn(200f)
        `when`(mockEvent.pressure).thenReturn(0.7f)
        // Both stylus primary (writing) and stylus secondary (screenshot) pressed
        `when`(mockEvent.buttonState).thenReturn(
            MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY
        )

        val frame = provider.parseMotionEvent(mockEvent, 3200, 2136)

        assertTrue(frame.isDown)
        assertTrue((frame.buttonMask and TabletButtons.MASK_PRIMARY) != 0)
        assertTrue((frame.buttonMask and TabletButtons.MASK_STYLUS) != 0)
        assertTrue((frame.buttonMask and TabletButtons.MASK_STYLUS2) != 0)
    }

    @Test
    fun testXiaomiAuxiliaryButtonGeneric4Mask() {
        `when`(mockEvent.getToolType(0)).thenReturn(MotionEvent.TOOL_TYPE_STYLUS)
        `when`(mockEvent.actionMasked).thenReturn(MotionEvent.ACTION_HOVER_MOVE)
        `when`(mockEvent.getAxisValue(MotionEvent.AXIS_GENERIC_4)).thenReturn(2.0f) // bit 1: STYLUS2

        val frame = provider.parseMotionEvent(mockEvent, 3200, 2136)

        assertTrue((frame.buttonMask and TabletButtons.MASK_STYLUS2) != 0)
    }
}
