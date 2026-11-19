package com.alw.workstation.core.session.rice

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Window
import java.io.File
import java.net.Inet4Address
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Android host bridge for guest desktop rices (TASK-025).
 *
 * Serves a line-oriented protocol on `$XDG_RUNTIME_DIR/alw-host.sock` so Waybar / AGS /
 * Quickshell / eww / SwayNC and their CLI helpers can read and drive real device state
 * (battery, screen brightness, media volume, network) instead of failing on missing
 * systemd / upower / NetworkManager / sysfs nodes.
 */
/**
 * Live graphics state exposed to guest rices (FPS, dynamic render scale).
 */
interface RiceGraphicsControl {
    fun actualFps(): Float
    fun renderScale(): Float
    fun setRenderScale(scale: Float)
    fun outputSize(): Pair<Int, Int>
}

class RiceHostBridge(
    private val context: Context,
    private val socketFile: File,
    private val sysfsRoot: File? = null,
    private val windowProvider: (() -> Window?)? = null,
    private val sysfsRefreshMillis: Long = 10_000L,
    private val graphics: RiceGraphicsControl? = null
) {
    companion object {
        const val TAG = "ALW::RiceHostBridge"
        const val SOCKET_NAME = "alw-host.sock"
    }

    private val running = AtomicBoolean(false)
    private var serverFd = -1
    private var acceptThread: Thread? = null
    private var sysfsThread: Thread? = null

    @Volatile
    private var lastBrightness = 50

    data class BatterySnapshot(
        val capacity: Int,
        val status: String,
        val present: Boolean,
        val voltageUv: Long,
        val currentUa: Long,
        val temperatureTenthsC: Int
    )

    data class VolumeSnapshot(val volume: Int, val muted: Boolean)

    fun start() {
        if (!running.compareAndSet(false, true)) return
        ensureNativeLoaded()

        try {
            socketFile.parentFile?.mkdirs()
            if (socketFile.exists()) socketFile.delete()

            // Android's public API cannot bind an AF_UNIX path, so the listening
            // socket is created (and accepted) by the native bridge library.
            val fd = nativeCreateUnixServer(socketFile.absolutePath)
            if (fd < 0) {
                Log.e(TAG, "native bind failed ($fd)")
                running.set(false)
                return
            }
            serverFd = fd
        } catch (e: Throwable) {
            Log.e(TAG, "Could not bind ${socketFile.absolutePath}: ${e.message}")
            running.set(false)
            return
        }
        Log.i(TAG, "Host bridge listening on ${socketFile.absolutePath}")

        acceptThread = Thread({ acceptLoop() }, "alw-rice-host").apply {
            isDaemon = true
            start()
        }

        syncBrightnessFromWindow()
        if (sysfsRoot != null) {
            sysfsThread = Thread({ sysfsLoop() }, "alw-rice-sysfs").apply {
                isDaemon = true
                start()
            }
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        if (serverFd >= 0) {
            nativeCloseFd(serverFd)
            serverFd = -1
        }
        acceptThread?.join(500)
        sysfsThread?.join(500)
        acceptThread = null
        sysfsThread = null
        try {
            if (socketFile.exists()) socketFile.delete()
        } catch (_: Throwable) {}
        Log.i(TAG, "Host bridge stopped")
    }

    val isRunning: Boolean get() = running.get()

    private external fun nativeCreateUnixServer(path: String): Int
    private external fun nativeAccept(): Int
    private external fun nativeReadLine(fd: Int): ByteArray?
    private external fun nativeWriteFd(fd: Int, data: ByteArray): Int
    private external fun nativeCloseFd(fd: Int)

    private fun ensureNativeLoaded(): Boolean = try {
        System.loadLibrary("wayland_bridge")
        true
    } catch (_: Throwable) {
        false
    }

    // --- server ------------------------------------------------------------

    private fun acceptLoop() {
        while (running.get()) {
            val clientFd = nativeAccept()
            if (clientFd < 0) {
                if (!running.get()) return
                if (clientFd == -4 /* EINTR */) continue
                Log.w(TAG, "accept failed: $clientFd")
                continue
            }
            Thread({ handleConnection(clientFd) }, "alw-rice-conn").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun handleConnection(clientFd: Int) {
        try {
            val request = nativeReadLine(clientFd)?.toString(Charsets.UTF_8)?.trim()
            if (!request.isNullOrEmpty()) {
                val response = execute(request)
                nativeWriteFd(clientFd, response.toByteArray(Charsets.UTF_8))
            }
        } catch (e: Throwable) {
            Log.w(TAG, "connection error: ${e.message}")
        } finally {
            nativeCloseFd(clientFd)
        }
    }

    internal fun execute(request: String): String {
        val parts = request.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return "error=empty-request\n"

        return try {
            when (parts[0]) {
                "battery" -> battery()
                "brightness" -> brightness(parts)
                "volume" -> volume(parts)
                "network" -> network()
                "fps" -> fps()
                "scale" -> scale(parts)
                "open" -> open(parts.drop(1).joinToString(" "))
                else -> "error=unknown-command\n"
            }
        } catch (e: Throwable) {
            Log.w(TAG, "command '$request' failed: ${e.message}")
            "error=${e.message ?: "failed"}\n"
        }
    }

    // --- graphics ----------------------------------------------------------

    private fun fps(): String {
        val control = graphics ?: return "error=graphics-unavailable\n"
        val (width, height) = control.outputSize()
        return "fps=${control.actualFps().toInt()} res=${width}x${height} " +
            "scale=${"%.2f".format(control.renderScale())} gpu=zink-adreno825\n"
    }

    private fun scale(parts: List<String>): String {
        val control = graphics ?: return "error=graphics-unavailable\n"
        val action = parts.getOrNull(1) ?: "get"
        return when (action) {
            "get" -> "scale=${"%.2f".format(control.renderScale())}\n"
            "set" -> {
                val requested = parts.getOrNull(2)?.toFloatOrNull()
                    ?: return "error=invalid-scale\n"
                if (requested < 0.35f || requested > 1.0f) return "error=out-of-range\n"
                control.setRenderScale(requested)
                "ok=1 scale=${"%.2f".format(control.renderScale())}\n"
            }
            else -> "error=unknown-scale-command\n"
        }
    }

    // --- battery -----------------------------------------------------------

    fun readBattery(): BatterySnapshot {
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val voltageMv = sticky?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val temperature = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val statusCode = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val present = sticky?.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true) ?: true

        val propCapacity = manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val capacity = when {
            level >= 0 && scale > 0 -> (level * 100 / scale)
            else -> propCapacity ?: 0
        }.coerceIn(0, 100)

        val currentUa = manager?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: 0L

        val status = when (statusCode) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
            BatteryManager.BATTERY_STATUS_FULL -> "Full"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
            else -> "Unknown"
        }

        return BatterySnapshot(
            capacity = capacity,
            status = status,
            present = present,
            voltageUv = voltageMv * 1000L,
            currentUa = currentUa,
            temperatureTenthsC = temperature
        )
    }

    private fun battery(): String {
        val b = readBattery()
        return buildString {
            appendLine("capacity=${b.capacity}")
            appendLine("status=${b.status}")
            appendLine("present=${if (b.present) 1 else 0}")
            appendLine("voltage_now=${b.voltageUv}")
            appendLine("current_now=${b.currentUa}")
            appendLine("temperature=${b.temperatureTenthsC}")
        }
    }

    // --- brightness --------------------------------------------------------

    private fun brightness(parts: List<String>): String {
        val action = parts.getOrNull(1) ?: "get"
        if (action == "set") {
            val requested = parts.getOrNull(2)?.toIntOrNull()
                ?: return "error=invalid-argument\n"
            applyBrightness(requested)
        }
        return "brightness=${currentBrightness()}\n"
    }

    fun currentBrightness(): Int = lastBrightness

    /** Reads the live window brightness once, on the main thread, and caches it. */
    private fun syncBrightnessFromWindow() {
        Handler(Looper.getMainLooper()).post {
            try {
                val attr = windowProvider?.invoke()?.attributes?.screenBrightness ?: -1f
                if (attr >= 0f) lastBrightness = (attr * 100f).roundToInt().coerceIn(0, 100)
            } catch (_: Throwable) {}
        }
    }

    fun applyBrightness(percent: Int): Int {
        val value = percent.coerceIn(0, 100)
        lastBrightness = value
        // Deferred off the current VSync dispatch: mutating window attributes inside
        // a frame callback re-enters the view hierarchy and corrupts the Choreographer.
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                val window = windowProvider?.invoke() ?: return@postDelayed
                val attrs = window.attributes
                attrs.screenBrightness = value / 100f
                window.attributes = attrs
            } catch (e: Throwable) {
                Log.w(TAG, "brightness apply failed: ${e.message}")
            }
        }, 64)
        updateSysfs()
        return value
    }

    // --- volume ------------------------------------------------------------

    private fun audioManager(): AudioManager? =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    fun readVolume(): VolumeSnapshot {
        val audio = audioManager() ?: return VolumeSnapshot(0, false)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val muted = try {
            audio.isStreamMute(AudioManager.STREAM_MUSIC)
        } catch (_: Throwable) {
            current == 0
        }
        return VolumeSnapshot((current * 100.0 / max).roundToInt().coerceIn(0, 100), muted)
    }

    private fun volume(parts: List<String>): String {
        val action = parts.getOrNull(1) ?: "get"
        val audio = audioManager() ?: return "error=no-audio\n"
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

        when (action) {
            "set" -> {
                val target = parts.getOrNull(2)?.toIntOrNull() ?: return "error=invalid-argument\n"
                val index = (target.coerceIn(0, 100) * max / 100.0).roundToInt()
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
            }
            "step" -> {
                val delta = parts.getOrNull(2)?.toIntOrNull() ?: return "error=invalid-argument\n"
                val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                val step = (kotlin.math.abs(delta) * max / 100.0).roundToInt().coerceAtLeast(1)
                val target = if (delta >= 0) current + step else current - step
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, target.coerceIn(0, max), 0)
            }
            "mute" -> {
                when (parts.getOrNull(2)) {
                    "toggle" -> audio.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0
                    )
                    "1", "on", "true" -> audio.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0
                    )
                    "0", "off", "false" -> audio.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0
                    )
                    else -> return "error=invalid-argument\n"
                }
            }
        }

        val snapshot = readVolume()
        return "volume=${snapshot.volume}\nmuted=${if (snapshot.muted) 1 else 0}\n"
    }

    // --- network -----------------------------------------------------------

    fun networkSnapshot(): Map<String, String> {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val active = cm?.activeNetwork
        val caps = active?.let { cm.getNetworkCapabilities(it) }
        val link = active?.let { cm.getLinkProperties(it) }

        val transport = when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "other"
        }

        val ip = link?.linkAddresses
            ?.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
            ?.address?.hostAddress ?: ""

        var ssid = ""
        var signal = 0
        if (transport == "wifi") {
            try {
                val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val info = wifi?.connectionInfo
                val raw = info?.ssid ?: ""
                ssid = raw.trim('"').takeUnless { it == "<unknown ssid>" || it == "0x" } ?: ""
                signal = WifiManager.calculateSignalLevel(info?.rssi ?: -100, 100).coerceIn(0, 100)
            } catch (e: Throwable) {
                Log.d(TAG, "SSID unavailable: ${e.message}")
            }
        }

        return mapOf(
            "connected" to if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) "1" else "0",
            "ssid" to ssid,
            "signal" to signal.toString(),
            "ip" to ip,
            "transport" to transport
        )
    }

    private fun network(): String =
        networkSnapshot().entries.joinToString("") { "${it.key}=${it.value}\n" }

    // --- open --------------------------------------------------------------

    private fun open(target: String): String {
        if (target.isBlank()) return "error=no-target\n"
        val uri = when {
            target.startsWith("http://") || target.startsWith("https://") ||
                target.startsWith("mailto:") || target.startsWith("content:") -> Uri.parse(target)
            target.startsWith("file://") -> Uri.parse(target)
            else -> {
                val file = File(target)
                if (file.exists()) Uri.fromFile(file) else Uri.parse(target)
            }
        }

        return try {
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "ok=1\n"
        } catch (e: Throwable) {
            Log.w(TAG, "open '$target' failed: ${e.message}")
            "error=${e.message ?: "no-activity"}\n"
        }
    }

    // --- fake sysfs --------------------------------------------------------

    private fun sysfsLoop() {
        while (running.get()) {
            try {
                updateSysfs()
            } catch (e: Throwable) {
                Log.w(TAG, "sysfs refresh failed: ${e.message}")
            }
            try {
                Thread.sleep(sysfsRefreshMillis)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    /** Mirrors live battery + brightness into the guest-visible fake /sys tree. */
    fun updateSysfs() {
        val root = sysfsRoot ?: return
        val bat = readBattery()
        val batteryDir = File(root, "sys/class/power_supply/BAT0")
        writeSysfs(batteryDir, "capacity", bat.capacity)
        writeSysfs(batteryDir, "status", bat.status)
        writeSysfs(batteryDir, "present", if (bat.present) 1 else 0)
        writeSysfs(batteryDir, "type", "Battery")
        writeSysfs(batteryDir, "voltage_now", bat.voltageUv)
        writeSysfs(batteryDir, "current_now", bat.currentUa)
        writeSysfs(batteryDir, "temperature", bat.temperatureTenthsC)
        writeSysfs(
            batteryDir,
            "uevent",
            "POWER_SUPPLY_NAME=BAT0\n" +
                "POWER_SUPPLY_TYPE=Battery\n" +
                "POWER_SUPPLY_STATUS=${bat.status}\n" +
                "POWER_SUPPLY_PRESENT=${if (bat.present) 1 else 0}\n" +
                "POWER_SUPPLY_CAPACITY=${bat.capacity}\n" +
                "POWER_SUPPLY_VOLTAGE_NOW=${bat.voltageUv}\n" +
                "POWER_SUPPLY_CURRENT_NOW=${bat.currentUa}\n"
        )

        val backlightValue = currentBrightness()
        val backlightDir = File(root, "sys/class/backlight/alw_backlight")
        writeSysfs(backlightDir, "brightness", backlightValue)
        writeSysfs(backlightDir, "actual_brightness", backlightValue)
        writeSysfs(backlightDir, "max_brightness", 100)
    }

    private fun writeSysfs(dir: File, name: String, value: Any) {
        try {
            if (!dir.exists()) dir.mkdirs()
            val text = value.toString()
            File(dir, name).writeText(if (text.endsWith("\n")) text else "$text\n")
        } catch (_: Throwable) {}
    }
}
