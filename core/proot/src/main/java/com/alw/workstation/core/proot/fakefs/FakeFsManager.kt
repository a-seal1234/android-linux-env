package com.alw.workstation.core.proot.fakefs

import android.os.SystemClock
import com.alw.workstation.core.proot.RuntimeDirectories
import com.alw.workstation.core.proot.model.BindMount
import java.io.File
import java.io.FileInputStream

/**
 * Manages the host-backed Virtual Fake Filesystem layer for unrooted PRoot execution.
 *
 * Responsibilities:
 * 1. Materializes writable host-backed directories:
 *    - `dev/shm`, `tmp`, `var/tmp`, `run`, `run/lock`, `run/dbus`, `run/user/1000`
 * 2. Materializes standard network configuration files:
 *    - `etc/resolv.conf`, `etc/hosts`
 * 3. Generates synthetic proc nodes:
 *    - `proc/stat`, `proc/uptime`, `proc/loadavg`, `proc/version`
 *    and binds them ONLY when the corresponding host proc node is unreadable
 *    due to Android 16 SELinux restrictions.
 */
class FakeFsManager(
    val baseDir: File,
    private val hostProcChecker: (String) -> Boolean = defaultHostProcChecker
) {
    companion object {
        val defaultHostProcChecker: (String) -> Boolean = { path ->
            val file = File(path)
            if (!file.exists()) {
                false
            } else {
                try {
                    FileInputStream(file).use { it.read() }
                    true
                } catch (_: Throwable) {
                    false
                }
            }
        }
    }

    constructor(
        dirs: RuntimeDirectories,
        hostProcChecker: (String) -> Boolean = defaultHostProcChecker
    ) : this(dirs.fakefsBaseDir, hostProcChecker)

    val devShmDir: File get() = File(baseDir, "dev/shm")
    val tmpDir: File get() = File(baseDir, "tmp")
    val varTmpDir: File get() = File(baseDir, "var/tmp")
    val runDir: File get() = File(baseDir, "run")
    val runLockDir: File get() = File(runDir, "lock")
    val runDbusDir: File get() = File(runDir, "dbus")
    val runUser1000Dir: File get() = File(runDir, "user/1000")

    val etcResolvConf: File get() = File(baseDir, "etc/resolv.conf")
    val etcHosts: File get() = File(baseDir, "etc/hosts")

    val procStat: File get() = File(baseDir, "proc/stat")
    val procUptime: File get() = File(baseDir, "proc/uptime")
    val procLoadavg: File get() = File(baseDir, "proc/loadavg")
    val procVersion: File get() = File(baseDir, "proc/version")

    /**
     * Materializes all fake filesystem directories and files on host storage.
     */
    fun materialize(): FakeFsManager {
        // Materialize writable host-backed directories
        listOf(
            devShmDir,
            tmpDir,
            varTmpDir,
            runDir,
            runLockDir,
            runDbusDir,
            runUser1000Dir
        ).forEach { dir ->
            if (!dir.exists()) {
                dir.mkdirs()
            }
            try {
                dir.setReadable(true, false)
                dir.setWritable(true, false)
                dir.setExecutable(true, false)
            } catch (_: Throwable) {}
        }

        // Subdirectories required inside tmp
        val runtimeAlwDir = File(tmpDir, "runtime-alw")
        if (!runtimeAlwDir.exists()) {
            runtimeAlwDir.mkdirs()
            try {
                runtimeAlwDir.setReadable(true, false)
                runtimeAlwDir.setWritable(true, false)
                runtimeAlwDir.setExecutable(true, false)
            } catch (_: Throwable) {}
        }

        // Bridge sockets into run/user/1000
        val runUser1000Pulse = File(runUser1000Dir, "pulse")
        if (!runUser1000Pulse.exists()) {
            try {
                java.nio.file.Files.createSymbolicLink(
                    runUser1000Pulse.toPath(),
                    java.nio.file.Paths.get("/tmp/runtime-alw/pulse")
                )
            } catch (_: Throwable) {}
        }
        val runUser1000W0 = File(runUser1000Dir, "wayland-0")
        if (!runUser1000W0.exists()) {
            try {
                java.nio.file.Files.createSymbolicLink(
                    runUser1000W0.toPath(),
                    java.nio.file.Paths.get("/tmp/runtime-alw/wayland-0")
                )
            } catch (_: Throwable) {}
        }
        val runUser1000W1 = File(runUser1000Dir, "wayland-1")
        if (!runUser1000W1.exists()) {
            try {
                java.nio.file.Files.createSymbolicLink(
                    runUser1000W1.toPath(),
                    java.nio.file.Paths.get("/tmp/runtime-alw/wayland-1")
                )
            } catch (_: Throwable) {}
        }

        // Materialize network configuration files
        etcResolvConf.parentFile?.mkdirs()
        etcResolvConf.writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")

        etcHosts.parentFile?.mkdirs()
        etcHosts.writeText("127.0.0.1 localhost\n::1 localhost\n")

        // Materialize synthetic /proc nodes
        procStat.parentFile?.mkdirs()
        procStat.writeText(generateSyntheticStat())
        procUptime.writeText(generateSyntheticUptime())
        procLoadavg.writeText("0.10 0.08 0.05 1/250 1000\n")
        procVersion.writeText("Linux version 6.6.0-alw-proot-aarch64 (antigravity@alw-workstation) (gcc version 14.2.1 20240910 (GCC)) #1 SMP PREEMPT Sun Nov 15 20:00:00 UTC 2026\n")

        return this
    }

    private fun generateSyntheticStat(): String = buildString {
        appendLine("cpu  102345 5432 23456 1234567 1234 0 543 0 0 0")
        for (i in 0 until 8) {
            appendLine("cpu$i 12345 543 2345 154321 123 0 54 0 0 0")
        }
        appendLine("intr 1234567 0 0 0")
        appendLine("ctxt 2345678")
        appendLine("btime 1700000000")
        appendLine("processes 1234")
        appendLine("procs_running 1")
        appendLine("procs_blocked 0")
        appendLine("softirq 123456 0 54321 0 0 0 0 123 0 0 0")
    }

    private fun generateSyntheticUptime(): String {
        val uptimeSeconds = try {
            (SystemClock.elapsedRealtime() / 1000.0).coerceAtLeast(100.0)
        } catch (_: Throwable) {
            12345.67
        }
        val idleSeconds = uptimeSeconds * 6.0
        return String.format(java.util.Locale.US, "%.2f %.2f\n", uptimeSeconds, idleSeconds)
    }

    /**
     * Computes all BindMounts to be passed to PRoot.
     * Synthetic proc nodes are only bound if host proc nodes are unreadable.
     */
    fun getBindMounts(): List<BindMount> {
        val mounts = mutableListOf<BindMount>()

        // 1. Host-backed writable directories
        mounts.add(BindMount(devShmDir.absolutePath, "/dev/shm"))
        mounts.add(BindMount(tmpDir.absolutePath, "/tmp"))
        mounts.add(BindMount(varTmpDir.absolutePath, "/var/tmp"))
        mounts.add(BindMount(runDir.absolutePath, "/run"))

        // 2. DNS and hosts
        mounts.add(BindMount(etcResolvConf.absolutePath, "/etc/resolv.conf"))
        mounts.add(BindMount(etcHosts.absolutePath, "/etc/hosts"))

        // 3. Synthetic proc nodes (bound ONLY when host proc node is unreadable)
        val procNodes = listOf(
            "/proc/stat" to procStat,
            "/proc/uptime" to procUptime,
            "/proc/loadavg" to procLoadavg,
            "/proc/version" to procVersion
        )

        for ((hostNode, syntheticFile) in procNodes) {
            if (!hostProcChecker(hostNode)) {
                mounts.add(BindMount(syntheticFile.absolutePath, hostNode))
            }
        }

        return mounts
    }
}
