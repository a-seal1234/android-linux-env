package com.alw.workstation.core.proot.driver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

class ElfAbiValidatorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun createSyntheticElf(
        machine: Int = 183, // EM_AARCH64
        is64Bit: Boolean = true,
        strings: List<String>
    ): ByteArray {
        val stream = ByteArrayOutputStream()
        // 0..3: ELF Magic
        stream.write(byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))
        // 4: Class (2 = 64-bit, 1 = 32-bit)
        stream.write(if (is64Bit) 2 else 1)
        // 5: Data (1 = Little-endian)
        stream.write(1)
        // 6: Version
        stream.write(1)
        // 7: OS/ABI
        stream.write(0)
        // 8..15: Padding
        stream.write(ByteArray(8))
        // 16..17: e_type (3 = ET_DYN)
        stream.write(byteArrayOf(3, 0))
        // 18..19: e_machine
        stream.write(byteArrayOf((machine and 0xFF).toByte(), ((machine shr 8) and 0xFF).toByte()))
        // 20..23: e_version
        stream.write(byteArrayOf(1, 0, 0, 0))

        // Pad up to 64-byte ELF header
        while (stream.size() < 64) {
            stream.write(0)
        }

        // Strings / Symbols payload
        for (s in strings) {
            stream.write(s.toByteArray(Charsets.US_ASCII))
            stream.write(0)
        }

        return stream.toByteArray()
    }

    @Test
    fun testValidGlibcAarch64Elf() {
        val bytes = createSyntheticElf(
            machine = 183,
            is64Bit = true,
            strings = listOf("libvulkan_freedreno.so", "libc.so.6", "GLIBC_2.38", "libm.so.6")
        )
        val file = File(tempFolder.root, "libvulkan_freedreno.so").apply { writeBytes(bytes) }

        val result = ElfAbiValidator.validate(file)

        assertTrue(result.isValid)
        assertEquals(ElfAbiType.GLIBC_AARCH64, result.abiType)
        assertTrue(result.isAarch64)
        assertTrue(result.is64Bit)
        assertTrue(result.detectedLibraries.contains("libc.so.6"))
    }

    @Test
    fun testIncompatibleBionicAndroidElfFlagged() {
        val bytes = createSyntheticElf(
            machine = 183,
            is64Bit = true,
            strings = listOf("libvulkan_freedreno.so", "/system/bin/linker64", "liblog.so", "__android_log_print", "libc.so")
        )
        val file = File(tempFolder.root, "bionic_freedreno.so").apply { writeBytes(bytes) }

        val result = ElfAbiValidator.validate(file)

        assertFalse(result.isValid)
        assertEquals(ElfAbiType.BIONIC_ANDROID, result.abiType)
        assertTrue(result.isAarch64)
        assertTrue(result.detailMessage.contains("Android Bionic"))
    }

    @Test
    fun testUnsupportedArchitectureX86_64() {
        // EM_X86_64 = 62
        val bytes = createSyntheticElf(
            machine = 62,
            is64Bit = true,
            strings = listOf("libc.so.6", "GLIBC_2.34")
        )
        val file = File(tempFolder.root, "x86_freedreno.so").apply { writeBytes(bytes) }

        val result = ElfAbiValidator.validate(file)

        assertFalse(result.isValid)
        assertEquals(ElfAbiType.UNSUPPORTED_ARCH, result.abiType)
        assertFalse(result.isAarch64)
    }

    @Test
    fun testInvalidNonElfFile() {
        val file = File(tempFolder.root, "corrupted.so").apply { writeText("THIS IS NOT AN ELF FILE") }

        val result = ElfAbiValidator.validate(file)

        assertFalse(result.isValid)
        assertEquals(ElfAbiType.INVALID_ELF, result.abiType)
    }
}
