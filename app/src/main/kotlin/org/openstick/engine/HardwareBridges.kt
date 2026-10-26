package org.openstick.engine

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import org.openstick.bridge.*
import java.io.File

object HardwareBridges {
    private const val TAG = "HardwareBridges"

    var cameraBridgeEnabled = true
    var micBridgeEnabled = true
    var audioOutputEnabled = true
    var notificationBridgeEnabled = true
    var calendarSyncEnabled = true
    var clipboardSyncEnabled = true
    var printingBridgeEnabled = true
    var sshServerEnabled = false
    var vncServerEnabled = false
    var processLimit = 32

    fun applyDnsConfig(env: LinuxEnvironment, dnsChoice: String, customIp: String = "") {
        dnsBridge?.applyCurrentDns()
    }

    fun startSshServer(runner: ProotRunner) {
        sshServerEnabled = true
        runner.start(
            command = listOf(
                "/bin/sh", "-c",
                "service dropbear start 2>/dev/null || /usr/sbin/dropbear -p 2222 2>/dev/null || service ssh start"
            )
        )
    }

    fun stopSshServer(runner: ProotRunner) {
        sshServerEnabled = false
        runner.start(
            command = listOf("/bin/sh", "-c", "killall dropbear 2>/dev/null || killall sshd 2>/dev/null")
        )
    }

    fun startVncServer(runner: ProotRunner) {
        vncServerEnabled = true
        runner.start(
            command = listOf(
                "/bin/sh", "-c",
                "x11vnc -display :0 -rfbport 5900 -shared -forever -bg 2>/dev/null || tightvncserver :1"
            )
        )
    }

    fun stopVncServer(runner: ProotRunner) {
        vncServerEnabled = false
        runner.start(
            command = listOf("/bin/sh", "-c", "killall x11vnc 2>/dev/null || killall Xvnc 2>/dev/null")
        )
    }

    fun repairPackages(runner: ProotRunner, onProgress: (String) -> Unit) {
        runner.start(
            command = listOf(
                "/bin/sh", "-c",
                "export DEBIAN_FRONTEND=noninteractive; dpkg --configure -a 2>&1; apt-get -f install -y 2>&1; apt-get clean 2>&1; echo 'Repair complete.'"
            ),
            asRoot = true,
            onOutput = onProgress
        )
    }

    fun forceStopSession(runner: ProotRunner, onOutput: (String) -> Unit) {
        runner.start(
            command = listOf("/bin/sh", "-c", "kill -9 -1 2>/dev/null; echo 'Session stopped.'"),
            asRoot = true,
            onOutput = onOutput
        )
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
