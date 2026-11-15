package com.alw.workstation.core.proot.emulator

import com.alw.workstation.core.proot.driver.ElfAbiType
import org.json.JSONObject

/**
 * Metadata record for an installed x86_64 emulator overlay (FEX-Emu / box64)
 * stored centrally in `files/overlays/<engine>/<version>/`.
 */
data class EmulatorManifest(
    val engine: String, // "fex" or "box64"
    val version: String,
    val displayName: String,
    val interpreterRelativePath: String, // e.g. "bin/FEXInterpreter" or "bin/box64"
    val sha256Checksum: String,
    val abiType: ElfAbiType = ElfAbiType.GLIBC_AARCH64,
    val hasThunkLibs: Boolean = true,
    val hasRootfsOverlay: Boolean = true,
    val installedAtEpochMs: Long = System.currentTimeMillis()
) {
    fun toJson(): String {
        val root = JSONObject()
        root.put("engine", engine)
        root.put("version", version)
        root.put("displayName", displayName)
        root.put("interpreterRelativePath", interpreterRelativePath)
        root.put("sha256Checksum", sha256Checksum)
        root.put("abiType", abiType.name)
        root.put("hasThunkLibs", hasThunkLibs)
        root.put("hasRootfsOverlay", hasRootfsOverlay)
        root.put("installedAtEpochMs", installedAtEpochMs)
        return root.toString(2)
    }

    companion object {
        fun fromJson(jsonStr: String): EmulatorManifest {
            val obj = JSONObject(jsonStr)
            val engine = obj.getString("engine")
            val defaultInterpreter = if (engine.lowercase() == "fex") "bin/FEXInterpreter" else "bin/box64"
            return EmulatorManifest(
                engine = engine,
                version = obj.getString("version"),
                displayName = obj.optString("displayName", "${engine.uppercase()} ${obj.getString("version")}"),
                interpreterRelativePath = obj.optString("interpreterRelativePath", defaultInterpreter),
                sha256Checksum = obj.optString("sha256Checksum", ""),
                abiType = ElfAbiType.valueOf(obj.optString("abiType", ElfAbiType.GLIBC_AARCH64.name)),
                hasThunkLibs = obj.optBoolean("hasThunkLibs", true),
                hasRootfsOverlay = obj.optBoolean("hasRootfsOverlay", true),
                installedAtEpochMs = obj.optLong("installedAtEpochMs", System.currentTimeMillis())
            )
        }
    }
}
