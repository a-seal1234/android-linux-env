package com.alw.workstation.core.proot.driver

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Target ABI classification for ELF binaries and shared libraries.
 */
enum class ElfAbiType {
    /** GNU C Library for 64-bit ARM (Standard Arch Linux ARM / Debian aarch64) */
    GLIBC_AARCH64,

    /** Android Bionic C Library for 64-bit ARM (Incompatible with glibc guest loaders) */
    BIONIC_ANDROID,

    /** 64-bit ARM ELF with unrecognized C library runtime */
    OTHER_AARCH64,

    /** Valid ELF but not 64-bit ARM (e.g. x86_64, armv7l, riscv64) */
    UNSUPPORTED_ARCH,

    /** Corrupted, truncated, or non-ELF file */
    INVALID_ELF
}

/**
 * Inspection result for ELF binary verification.
 */
data class ElfValidationResult(
    val isValid: Boolean,
    val abiType: ElfAbiType,
    val isAarch64: Boolean,
    val is64Bit: Boolean,
    val detectedLibraries: List<String>,
    val detailMessage: String
)

/**
 * Validates ELF header, machine architecture, and dynamic runtime symbols to prevent
 * Android Bionic-linked Vulkan drivers from crashing glibc loaders in Arch Linux PRoot.
 */
object ElfAbiValidator {

    private const val ELF_MAGIC_0 = 0x7F.toByte()
    private const val ELF_MAGIC_1 = 'E'.code.toByte()
    private const val ELF_MAGIC_2 = 'L'.code.toByte()
    private const val ELF_MAGIC_3 = 'F'.code.toByte()

    private const val ELFCLASS64 = 2
    private const val ELFDATA2LSB = 1
    private const val EM_AARCH64 = 183

    fun validate(file: File): ElfValidationResult {
        if (!file.exists() || !file.isFile || file.length() < 64) {
            return ElfValidationResult(
                isValid = false,
                abiType = ElfAbiType.INVALID_ELF,
                isAarch64 = false,
                is64Bit = false,
                detectedLibraries = emptyList(),
                detailMessage = "File does not exist or is too small to be an ELF binary"
            )
        }

        return try {
            RandomAccessFile(file, "r").use { raf ->
                val headerBytes = ByteArray(64)
                raf.readFully(headerBytes)
                validateHeaderAndContent(headerBytes, raf, file.length())
            }
        } catch (e: Exception) {
            ElfValidationResult(
                isValid = false,
                abiType = ElfAbiType.INVALID_ELF,
                isAarch64 = false,
                is64Bit = false,
                detectedLibraries = emptyList(),
                detailMessage = "I/O error reading ELF file: ${e.message}"
            )
        }
    }

    fun validateBytes(bytes: ByteArray): ElfValidationResult {
        if (bytes.size < 64) {
            return ElfValidationResult(
                isValid = false,
                abiType = ElfAbiType.INVALID_ELF,
                isAarch64 = false,
                is64Bit = false,
                detectedLibraries = emptyList(),
                detailMessage = "Byte array too small to be an ELF binary"
            )
        }
        val headerBytes = bytes.copyOfRange(0, 64)
        val fullBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return parseElf(headerBytes, { offset, dst ->
            if (offset < bytes.size) {
                fullBuffer.position(offset.toInt())
                val toRead = Math.min(dst.size, bytes.size - offset.toInt())
                fullBuffer.get(dst, 0, toRead)
                toRead
            } else {
                -1
            }
        }, bytes.size.toLong())
    }

    private fun validateHeaderAndContent(
        headerBytes: ByteArray,
        raf: RandomAccessFile,
        fileLength: Long
    ): ElfValidationResult {
        return parseElf(headerBytes, { offset, dst ->
            raf.seek(offset)
            raf.read(dst)
        }, fileLength)
    }

    private fun parseElf(
        headerBytes: ByteArray,
        reader: (offset: Long, dst: ByteArray) -> Int,
        fileLength: Long
    ): ElfValidationResult {
        // 1. Check Magic Number: 0x7F 'E' 'L' 'F'
        if (headerBytes[0] != ELF_MAGIC_0 ||
            headerBytes[1] != ELF_MAGIC_1 ||
            headerBytes[2] != ELF_MAGIC_2 ||
            headerBytes[3] != ELF_MAGIC_3
        ) {
            return ElfValidationResult(
                isValid = false,
                abiType = ElfAbiType.INVALID_ELF,
                isAarch64 = false,
                is64Bit = false,
                detectedLibraries = emptyList(),
                detailMessage = "Invalid ELF magic signature"
            )
        }

        // 2. Check 64-bit class
        val is64Bit = headerBytes[4].toInt() == ELFCLASS64
        val isLittleEndian = headerBytes[5].toInt() == ELFDATA2LSB

        if (!is64Bit || !isLittleEndian) {
            return ElfValidationResult(
                isValid = false,
                abiType = ElfAbiType.UNSUPPORTED_ARCH,
                isAarch64 = false,
                is64Bit = is64Bit,
                detectedLibraries = emptyList(),
                detailMessage = "ELF is not 64-bit Little Endian (class=${headerBytes[4]}, data=${headerBytes[5]})"
            )
        }

        // 3. Check Machine Architecture: EM_AARCH64 (183)
        val headerBuf = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
        val machine = headerBuf.getShort(18).toInt() and 0xFFFF
        val isAarch64 = machine == EM_AARCH64

        if (!isAarch64) {
            return ElfValidationResult(
                isValid = false,
                abiType = ElfAbiType.UNSUPPORTED_ARCH,
                isAarch64 = false,
                is64Bit = true,
                detectedLibraries = emptyList(),
                detailMessage = "ELF machine architecture is not AArch64 (machine=$machine, expected $EM_AARCH64)"
            )
        }

        // 4. Scan strings to detect runtime C library linkage (glibc vs Bionic)
        val strings = extractSampleStrings(reader, fileLength)
        val detectedLibs = mutableListOf<String>()

        var hasGlibcMarker = false
        var hasBionicMarker = false

        for (s in strings) {
            if (s.endsWith(".so") || s.contains(".so.")) {
                detectedLibs.add(s)
            }
            if (s == "libc.so.6" || s.startsWith("GLIBC_") || s.contains("ld-linux-aarch64.so.1")) {
                hasGlibcMarker = true
            }
            if (s == "liblog.so" || s == "/system/bin/linker64" ||
                s == "__android_log_print" || s == "__system_property_get" ||
                s == "android_set_abort_message"
            ) {
                hasBionicMarker = true
            }
        }

        // Check standalone "libc.so" without ".6" as Bionic indicator
        if (!hasGlibcMarker && detectedLibs.any { it == "libc.so" }) {
            hasBionicMarker = true
        }

        val abiType = when {
            hasGlibcMarker && !hasBionicMarker -> ElfAbiType.GLIBC_AARCH64
            hasBionicMarker && !hasGlibcMarker -> ElfAbiType.BIONIC_ANDROID
            hasGlibcMarker && hasBionicMarker -> {
                // Hybrid/thunk or ambiguous; mark Bionic to prevent glibc crash unless explicitly verified
                ElfAbiType.BIONIC_ANDROID
            }
            else -> ElfAbiType.OTHER_AARCH64
        }

        val isValid = abiType == ElfAbiType.GLIBC_AARCH64

        val message = when (abiType) {
            ElfAbiType.GLIBC_AARCH64 -> "Valid AArch64 ELF linked against glibc"
            ElfAbiType.BIONIC_ANDROID -> "Incompatible AArch64 ELF linked against Android Bionic C library (will crash glibc Vulkan loader)"
            ElfAbiType.OTHER_AARCH64 -> "AArch64 ELF with no detected glibc or Bionic runtime signatures"
            ElfAbiType.UNSUPPORTED_ARCH -> "Unsupported ELF machine architecture ($machine)"
            ElfAbiType.INVALID_ELF -> "Invalid ELF structure"
        }

        return ElfValidationResult(
            isValid = isValid,
            abiType = abiType,
            isAarch64 = true,
            is64Bit = true,
            detectedLibraries = detectedLibs.distinct(),
            detailMessage = message
        )
    }

    private fun extractSampleStrings(
        reader: (offset: Long, dst: ByteArray) -> Int,
        fileLength: Long
    ): Set<String> {
        val strings = mutableSetOf<String>()
        val buffer = ByteArray(65536)

        // Read initial chunk (covers ELF headers and .dynstr for typical libraries)
        val readCount = reader(0, buffer)
        if (readCount > 0) {
            extractAsciiStringsFromBuffer(buffer, readCount, strings)
        }

        // If file is larger, read the end chunk (often where section headers and string tables reside)
        if (fileLength > 65536) {
            val tailOffset = Math.max(0L, fileLength - 65536)
            val tailReadCount = reader(tailOffset, buffer)
            if (tailReadCount > 0) {
                extractAsciiStringsFromBuffer(buffer, tailReadCount, strings)
            }
        }

        return strings
    }

    private fun extractAsciiStringsFromBuffer(
        buffer: ByteArray,
        length: Int,
        outStrings: MutableSet<String>
    ) {
        val sb = java.lang.StringBuilder()
        for (i in 0 until length) {
            val b = buffer[i].toInt() and 0xFF
            if (b in 32..126) {
                sb.append(b.toChar())
            } else {
                if (sb.length in 3..128) {
                    outStrings.add(sb.toString())
                }
                sb.setLength(0)
            }
        }
        if (sb.length in 3..128) {
            outStrings.add(sb.toString())
        }
    }
}
