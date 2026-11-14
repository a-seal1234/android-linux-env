package com.alw.workstation.bridge.wayland

import com.alw.workstation.bridge.wayland.input.StylusTiltMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class StylusTiltMathTest {

    @Test
    fun testPerpendicularStylusYieldsZeroTilt() {
        val (tiltX, tiltY) = StylusTiltMath.computePlanarTilt(0.0, 0.0)
        assertEquals(0f, tiltX, 0.001f)
        assertEquals(0f, tiltY, 0.001f)
    }

    @Test
    fun testTiltNorthPointingUpYieldsNegativeY() {
        // Altitude = 30 deg, Orientation = 0 rad (pointing up towards -Y)
        val tiltRad = Math.toRadians(30.0)
        val orientationRad = 0.0
        val (tiltX, tiltY) = StylusTiltMath.computePlanarTilt(tiltRad, orientationRad)

        assertEquals(0f, tiltX, 0.001f)
        assertEquals(-30f, tiltY, 0.001f)
    }

    @Test
    fun testTiltEastPointingRightYieldsPositiveX() {
        // Altitude = 45 deg, Orientation = +pi/2 rad (pointing right towards +X)
        val tiltRad = Math.toRadians(45.0)
        val orientationRad = PI / 2.0
        val (tiltX, tiltY) = StylusTiltMath.computePlanarTilt(tiltRad, orientationRad)

        assertEquals(45f, tiltX, 0.001f)
        assertEquals(0f, tiltY, 0.001f)
    }

    @Test
    fun testTiltSouthPointingDownYieldsPositiveY() {
        // Altitude = 60 deg, Orientation = pi rad (pointing down towards +Y)
        val tiltRad = Math.toRadians(60.0)
        val orientationRad = PI
        val (tiltX, tiltY) = StylusTiltMath.computePlanarTilt(tiltRad, orientationRad)

        assertEquals(0f, tiltX, 0.001f)
        assertEquals(60f, tiltY, 0.001f)
    }

    @Test
    fun testTiltWestPointingLeftYieldsNegativeX() {
        // Altitude = 45 deg, Orientation = -pi/2 rad (pointing left towards -X)
        val tiltRad = Math.toRadians(45.0)
        val orientationRad = -PI / 2.0
        val (tiltX, tiltY) = StylusTiltMath.computePlanarTilt(tiltRad, orientationRad)

        assertEquals(-45f, tiltX, 0.001f)
        assertEquals(0f, tiltY, 0.001f)
    }

    @Test
    fun testBoundaryClampingDoesNotProduceNaN() {
        // Beyond 90 degrees or extreme inputs
        val (tiltX, tiltY) = StylusTiltMath.computePlanarTilt(PI, PI / 4.0)
        assertTrue(!tiltX.isNaN())
        assertTrue(!tiltY.isNaN())
        assertTrue(tiltX in -90f..90f)
        assertTrue(tiltY in -90f..90f)
    }

    @Test
    fun testNormalizePressureRange() {
        assertEquals(0, StylusTiltMath.normalizePressure(0.0f))
        assertEquals(65535, StylusTiltMath.normalizePressure(1.0f))
        assertEquals(32767, StylusTiltMath.normalizePressure(0.5f))
        assertEquals(0, StylusTiltMath.normalizePressure(-0.5f))
        assertEquals(65535, StylusTiltMath.normalizePressure(1.5f))
    }
}
