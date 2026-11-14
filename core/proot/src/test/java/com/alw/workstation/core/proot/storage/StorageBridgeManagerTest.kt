package com.alw.workstation.core.proot.storage

import com.alw.workstation.core.proot.RuntimeDirectories
import com.alw.workstation.core.session.model.StackProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StorageBridgeManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var dirs: RuntimeDirectories
    private lateinit var storageBridge: StorageBridgeManager

    @Before
    fun setUp() {
        filesDir = tempFolder.newFolder("files")
        dirs = RuntimeDirectories(
            filesDir = filesDir,
            nativeLibDir = File(tempFolder.root, "nativeLib").apply { mkdirs() }.absolutePath
        )
        storageBridge = StorageBridgeManager(dirs)
    }

    @Test
    fun testResolveStorageMountsDisabled() {
        val profile = StackProfile.safeModeProfile().copy(
            storage = StackProfile.StorageConfig(bindSdcard = false)
        )
        val mounts = storageBridge.resolveStorageMounts(profile)
        assertTrue(mounts.isEmpty())
    }

    @Test
    fun testResolveStorageMountsWithMasking() {
        val mockSdcard = tempFolder.newFolder("mock-sdcard")
        val profile = StackProfile.defaultArchXiaomiPad8()

        val mounts = storageBridge.resolveStorageMounts(profile, sharedStorageOverride = mockSdcard)

        assertEquals(3, mounts.size)
        // 1. Primary shared storage
        val primary = mounts.first { it.guestPath == "/mnt/sdcard" }
        assertEquals(mockSdcard.absolutePath, primary.hostPath)

        // 2. Android/data mask
        val dataMask = mounts.first { it.guestPath == "/mnt/sdcard/Android/data" }
        assertEquals(storageBridge.getEmptyMaskDir().absolutePath, dataMask.hostPath)

        // 3. Android/obb mask
        val obbMask = mounts.first { it.guestPath == "/mnt/sdcard/Android/obb" }
        assertEquals(storageBridge.getEmptyMaskDir().absolutePath, obbMask.hostPath)
    }

    @Test
    fun testResolveCacheEnvironment() {
        val profile = StackProfile.defaultArchXiaomiPad8()
        val cacheEnv = storageBridge.resolveCacheEnvironment(profile)

        assertEquals("/home/alw/.cache/cargo-target", cacheEnv["CARGO_TARGET_DIR"])
        assertEquals("/home/alw/.cache/uv-env", cacheEnv["UV_PROJECT_ENVIRONMENT"])
        assertEquals("/home/alw/.cache/pnpm-store", cacheEnv["PNPM_STORE_DIR"])
        assertEquals("/home/alw/.cache/go-build", cacheEnv["GOCACHE"])
        assertEquals("/home/alw/.cache/ccache", cacheEnv["CCACHE_DIR"])
        assertEquals("/home/alw/.cache/gradle-user-home", cacheEnv["GRADLE_USER_HOME"])
    }

    @Test
    fun testEnsureCacheDirectoriesExist() {
        val rootfsDir = tempFolder.newFolder("rootfs-arch")
        val created = storageBridge.ensureCacheDirectoriesExist(rootfsDir, defaultUser = "alw")

        assertEquals(6, created.size)
        val cargoDir = File(rootfsDir, "home/alw/.cache/cargo-target")
        assertTrue(cargoDir.exists())
        assertTrue(cargoDir.isDirectory)
        assertTrue(cargoDir.canRead())
        assertTrue(cargoDir.canExecute())

        val pnpmDir = File(rootfsDir, "home/alw/.cache/pnpm-store")
        assertTrue(pnpmDir.exists())
    }

    @Test
    fun testResolveStorageOverall() {
        val mockSdcard = tempFolder.newFolder("mock-sdcard-2")
        val profile = StackProfile.defaultArchXiaomiPad8()

        val resolved = storageBridge.resolveStorage(profile, sharedStorageOverride = mockSdcard)

        assertTrue(resolved.link2symlinkEnabled)
        assertEquals(3, resolved.bindMounts.size)
        assertEquals(6, resolved.cacheEnvironment.size)
    }
}
