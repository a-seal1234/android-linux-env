package com.alw.workstation.core.proot.driver

import org.json.JSONObject
import java.io.File

/**
 * Generates deterministic Vulkan Installable Client Driver (ICD) JSON configuration files
 * for Mesa Turnip Freedreno and Qualcomm proprietary Adreno Vulkan drivers.
 */
object IcdConfigGenerator {

    const val DEFAULT_FREEDRENO_LIB = "/opt/drivers/turnip/active/libvulkan_freedreno.so"
    const val DEFAULT_ADRENO_LIB = "/opt/drivers/turnip/active/libvulkan_adreno.so"
    const val DEFAULT_API_VERSION = "1.4.0"

    /**
     * Generates standard Vulkan ICD JSON for open-source Mesa Turnip Freedreno.
     */
    fun generateFreedrenoIcdJson(
        libraryPath: String = DEFAULT_FREEDRENO_LIB,
        apiVersion: String = DEFAULT_API_VERSION
    ): String {
        val root = JSONObject()
        root.put("file_format_version", "1.0.0")
        val icd = JSONObject()
        icd.put("library_path", libraryPath)
        icd.put("api_version", apiVersion)
        root.put("ICD", icd)
        return root.toString(2)
    }

    /**
     * Generates standard Vulkan ICD JSON for extracted Qualcomm proprietary Adreno Vulkan driver.
     */
    fun generateAdrenoProprietaryIcdJson(
        libraryPath: String = DEFAULT_ADRENO_LIB,
        apiVersion: String = DEFAULT_API_VERSION
    ): String {
        val root = JSONObject()
        root.put("file_format_version", "1.0.0")
        val icd = JSONObject()
        icd.put("library_path", libraryPath)
        icd.put("api_version", apiVersion)
        root.put("ICD", icd)
        return root.toString(2)
    }

    /**
     * Writes the ICD configuration to a destination file.
     */
    fun writeIcdFile(targetFile: File, jsonContent: String) {
        targetFile.parentFile?.mkdirs()
        targetFile.writeText(jsonContent)
    }
}
