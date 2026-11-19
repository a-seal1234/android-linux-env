package com.alw.workstation.bridge.wayland.input

import android.view.KeyEvent

/**
 * Android `KEYCODE_*` → Linux evdev code (`linux/input-event-codes.h`).
 *
 * Only consulted when a `KeyEvent` carries no hardware scanCode; a non-zero
 * hardware scanCode always wins as ground truth. Covers the complete desktop
 * keyboard: letters, digits, symbols, modifiers, the F-row, navigation,
 * SysRq/ScrollLock/Pause, the numpad block and the ISO 102nd key.
 */
object EvdevKeyMap {

    val KEYCODE_TO_EVDEV: Map<Int, Int> = buildMap {
        // Controls
        put(KeyEvent.KEYCODE_ESCAPE, 1)
        put(KeyEvent.KEYCODE_BACK, 1) // Back acts as Esc inside the session
        put(KeyEvent.KEYCODE_TAB, 15)
        put(KeyEvent.KEYCODE_ENTER, 28)
        put(KeyEvent.KEYCODE_SPACE, 57)
        put(KeyEvent.KEYCODE_DEL, 14) // Backspace
        put(KeyEvent.KEYCODE_FORWARD_DEL, 111) // KEY_DELETE

        // Modifiers
        put(KeyEvent.KEYCODE_SHIFT_LEFT, 42)
        put(KeyEvent.KEYCODE_SHIFT_RIGHT, 54)
        put(KeyEvent.KEYCODE_CTRL_LEFT, 29)
        put(KeyEvent.KEYCODE_CTRL_RIGHT, 97)
        put(KeyEvent.KEYCODE_ALT_LEFT, 56)
        put(KeyEvent.KEYCODE_ALT_RIGHT, 100)
        put(KeyEvent.KEYCODE_META_LEFT, 125)
        put(KeyEvent.KEYCODE_META_RIGHT, 126)
        put(KeyEvent.KEYCODE_CAPS_LOCK, 58)
        put(KeyEvent.KEYCODE_NUM_LOCK, 69)
        put(KeyEvent.KEYCODE_SCROLL_LOCK, 70)
        put(KeyEvent.KEYCODE_SYSRQ, 99)
        put(KeyEvent.KEYCODE_BREAK, 119) // Pause
        put(KeyEvent.KEYCODE_MENU, 127) // KEY_COMPOSE

        // F-row
        put(KeyEvent.KEYCODE_F1, 59)
        put(KeyEvent.KEYCODE_F2, 60)
        put(KeyEvent.KEYCODE_F3, 61)
        put(KeyEvent.KEYCODE_F4, 62)
        put(KeyEvent.KEYCODE_F5, 63)
        put(KeyEvent.KEYCODE_F6, 64)
        put(KeyEvent.KEYCODE_F7, 65)
        put(KeyEvent.KEYCODE_F8, 66)
        put(KeyEvent.KEYCODE_F9, 67)
        put(KeyEvent.KEYCODE_F10, 68)
        put(KeyEvent.KEYCODE_F11, 87)
        put(KeyEvent.KEYCODE_F12, 88)

        // Navigation
        put(KeyEvent.KEYCODE_DPAD_UP, 103)
        put(KeyEvent.KEYCODE_DPAD_DOWN, 108)
        put(KeyEvent.KEYCODE_DPAD_LEFT, 105)
        put(KeyEvent.KEYCODE_DPAD_RIGHT, 106)
        put(KeyEvent.KEYCODE_MOVE_HOME, 102)
        put(KeyEvent.KEYCODE_MOVE_END, 107)
        put(KeyEvent.KEYCODE_PAGE_UP, 104)
        put(KeyEvent.KEYCODE_PAGE_DOWN, 109)
        put(KeyEvent.KEYCODE_INSERT, 110)

        // Digits
        put(KeyEvent.KEYCODE_0, 11)
        for (digit in 1..9) {
            put(KeyEvent.KEYCODE_1 + digit - 1, digit + 1)
        }

        // Letters
        put(KeyEvent.KEYCODE_A, 30)
        put(KeyEvent.KEYCODE_B, 48)
        put(KeyEvent.KEYCODE_C, 46)
        put(KeyEvent.KEYCODE_D, 32)
        put(KeyEvent.KEYCODE_E, 18)
        put(KeyEvent.KEYCODE_F, 33)
        put(KeyEvent.KEYCODE_G, 34)
        put(KeyEvent.KEYCODE_H, 35)
        put(KeyEvent.KEYCODE_I, 23)
        put(KeyEvent.KEYCODE_J, 36)
        put(KeyEvent.KEYCODE_K, 37)
        put(KeyEvent.KEYCODE_L, 38)
        put(KeyEvent.KEYCODE_M, 50)
        put(KeyEvent.KEYCODE_N, 49)
        put(KeyEvent.KEYCODE_O, 24)
        put(KeyEvent.KEYCODE_P, 25)
        put(KeyEvent.KEYCODE_Q, 16)
        put(KeyEvent.KEYCODE_R, 19)
        put(KeyEvent.KEYCODE_S, 31)
        put(KeyEvent.KEYCODE_T, 20)
        put(KeyEvent.KEYCODE_U, 22)
        put(KeyEvent.KEYCODE_V, 47)
        put(KeyEvent.KEYCODE_W, 17)
        put(KeyEvent.KEYCODE_X, 45)
        put(KeyEvent.KEYCODE_Y, 21)
        put(KeyEvent.KEYCODE_Z, 44)

        // Symbols
        put(KeyEvent.KEYCODE_COMMA, 51)
        put(KeyEvent.KEYCODE_PERIOD, 52)
        put(KeyEvent.KEYCODE_MINUS, 12)
        put(KeyEvent.KEYCODE_EQUALS, 13)
        put(KeyEvent.KEYCODE_LEFT_BRACKET, 26)
        put(KeyEvent.KEYCODE_RIGHT_BRACKET, 27)
        put(KeyEvent.KEYCODE_BACKSLASH, 43)
        put(KeyEvent.KEYCODE_SEMICOLON, 39)
        put(KeyEvent.KEYCODE_APOSTROPHE, 40)
        put(KeyEvent.KEYCODE_SLASH, 53)
        put(KeyEvent.KEYCODE_GRAVE, 41)
        put(KeyEvent.KEYCODE_POUND, 86) // ISO 102nd key (# on UK ISO layouts)

        // Numpad
        put(KeyEvent.KEYCODE_NUMPAD_0, 82)
        put(KeyEvent.KEYCODE_NUMPAD_1, 79)
        put(KeyEvent.KEYCODE_NUMPAD_2, 80)
        put(KeyEvent.KEYCODE_NUMPAD_3, 81)
        put(KeyEvent.KEYCODE_NUMPAD_4, 75)
        put(KeyEvent.KEYCODE_NUMPAD_5, 76)
        put(KeyEvent.KEYCODE_NUMPAD_6, 77)
        put(KeyEvent.KEYCODE_NUMPAD_7, 71)
        put(KeyEvent.KEYCODE_NUMPAD_8, 72)
        put(KeyEvent.KEYCODE_NUMPAD_9, 73)
        put(KeyEvent.KEYCODE_NUMPAD_DOT, 83)
        put(KeyEvent.KEYCODE_NUMPAD_ENTER, 96)
        put(KeyEvent.KEYCODE_NUMPAD_ADD, 78)
        put(KeyEvent.KEYCODE_NUMPAD_SUBTRACT, 74)
        put(KeyEvent.KEYCODE_NUMPAD_MULTIPLY, 55)
        put(KeyEvent.KEYCODE_NUMPAD_DIVIDE, 98)
    }

    fun evdevFor(keyCode: Int): Int = KEYCODE_TO_EVDEV[keyCode] ?: 0
}
