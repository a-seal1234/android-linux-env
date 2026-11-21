package com.alw.workstation.bridge.wayland

import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class PointerMotionCoalescingTest {

    private lateinit var serverManager: WaylandServerManager
    private lateinit var dispatcher: InputDispatcher

    @Before
    fun setUp() {
        serverManager = WaylandServerManager()
        dispatcher = InputDispatcher(serverManager = serverManager)
    }

    @Test
    fun testServerManagerFlushPointerMotion() {
        assertFalse(serverManager.mockPointerMotionFlushed)
        serverManager.flushPointerMotion()
        assertTrue(serverManager.mockPointerMotionFlushed)
    }

    @Test
    fun testCoalescedTouchMoveDispatchesLatestCoordinates() {
        // Simulating the coalescing queue where intermediate moves are collapsed
        var pendingEvent: MotionEvent? = null

        fun queueMove(event: MotionEvent) {
            pendingEvent = event
        }

        fun flush(width: Int = 1920, height: Int = 1080) {
            pendingEvent?.let {
                dispatcher.handleMotionEvent(it, width, height)
                serverManager.flushPointerMotion()
                pendingEvent = null
            }
        }

        // 3 high-frequency touch move events arrive before VSync
        val move1 = mock(MotionEvent::class.java).apply {
            `when`(getToolType(0)).thenReturn(MotionEvent.TOOL_TYPE_FINGER)
            `when`(actionMasked).thenReturn(MotionEvent.ACTION_MOVE)
            `when`(actionIndex).thenReturn(0)
            `when`(getPointerId(0)).thenReturn(1)
            `when`(getX(0)).thenReturn(100f)
            `when`(getY(0)).thenReturn(150f)
            `when`(pointerCount).thenReturn(1)
        }
        val move2 = mock(MotionEvent::class.java).apply {
            `when`(getToolType(0)).thenReturn(MotionEvent.TOOL_TYPE_FINGER)
            `when`(actionMasked).thenReturn(MotionEvent.ACTION_MOVE)
            `when`(actionIndex).thenReturn(0)
            `when`(getPointerId(0)).thenReturn(1)
            `when`(getX(0)).thenReturn(120f)
            `when`(getY(0)).thenReturn(170f)
            `when`(pointerCount).thenReturn(1)
        }
        val move3 = mock(MotionEvent::class.java).apply {
            `when`(getToolType(0)).thenReturn(MotionEvent.TOOL_TYPE_FINGER)
            `when`(actionMasked).thenReturn(MotionEvent.ACTION_MOVE)
            `when`(actionIndex).thenReturn(0)
            `when`(getPointerId(0)).thenReturn(1)
            `when`(getX(0)).thenReturn(150f)
            `when`(getY(0)).thenReturn(200f)
            `when`(pointerCount).thenReturn(1)
        }

        queueMove(move1)
        queueMove(move2)
        queueMove(move3)

        // Only move3 should be flushed on the VSync tick
        flush()

        assertEquals(1, serverManager.mockTouchEvents.size)
        val touch = serverManager.mockTouchEvents[0]
        assertEquals(150f, touch.x, 0.001f)
        assertEquals(200f, touch.y, 0.001f)
        assertEquals(150f, serverManager.mockPointer.x, 0.001f)
        assertEquals(200f, serverManager.mockPointer.y, 0.001f)
        assertTrue(serverManager.mockPointerMotionFlushed)
    }

    @Test
    fun testDownEventFlushesPendingCoalescedMoveFirst() {
        val moveEvent = mock(MotionEvent::class.java).apply {
            `when`(getToolType(0)).thenReturn(MotionEvent.TOOL_TYPE_FINGER)
            `when`(actionMasked).thenReturn(MotionEvent.ACTION_MOVE)
            `when`(actionIndex).thenReturn(0)
            `when`(getPointerId(0)).thenReturn(1)
            `when`(getX(0)).thenReturn(50f)
            `when`(getY(0)).thenReturn(60f)
            `when`(pointerCount).thenReturn(1)
        }

        var pendingEvent: MotionEvent? = moveEvent

        fun flushIfPending() {
            pendingEvent?.let {
                dispatcher.handleMotionEvent(it, 1920, 1080)
                serverManager.flushPointerMotion()
                pendingEvent = null
            }
        }

        // Action UP arrives: flush pending move first
        flushIfPending()

        val upEvent = mock(MotionEvent::class.java).apply {
            `when`(getToolType(0)).thenReturn(MotionEvent.TOOL_TYPE_FINGER)
            `when`(actionMasked).thenReturn(MotionEvent.ACTION_UP)
            `when`(actionIndex).thenReturn(0)
            `when`(getPointerId(0)).thenReturn(1)
            `when`(getX(0)).thenReturn(50f)
            `when`(getY(0)).thenReturn(60f)
            `when`(pointerCount).thenReturn(1)
        }
        dispatcher.handleMotionEvent(upEvent, 1920, 1080)

        assertEquals(2, serverManager.mockTouchEvents.size)
        assertEquals(MotionEvent.ACTION_MOVE, serverManager.mockTouchEvents[0].action)
        assertEquals(MotionEvent.ACTION_UP, serverManager.mockTouchEvents[1].action)
    }
}
