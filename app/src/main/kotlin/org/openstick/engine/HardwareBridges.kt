package org.openstick.engine

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.openstick.bridge.*
import java.io.File

object HardwareBridges {
    private const val TAG = "HardwareBridges"

    var cameraBridgeEnabled = false
    var micBridgeEnabled = false
    var audioOutputEnabled = false
    var notificationBridgeEnabled = false
    var calendarSyncEnabled = false
    var clipboardSyncEnabled = false
    var printingBridgeEnabled = false
    var sshServerEnabled = false
    var vncServerEnabled = false
    var processLimit = 32

    fun applyDnsConfig(env: LinuxEnvironment, dnsChoice: String, customIp: String = "") {
        val servers = when (dnsChoice) {
            "Cloudflare" -> listOf("1.1.1.1", "1.0.0.1")
            "Google" -> listOf("8.8.8.8", "8.8.4.4")
            "OpenDNS" -> listOf("208.67.222.222", "208.67.220.220")
            "Custom" -> if (customIp.isNotBlank()) listOf(customIp) else listOf("1.1.1.1")
            else -> emptyList()
        }
        if (servers.isNotEmpty()) {
            if (dnsBridge != null) {
                dnsBridge?.applyCustomDns(servers)
            } else {
                try {
                    val f = File(env.rootfsDir, "etc/resolv.conf")
                    f.parentFile?.mkdirs()
                    f.writeText("# Configured by OpenStick\n" + servers.joinToString("") { "nameserver $it\n" })
                } catch (_: Exception) {}
            }
        } else {
            dnsBridge?.applyCurrentDns()
        }
    }

    fun toggleCamera(context: Context, rootfs: File, scope: CoroutineScope, enable: Boolean) {
        cameraBridgeEnabled = enable
        if (enable) {
            try {
                if (cameraBridge == null) {
                    cameraBridge = CameraBridge(context, rootfs).apply { start(scope) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting CameraBridge", e)
            }
        } else {
            try { cameraBridge?.stop() } catch (_: Exception) {}
            cameraBridge = null
        }
    }

    fun toggleMic(context: Context, rootfs: File, scope: CoroutineScope, enable: Boolean) {
        micBridgeEnabled = enable
        if (enable) {
            try {
                if (micBridge == null) {
                    micBridge = MicBridge(context, rootfs).apply { start(scope) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting MicBridge", e)
            }
        } else {
            try { micBridge?.stop() } catch (_: Exception) {}
            micBridge = null
        }
    }

    fun toggleAudio(scope: CoroutineScope, enable: Boolean) {
        audioOutputEnabled = enable
        if (enable) {
            try {
                if (audioBridge == null) {
                    audioBridge = AudioBridge().apply { start(scope) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting AudioBridge", e)
            }
        } else {
            try { audioBridge?.stop() } catch (_: Exception) {}
            audioBridge = null
        }
    }

    fun toggleNotifications(context: Context, rootfs: File, enable: Boolean) {
        notificationBridgeEnabled = enable
        if (enable) {
            try {
                if (guestEventBridge == null) {
                    guestEventBridge = GuestEventBridge(context, rootfs).apply { start() }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting GuestEventBridge", e)
            }
        } else {
            try { guestEventBridge?.stop() } catch (_: Exception) {}
            guestEventBridge = null
        }
    }

    fun toggleClipboard(context: Context, rootfs: File, enable: Boolean) {
        clipboardSyncEnabled = enable
        if (enable) {
            try {
                if (clipboardBridge == null) {
                    clipboardBridge = ClipboardBridge(context, rootfs).apply { start() }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting ClipboardBridge", e)
            }
        } else {
            try { clipboardBridge?.stop() } catch (_: Exception) {}
            clipboardBridge = null
        }
    }

    fun togglePrinting(context: Context, rootfs: File, enable: Boolean) {
        printingBridgeEnabled = enable
        if (enable) {
            try {
                if (printBridge == null) {
                    printBridge = PrintBridge(context, rootfs).apply { start() }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting PrintBridge", e)
            }
        } else {
            try { printBridge?.stop() } catch (_: Exception) {}
            printBridge = null
        }
    }

    fun toggleCalendar(context: Context, rootfs: File, enable: Boolean) {
        calendarSyncEnabled = enable
        if (enable) {
            try {
                if (calendarBridge == null) {
                    calendarBridge = CalendarBridge(context, rootfs).apply { sync() }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing CalendarBridge", e)
            }
        } else {
            calendarBridge = null
        }
    }

    fun startSshServer(runner: ProotRunner, scope: CoroutineScope) {
        sshServerEnabled = true
        scope.launch {
            runner.executeOneShot(
                command = listOf(
                    "/bin/sh", "-c",
                    "service dropbear start 2>/dev/null || /usr/sbin/dropbear -p 2222 2>/dev/null || service ssh start"
                ),
                asRoot = true
            )
        }
    }

    fun stopSshServer(runner: ProotRunner, scope: CoroutineScope) {
        sshServerEnabled = false
        scope.launch {
            runner.executeOneShot(
                command = listOf("/bin/sh", "-c", "killall dropbear 2>/dev/null || killall sshd 2>/dev/null"),
                asRoot = true
            )
        }
    }

    fun startVncServer(runner: ProotRunner, scope: CoroutineScope) {
        vncServerEnabled = true
        scope.launch {
            runner.executeOneShot(
                command = listOf(
                    "/bin/sh", "-c",
                    "x11vnc -display :0 -rfbport 5900 -shared -forever -bg 2>/dev/null || tightvncserver :1"
                ),
                asRoot = true
            )
        }
    }

    fun stopVncServer(runner: ProotRunner, scope: CoroutineScope) {
        vncServerEnabled = false
        scope.launch {
            runner.executeOneShot(
                command = listOf("/bin/sh", "-c", "killall x11vnc 2>/dev/null || killall Xvnc 2>/dev/null"),
                asRoot = true
            )
        }
    }

    fun repairPackages(runner: ProotRunner, scope: CoroutineScope, onProgress: (String) -> Unit) {
        scope.launch {
            runner.executeOneShot(
                command = listOf(
                    "/bin/sh", "-c",
                    "export DEBIAN_FRONTEND=noninteractive; dpkg --configure -a 2>&1; apt-get -f install -y 2>&1; apt-get clean 2>&1; echo 'Repair complete.'"
                ),
                asRoot = true,
                onOutput = onProgress
            )
        }
    }

    fun forceStopSession(runner: ProotRunner, onOutput: (String) -> Unit) {
        runner.kill()
        try {
            Runtime.getRuntime().exec(
                arrayOf(
                    "sh",
                    "-c",
                    "pkill -9 -f libproot.so 2>/dev/null; pkill -9 -f xfce4 2>/dev/null; pkill -9 -f xfwm4 2>/dev/null; pkill -9 -f xfdesktop 2>/dev/null; pkill -9 -f openbox 2>/dev/null; rm -f /tmp/runtime-*/bus /tmp/.X*-lock /tmp/.X11-unix/X* 2>/dev/null || true"
                )
            ).waitFor()
        } catch (_: Exception) {}
        onOutput("All sessions forcefully stopped and lock files cleared.")
    }

    // Active bridge instances
    private var guestEventBridge: GuestEventBridge? = null
    private var clipboardBridge: ClipboardBridge? = null
    private var hostBridge: HostBridge? = null
    private var dnsBridge: DnsBridge? = null
    private var audioBridge: AudioBridge? = null
    private var micBridge: MicBridge? = null
    private var cameraBridge: CameraBridge? = null
    private var calendarBridge: CalendarBridge? = null
    private var printBridge: PrintBridge? = null

    /**
     * Start all enabled background hardware and software bridges.
     */
    fun startAll(context: Context, env: LinuxEnvironment, scope: CoroutineScope) {
        val rootfs = env.rootfsDir
        if (!env.isInstalled()) return

        installGuestShims(rootfs)

        // 1. Dynamic DNS
        try {
            if (dnsBridge == null) {
                dnsBridge = DnsBridge(context, rootfs).apply { start() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting DnsBridge", e)
        }

        // 2. Host Battery & Network Status
        try {
            if (hostBridge == null) {
                hostBridge = HostBridge(context, rootfs).apply { start(scope) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting HostBridge", e)
        }

        // 3. Guest Notifications & URL Opener
        try {
            if (notificationBridgeEnabled && guestEventBridge == null) {
                guestEventBridge = GuestEventBridge(context, rootfs).apply { start() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting GuestEventBridge", e)
        }

        // 4. Two-Way Clipboard Bridge
        try {
            if (clipboardSyncEnabled && clipboardBridge == null) {
                clipboardBridge = ClipboardBridge(context, rootfs).apply { start() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting ClipboardBridge", e)
        }

        // 5. Audio Output Playback (PulseAudio TCP -> Android AudioTrack)
        try {
            if (audioOutputEnabled && audioBridge == null) {
                audioBridge = AudioBridge().apply { start(scope) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting AudioBridge", e)
        }

        // 6. Microphone Input (AudioRecord -> PulseAudio FIFO)
        try {
            if (micBridgeEnabled && micBridge == null) {
                micBridge = MicBridge(context, rootfs).apply { start(scope) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting MicBridge", e)
        }

        // 7. Camera (Camera2 MJPEG Server -> /run/openstick/camera)
        try {
            if (cameraBridgeEnabled && cameraBridge == null) {
                cameraBridge = CameraBridge(context, rootfs).apply { start(scope) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting CameraBridge", e)
        }

        // 8. Printing Spool Watcher
        try {
            if (printingBridgeEnabled && printBridge == null) {
                printBridge = PrintBridge(context, rootfs).apply { start() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting PrintBridge", e)
        }

        // 9. Calendar Sync
        try {
            if (calendarSyncEnabled && calendarBridge == null) {
                calendarBridge = CalendarBridge(context, rootfs).apply { sync() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing CalendarBridge", e)
        }
    }

    /**
     * Stop all background bridges safely.
     */
    fun stopAll() {
        try { dnsBridge?.stop(); dnsBridge = null } catch (_: Exception) {}
        try { hostBridge?.stop(); hostBridge = null } catch (_: Exception) {}
        try { guestEventBridge?.stop(); guestEventBridge = null } catch (_: Exception) {}
        try { clipboardBridge?.stop(); clipboardBridge = null } catch (_: Exception) {}
        try { audioBridge?.stop(); audioBridge = null } catch (_: Exception) {}
        try { micBridge?.stop(); micBridge = null } catch (_: Exception) {}
        try { cameraBridge?.stop(); cameraBridge = null } catch (_: Exception) {}
        try { printBridge?.stop(); printBridge = null } catch (_: Exception) {}
        calendarBridge = null
    }

    /**
     * Installs wrapper shims for xdg-open and notify-send inside Debian rootfs.
     */
    private fun installGuestShims(rootfsDir: File) {
        val binDir = File(rootfsDir, "usr/local/bin")
        binDir.mkdirs()

        // notify-send shim
        val notifyScript = File(binDir, "notify-send")
        if (!notifyScript.exists()) {
            notifyScript.writeText(
                """
                #!/bin/sh
                app="Linux"
                summary="${'$'}1"
                body="${'$'}2"
                printf "%s\t%s\t%s\n" "${'$'}app" "${'$'}summary" "${'$'}body" >> /tmp/.openstick-notify 2>/dev/null || printf "%s\t%s\t%s\n" "${'$'}app" "${'$'}summary" "${'$'}body" >> /tmp/.bsl-notify
                """.trimIndent() + "\n"
            )
            notifyScript.setExecutable(true, false)
        }

        // xdg-open shim
        val openScript = File(binDir, "xdg-open")
        if (!openScript.exists()) {
            openScript.writeText(
                """
                #!/bin/sh
                echo "${'$'}1" >> /tmp/.openstick-open 2>/dev/null || echo "${'$'}1" >> /tmp/.bsl-open
                """.trimIndent() + "\n"
            )
            openScript.setExecutable(true, false)
        }
    }
}
