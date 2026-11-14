package com.alw.workstation.core.session.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Versioned immutable StackProfile model representing workstation configuration.
 * Adheres strictly to the Non-Destructive Invariant: user dotfiles in guest /home are never modified.
 */
data class StackProfile(
    val schemaVersion: String = "1.0.0",
    val id: String,
    val name: String,
    val distro: DistroConfig,
    val compositor: CompositorConfig,
    val gpu: GpuConfig,
    val display: DisplayConfig,
    val storage: StorageConfig,
    val input: InputConfig,
    val emulation: EmulationConfig = EmulationConfig()
) {
    data class DistroConfig(
        val rootfsId: String,
        val architecture: String = "aarch64",
        val defaultUser: String = "alw",
        val shell: String = "/bin/zsh"
    )

    data class CompositorConfig(
        val type: CompositorType,
        val execCommand: String,
        val configMode: ConfigMode = ConfigMode.WRAP_AND_OVERRIDE,
        val userConfigPath: String? = null,
        val sessionConfigPath: String = "/tmp/android-session.conf",
        val extraArgs: List<String> = emptyList()
    )

    enum class CompositorType {
        HYPRLAND,
        LABWC,
        PLASMA,
        CUSTOM
    }

    enum class ConfigMode {
        WRAP_AND_OVERRIDE,
        DIRECT,
        SAFE_MODE
    }

    data class GpuConfig(
        val driverMode: DriverMode,
        val externalDriverId: String,
        val kgslNode: String = "/dev/kgsl-3d0",
        val mesaLoaderOverride: String? = null,
        val environmentOverrides: Map<String, String> = emptyMap(),
        val tuDebugNoConform: Boolean = false
    )

    enum class DriverMode {
        /** Open-source Mesa Turnip direct hardware acceleration via /dev/kgsl-3d0 */
        TURNIP_KGSL,
        /** OpenGL over Turnip Vulkan via Zink */
        TURNIP_ZINK,
        /** Extracted Qualcomm proprietary Adreno Vulkan driver paired with Zink for OpenGL */
        ADRENO_PROPRIETARY_ZINK,
        /** Mesa LLVM Software Rasterizer (One-Tap Safe Mode fallback) */
        LLVMPIPE,
        /** Virtualized GPU */
        VIRGL
    }

    data class DisplayConfig(
        val resolutionMode: ResolutionMode = ResolutionMode.NATIVE,
        val customWidth: Int? = null,
        val customHeight: Int? = null,
        val scaleFactor: Float = 1.75f,
        val refreshRateHz: Int = 144
    )

    enum class ResolutionMode {
        NATIVE,
        CUSTOM,
        DYNAMIC
    }

    data class StorageConfig(
        val bindSdcard: Boolean = true,
        val sdcardMountPoint: String = "/mnt/sdcard",
        val excludePaths: List<String> = listOf(
            "/storage/emulated/0/Android/data",
            "/storage/emulated/0/Android/obb"
        ),
        val cacheRedirection: CacheRedirectionConfig = CacheRedirectionConfig(),
        val enableSymlink2File: Boolean = true
    )

    data class CacheRedirectionConfig(
        val enabled: Boolean = true,
        val internalBaseDir: String = "/home/alw/.cache"
    )

    data class InputConfig(
        val requestPointerCapture: Boolean = true,
        val rebindCapsToSuper: Boolean = true,
        val touchpadNaturalScroll: Boolean = true,
        val xkbOptions: String = "caps:super"
    )

    data class EmulationConfig(
        val enabled: Boolean = false,
        val engine: EmulationEngine = EmulationEngine.FEX,
        val thunkLibsEnabled: Boolean = true
    )

    enum class EmulationEngine {
        FEX,
        BOX64
    }

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("schemaVersion", schemaVersion)
            put("id", id)
            put("name", name)
            put("distro", JSONObject().apply {
                put("rootfsId", distro.rootfsId)
                put("architecture", distro.architecture)
                put("defaultUser", distro.defaultUser)
                put("shell", distro.shell)
            })
            put("compositor", JSONObject().apply {
                put("type", compositor.type.name.lowercase())
                put("execCommand", compositor.execCommand)
                put("configMode", compositor.configMode.name.lowercase())
                compositor.userConfigPath?.let { put("userConfigPath", it) }
                put("sessionConfigPath", compositor.sessionConfigPath)
                put("extraArgs", JSONArray(compositor.extraArgs))
            })
            put("gpu", JSONObject().apply {
                put("driverMode", gpu.driverMode.name.lowercase())
                put("externalDriverId", gpu.externalDriverId)
                put("kgslNode", gpu.kgslNode)
                gpu.mesaLoaderOverride?.let { put("mesaLoaderOverride", it) }
                put("tuDebugNoConform", gpu.tuDebugNoConform)
                put("environmentOverrides", JSONObject(gpu.environmentOverrides))
            })
            put("display", JSONObject().apply {
                put("resolutionMode", display.resolutionMode.name.lowercase())
                display.customWidth?.let { put("customWidth", it) }
                display.customHeight?.let { put("customHeight", it) }
                put("scaleFactor", display.scaleFactor.toDouble())
                put("refreshRateHz", display.refreshRateHz)
            })
            put("storage", JSONObject().apply {
                put("bindSdcard", storage.bindSdcard)
                put("sdcardMountPoint", storage.sdcardMountPoint)
                put("excludePaths", JSONArray(storage.excludePaths))
                put("cacheRedirection", JSONObject().apply {
                    put("enabled", storage.cacheRedirection.enabled)
                    put("internalBaseDir", storage.cacheRedirection.internalBaseDir)
                })
                put("enableSymlink2File", storage.enableSymlink2File)
            })
            put("input", JSONObject().apply {
                put("requestPointerCapture", input.requestPointerCapture)
                put("rebindCapsToSuper", input.rebindCapsToSuper)
                put("touchpadNaturalScroll", input.touchpadNaturalScroll)
                put("xkbOptions", input.xkbOptions)
            })
            put("emulation", JSONObject().apply {
                put("enabled", emulation.enabled)
                put("engine", emulation.engine.name.lowercase())
                put("thunkLibsEnabled", emulation.thunkLibsEnabled)
            })
        }
    }

    companion object {
        fun fromJson(json: JSONObject): StackProfile {
            val distroObj = json.getJSONObject("distro")
            val compObj = json.getJSONObject("compositor")
            val gpuObj = json.getJSONObject("gpu")
            val displayObj = json.getJSONObject("display")
            val storageObj = json.getJSONObject("storage")
            val inputObj = json.getJSONObject("input")
            val emuObj = json.optJSONObject("emulation")

            val extraArgsList = mutableListOf<String>()
            val extraArgsArray = compObj.optJSONArray("extraArgs")
            if (extraArgsArray != null) {
                for (i in 0 until extraArgsArray.length()) {
                    extraArgsList.add(extraArgsArray.getString(i))
                }
            }

            val envOverrides = mutableMapOf<String, String>()
            val envObj = gpuObj.optJSONObject("environmentOverrides")
            if (envObj != null) {
                val keys = envObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    envOverrides[k] = envObj.getString(k)
                }
            }

            val excludeList = mutableListOf<String>()
            val excludeArray = storageObj.optJSONArray("excludePaths")
            if (excludeArray != null) {
                for (i in 0 until excludeArray.length()) {
                    excludeList.add(excludeArray.getString(i))
                }
            } else {
                excludeList.addAll(listOf("/storage/emulated/0/Android/data", "/storage/emulated/0/Android/obb"))
            }

            val cacheObj = storageObj.optJSONObject("cacheRedirection")

            return StackProfile(
                schemaVersion = json.optString("schemaVersion", "1.0.0"),
                id = json.getString("id"),
                name = json.getString("name"),
                distro = DistroConfig(
                    rootfsId = distroObj.getString("rootfsId"),
                    architecture = distroObj.optString("architecture", "aarch64"),
                    defaultUser = distroObj.optString("defaultUser", "alw"),
                    shell = distroObj.optString("shell", "/bin/zsh")
                ),
                compositor = CompositorConfig(
                    type = CompositorType.valueOf(compObj.getString("type").uppercase()),
                    execCommand = compObj.getString("execCommand"),
                    configMode = ConfigMode.valueOf(compObj.optString("configMode", "WRAP_AND_OVERRIDE").uppercase()),
                    userConfigPath = compObj.optString("userConfigPath").takeIf { it.isNotBlank() },
                    sessionConfigPath = compObj.optString("sessionConfigPath", "/tmp/android-session.conf"),
                    extraArgs = extraArgsList
                ),
                gpu = GpuConfig(
                    driverMode = DriverMode.valueOf(gpuObj.getString("driverMode").uppercase()),
                    externalDriverId = gpuObj.getString("externalDriverId"),
                    kgslNode = gpuObj.optString("kgslNode", "/dev/kgsl-3d0"),
                    mesaLoaderOverride = gpuObj.optString("mesaLoaderOverride").takeIf { it.isNotBlank() },
                    environmentOverrides = envOverrides,
                    tuDebugNoConform = gpuObj.optBoolean("tuDebugNoConform", false)
                ),
                display = DisplayConfig(
                    resolutionMode = ResolutionMode.valueOf(displayObj.optString("resolutionMode", "NATIVE").uppercase()),
                    customWidth = if (displayObj.has("customWidth")) displayObj.getInt("customWidth") else null,
                    customHeight = if (displayObj.has("customHeight")) displayObj.getInt("customHeight") else null,
                    scaleFactor = displayObj.optDouble("scaleFactor", 1.75).toFloat(),
                    refreshRateHz = displayObj.optInt("refreshRateHz", 144)
                ),
                storage = StorageConfig(
                    bindSdcard = storageObj.optBoolean("bindSdcard", true),
                    sdcardMountPoint = storageObj.optString("sdcardMountPoint", "/mnt/sdcard"),
                    excludePaths = excludeList,
                    cacheRedirection = CacheRedirectionConfig(
                        enabled = cacheObj?.optBoolean("enabled", true) ?: true,
                        internalBaseDir = cacheObj?.optString("internalBaseDir", "/home/alw/.cache") ?: "/home/alw/.cache"
                    ),
                    enableSymlink2File = storageObj.optBoolean("enableSymlink2File", true)
                ),
                input = InputConfig(
                    requestPointerCapture = inputObj.optBoolean("requestPointerCapture", true),
                    rebindCapsToSuper = inputObj.optBoolean("rebindCapsToSuper", true),
                    touchpadNaturalScroll = inputObj.optBoolean("touchpadNaturalScroll", true),
                    xkbOptions = inputObj.optString("xkbOptions", "caps:super")
                ),
                emulation = EmulationConfig(
                    enabled = emuObj?.optBoolean("enabled", false) ?: false,
                    engine = EmulationEngine.valueOf(emuObj?.optString("engine", "FEX")?.uppercase() ?: "FEX"),
                    thunkLibsEnabled = emuObj?.optBoolean("thunkLibsEnabled", true) ?: true
                )
            )
        }

        fun fromJsonString(jsonString: String): StackProfile {
            return fromJson(JSONObject(jsonString))
        }

        fun defaultArchXiaomiPad8(): StackProfile {
            return StackProfile(
                id = "arch-xiaomi-pad-8",
                name = "Arch Linux ARM (Hyprland + Turnip KGSL)",
                distro = DistroConfig(
                    rootfsId = "archlinux-default",
                    architecture = "aarch64",
                    defaultUser = "alw",
                    shell = "/bin/zsh"
                ),
                compositor = CompositorConfig(
                    type = CompositorType.HYPRLAND,
                    execCommand = "Hyprland",
                    configMode = ConfigMode.WRAP_AND_OVERRIDE,
                    userConfigPath = "/home/alw/.config/hypr/hyprland.conf",
                    sessionConfigPath = "/tmp/android-session.conf",
                    extraArgs = listOf("-c", "/tmp/android-session.conf")
                ),
                gpu = GpuConfig(
                    driverMode = DriverMode.TURNIP_KGSL,
                    externalDriverId = "turnip-mesa-25.0-adreno825",
                    kgslNode = "/dev/kgsl-3d0",
                    mesaLoaderOverride = "zink",
                    tuDebugNoConform = true,
                    environmentOverrides = mapOf(
                        "TU_DEBUG" to "noconform",
                        "MESA_VK_WSI_PRESENT_MODE" to "mailbox"
                    )
                ),
                display = DisplayConfig(
                    resolutionMode = ResolutionMode.NATIVE,
                    customWidth = 3200,
                    customHeight = 2136,
                    scaleFactor = 1.75f,
                    refreshRateHz = 144
                ),
                storage = StorageConfig(),
                input = InputConfig()
            )
        }

        fun safeModeProfile(): StackProfile {
            return StackProfile(
                id = "safe-mode",
                name = "Safe Mode (LabWC + llvmpipe)",
                distro = DistroConfig(
                    rootfsId = "archlinux-default",
                    architecture = "aarch64",
                    defaultUser = "alw",
                    shell = "/bin/bash"
                ),
                compositor = CompositorConfig(
                    type = CompositorType.LABWC,
                    execCommand = "labwc",
                    configMode = ConfigMode.SAFE_MODE,
                    sessionConfigPath = "/tmp/android-safe-mode.conf",
                    extraArgs = listOf("-s", "kitty")
                ),
                gpu = GpuConfig(
                    driverMode = DriverMode.LLVMPIPE,
                    externalDriverId = "system-fallback",
                    mesaLoaderOverride = "llvmpipe"
                ),
                display = DisplayConfig(
                    scaleFactor = 1.5f,
                    refreshRateHz = 60
                ),
                storage = StorageConfig(
                    bindSdcard = false
                ),
                input = InputConfig(
                    rebindCapsToSuper = false
                )
            )
        }
    }
}
