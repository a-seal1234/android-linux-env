package com.alw.workstation.bridge.wayland

import android.view.KeyEvent
import android.view.MotionEvent
import com.alw.workstation.bridge.wayland.input.EvdevKeyMap
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

    @Test
    fun testEvdevTableCoversFunctionRowNavigationAndSystemKeys() {
        val map = EvdevKeyMap.KEYCODE_TO_EVDEV
        // F-row (TASK-027: F1-F10 = 59-68, F11/F12 = 87/88)
        assertEquals(59, map[KeyEvent.KEYCODE_F1])
        assertEquals(60, map[KeyEvent.KEYCODE_F2])
        assertEquals(61, map[KeyEvent.KEYCODE_F3])
        assertEquals(62, map[KeyEvent.KEYCODE_F4])
        assertEquals(63, map[KeyEvent.KEYCODE_F5])
        assertEquals(64, map[KeyEvent.KEYCODE_F6])
        assertEquals(65, map[KeyEvent.KEYCODE_F7])
        assertEquals(66, map[KeyEvent.KEYCODE_F8])
        assertEquals(67, map[KeyEvent.KEYCODE_F9])
        assertEquals(68, map[KeyEvent.KEYCODE_F10])
        assertEquals(87, map[KeyEvent.KEYCODE_F11])
        assertEquals(88, map[KeyEvent.KEYCODE_F12])
        // Navigation
        assertEquals(102, map[KeyEvent.KEYCODE_MOVE_HOME])
        assertEquals(107, map[KeyEvent.KEYCODE_MOVE_END])
        assertEquals(104, map[KeyEvent.KEYCODE_PAGE_UP])
        assertEquals(109, map[KeyEvent.KEYCODE_PAGE_DOWN])
        assertEquals(110, map[KeyEvent.KEYCODE_INSERT])
        assertEquals(111, map[KeyEvent.KEYCODE_FORWARD_DEL])
        // SysRq / ScrollLock / Pause / Menu-Compose
        assertEquals(99, map[KeyEvent.KEYCODE_SYSRQ])
        assertEquals(70, map[KeyEvent.KEYCODE_SCROLL_LOCK])
        assertEquals(119, map[KeyEvent.KEYCODE_BREAK])
        assertEquals(127, map[KeyEvent.KEYCODE_MENU])
    }

    @Test
    fun testEvdevTableCoversCompleteNumpadBlock() {
        val map = EvdevKeyMap.KEYCODE_TO_EVDEV
        assertEquals(82, map[KeyEvent.KEYCODE_NUMPAD_0])
        assertEquals(79, map[KeyEvent.KEYCODE_NUMPAD_1])
        assertEquals(80, map[KeyEvent.KEYCODE_NUMPAD_2])
        assertEquals(81, map[KeyEvent.KEYCODE_NUMPAD_3])
        assertEquals(75, map[KeyEvent.KEYCODE_NUMPAD_4])
        assertEquals(76, map[KeyEvent.KEYCODE_NUMPAD_5])
        assertEquals(77, map[KeyEvent.KEYCODE_NUMPAD_6])
        assertEquals(71, map[KeyEvent.KEYCODE_NUMPAD_7])
        assertEquals(72, map[KeyEvent.KEYCODE_NUMPAD_8])
        assertEquals(73, map[KeyEvent.KEYCODE_NUMPAD_9])
        assertEquals(83, map[KeyEvent.KEYCODE_NUMPAD_DOT])
        assertEquals(96, map[KeyEvent.KEYCODE_NUMPAD_ENTER])
        assertEquals(78, map[KeyEvent.KEYCODE_NUMPAD_ADD])
        assertEquals(74, map[KeyEvent.KEYCODE_NUMPAD_SUBTRACT])
        assertEquals(55, map[KeyEvent.KEYCODE_NUMPAD_MULTIPLY])
        assertEquals(98, map[KeyEvent.KEYCODE_NUMPAD_DIVIDE])
        assertEquals(69, map[KeyEvent.KEYCODE_NUM_LOCK])
    }

    @Test
    fun testEvdevTableCoversModifiersAlphabetDigitsAndIso102nd() {
        val map = EvdevKeyMap.KEYCODE_TO_EVDEV
        // Modifiers (both sides)
        assertEquals(42, map[KeyEvent.KEYCODE_SHIFT_LEFT])
        assertEquals(54, map[KeyEvent.KEYCODE_SHIFT_RIGHT])
        assertEquals(29, map[KeyEvent.KEYCODE_CTRL_LEFT])
        assertEquals(97, map[KeyEvent.KEYCODE_CTRL_RIGHT])
        assertEquals(56, map[KeyEvent.KEYCODE_ALT_LEFT])
        assertEquals(100, map[KeyEvent.KEYCODE_ALT_RIGHT])
        assertEquals(125, map[KeyEvent.KEYCODE_META_LEFT])
        assertEquals(126, map[KeyEvent.KEYCODE_META_RIGHT])
        assertEquals(58, map[KeyEvent.KEYCODE_CAPS_LOCK])
        // ISO 102nd key
        assertEquals(86, map[KeyEvent.KEYCODE_POUND])
        // Core letters
        val letters = mapOf(
            KeyEvent.KEYCODE_A to 30, KeyEvent.KEYCODE_B to 48, KeyEvent.KEYCODE_C to 46,
            KeyEvent.KEYCODE_D to 32, KeyEvent.KEYCODE_E to 18, KeyEvent.KEYCODE_F to 33,
            KeyEvent.KEYCODE_G to 34, KeyEvent.KEYCODE_H to 35, KeyEvent.KEYCODE_I to 23,
            KeyEvent.KEYCODE_J to 36, KeyEvent.KEYCODE_K to 37, KeyEvent.KEYCODE_L to 38,
            KeyEvent.KEYCODE_M to 50, KeyEvent.KEYCODE_N to 49, KeyEvent.KEYCODE_O to 24,
            KeyEvent.KEYCODE_P to 25, KeyEvent.KEYCODE_Q to 16, KeyEvent.KEYCODE_R to 19,
            KeyEvent.KEYCODE_S to 31, KeyEvent.KEYCODE_T to 20, KeyEvent.KEYCODE_U to 22,
            KeyEvent.KEYCODE_V to 47, KeyEvent.KEYCODE_W to 17, KeyEvent.KEYCODE_X to 45,
            KeyEvent.KEYCODE_Y to 21, KeyEvent.KEYCODE_Z to 44
        )
        for ((keyCode, evdev) in letters) {
            assertEquals("KEYCODE $keyCode", evdev, map[keyCode])
        }
        // Digits
        assertEquals(11, map[KeyEvent.KEYCODE_0])
        assertEquals(2, map[KeyEvent.KEYCODE_1])
        assertEquals(10, map[KeyEvent.KEYCODE_9])
        // Controls
        assertEquals(1, map[KeyEvent.KEYCODE_ESCAPE])
        assertEquals(1, map[KeyEvent.KEYCODE_BACK])
        assertEquals(28, map[KeyEvent.KEYCODE_ENTER])
        assertEquals(15, map[KeyEvent.KEYCODE_TAB])
        assertEquals(57, map[KeyEvent.KEYCODE_SPACE])
        assertEquals(14, map[KeyEvent.KEYCODE_DEL])
    }

    @Test
    fun testKeyboardFallbackUsesEvdevTableForFunctionKeys() {
        val mockEvent = mock(KeyEvent::class.java)
        `when`(mockEvent.scanCode).thenReturn(0)
        `when`(mockEvent.keyCode).thenReturn(KeyEvent.KEYCODE_F5)
        `when`(mockEvent.action).thenReturn(KeyEvent.ACTION_DOWN)

        val handled = dispatcher.handleKeyEvent(mockEvent)

        assertTrue(handled)
        assertEquals(1, serverManager.mockKeyEvents.size)
        assertEquals(63, serverManager.mockKeyEvents[0].scanCode)
    }

    @Test
    fun testKeyboardFallbackUsesEvdevTableForNavigationKeys() {
        val mockEvent = mock(KeyEvent::class.java)
        `when`(mockEvent.scanCode).thenReturn(0)
        `when`(mockEvent.keyCode).thenReturn(KeyEvent.KEYCODE_PAGE_DOWN)
        `when`(mockEvent.action).thenReturn(KeyEvent.ACTION_DOWN)

        val handled = dispatcher.handleKeyEvent(mockEvent)

        assertTrue(handled)
        assertEquals(1, serverManager.mockKeyEvents.size)
        assertEquals(109, serverManager.mockKeyEvents[0].scanCode)
    }
}
