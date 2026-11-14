package com.alw.workstation.core.proot.driver

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class IcdConfigGeneratorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testGenerateFreedrenoIcdJson() {
        val jsonStr = IcdConfigGenerator.generateFreedrenoIcdJson(
            libraryPath = "/opt/drivers/turnip/active/libvulkan_freedreno.so",
            apiVersion = "1.4.0"
        )

        val obj = JSONObject(jsonStr)
        assertEquals("1.0.0", obj.getString("file_format_version"))
        val icd = obj.getJSONObject("ICD")
        assertEquals("/opt/drivers/turnip/active/libvulkan_freedreno.so", icd.getString("library_path"))
        assertEquals("1.4.0", icd.getString("api_version"))
    }

    @Test
    fun testGenerateAdrenoProprietaryIcdJson() {
        val jsonStr = IcdConfigGenerator.generateAdrenoProprietaryIcdJson(
            libraryPath = "/opt/drivers/turnip/active/libvulkan_adreno.so",
            apiVersion = "1.3.275"
        )

        val obj = JSONObject(jsonStr)
        assertEquals("1.0.0", obj.getString("file_format_version"))
        val icd = obj.getJSONObject("ICD")
        assertEquals("/opt/drivers/turnip/active/libvulkan_adreno.so", icd.getString("library_path"))
        assertEquals("1.3.275", icd.getString("api_version"))
    }

    @Test
    fun testWriteIcdFile() {
        val targetFile = File(tempFolder.root, "icd.d/freedreno_icd.aarch64.json")
        val jsonStr = IcdConfigGenerator.generateFreedrenoIcdJson()

        IcdConfigGenerator.writeIcdFile(targetFile, jsonStr)

        assertTrue(targetFile.exists())
        val readBack = JSONObject(targetFile.readText())
        assertEquals("/opt/drivers/turnip/active/libvulkan_freedreno.so", readBack.getJSONObject("ICD").getString("library_path"))
    }
}
