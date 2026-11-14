package com.alw.workstation.core.session

import com.alw.workstation.core.session.model.StackProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionControllerTest {

    @Test
    fun testLifecycleTransitions() {
        val controller = SessionController()
        assertEquals(SessionState.Idle, controller.sessionState.value)
        assertNull(controller.getActiveProfile())

        val profile = StackProfile.defaultArchXiaomiPad8()
        val prepResult = controller.prepareSession(profile)
        assertTrue(prepResult.isSuccess)
        assertTrue(controller.sessionState.value is SessionState.Starting)

        controller.updateState(SessionState.Running(profile, uptimeMillis = 1000L))
        assertEquals(profile, controller.getActiveProfile())

        controller.stopSession()
        assertEquals(SessionState.Stopped, controller.sessionState.value)
        assertNull(controller.getActiveProfile())
    }

    @Test
    fun testRejectInvalidProfilePreparation() {
        val controller = SessionController()
        val invalid = StackProfile.defaultArchXiaomiPad8().copy(id = "bad id!")
        val prepResult = controller.prepareSession(invalid)
        assertTrue(prepResult.isFailure)
        assertTrue(controller.sessionState.value is SessionState.Error)
    }
}
