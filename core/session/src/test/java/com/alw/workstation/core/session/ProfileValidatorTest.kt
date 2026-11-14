package com.alw.workstation.core.session

import com.alw.workstation.core.session.model.StackProfile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileValidatorTest {

    @Test
    fun testValidDefaultProfiles() {
        val arch = StackProfile.defaultArchXiaomiPad8()
        val archResult = ProfileValidator.validate(arch)
        assertTrue(archResult is ProfileValidator.ValidationResult.Valid)

        val safe = StackProfile.safeModeProfile()
        val safeResult = ProfileValidator.validate(safe)
        assertTrue(safeResult is ProfileValidator.ValidationResult.Valid)
    }

    @Test
    fun testRejectInvalidId() {
        val invalid = StackProfile.defaultArchXiaomiPad8().copy(id = "invalid id with spaces!")
        val result = ProfileValidator.validate(invalid)
        assertTrue(result is ProfileValidator.ValidationResult.Invalid)
        val errors = (result as ProfileValidator.ValidationResult.Invalid).errors
        assertTrue(errors.any { it.contains("invalid") })
    }

    @Test
    fun testRejectDestructiveHomeSessionConfig() {
        // Enforcing Non-Destructive Invariant
        val destructive = StackProfile.defaultArchXiaomiPad8().copy(
            compositor = StackProfile.defaultArchXiaomiPad8().compositor.copy(
                sessionConfigPath = "/home/alw/.config/hypr/hyprland.conf"
            )
        )
        val result = ProfileValidator.validate(destructive)
        assertTrue(result is ProfileValidator.ValidationResult.Invalid)
        val errors = (result as ProfileValidator.ValidationResult.Invalid).errors
        assertTrue(errors.any { it.contains("Non-Destructive Invariant Violation") })
    }

    @Test
    fun testRejectDangerousSdcardBindingWithoutExclusion() {
        val dangerous = StackProfile.defaultArchXiaomiPad8().copy(
            storage = StackProfile.defaultArchXiaomiPad8().storage.copy(
                bindSdcard = true,
                excludePaths = emptyList()
            )
        )
        val result = ProfileValidator.validate(dangerous)
        assertTrue(result is ProfileValidator.ValidationResult.Invalid)
        val errors = (result as ProfileValidator.ValidationResult.Invalid).errors
        assertTrue(errors.any { it.contains("Storage safety violation") })
    }

    @Test
    fun testRejectInvalidDisplayBounds() {
        val outOfBoundsScale = StackProfile.defaultArchXiaomiPad8().copy(
            display = StackProfile.defaultArchXiaomiPad8().display.copy(scaleFactor = 10.0f)
        )
        val result = ProfileValidator.validate(outOfBoundsScale)
        assertTrue(result is ProfileValidator.ValidationResult.Invalid)
    }
}
