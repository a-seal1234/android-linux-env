package com.alw.workstation.core.session

import com.alw.workstation.core.session.model.StackProfile

/**
 * Validates StackProfile definitions, strictly enforcing the Non-Destructive Invariant
 * and parameter safety bounds.
 */
object ProfileValidator {

    private val ID_REGEX = Regex("^[a-zA-Z0-9_-]+$")

    sealed interface ValidationResult {
        data object Valid : ValidationResult
        data class Invalid(val errors: List<String>) : ValidationResult
    }

    fun validate(profile: StackProfile): ValidationResult {
        val errors = mutableListOf<String>()

        // 1. Identity validation
        if (!ID_REGEX.matches(profile.id)) {
            errors.add("Profile ID '${profile.id}' is invalid. Must contain only alphanumeric characters, underscores, or dashes.")
        }
        if (profile.name.isBlank()) {
            errors.add("Profile name must not be blank.")
        }
        if (profile.distro.rootfsId.isBlank()) {
            errors.add("Distro rootfsId must not be blank.")
        }

        // 2. Non-Destructive Invariant validation
        val sessionPath = profile.compositor.sessionConfigPath
        if (sessionPath.startsWith("/home/") || sessionPath.startsWith("~")) {
            errors.add("Non-Destructive Invariant Violation: sessionConfigPath ($sessionPath) must reside in /tmp/, not inside user home.")
        }
        if (profile.compositor.userConfigPath != null && profile.compositor.userConfigPath == sessionPath) {
            errors.add("Non-Destructive Invariant Violation: userConfigPath cannot be identical to sessionConfigPath.")
        }

        // 3. Display bounds validation
        if (profile.display.scaleFactor !in 0.5f..4.0f) {
            errors.add("Display scaleFactor must be between 0.5 and 4.0.")
        }
        if (profile.display.refreshRateHz !in 30..240) {
            errors.add("Display refreshRateHz must be between 30 and 240 Hz.")
        }

        // 4. Storage safety validation
        if (profile.storage.bindSdcard) {
            val hasAndroidDataExclusion = profile.storage.excludePaths.any {
                it.contains("Android/data")
            }
            if (!hasAndroidDataExclusion) {
                errors.add("Storage safety violation: binding /storage/emulated/0 requires excluding Android/data to prevent Scoped Storage hangs.")
            }
        }

        // 5. GPU driver validation
        if (profile.gpu.driverMode == StackProfile.DriverMode.TURNIP_KGSL && profile.gpu.externalDriverId.isBlank()) {
            errors.add("Turnip KGSL driver mode requires a non-blank externalDriverId.")
        }

        return if (errors.isEmpty()) {
            ValidationResult.Valid
        } else {
            ValidationResult.Invalid(errors)
        }
    }
}
