package org.openstick.engine

import android.content.Context
import com.github.luben.zstd.ZstdInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

class RootfsDownloader(
    private val context: Context,
    private val desktop: Boolean = false
) {
    companion object {
        const val BASE_URL = "https://cdn.breadstick.io/rootfs"
    }

    val imageFile: File
        get() = File(context.filesDir, if (desktop) "rootfs-desktop.tar.zst" else "rootfs-base.tar.zst")

    private val partFile: File
        get() = File(context.filesDir, "${imageFile.name}.part")

    private val partOwnerFile: File
        get() = File(context.filesDir, "${imageFile.name}.part.for")

    private val stampFile: File
        get() = File(context.filesDir, "${imageFile.name}.sha256")

    fun isDownloaded(): Boolean = imageFile.isFile && imageFile.length() > 0

    fun download(onProgress: (String, Float) -> Unit) {
        onProgress("Contacting distribution mirror...", 0.0f)
        val manifestName = if (desktop) "manifest-desktop.json" else "manifest.json"
        val manifestUrl = "$BASE_URL/$manifestName"

        val manifestObj = fetchManifest(manifestUrl)
        val fileName = manifestObj.getString("file")
        val expectedSize = manifestObj.getLong("size")
        val expectedSha = manifestObj.getString("sha256").lowercase(Locale.ROOT)

        if (isDownloaded()) {
            val stamp = try { stampFile.readText().trim().lowercase(Locale.ROOT) } catch (_: Exception) { "" }
            if (stamp == expectedSha) {
                onProgress("Image verified", 1.0f)
                return
            }
            // Checksha of existing file
            if (sha256Of(imageFile) == expectedSha) {
                try { stampFile.writeText(expectedSha) } catch (_: Exception) {}
                onProgress("Image verified", 1.0f)
                return
            }
            imageFile.delete()
        }

        // Validate partial download
        val ownerSha = try { partOwnerFile.readText().trim().lowercase(Locale.ROOT) } catch (_: Exception) { "" }
        if (partFile.isFile && ownerSha != expectedSha) {
            partFile.delete()
        }

        var downloadedBytes = if (partFile.isFile) partFile.length() else 0L
        if (downloadedBytes > expectedSize) {
            partFile.delete()
            downloadedBytes = 0L
        }

        try { partOwnerFile.writeText(expectedSha) } catch (_: Exception) {}

        val downloadUrl = "$BASE_URL/$fileName"
        while (downloadedBytes < expectedSize) {
            try {
                downloadedBytes = downloadRange(downloadUrl, downloadedBytes, expectedSize, onProgress)
            } catch (e: IOException) {
                val fraction = if (expectedSize > 0) (downloadedBytes.toFloat() / expectedSize) * 0.95f else 0.5f
                onProgress("Connection retry in 3s...", fraction)
                Thread.sleep(3000)
                downloadedBytes = downloadRange(downloadUrl, downloadedBytes, expectedSize, onProgress)
            }
        }

        onProgress("Verifying checksum...", 0.98f)
        val computedSha = sha256Of(partFile)
        if (computedSha != expectedSha) {
            partFile.delete()
            throw IOException("Download corrupted: expected $expectedSha but got $computedSha")
        }

        if (imageFile.exists()) imageFile.delete()
        if (!partFile.renameTo(imageFile)) {
            partFile.copyTo(imageFile, overwrite = true)
            partFile.delete()
        }

        try { stampFile.writeText(expectedSha) } catch (_: Exception) {}
        onProgress("Download completed", 1.0f)
    }

    private fun fetchManifest(urlStr: String): JSONObject {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            setRequestProperty("User-Agent", "openstick-android/1.0")
        }
        try {
            if (conn.responseCode != 200) {
                throw IOException("Server returned HTTP ${conn.responseCode} for manifest")
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun downloadRange(
        urlStr: String,
        from: Long,
        totalSize: Long,
        onProgress: (String, Float) -> Unit
    ): Long {
        var bytesDownloaded = from
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            setRequestProperty("User-Agent", "openstick-android/1.0")
            if (bytesDownloaded > 0) {
                setRequestProperty("Range", "bytes=$bytesDownloaded-")
            }
        }

        try {
            val code = conn.responseCode
            if (code != 200 && code != 206) {
                throw IOException("Server returned HTTP $code for rootfs download")
            }
            if (code == 200) {
                bytesDownloaded = 0L
            }

            RandomAccessFile(partFile, "rw").use { raf ->
                raf.setLength(bytesDownloaded)
                raf.seek(bytesDownloaded)

                val buffer = ByteArray(65536)
                conn.inputStream.use { input ->
                    var lastPercent = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        raf.write(buffer, 0, read)
                        bytesDownloaded += read

                        val currentPercent = if (totalSize > 0) ((bytesDownloaded * 100) / totalSize).toInt() else 0
                        if (currentPercent != lastPercent) {
                            lastPercent = currentPercent
                            val mbDownloaded = bytesDownloaded / 1048576
                            val mbTotal = totalSize / 1048576
                            val fraction = (bytesDownloaded.toFloat() / totalSize) * 0.95f
                            onProgress("Downloading rootfs: $mbDownloaded / $mbTotal MB ($currentPercent%)", fraction)
                        }
                    }
                }
            }
            return bytesDownloaded
        } finally {
            conn.disconnect()
        }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(65536)
            while (true) {
                val r = input.read(buf)
                if (r < 0) break
                digest.update(buf, 0, r)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
