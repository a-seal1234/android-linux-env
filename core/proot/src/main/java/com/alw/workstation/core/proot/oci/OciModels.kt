package com.alw.workstation.core.proot.oci

import java.io.File

/**
 * Data structures for OCI Distribution Spec v2 and Docker Registry v2 API.
 */
data class OciDistroEntry(
    val id: String,
    val name: String,
    val repository: String,
    val defaultTag: String,
    val popularTags: List<String>,
    val description: String,
    val packageManager: String
)

object OciDistroCatalog {
    val ENTRIES: List<OciDistroEntry> = listOf(
        OciDistroEntry(
            id = "archlinux",
            name = "Arch Linux ARM",
            repository = "library/archlinux",
            defaultTag = "base",
            popularTags = listOf("base", "base-devel", "latest"),
            description = "Rolling release Arch Linux distribution for aarch64",
            packageManager = "pacman"
        ),
        OciDistroEntry(
            id = "ubuntu",
            name = "Ubuntu",
            repository = "library/ubuntu",
            defaultTag = "noble",
            popularTags = listOf("noble", "jammy", "latest", "devel"),
            description = "Canonical Ubuntu LTS / latest Linux workstation",
            packageManager = "apt"
        ),
        OciDistroEntry(
            id = "debian",
            name = "Debian",
            repository = "library/debian",
            defaultTag = "bookworm",
            popularTags = listOf("bookworm", "trixie", "testing", "sid"),
            description = "Universal Linux operating system known for stability",
            packageManager = "apt"
        ),
        OciDistroEntry(
            id = "fedora",
            name = "Fedora",
            repository = "library/fedora",
            defaultTag = "41",
            popularTags = listOf("41", "40", "latest", "rawhide"),
            description = "Cutting-edge Red Hat developer workstation distribution",
            packageManager = "dnf"
        ),
        OciDistroEntry(
            id = "kalilinux",
            name = "Kali Linux",
            repository = "kalilinux/kali-rolling",
            defaultTag = "latest",
            popularTags = listOf("latest"),
            description = "Advanced penetration testing and security auditing platform",
            packageManager = "apt"
        ),
        OciDistroEntry(
            id = "alpine",
            name = "Alpine Linux",
            repository = "library/alpine",
            defaultTag = "latest",
            popularTags = listOf("latest", "edge", "3.21", "3.20"),
            description = "Ultra-lightweight musl-based container distribution (~5MB)",
            packageManager = "apk"
        ),
        OciDistroEntry(
            id = "opensuse",
            name = "openSUSE Tumbleweed",
            repository = "opensuse/tumbleweed",
            defaultTag = "latest",
            popularTags = listOf("latest"),
            description = "Reliable rolling-release openSUSE distribution",
            packageManager = "zypper"
        )
    )

    fun findById(id: String): OciDistroEntry? = ENTRIES.firstOrNull { it.id.equals(id, ignoreCase = true) }
}

data class OciAuthToken(
    val token: String,
    val issuedAtEpochMs: Long = System.currentTimeMillis()
)

data class OciPlatform(
    val architecture: String,
    val os: String,
    val variant: String? = null
) {
    fun isArm64(): Boolean {
        return os == "linux" && (architecture == "arm64" || architecture == "aarch64")
    }
}

data class OciManifestDescriptor(
    val mediaType: String,
    val size: Long,
    val digest: String,
    val platform: OciPlatform? = null
)

data class OciLayerDescriptor(
    val mediaType: String,
    val size: Long,
    val digest: String
)

data class OciImageManifest(
    val schemaVersion: Int,
    val mediaType: String?,
    val configDigest: String,
    val layers: List<OciLayerDescriptor>
)

data class OciPullProgress(
    val currentLayerIndex: Int,
    val totalLayers: Int,
    val layerBytesDownloaded: Long,
    val layerBytesTotal: Long,
    val overallBytesDownloaded: Long,
    val overallBytesTotal: Long,
    val currentDigest: String,
    val statusMessage: String
)

enum class DistroFamily {
    ARCH,
    DEBIAN,
    FEDORA,
    ALPINE,
    OPENSUSE,
    UNKNOWN
}
