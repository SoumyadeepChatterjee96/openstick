package org.openstick.bridge

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.*
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.max

/**
 * Connects to guest Linux PulseAudio simple protocol TCP sink on 127.0.0.1:4713
 * and plays PCM audio through Android AudioTrack (phone speakers / headphones).
 */
class AudioBridge(
    private val host: String = "127.0.0.1",
    private val port: Int = 4713
) {
    companion object {
        private const val TAG = "AudioBridge"
        private const val SAMPLE_RATE = 48000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_STEREO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }

    private var job: Job? = null
    @Volatile
    private var running = false

    fun start(scope: CoroutineScope) {
        if (running) return
        running = true
        job = scope.launch(Dispatchers.IO) {
            loop()
        }
    }

    fun stop() {
        running = false
        job?.cancel()
        job = null
    }

    private suspend fun loop() {
        val minBufferSize = max(AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, ENCODING), 8192)

        while (running) {
            var socket: Socket? = null
            var audioTrack: AudioTrack? = null

            try {
                // Connect to PulseAudio TCP server
                val s = Socket()
                s.connect(InetSocketAddress(host, port), 2000)
                socket = s

                val track = AudioTrack(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNEL_CONFIG)
                        .setEncoding(ENCODING)
                        .build(),
                    minBufferSize * 2,
                    AudioTrack.MODE_STREAM,
                    0
                )
                track.play()
                audioTrack = track

                val input: InputStream = s.getInputStream()
                val buffer = ByteArray(minBufferSize)

                var isPaused = false
                var silentBytes = 0L

                while (running) {
                    val read = input.read(buffer)
                    if (read < 0) break

                    if (isBufferSilent(buffer, read)) {
                        silentBytes += read
                        if (!isPaused && silentBytes >= SAMPLE_RATE * 4 * 2) { // 2 seconds of silence
                            track.pause()
                            track.flush()
                            isPaused = true
                        }
                    } else {
                        if (isPaused) {
                            track.play()
                            isPaused = false
                        }
                        silentBytes = 0L
                    }

                    if (!isPaused) {
                        track.write(buffer, 0, read)
                    }
                }
            } catch (e: Exception) {
                // PulseAudio server might not be up yet; wait and retry
                delay(3000)
            } finally {
                try {
                    audioTrack?.pause()
                    audioTrack?.flush()
                    audioTrack?.release()
                } catch (_: Exception) {}
                try {
                    socket?.close()
                } catch (_: Exception) {}
            }
        }
    }

    private fun isBufferSilent(buf: ByteArray, len: Int): Boolean {
        var i = 0
        while (i < len) {
            val sample = (buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)
            if (sample > 300 || sample < -300) {
                return false
            }
            i += 4 // 16-bit stereo = 4 bytes per frame
        }
        return true
    }
}
