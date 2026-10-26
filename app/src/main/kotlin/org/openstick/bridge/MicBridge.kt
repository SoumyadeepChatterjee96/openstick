package org.openstick.bridge

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/**
 * Pipes Android microphone input through AudioRecord into Linux guest FIFO
 * (/tmp/openstick-mic.fifo) for PulseAudio module-pipe-source.
 */
class MicBridge(
    private val context: Context,
    private val rootfsDir: File
) {
    companion object {
        private const val TAG = "MicBridge"
        private const val RATE = 48000
        private const val CHANNELS = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }

    private val fifo = File(rootfsDir, "tmp/openstick-mic.fifo")
    private var job: Job? = null
    @Volatile
    private var running = false

    fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    fun start(scope: CoroutineScope) {
        if (running || !hasPermission()) return
        running = true

        ensureFifo()

        job = scope.launch(Dispatchers.IO) {
            loop()
        }
    }

    fun stop() {
        running = false
        job?.cancel()
        job = null
    }

    private fun ensureFifo() {
        fifo.parentFile?.mkdirs()
        if (!fifo.exists()) {
            try {
                Os.mkfifo(fifo.absolutePath, 0x1B6) // 0666
            } catch (_: Exception) {}
        }
    }

    private suspend fun loop() {
        val minBufferSize = max(AudioRecord.getMinBufferSize(RATE, CHANNELS, ENCODING), 4096)

        while (running) {
            var record: AudioRecord? = null
            var outStream: FileOutputStream? = null

            try {
                ensureFifo()
                // Open FIFO non-blocking or write
                val fd = Os.open(fifo.absolutePath, OsConstants.O_WRONLY, 0)
                outStream = FileOutputStream(fd)

                val audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    RATE,
                    CHANNELS,
                    ENCODING,
                    minBufferSize * 2
                )

                if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                    delay(2000)
                    continue
                }

                audioRecord.startRecording()
                record = audioRecord

                val buffer = ByteArray(minBufferSize)
                while (running) {
                    val read = audioRecord.read(buffer, 0, minBufferSize)
                    if (read > 0) {
                        outStream.write(buffer, 0, read)
                    } else {
                        delay(20)
                    }
                }
            } catch (e: Exception) {
                // If FIFO has no readers (PulseAudio not yet started), sleep and retry
                delay(2000)
            } finally {
                try {
                    record?.stop()
                    record?.release()
                } catch (_: Exception) {}
                try {
                    outStream?.close()
                } catch (_: Exception) {}
            }
        }
    }
}
