package com.alw.workstation.bridge.wayland

import android.view.MotionEvent
import com.alw.workstation.bridge.wayland.input.DefaultMotionEventStylusProvider
import com.alw.workstation.bridge.wayland.input.StylusToolType
import com.alw.workstation.bridge.wayland.input.TabletButtons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import kotlin.math.PI

class DefaultMotionEventStylusProviderTest {

    private lateinit var provider: DefaultMotionEventStylusProvider
    private lateinit var mockEvent: MotionEvent

    @Before
    fun setUp() {
        provider = DefaultMotionEventStylusProvider()
        mockEvent = mock(MotionEvent::class.java)
    }

    @Test
    fun testParsePenDownWithPressureAndButtons() {
        `when`(mockEvent.getToolType(0)).thenReturn(MotionEvent.TOOL_TYPE_STYLUS)
        `when`(mockEvent.actionMasked).thenReturn(MotionEvent.ACTION_DOWN)
        `when`(mockEvent.x).thenReturn(450f)
        `when`(mockEvent.y).thenReturn(650f)
        `when`(mockEvent.pressure).thenReturn(0.8f)
        `when`(mockEvent.getAxisValue(MotionEvent.AXIS_TILT)).thenReturn(Math.toRadians(30.0).toFloat())
        `when`(mockEvent.getAxisValue(MotionEvent.AXIS_ORIENTATION)).thenReturn((PI / 2.0).toFloat())
        `when`(mockEvent.getAxisValue(MotionEvent.AXIS_DISTANCE)).thenReturn(0f)
        `when`(mockEvent.buttonState).thenReturn(MotionEvent.BUTTON_STYLUS_PRIMARY)

        val frame = provider.parseMotionEvent(mockEvent, 1920, 1080)

        assertEquals(StylusToolType.PEN, frame.toolType)
        assertTrue(frame.isDown)
        assertTrue(frame.inProximity)
        assertEquals(450f, frame.x, 0.001f)
        assertEquals(650f, frame.y, 0.001f)
        assertEquals((0.8f * 65535f).toInt(), frame.pressure)
        assertEquals(30f, frame.tiltXDeg, 0.01f)
        assertEquals(0f, frame.tiltYDeg, 0.01f)
        assertTrue((frame.buttonMask and TabletButtons.MASK_PRIMARY) != 0)
        assertTrue((frame.buttonMask and TabletButtons.MASK_STYLUS) != 0)
    }

    @Test
    fun testParseEraserHoverProximity() {
        `when`(mockEvent.getToolType(0)).thenReturn(MotionEvent.TOOL_TYPE_ERASER)
        `when`(mockEvent.actionMasked).thenReturn(MotionEvent.ACTION_HOVER_MOVE)
        `when`(mockEvent.x).thenReturn(100f)
        `when`(mockEvent.y).thenReturn(200f)
        `when`(mockEvent.pressure).thenReturn(0f)
        `when`(mockEvent.getAxisValue(MotionEvent.AXIS_DISTANCE)).thenReturn(0.25f)

        val frame = provider.parseMotionEvent(mockEvent, 1920, 1080)

        assertEquals(StylusToolType.ERASER, frame.toolType)
        assertFalse(frame.isDown)
        assertTrue(frame.isHovering)
        assertTrue(frame.inProximity)
        assertEquals(0, frame.pressure)
        assertEquals(0.25f, frame.distance, 0.001f)
    }

    @Test
    fun testParseHoverExitProximityOut() {
        `when`(mockEvent.getToolType(0)).thenReturn(MotionEvent.TOOL_TYPE_STYLUS)
        `when`(mockEvent.actionMasked).thenReturn(MotionEvent.ACTION_HOVER_EXIT)

        val frame = provider.parseMotionEvent(mockEvent, 1920, 1080)
        assertFalse(frame.inProximity)
        assertFalse(frame.isDown)
    }
}
