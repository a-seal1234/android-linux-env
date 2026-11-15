package com.alw.workstation.bridge.wayland

import android.view.KeyEvent
import android.view.MotionEvent
import com.alw.workstation.bridge.wayland.input.InputProfile
import com.alw.workstation.bridge.wayland.input.StylusToolType
import com.alw.workstation.bridge.wayland.input.TabletButtons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class InputDispatcherTest {

    private lateinit var serverManager: WaylandServerManager
    private lateinit var dispatcher: InputDispatcher

    @Before
    fun setUp() {
        serverManager = WaylandServerManager()
        dispatcher = InputDispatcher(serverManager = serverManager, rebindCapsToSuper = true)
    }

    @Test
    fun testStylusEventsDispatchedExclusivelyToTabletProtocol() {
        val mockEvent = mock(MotionEvent::class.java)
        `when`(mockEvent.getToolType(0)).thenReturn(MotionEvent.TOOL_TYPE_STYLUS)
        `when`(mockEvent.actionMasked).thenReturn(MotionEvent.ACTION_DOWN)
        `when`(mockEvent.x).thenReturn(300f)
        `when`(mockEvent.y).thenReturn(400f)
        `when`(mockEvent.pressure).thenReturn(0.75f)
        `when`(mockEvent.getAxisValue(MotionEvent.AXIS_DISTANCE)).thenReturn(0f)

        val handled = dispatcher.handleMotionEvent(mockEvent, 1920, 1080)
        assertTrue(handled)

        // Verify tablet state was updated
        val tablet = serverManager.mockTablet
        assertTrue(tablet.isDown)
        assertEquals(300f, tablet.x, 0.001f)
        assertEquals(400f, tablet.y, 0.001f)
        assertEquals((0.75f * 65535f).toInt(), tablet.pressure)
        assertTrue((tablet.buttonMask and TabletButtons.MASK_PRIMARY) != 0)

        // Strict layer separation: verify pointer and touch states received NOTHING
        assertEquals(0f, serverManager.mockPointer.dx, 0.001f)
        assertEquals(0f, serverManager.mockPointer.dy, 0.001f)
        assertEquals(0, serverManager.mockTouchEvents.size)
    }

    @Test
    fun testFingerEventsDispatchedExclusivelyToTouchProtocol() {
        val mockEvent = mock(MotionEvent::class.java)
        `when`(mockEvent.getToolType(0)).thenReturn(MotionEvent.TOOL_TYPE_FINGER)
        `when`(mockEvent.actionMasked).thenReturn(MotionEvent.ACTION_DOWN)
        `when`(mockEvent.actionIndex).thenReturn(0)
        `when`(mockEvent.getPointerId(0)).thenReturn(101)
        `when`(mockEvent.getX(0)).thenReturn(500f)
        `when`(mockEvent.getY(0)).thenReturn(600f)

        val handled = dispatcher.handleMotionEvent(mockEvent, 1920, 1080)
        assertTrue(handled)

        // Verify touch event received
        assertEquals(1, serverManager.mockTouchEvents.size)
        val touchRecord = serverManager.mockTouchEvents[0]
        assertEquals(MotionEvent.ACTION_DOWN, touchRecord.action)
        assertEquals(101, touchRecord.touchId)
        assertEquals(500f, touchRecord.x, 0.001f)
        assertEquals(600f, touchRecord.y, 0.001f)

        // Strict layer separation: tablet and pointer received NOTHING
        assertFalse(serverManager.mockTablet.isDown)
        assertEquals(0, serverManager.mockTablet.pressure)
        assertEquals(0f, serverManager.mockPointer.dx, 0.001f)
    }

    @Test
    fun testMouseEventsDispatchedExclusivelyToPointerProtocol() {
        val mockEvent = mock(MotionEvent::class.java)
        `when`(mockEvent.getToolType(0)).thenReturn(MotionEvent.TOOL_TYPE_MOUSE)
        `when`(mockEvent.getAxisValue(MotionEvent.AXIS_RELATIVE_X)).thenReturn(14.5f)
        `when`(mockEvent.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)).thenReturn(-8.2f)
        `when`(mockEvent.buttonState).thenReturn(MotionEvent.BUTTON_PRIMARY)

        val handled = dispatcher.handleMotionEvent(mockEvent, 1920, 1080)
        assertTrue(handled)

        // Verify relative pointer deltas and buttons received
        assertEquals(14.5f, serverManager.mockPointer.dx, 0.001f)
        assertEquals(-8.2f, serverManager.mockPointer.dy, 0.001f)
        assertEquals(MotionEvent.BUTTON_PRIMARY, serverManager.mockPointer.buttonMask)

        // Strict layer separation: tablet and touch received NOTHING
        assertFalse(serverManager.mockTablet.isDown)
        assertEquals(0, serverManager.mockTouchEvents.size)
    }

    @Test
    fun testKeyboardCapsLockRemappedToSuperKey() {
        val mockEvent = mock(KeyEvent::class.java)
        `when`(mockEvent.scanCode).thenReturn(58) // KEY_CAPSLOCK
        `when`(mockEvent.action).thenReturn(KeyEvent.ACTION_DOWN)

        val handled = dispatcher.handleKeyEvent(mockEvent)
        assertTrue(handled)

        assertEquals(1, serverManager.mockKeyEvents.size)
        val keyRecord = serverManager.mockKeyEvents[0]
        assertEquals(125, keyRecord.scanCode) // Remapped to KEY_LEFTMETA (Super)
        assertTrue(keyRecord.isDown)
    }

    @Test
    fun testKeyboardKeyCodeFallbackWhenScanCodeZero() {
        val mockEvent = mock(KeyEvent::class.java)
        `when`(mockEvent.scanCode).thenReturn(0)
        `when`(mockEvent.keyCode).thenReturn(KeyEvent.KEYCODE_ENTER)
        `when`(mockEvent.action).thenReturn(KeyEvent.ACTION_DOWN)

        val handled = dispatcher.handleKeyEvent(mockEvent)
        assertTrue(handled)

        assertEquals(1, serverManager.mockKeyEvents.size)
        val keyRecord = serverManager.mockKeyEvents[0]
        assertEquals(28, keyRecord.scanCode) // Linux KEY_ENTER = 28
        assertTrue(keyRecord.isDown)
    }

    @Test
    fun testMouseAbsoluteCoordinatesWhenRelativeDeltaZero() {
        val mockEvent = mock(MotionEvent::class.java)
        `when`(mockEvent.getToolType(0)).thenReturn(MotionEvent.TOOL_TYPE_MOUSE)
        `when`(mockEvent.getAxisValue(MotionEvent.AXIS_RELATIVE_X)).thenReturn(0f)
        `when`(mockEvent.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)).thenReturn(0f)
        `when`(mockEvent.x).thenReturn(450f)
        `when`(mockEvent.y).thenReturn(650f)
        `when`(mockEvent.buttonState).thenReturn(MotionEvent.BUTTON_PRIMARY)

        val handled = dispatcher.handleMotionEvent(mockEvent, 1920, 1080)
        assertTrue(handled)

        assertEquals(450f, serverManager.mockPointer.x, 0.001f)
        assertEquals(650f, serverManager.mockPointer.y, 0.001f)
        assertEquals(MotionEvent.BUTTON_PRIMARY, serverManager.mockPointer.buttonMask)
    }

    @Test
    fun testTouchSingleFingerDrivesPointerAbsoluteAndClick() {
        val mockEvent = mock(MotionEvent::class.java)
        `when`(mockEvent.getToolType(0)).thenReturn(MotionEvent.TOOL_TYPE_FINGER)
        `when`(mockEvent.actionMasked).thenReturn(MotionEvent.ACTION_DOWN)
        `when`(mockEvent.actionIndex).thenReturn(0)
        `when`(mockEvent.pointerCount).thenReturn(1)
        `when`(mockEvent.getPointerId(0)).thenReturn(1)
        `when`(mockEvent.getX(0)).thenReturn(320f)
        `when`(mockEvent.getY(0)).thenReturn(240f)

        val handled = dispatcher.handleMotionEvent(mockEvent, 1920, 1080)
        assertTrue(handled)

        assertEquals(320f, serverManager.mockPointer.x, 0.001f)
        assertEquals(240f, serverManager.mockPointer.y, 0.001f)
        assertEquals(1, serverManager.mockPointer.buttonMask)
    }

    @Test
    fun testKeyboardCapsLockNotRemappedWhenDisabled() {
        dispatcher.rebindCapsToSuper = false

        val mockEvent = mock(KeyEvent::class.java)
        `when`(mockEvent.scanCode).thenReturn(58)
        `when`(mockEvent.action).thenReturn(KeyEvent.ACTION_DOWN)

        dispatcher.handleKeyEvent(mockEvent)

        assertEquals(1, serverManager.mockKeyEvents.size)
        val keyRecord = serverManager.mockKeyEvents[0]
        assertEquals(58, keyRecord.scanCode) // Kept original CapsLock
    }

    @Test
    fun testInputProfileSwitching() {
        assertEquals(InputProfile.CREATIVE_PEN, dispatcher.inputProfile)
        dispatcher.inputProfile = InputProfile.GAMING_RAW
        assertEquals(InputProfile.GAMING_RAW, dispatcher.inputProfile)
    }
}
