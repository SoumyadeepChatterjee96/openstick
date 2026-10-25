package org.openstick.engine

import android.content.Context
import android.system.Os
import com.github.luben.zstd.ZstdInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.*
import java.util.zip.GZIPInputStream

class RootfsInstaller(private val env: LinuxEnvironment) {

    fun installFromTar(
        archiveFile: File,
        username: String = "openstick",
        onProgress: (String, Float) -> Unit
    ) {
        onProgress("Preparing staging area...", 0.02f)
        val stagingDir = File(env.rootfsDir.parentFile, "rootfs.partial")
        if (stagingDir.exists()) stagingDir.deleteRecursively()
        stagingDir.mkdirs()
        env.tmpDir.mkdirs()

        val hardLinks = mutableListOf<Pair<String, String>>() // linkTarget to linkPath

        onProgress("Unpacking Debian filesystem...", 0.05f)

        val rawInput = BufferedInputStream(FileInputStream(archiveFile), 65536)
        val decompressedStream: InputStream = when {
            archiveFile.name.endsWith(".zst") || archiveFile.name.endsWith(".tzst") -> ZstdInputStream(rawInput)
            archiveFile.name.endsWith(".gz") || archiveFile.name.endsWith(".tgz") -> GZIPInputStream(rawInput)
            else -> rawInput
        }

        var entryCount = 0
        TarArchiveInputStream(decompressedStream).use { tar ->
            while (true) {
                val entry: TarArchiveEntry = tar.nextTarEntry ?: break
                val name = entry.name.removePrefix("./").removePrefix("/")
                if (name.isEmpty() || name.contains("..")) continue

                val destFile = File(stagingDir, name)

                if (entry.isDirectory) {
                    destFile.mkdirs()
                } else if (entry.isSymbolicLink) {
                    destFile.parentFile?.mkdirs()
                    if (destFile.exists()) destFile.delete()
                    try {
                        Os.symlink(entry.linkName, destFile.absolutePath)
                    } catch (_: Exception) {}
                } else if (entry.isLink) {
                    hardLinks.add(Pair(entry.linkName.removePrefix("./").removePrefix("/"), name))
                } else if (entry.isFile) {
                    destFile.parentFile?.mkdirs()
                    FileOutputStream(destFile).use { out ->
                        tar.copyTo(out, 65536)
                    }
                    try {
                        Os.chmod(destFile.absolutePath, entry.mode and 4095) // 07777 mask preserving setuid/setgid
                    } catch (_: Exception) {}
                }

                entryCount++
                if (entryCount % 400 == 0) {
                    val progressFraction = 0.05f + (entryCount.toFloat() / 25000f).coerceAtMost(0.85f)
                    onProgress("Extracted $entryCount files...", progressFraction)
                }
            }
        }

        onProgress("Linking system files...", 0.92f)
        for ((target, link) in hardLinks) {
            val targetFile = File(stagingDir, target)
            val linkFile = File(stagingDir, link)
            linkFile.parentFile?.mkdirs()
            if (linkFile.exists()) linkFile.delete()
            try {
                if (targetFile.exists()) {
                    Os.link(targetFile.absolutePath, linkFile.absolutePath)
                }
            } catch (_: Exception) {
                // Fallback to copy if link fails
                try {
                    if (targetFile.exists()) targetFile.copyTo(linkFile, overwrite = true)
                } catch (_: Exception) {}
            }
        }

        // Verify that we unpacked a valid system
        val hasShell = File(stagingDir, "bin/sh").exists() ||
                File(stagingDir, "usr/bin/bash").exists() ||
                File(stagingDir, "bin/bash").exists()

        if (!hasShell) {
            stagingDir.deleteRecursively()
            throw IOException("Archive does not contain a valid Linux rootfs (missing bin/sh or usr/bin/bash)")
        }

        onProgress("Finalizing installation...", 0.95f)
        if (env.rootfsDir.exists()) {
            env.rootfsDir.deleteRecursively()
        }

        if (!stagingDir.renameTo(env.rootfsDir)) {
            stagingDir.copyRecursively(env.rootfsDir, overwrite = true)
            stagingDir.deleteRecursively()
        }

        // Create core directories and configs
        env.tmpDir.mkdirs()
        File(env.rootfsDir, "etc").mkdirs()
        File(env.rootfsDir, "root").mkdirs()
        val userHome = File(env.rootfsDir, "home/$username")
        userHome.mkdirs()

        // Skeletons
        val skelDir = File(env.rootfsDir, "etc/skel")
        if (skelDir.isDirectory) {
            skelDir.listFiles()?.forEach { skelFile ->
                val dest = File(userHome, skelFile.name)
                if (!dest.exists()) {
                    try { skelFile.copyTo(dest, overwrite = true) } catch (_: Exception) {}
                }
            }
        }

        // Hostname & Hosts
        File(env.rootfsDir, "etc/hostname").writeText("openstick\n")
        File(env.rootfsDir, "etc/hosts").writeText(
            "127.0.0.1\tlocalhost openstick\n::1\tlocalhost ip6-localhost ip6-loopback\n"
        )

        // DNS
        File(env.rootfsDir, "etc/resolv.conf").writeText(
            "nameserver 1.1.1.1\nnameserver 8.8.8.8\n"
        )

        // Passwd, Group, Shadow entries for the user
        val passwdFile = File(env.rootfsDir, "etc/passwd")
        if (passwdFile.exists()) {
            val content = passwdFile.readText()
            if (!content.contains("$username:")) {
                passwdFile.appendText("$username:x:1000:1000:$username:/home/$username:/bin/bash\n")
            }
        } else {
            passwdFile.writeText("root:x:0:0:root:/root:/bin/bash\n$username:x:1000:1000:$username:/home/$username:/bin/bash\n")
        }

        val groupFile = File(env.rootfsDir, "etc/group")
        if (groupFile.exists()) {
            val content = groupFile.readText()
            if (!content.contains("$username:")) {
                groupFile.appendText("$username:x:1000:\n")
            }
            if (!content.contains("sudo:")) {
                groupFile.appendText("sudo:x:27:$username\n")
            }
        } else {
            groupFile.writeText("root:x:0:\nsudo:x:27:$username\n$username:x:1000:\n")
        }

        val shadowFile = File(env.rootfsDir, "etc/shadow")
        if (shadowFile.exists()) {
            val content = shadowFile.readText()
            if (!content.contains("$username:")) {
                shadowFile.appendText("$username:*:19000:0:99999:7:::\n")
            }
        }

        // Sudoers
        val sudoersDir = File(env.rootfsDir, "etc/sudoers.d")
        sudoersDir.mkdirs()
        val sudoersUserFile = File(sudoersDir, "openstick")
        sudoersUserFile.writeText("$username ALL=(ALL:ALL) NOPASSWD:ALL\n")
        try {
            Os.chmod(sudoersUserFile.absolutePath, 288) // 0440
        } catch (_: Exception) {}

        // Unlocked runtime flags
        val bslRunDir = File(env.rootfsDir, "run/bsl")
        bslRunDir.mkdirs()
        File(bslRunDir, "pro").writeText("1\n")
        File(bslRunDir, "features").writeText("notify-v2 clipboard-v1 print-v1 nested-apps-v1 gpu-v2\n")

        // Store configured username
        val openstickMetaDir = File(env.rootfsDir, "etc/openstick")
        openstickMetaDir.mkdirs()
        File(openstickMetaDir, "username").writeText("$username\n")

        // Ensure scripts are in place
        env.extractAssetScripts()

        // Run user setup script if present
        try {
            val setupScript = File(env.scriptsDir, "setup-user.sh")
            if (setupScript.exists()) {
                val pb = ProcessBuilder(
                    env.buildProotCommand(
                        workdir = "/root",
                        asRoot = true,
                        command = listOf("/bin/sh", "-c", "echo '$username' | ${setupScript.absolutePath} '$username'")
                    )
                )
                pb.environment().putAll(env.prootEnv())
                pb.environment().putAll(env.getEnvironmentVariables(asRoot = true))
                pb.directory(env.rootfsDir)
                val proc = pb.start()
                proc.waitFor()
            }
        } catch (_: Throwable) {}

        onProgress("Installation complete!", 1.0f)
    }
}
