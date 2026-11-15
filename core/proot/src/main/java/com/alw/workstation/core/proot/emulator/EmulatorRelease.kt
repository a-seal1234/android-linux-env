package com.alw.workstation.core.proot.emulator

/**
 * Curated or online discoverable release descriptor for an x86_64 emulator overlay.
 */
data class EmulatorRelease(
    val engine: String, // "fex" or "box64"
    val version: String,
    val releaseTag: String,
    val displayName: String,
    val archiveName: String,
    val downloadUrl: String,
    val sha256Checksum: String,
    val interpreterPath: String, // relative to unpacked root e.g. "bin/FEXInterpreter"
    val sizeBytes: Long = 0L,
    val description: String = ""
) {
    companion object {
        fun curatedReleases(): List<EmulatorRelease> {
            return listOf(
                EmulatorRelease(
                    engine = "fex",
                    version = "fex-2412",
                    releaseTag = "v2412",
                    displayName = "FEX-Emu v2412 (Turnip Vulkan Thunks)",
                    archiveName = "fex-2412-aarch64.tar.gz",
                    downloadUrl = "https://github.com/FEX-Emu/FEX/releases/download/v2412/fex-2412-aarch64.tar.gz",
                    sha256Checksum = "d1f8a846c2b18974a95a86a6833c8b4172f3e8432a5fa59b3c3b0f513f50a8b9",
                    interpreterPath = "bin/FEXInterpreter",
                    sizeBytes = 28_450_000L,
                    description = "Fast x86/x86_64 JIT emulator with direct Vulkan/OpenGL guest-to-host thunking for Turnip Adreno 825."
                ),
                EmulatorRelease(
                    engine = "fex",
                    version = "fex-2411",
                    releaseTag = "v2411",
                    displayName = "FEX-Emu v2411",
                    archiveName = "fex-2411-aarch64.tar.gz",
                    downloadUrl = "https://github.com/FEX-Emu/FEX/releases/download/v2411/fex-2411-aarch64.tar.gz",
                    sha256Checksum = "a4c7e63b84f2910d540291d90472e9a567c826d9715ecda06a4b3d881e19bb4f",
                    interpreterPath = "bin/FEXInterpreter",
                    sizeBytes = 27_100_000L,
                    description = "Stable FEX-Emu release with 64-bit ARM dynamic translation."
                ),
                EmulatorRelease(
                    engine = "box64",
                    version = "box64-v0.3.2",
                    releaseTag = "v0.3.2",
                    displayName = "box64 v0.3.2 (Dynarec AArch64)",
                    archiveName = "box64-0.3.2-aarch64.tar.gz",
                    downloadUrl = "https://github.com/ptitSeb/box64/releases/download/v0.3.2/box64-0.3.2-aarch64.tar.gz",
                    sha256Checksum = "c5b291a084ef7d87f73966fa19b0235d9472e811acb538e12187680bb4331ea2",
                    interpreterPath = "bin/box64",
                    sizeBytes = 8_900_000L,
                    description = "Linux Userspace x86_64 emulator with Dynarec for ARM64."
                )
            )
        }
    }
}
