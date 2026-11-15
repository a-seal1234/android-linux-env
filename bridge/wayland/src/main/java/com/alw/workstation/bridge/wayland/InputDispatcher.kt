package com.alw.workstation.bridge.wayland

import android.view.KeyEvent
import android.view.MotionEvent
import com.alw.workstation.bridge.wayland.input.DefaultMotionEventStylusProvider
import com.alw.workstation.bridge.wayland.input.InputProfile
import com.alw.workstation.bridge.wayland.input.StylusDataProvider
import com.alw.workstation.bridge.wayland.input.XiaomiPenStylusProvider

/**
 * Normalizes Android hardware input to Linux Wayland protocols.
 * Decouples input into three dedicated layers:
 * 1. Tablet-v2 (zwp_tablet_tool_v2) for high-precision stylus & eraser.
 * 2. Relative Pointer (wl_pointer) for desktop mouse capture.
 * 3. Touch (wl_touch) for finger touch gestures.
 *
 * Remaps physical scancodes (e.g. CapsLock -> Super) before guest dispatch.
 */
class InputDispatcher(
    val serverManager: WaylandServerManager,
    var stylusProvider: StylusDataProvider = DefaultMotionEventStylusProvider(),
    var inputProfile: InputProfile = InputProfile.CREATIVE_PEN,
    var rebindCapsToSuper: Boolean = true
) {
    private var lastStylusButtonMask = 0
    private var lastPointerButtonMask = 0
    private var lastTabletInProximity = false

    fun handleMotionEvent(
        event: MotionEvent,
        surfaceWidth: Int = 0,
        surfaceHeight: Int = 0
    ): Boolean {
        val toolType = event.getToolType(0)

        return when (toolType) {
            MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER -> {
                dispatchStylusEvent(event, surfaceWidth, surfaceHeight)
                true
            }
            MotionEvent.TOOL_TYPE_FINGER -> {
                dispatchTouchEvent(event)
                true
            }
            MotionEvent.TOOL_TYPE_MOUSE -> {
                dispatchMouseEvent(event)
                true
            }
            else -> {
                // Default fallback for unidentified tools
                if (event.isFromSource(android.view.InputDevice.SOURCE_MOUSE)) {
                    dispatchMouseEvent(event)
                    true
                } else {
                    false
                }
            }
        }
    }

    private fun dispatchStylusEvent(
        event: MotionEvent,
        surfaceWidth: Int,
        surfaceHeight: Int
    ) {
        val frame = stylusProvider.parseMotionEvent(event, surfaceWidth, surfaceHeight)

        // Proximity notifications
        if (frame.inProximity != lastTabletInProximity) {
            serverManager.sendTabletProximity(frame.toolType.nativeId, frame.inProximity)
            lastTabletInProximity = frame.inProximity
        }

        // Motion & dynamic parameters (tilt, distance, pressure, barrel roll)
        serverManager.sendTabletMotion(
            x = frame.x,
            y = frame.y,
            pressure = frame.pressure,
            tiltXDeg = frame.tiltXDeg,
            tiltYDeg = frame.tiltYDeg,
            distance = frame.distance,
            rotationDeg = frame.rotationDegrees
        )

        // Tip down / up lifecycle
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                serverManager.sendTabletDown(frame.x, frame.y, frame.pressure)
            }
            MotionEvent.ACTION_UP -> {
                serverManager.sendTabletUp()
            }
            MotionEvent.ACTION_MOVE -> {
                // Handle in-motion pressure touch if down state changed
                if (frame.isDown && frame.pressure > 0) {
                    serverManager.sendTabletDown(frame.x, frame.y, frame.pressure)
                }
            }
        }

        // Button events
        if (frame.buttonMask != lastStylusButtonMask) {
            val changed = frame.buttonMask xor lastStylusButtonMask
            for (i in 0..15) {
                val bit = 1 shl i
                if ((changed and bit) != 0) {
                    val isDown = (frame.buttonMask and bit) != 0
                    serverManager.sendTabletButton(bit, isDown)
                }
            }
            lastStylusButtonMask = frame.buttonMask
        }

        // Also drive desktop pointer so stylus can interact with standard Wayland windows
        serverManager.sendPointerAbsolute(frame.x, frame.y)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            serverManager.sendPointerButton(1, true)
        } else if (event.actionMasked == MotionEvent.ACTION_UP) {
            serverManager.sendPointerButton(1, false)
        }
    }

    private fun dispatchMouseEvent(event: MotionEvent) {
        // Relative deltas from pointer capture or absolute coordinates
        val dx = event.getAxisValue(MotionEvent.AXIS_RELATIVE_X)
        val dy = event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)
        if (dx != 0f || dy != 0f) {
            serverManager.sendPointerDelta(dx, dy)
        } else {
            serverManager.sendPointerAbsolute(event.x, event.y)
        }

        // Wheel axes
        val vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
        if (vScroll != 0f) {
            serverManager.sendPointerAxis(0, vScroll)
        }
        val hScroll = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
        if (hScroll != 0f) {
            serverManager.sendPointerAxis(1, hScroll)
        }

        // Mouse buttons
        val buttonState = event.buttonState
        if (buttonState != lastPointerButtonMask) {
            val changed = buttonState xor lastPointerButtonMask
            for (i in 0..7) {
                val bit = 1 shl i
                if ((changed and bit) != 0) {
                    val isDown = (buttonState and bit) != 0
                    serverManager.sendPointerButton(bit, isDown)
                }
            }
            lastPointerButtonMask = buttonState
        } else if (event.actionMasked == MotionEvent.ACTION_DOWN && buttonState == 0) {
            serverManager.sendPointerButton(1, true)
            lastPointerButtonMask = 1
        } else if (event.actionMasked == MotionEvent.ACTION_UP && lastPointerButtonMask != 0) {
            serverManager.sendPointerButton(lastPointerButtonMask, false)
            lastPointerButtonMask = 0
        }
    }

    private fun dispatchTouchEvent(event: MotionEvent) {
        val action = event.actionMasked
        val pointerIndex = event.actionIndex
        val pointerId = event.getPointerId(pointerIndex)
        val x = event.getX(pointerIndex)
        val y = event.getY(pointerIndex)

        serverManager.sendTouchEvent(action, pointerId, x, y)

        // Single finger touch drives pointer for desktop compositors
        if (event.pointerCount == 1) {
            serverManager.sendPointerAbsolute(x, y)
            when (action) {
                MotionEvent.ACTION_DOWN -> {
                    serverManager.sendPointerButton(1, true)
                    lastPointerButtonMask = 1
                }
                MotionEvent.ACTION_UP -> {
                    serverManager.sendPointerButton(1, false)
                    lastPointerButtonMask = 0
                }
            }
        }
    }

    fun handleKeyEvent(event: KeyEvent): Boolean {
        var scanCode = event.scanCode

        // Prevent Xiaomi HyperOS Super key interception by remapping CapsLock (58) to Super (125)
        if (rebindCapsToSuper && (scanCode == 58 || event.keyCode == KeyEvent.KEYCODE_CAPS_LOCK)) {
            scanCode = 125
        } else if (scanCode == 0) {
            scanCode = mapKeyCodeToScanCode(event.keyCode)
        }

        if (scanCode > 0) {
            val isDown = event.action == KeyEvent.ACTION_DOWN
            serverManager.sendKey(scanCode, isDown)
            return true
        }
        return false
    }

    private fun mapKeyCodeToScanCode(keyCode: Int): Int {
        return when (keyCode) {
            KeyEvent.KEYCODE_ENTER -> 28
            KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BACK -> 1
            KeyEvent.KEYCODE_TAB -> 15
            KeyEvent.KEYCODE_SPACE -> 57
            KeyEvent.KEYCODE_DEL -> 14
            KeyEvent.KEYCODE_FORWARD_DEL -> 111
            KeyEvent.KEYCODE_DPAD_UP -> 103
            KeyEvent.KEYCODE_DPAD_DOWN -> 108
            KeyEvent.KEYCODE_DPAD_LEFT -> 105
            KeyEvent.KEYCODE_DPAD_RIGHT -> 106
            KeyEvent.KEYCODE_CTRL_LEFT -> 29
            KeyEvent.KEYCODE_CTRL_RIGHT -> 97
            KeyEvent.KEYCODE_SHIFT_LEFT -> 42
            KeyEvent.KEYCODE_SHIFT_RIGHT -> 54
            KeyEvent.KEYCODE_ALT_LEFT -> 56
            KeyEvent.KEYCODE_ALT_RIGHT -> 100
            KeyEvent.KEYCODE_META_LEFT -> 125
            KeyEvent.KEYCODE_META_RIGHT -> 126
            KeyEvent.KEYCODE_0 -> 11
            in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> keyCode - KeyEvent.KEYCODE_1 + 2
            KeyEvent.KEYCODE_A -> 30
            KeyEvent.KEYCODE_B -> 48
            KeyEvent.KEYCODE_C -> 46
            KeyEvent.KEYCODE_D -> 32
            KeyEvent.KEYCODE_E -> 18
            KeyEvent.KEYCODE_F -> 33
            KeyEvent.KEYCODE_G -> 34
            KeyEvent.KEYCODE_H -> 35
            KeyEvent.KEYCODE_I -> 23
            KeyEvent.KEYCODE_J -> 36
            KeyEvent.KEYCODE_K -> 37
            KeyEvent.KEYCODE_L -> 38
            KeyEvent.KEYCODE_M -> 50
            KeyEvent.KEYCODE_N -> 49
            KeyEvent.KEYCODE_O -> 24
            KeyEvent.KEYCODE_P -> 25
            KeyEvent.KEYCODE_Q -> 16
            KeyEvent.KEYCODE_R -> 19
            KeyEvent.KEYCODE_S -> 31
            KeyEvent.KEYCODE_T -> 20
            KeyEvent.KEYCODE_U -> 22
            KeyEvent.KEYCODE_V -> 47
            KeyEvent.KEYCODE_W -> 17
            KeyEvent.KEYCODE_X -> 45
            KeyEvent.KEYCODE_Y -> 21
            KeyEvent.KEYCODE_Z -> 44
            KeyEvent.KEYCODE_COMMA -> 51
            KeyEvent.KEYCODE_PERIOD -> 52
            KeyEvent.KEYCODE_MINUS -> 12
            KeyEvent.KEYCODE_EQUALS -> 13
            KeyEvent.KEYCODE_LEFT_BRACKET -> 26
            KeyEvent.KEYCODE_RIGHT_BRACKET -> 27
            KeyEvent.KEYCODE_BACKSLASH -> 43
            KeyEvent.KEYCODE_SEMICOLON -> 39
            KeyEvent.KEYCODE_APOSTROPHE -> 40
            KeyEvent.KEYCODE_SLASH -> 53
            KeyEvent.KEYCODE_GRAVE -> 41
            else -> 0
        }
    }
}
