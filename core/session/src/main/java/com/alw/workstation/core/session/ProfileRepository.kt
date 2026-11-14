package com.alw.workstation.core.session

import com.alw.workstation.core.session.model.StackProfile
import java.io.File
import java.io.IOException

/**
 * Persists and retrieves versioned StackProfile JSON models from local storage.
 */
class ProfileRepository(private val baseDir: File) {

    init {
        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }
    }

    fun listProfiles(): List<StackProfile> {
        val files = baseDir.listFiles { _, name -> name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull { file ->
            try {
                StackProfile.fromJsonString(file.readText())
            } catch (e: Exception) {
                null
            }
        }.sortedBy { it.name }
    }

    fun getProfile(id: String): StackProfile? {
        val file = File(baseDir, "$id.json")
        if (!file.exists()) return null
        return try {
            StackProfile.fromJsonString(file.readText())
        } catch (e: Exception) {
            null
        }
    }

    fun saveProfile(profile: StackProfile): Result<Unit> {
        val validation = ProfileValidator.validate(profile)
        if (validation is ProfileValidator.ValidationResult.Invalid) {
            return Result.failure(IllegalArgumentException("Profile validation failed: ${validation.errors.joinToString()}"))
        }

        val targetFile = File(baseDir, "${profile.id}.json")
        val tempFile = File(baseDir, "${profile.id}.json.tmp")

        return try {
            val jsonString = profile.toJson().toString(2)
            tempFile.writeText(jsonString)
            if (!tempFile.renameTo(targetFile)) {
                // Fallback if atomic rename fails
                targetFile.writeText(jsonString)
                tempFile.delete()
            }
            Result.success(Unit)
        } catch (e: IOException) {
            tempFile.delete()
            Result.failure(e)
        }
    }

    fun deleteProfile(id: String): Boolean {
        // Protect Safe Mode from accidental deletion
        if (id == "safe-mode") return false
        val file = File(baseDir, "$id.json")
        return file.exists() && file.delete()
    }

    fun ensureDefaultProfiles() {
        if (getProfile("arch-xiaomi-pad-8") == null) {
            saveProfile(StackProfile.defaultArchXiaomiPad8())
        }
        if (getProfile("safe-mode") == null) {
            saveProfile(StackProfile.safeModeProfile())
        }
    }
}
