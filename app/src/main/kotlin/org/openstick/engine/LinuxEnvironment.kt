package org.openstick.engine

import android.content.Context
import android.os.Environment
import android.system.Os
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

class LinuxEnvironment(val context: Context) {
    val nativeLibDir: String = context.applicationInfo.nativeLibraryDir
    val prootBin = File(nativeLibDir, "libproot.so")
    val loaderBin = File(nativeLibDir, "libproot-loader.so")
    val virglServerBin = File(nativeLibDir, "libvirgl_server.so")
    val rootfsDir = File(context.filesDir, "rootfs")
    val tmpDir = File(context.filesDir, "tmp")
    val scriptsDir = File(context.filesDir, "scripts")

    fun prootEnv(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        if (loaderBin.exists()) {
            map["PROOT_LOADER"] = loaderBin.absolutePath
        }
        map["PROOT_TMP_DIR"] = tmpDir.absolutePath
        return map
    }

    fun isInstalled(): Boolean {
        return File(rootfsDir, "bin/sh").exists() || File(rootfsDir, "usr/bin/bash").exists()
    }

    fun getConfiguredUsername(): String {
        val meta = File(rootfsDir, "etc/openstick/username")
        if (meta.exists()) {
            val name = meta.readText().trim()
            if (name.isNotEmpty()) return name
        }
        val legacy = File(rootfsDir, "etc/breadstick/username")
        if (legacy.exists()) {
            val name = legacy.readText().trim()
            if (name.isNotEmpty()) return name
        }
        return "openstick"
    }

    fun deleteLinuxSystem(): Boolean {
        return if (rootfsDir.exists()) {
            rootfsDir.deleteRecursively()
        } else true
    }

    fun getHomeDir(username: String = getConfiguredUsername()): File {
        val userHome = File(rootfsDir, "home/$username")
        return if (userHome.exists()) userHome else File(rootfsDir, "root")
    }

    fun ensureDirectories() {
        rootfsDir.mkdirs()
        tmpDir.mkdirs()
        scriptsDir.mkdirs()
        val x11Unix = File(tmpDir, ".X11-unix")
        x11Unix.mkdirs()
        val bslRunDir = File(rootfsDir, "run/bsl")
        bslRunDir.mkdirs()
        try {
            File(bslRunDir, "pro").writeText("1\n")
            File(bslRunDir, "features").writeText("notify-v2 clipboard-v1 print-v1 nested-apps-v1 gpu-v2\n")
        } catch (_: Exception) {}
        try {
            Os.chmod(tmpDir.absolutePath, 511) // 0777
            Os.chmod(x11Unix.absolutePath, 1023) // 01777
        } catch (_: Exception) {}
    }

    fun extractXkb() {
        val xkbTarget = File(context.filesDir, "xkb")
        if (File(xkbTarget, "rules/evdev").exists()) return
        try {
            xkbTarget.mkdirs()
            context.assets.open("xkb.tar.gz").use { input ->
                java.util.zip.GZIPInputStream(input).use { gzip ->
                    org.apache.commons.compress.archivers.tar.TarArchiveInputStream(gzip).use { tar ->
                        while (true) {
                            val entry = tar.nextTarEntry ?: break
                            val outPath = File(context.filesDir, entry.name)
                            if (entry.isDirectory) {
                                outPath.mkdirs()
                            } else {
                                outPath.parentFile?.mkdirs()
                                FileOutputStream(outPath).use { out ->
                                    tar.copyTo(out)
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    fun extractAssetScripts() {
        ensureDirectories()
        val assetList = listOf(
            "setup-user.sh",
            "migrate-trixie.sh"
        )
        for (asset in assetList) {
            val dest = File(scriptsDir, asset)
            if (!dest.exists()) {
                try {
                    context.assets.open(asset).use { input ->
                        FileOutputStream(dest).use { output ->
                            input.copyTo(output)
                        }
                    }
                    dest.setExecutable(true, false)
                } catch (_: Exception) {}
            }
        }
    }

    fun copyAsset(assetPath: String, dest: File) {
        try {
            dest.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                FileOutputStream(dest).use { output ->
                    input.copyTo(output)
                }
            }
            dest.setExecutable(true, false)
        } catch (_: Exception) {}
    }

    fun installDesktopScripts() {
        try {
            File(rootfsDir, "root/.breadstick").mkdirs()
            copyAsset("desktop/provision.sh", File(rootfsDir, "root/.breadstick/provision.sh"))
            copyAsset("desktop/start-desktop-x11.sh", File(rootfsDir, "root/.breadstick/start-desktop-x11.sh"))
            File(rootfsDir, "usr/local/bin").mkdirs()
            copyAsset("desktop/start-desktop-x11.sh", File(rootfsDir, "usr/local/bin/start-desktop-x11"))
            copyAsset("desktop/list-apps.sh", File(rootfsDir, "usr/local/bin/breadstick-list-apps"))
            copyAsset("desktop/bsl-status.sh", File(rootfsDir, "usr/local/bin/bsl-status"))
            val xfwm4Wrapper = File(rootfsDir, "usr/local/bin/xfwm4")
            xfwm4Wrapper.writeText("#!/bin/sh\nexec /usr/bin/xfwm4 --compositor=off \"\$@\"\n")
            xfwm4Wrapper.setExecutable(true, false)
            File(rootfsDir, "usr/lib/breadstick").mkdirs()
            copyAsset("desktop/start-desktop-x11.sh", File(rootfsDir, "usr/lib/breadstick/start-desktop-x11.sh"))
        } catch (_: Exception) {}
    }

    fun startDesktopCommand(display: Int = 0, user: String = "root"): String {
        installDesktopScripts()
        return "rm -f /tmp/runtime-*/bus /tmp/.X$display-lock 2>/dev/null; export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; export DISPLAY=:$display; export BREADSTICK_DARK=1; export BREADSTICK_DPI=192; export BREADSTICK_LOCALE=C.UTF-8; F=/usr/lib/breadstick/start-desktop-x11.sh; [ -f \"\$F\" ] || F=/usr/local/bin/start-desktop-x11; [ -f \"\$F\" ] || F=/root/.breadstick/start-desktop-x11.sh; if [ -f \"\$F\" ]; then exec sh \"\$F\" $display $user 0; else exec startxfce4 2>/dev/null || exec openbox-session 2>/dev/null || exec twm; fi"
    }

    private fun procStandIns(): List<String> {
        val standinDir = File(context.filesDir, "proc-standin")
        standinDir.mkdirs()
        val binds = mutableListOf<String>()
        val checks = listOf(
            "sys/kernel/overflowuid" to "65534\n",
            "sys/kernel/overflowgid" to "65534\n"
        )
        for ((relPath, content) in checks) {
            val hostProc = File("/proc/$relPath")
            if (!hostProc.canRead()) {
                val standinFile = File(standinDir, relPath.substringAfterLast('/'))
                if (!standinFile.isFile || standinFile.readText() != content) {
                    standinFile.writeText(content)
                }
                binds.add("-b")
                binds.add("${standinFile.absolutePath}:/proc/$relPath")
            }
        }
        return binds
    }

    fun buildProotCommand(
        workdir: String? = null,
        asRoot: Boolean = false,
        command: List<String> = listOf("/bin/bash", "--login")
    ): List<String> {
        val configuredUser = getConfiguredUsername()
        val effectiveWorkdir = workdir ?: if (asRoot) "/root" else "/home/$configuredUser"
        val cmd = mutableListOf<String>()
        cmd.add(prootBin.absolutePath)
        cmd.add("--kill-on-exit")

        if (loaderBin.exists()) {
            cmd.add("--link2symlink")
        }
        cmd.add("--sysvipc")

        if (asRoot) {
            cmd.add("-0")
        } else {
            cmd.add("-i")
            cmd.add("1000:1000")
        }

        cmd.add("-r")
        cmd.add(rootfsDir.absolutePath)

        cmd.add("-b")
        cmd.add("/dev")
        cmd.add("-b")
        cmd.add("/proc")
        cmd.add("-b")
        cmd.add("/sys")

        cmd.addAll(procStandIns())

        // Temporary & socket bindings
        cmd.add("-b")
        cmd.add("${tmpDir.absolutePath}:/tmp")
        cmd.add("-b")
        cmd.add("${tmpDir.absolutePath}:/dev/shm")

        // Host shared storage
        try {
            val externalStorage = Environment.getExternalStorageDirectory()
            if (externalStorage != null && externalStorage.canRead()) {
                cmd.add("-b")
                cmd.add("${externalStorage.absolutePath}:/mnt/android")
                cmd.add("-b")
                cmd.add("${externalStorage.absolutePath}:/mnt/sdcard")
                cmd.add("-b")
                cmd.add("${externalStorage.absolutePath}:/sdcard")

                val downloadDir = File(externalStorage, "Download")
                if (downloadDir.isDirectory) {
                    val userHome = if (asRoot) "/root" else "/home/$configuredUser"
                    cmd.add("-b")
                    cmd.add("${downloadDir.absolutePath}:$userHome/Downloads")
                }
            }
        } catch (_: Exception) {}

        cmd.add("-w")
        cmd.add(effectiveWorkdir)

        cmd.addAll(command)
        return cmd
    }

    fun createSnapshot(destFile: File, onProgress: (String, Float) -> Unit) {
        onProgress("Creating rootfs snapshot...", 0.05f)
        destFile.parentFile?.mkdirs()
        val rawOut = java.io.BufferedOutputStream(java.io.FileOutputStream(destFile), 65536)
        val zstdOut = com.github.luben.zstd.ZstdOutputStream(rawOut)
        org.apache.commons.compress.archivers.tar.TarArchiveOutputStream(zstdOut).use { tarOut ->
            tarOut.setLongFileMode(org.apache.commons.compress.archivers.tar.TarArchiveOutputStream.LONGFILE_POSIX)
            val rootPath = rootfsDir.absolutePath
            val allFiles = rootfsDir.walkTopDown().toList()
            var count = 0
            val total = allFiles.size.coerceAtLeast(1)
            for (f in allFiles) {
                val relPath = f.absolutePath.removePrefix(rootPath).removePrefix("/")
                if (relPath.isEmpty() || relPath.startsWith("tmp/") || relPath.startsWith("dev/")) continue
                val entry = org.apache.commons.compress.archivers.tar.TarArchiveEntry(f, relPath)
                if (f.isDirectory) {
                    tarOut.putArchiveEntry(entry)
                    tarOut.closeArchiveEntry()
                } else if (f.isFile) {
                    tarOut.putArchiveEntry(entry)
                    java.io.FileInputStream(f).use { it.copyTo(tarOut, 65536) }
                    tarOut.closeArchiveEntry()
                }
                count++
                if (count % 500 == 0) {
                    onProgress("Archived $count files...", 0.05f + (count.toFloat() / total) * 0.9f)
                }
            }
        }
        onProgress("Snapshot created: ${destFile.name}", 1.0f)
    }

    fun restoreSnapshot(srcFile: File, onProgress: (String, Float) -> Unit) {
        val installer = RootfsInstaller(this)
        installer.installFromTar(srcFile, getConfiguredUsername(), onProgress)
    }

    fun getEnvironmentVariables(
        asRoot: Boolean = false,
        display: String = ":0",
        gpuEnabled: Boolean = true
    ): Map<String, String> {
        val configuredUser = getConfiguredUsername()
        val user = if (asRoot) "root" else configuredUser
        val home = if (asRoot) "/root" else "/home/$configuredUser"

        val env = mutableMapOf(
            "HOME" to home,
            "USER" to user,
            "LOGNAME" to user,
            "TERM" to "xterm-256color",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "DISPLAY" to display,
            "TMPDIR" to "/tmp",
            "LANG" to "C.UTF-8",
            "MOZ_DISABLE_CONTENT_SANDBOX" to "1",
            "ELECTRON_DISABLE_SANDBOX" to "1"
        )

        if (loaderBin.exists()) {
            env["PROOT_LOADER"] = loaderBin.absolutePath
        }
        env["PROOT_TMP_DIR"] = tmpDir.absolutePath

        if (gpuEnabled) {
            env["GALLIUM_DRIVER"] = "virpipe"
            env["VTEST_SOCKET_NAME"] = "/tmp/.virgl_test"
            env["LIBGL_KOPPER_DISABLE"] = "1"
        } else {
            env["LIBGL_ALWAYS_SOFTWARE"] = "1"
        }

        return env
    }
}
