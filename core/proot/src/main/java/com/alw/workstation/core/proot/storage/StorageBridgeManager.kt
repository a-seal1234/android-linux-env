package com.alw.workstation.core.proot.storage

import android.util.Log
import com.alw.workstation.core.proot.RuntimeDirectories
import com.alw.workstation.core.proot.model.BindMount
import com.alw.workstation.core.session.model.StackProfile
import java.io.File

/**
 * Manages the selective storage bridge between Android host filesystems and guest Linux,
 * enforcing Scoped Storage safety by masking forbidden paths (Android/data and Android/obb),
 * initializing high-speed internal UFS build cache directories, and detecting external media.
 */
class StorageBridgeManager(
    private val dirs: RuntimeDirectories
) {
    companion object {
        private const val TAG = "ALW::StorageBridge"

        const val PRIMARY_SHARED_STORAGE = "/storage/emulated/0"

        val FORBIDDEN_SCOPED_STORAGE_SUBPATHS = listOf(
            "Android/data",
            "Android/obb"
        )

        val DEFAULT_BUILD_CACHE_DIRECTORIES = listOf(
            "cargo-target",
            "uv-env",
            "pnpm-store",
            "go-build",
            "ccache",
            "gradle-user-home"
        )
    }

    data class ResolvedStorage(
        val bindMounts: List<BindMount>,
        val cacheEnvironment: Map<String, String>,
        val link2symlinkEnabled: Boolean
    )

    /**
     * Returns the host directory used to mask Scoped Storage forbidden paths.
     */
    fun getEmptyMaskDir(): File {
        val maskDir = File(dirs.filesDir, "empty_mask")
        if (!maskDir.exists()) {
            maskDir.mkdirs()
        }
        return maskDir
    }

    /**
     * Resolves the list of bind mounts required for the storage bridge, including
     * primary shared storage and Scoped Storage masking mounts.
     */
    fun resolveStorageMounts(
        profile: StackProfile,
        sharedStorageOverride: File? = null
    ): List<BindMount> {
        val mounts = mutableListOf<BindMount>()
        if (!profile.storage.bindSdcard) return mounts

        val hostStorage = sharedStorageOverride ?: File(PRIMARY_SHARED_STORAGE)
        if (hostStorage.exists() && hostStorage.isDirectory) {
            // 1. Primary shared storage mount
            mounts.add(
                BindMount(
                    hostPath = hostStorage.absolutePath,
                    guestPath = profile.storage.sdcardMountPoint,
                    isReadOnly = false
                )
            )

            // 2. Scoped Storage masking mounts
            val maskDir = getEmptyMaskDir()
            for (subpath in FORBIDDEN_SCOPED_STORAGE_SUBPATHS) {
                val targetGuestPath = "${profile.storage.sdcardMountPoint}/$subpath"
                mounts.add(
                    BindMount(
                        hostPath = maskDir.absolutePath,
                        guestPath = targetGuestPath,
                        isReadOnly = false
                    )
                )
            }
        } else {
            Log.d(TAG, "Shared storage path does not exist on host: ${hostStorage.absolutePath}")
        }

        return mounts
    }

    /**
     * Returns the build cache redirection environment variables targeting high-speed UFS storage.
     */
    fun resolveCacheEnvironment(profile: StackProfile): Map<String, String> {
        if (!profile.storage.cacheRedirection.enabled) return emptyMap()

        val baseCache = profile.storage.cacheRedirection.internalBaseDir
        return mapOf(
            "CARGO_TARGET_DIR" to "$baseCache/cargo-target",
            "UV_PROJECT_ENVIRONMENT" to "$baseCache/uv-env",
            "PNPM_STORE_DIR" to "$baseCache/pnpm-store",
            "GOCACHE" to "$baseCache/go-build",
            "CCACHE_DIR" to "$baseCache/ccache",
            "GRADLE_USER_HOME" to "$baseCache/gradle-user-home"
        )
    }

    /**
     * Assembles complete storage configuration (mounts, cache env, symlink emulation).
     */
    fun resolveStorage(
        profile: StackProfile,
        sharedStorageOverride: File? = null
    ): ResolvedStorage {
        val mounts = resolveStorageMounts(profile, sharedStorageOverride)
        val cacheEnv = resolveCacheEnvironment(profile)
        val symlink2file = profile.storage.enableSymlink2File

        return ResolvedStorage(
            bindMounts = mounts,
            cacheEnvironment = cacheEnv,
            link2symlinkEnabled = symlink2file
        )
    }

    /**
     * Initializes internal UFS build cache directories inside the guest rootfs /home/<user>/.cache/
     */
    fun ensureCacheDirectoriesExist(rootfsDir: File, defaultUser: String = "alw"): List<File> {
        val userCacheBase = File(rootfsDir, "home/$defaultUser/.cache")
        if (!userCacheBase.exists()) {
            userCacheBase.mkdirs()
        }

        val created = mutableListOf<File>()
        for (cacheSubdir in DEFAULT_BUILD_CACHE_DIRECTORIES) {
            val dir = File(userCacheBase, cacheSubdir)
            if (!dir.exists()) {
                dir.mkdirs()
            }
            dir.setReadable(true, false)
            dir.setExecutable(true, false)
            created.add(dir)
        }
        return created
    }

    /**
     * Discovers external physical storage media (SD cards and USB OTG storage).
     */
    fun detectExternalStorage(): List<File> {
        val storageRoot = File("/storage")
        if (!storageRoot.exists() || !storageRoot.isDirectory) return emptyList()

        return storageRoot.listFiles()?.filter { dir ->
            dir.isDirectory &&
                dir.canRead() &&
                dir.name != "emulated" &&
                dir.name != "self" &&
                !dir.name.startsWith(".")
        } ?: emptyList()
    }
}
