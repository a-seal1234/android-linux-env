package com.alw.workstation.core.proot.provisioning

import com.alw.workstation.core.proot.oci.DistroFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MultiDistroBootstrapperTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val bootstrapper = MultiDistroBootstrapper()

    @Test
    fun testDetectUbuntu() {
        val rootfsDir = tempFolder.newFolder("ubuntu-rootfs")
        val etcDir = File(rootfsDir, "etc").apply { mkdirs() }
        File(etcDir, "os-release").writeText(
            """
            NAME="Ubuntu"
            VERSION="24.04 LTS (Noble Numbat)"
            ID=ubuntu
            ID_LIKE=debian
            PRETTY_NAME="Ubuntu 24.04 LTS"
            VERSION_ID="24.04"
            """.trimIndent()
        )

        val info = bootstrapper.detectDistribution(rootfsDir)
        assertEquals(DistroFamily.DEBIAN, info.family)
        assertEquals("ubuntu", info.id)

        val script = bootstrapper.buildBootstrapScript(rootfsDir, username = "devuser")
        assertTrue(script.contains("apt-get update"))
        assertTrue(script.contains("devuser ALL=(ALL:ALL) NOPASSWD: ALL"))
        assertTrue(script.contains("/etc/resolv.conf"))
        assertTrue(script.contains("PULSE_SERVER=unix:/run/user/1000/pulse/native"))
    }

    @Test
    fun testDetectArchLinux() {
        val rootfsDir = tempFolder.newFolder("arch-rootfs")
        val etcDir = File(rootfsDir, "etc").apply { mkdirs() }
        File(etcDir, "os-release").writeText(
            """
            NAME="Arch Linux ARM"
            PRETTY_NAME="Arch Linux ARM"
            ID=archarm
            ID_LIKE=arch
            """.trimIndent()
        )

        val info = bootstrapper.detectDistribution(rootfsDir)
        assertEquals(DistroFamily.ARCH, info.family)

        val script = bootstrapper.buildBootstrapScript(rootfsDir, username = "archdev")
        assertTrue(script.contains("pacman -Sy"))
        assertTrue(script.contains("useradd -m -s /bin/bash -G wheel archdev"))
    }

    @Test
    fun testDetectFedora() {
        val rootfsDir = tempFolder.newFolder("fedora-rootfs")
        val etcDir = File(rootfsDir, "etc").apply { mkdirs() }
        File(etcDir, "os-release").writeText(
            """
            NAME="Fedora Linux"
            VERSION="41 (Container Image)"
            ID=fedora
            VERSION_ID=41
            """.trimIndent()
        )

        val info = bootstrapper.detectDistribution(rootfsDir)
        assertEquals(DistroFamily.FEDORA, info.family)

        val script = bootstrapper.buildBootstrapScript(rootfsDir, username = "fedoradev")
        assertTrue(script.contains("dnf makecache"))
    }

    @Test
    fun testDetectAlpine() {
        val rootfsDir = tempFolder.newFolder("alpine-rootfs")
        val etcDir = File(rootfsDir, "etc").apply { mkdirs() }
        File(etcDir, "os-release").writeText(
            """
            NAME="Alpine Linux"
            ID=alpine
            VERSION_ID=3.21.0
            PRETTY_NAME="Alpine Linux v3.21"
            """.trimIndent()
        )

        val info = bootstrapper.detectDistribution(rootfsDir)
        assertEquals(DistroFamily.ALPINE, info.family)

        val script = bootstrapper.buildBootstrapScript(rootfsDir, username = "alpinedev")
        assertTrue(script.contains("apk update"))
        assertTrue(script.contains("alpinedev"))
    }
}
