package com.alw.workstation.core.proot.driver

import org.json.JSONArray
import org.json.JSONObject

/**
 * Metadata record for an installed Turnip or Adreno Vulkan driver package in the shared driver store.
 */
data class TurnipDriverManifest(
    val version: String,
    val releaseTag: String = "",
    val driverType: DriverType = DriverType.TURNIP_KGSL,
    val sha256Checksum: String,
    val apiVersion: String = "1.4.0",
    val supportedGpus: List<String> = listOf("Adreno 825 (A8xx)", "Snapdragon 8s Gen 4"),
    val abiType: ElfAbiType = ElfAbiType.GLIBC_AARCH64,
    val libraryFileName: String = "libvulkan_freedreno.so",
    val icdFileName: String = "freedreno_icd.aarch64.json",
    val tuDebugRecommendation: String = "noconform"
) {
    enum class DriverType {
        TURNIP_KGSL,
        ADRENO_PROPRIETARY
    }

    fun toJson(): String {
        val root = JSONObject()
        root.put("version", version)
        root.put("releaseTag", releaseTag)
        root.put("driverType", driverType.name)
        root.put("sha256Checksum", sha256Checksum)
        root.put("apiVersion", apiVersion)
        root.put("supportedGpus", JSONArray(supportedGpus))
        root.put("abiType", abiType.name)
        root.put("libraryFileName", libraryFileName)
        root.put("icdFileName", icdFileName)
        root.put("tuDebugRecommendation", tuDebugRecommendation)
        return root.toString(2)
    }

    companion object {
        fun fromJson(jsonStr: String): TurnipDriverManifest {
            val obj = JSONObject(jsonStr)
            val gpus = mutableListOf<String>()
            val gpuArr = obj.optJSONArray("supportedGpus")
            if (gpuArr != null) {
                for (i in 0 until gpuArr.length()) {
                    gpus.add(gpuArr.getString(i))
                }
            }
            return TurnipDriverManifest(
                version = obj.getString("version"),
                releaseTag = obj.optString("releaseTag", ""),
                driverType = DriverType.valueOf(obj.optString("driverType", DriverType.TURNIP_KGSL.name)),
                sha256Checksum = obj.getString("sha256Checksum"),
                apiVersion = obj.optString("apiVersion", "1.4.0"),
                supportedGpus = if (gpus.isNotEmpty()) gpus else listOf("Adreno 825 (A8xx)"),
                abiType = ElfAbiType.valueOf(obj.optString("abiType", ElfAbiType.GLIBC_AARCH64.name)),
                libraryFileName = obj.optString("libraryFileName", "libvulkan_freedreno.so"),
                icdFileName = obj.optString("icdFileName", "freedreno_icd.aarch64.json"),
                tuDebugRecommendation = obj.optString("tuDebugRecommendation", "noconform")
            )
        }
    }
}
