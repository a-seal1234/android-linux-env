package com.alw.workstation.core.session

import com.alw.workstation.core.session.model.StackProfile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class ProfileRepositoryTest {

    private lateinit var tempDir: File
    private lateinit var repo: ProfileRepository

    @Before
    fun setUp() {
        tempDir = File.createTempFile("alw-test-repo", "").apply {
            delete()
            mkdirs()
        }
        repo = ProfileRepository(tempDir)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun testEnsureDefaultsAndList() {
        repo.ensureDefaultProfiles()
        val list = repo.listProfiles()
        assertEquals(2, list.size)
        assertTrue(list.any { it.id == "arch-xiaomi-pad-8" })
        assertTrue(list.any { it.id == "safe-mode" })
    }

    @Test
    fun testSaveAndGetCustomProfile() {
        val custom = StackProfile.defaultArchXiaomiPad8().copy(
            id = "custom-profile",
            name = "Custom LabWC Workstation",
            compositor = StackProfile.defaultArchXiaomiPad8().compositor.copy(
                type = StackProfile.CompositorType.LABWC,
                execCommand = "labwc"
            )
        )

        val saveResult = repo.saveProfile(custom)
        assertTrue(saveResult.isSuccess)

        val retrieved = repo.getProfile("custom-profile")
        assertNotNull(retrieved)
        assertEquals("Custom LabWC Workstation", retrieved?.name)
        assertEquals(StackProfile.CompositorType.LABWC, retrieved?.compositor?.type)
    }

    @Test
    fun testRejectInvalidSave() {
        val invalid = StackProfile.defaultArchXiaomiPad8().copy(id = "bad id with space")
        val saveResult = repo.saveProfile(invalid)
        assertTrue(saveResult.isFailure)
        assertNull(repo.getProfile("bad id with space"))
    }

    @Test
    fun testSafeModeCannotBeDeleted() {
        repo.ensureDefaultProfiles()
        val deleted = repo.deleteProfile("safe-mode")
        assertFalse(deleted)
        assertNotNull(repo.getProfile("safe-mode"))
    }

    @Test
    fun testDeleteCustomProfile() {
        val custom = StackProfile.defaultArchXiaomiPad8().copy(id = "temp-to-delete")
        repo.saveProfile(custom)
        assertNotNull(repo.getProfile("temp-to-delete"))

        val deleted = repo.deleteProfile("temp-to-delete")
        assertTrue(deleted)
        assertNull(repo.getProfile("temp-to-delete"))
    }
}
