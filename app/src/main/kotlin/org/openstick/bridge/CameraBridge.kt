package org.openstick.bridge

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.io.File
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * Streams Android Camera frames via Camera2 API as MJPEG over a local ServerSocket (127.0.0.1)
 * to Linux guest applications (/run/openstick/camera and /run/bsl/camera).
 */
class CameraBridge(
    private val context: Context,
    private val rootfsDir: File
) {
    companion object {
        private const val TAG = "CameraBridge"
        private const val BOUNDARY = "openstickframe"
    }

    private val descriptor = File(rootfsDir, "run/openstick/camera")
    private val bslDescriptor = File(rootfsDir, "run/bsl/camera")
    private val viewers = AtomicInteger(0)

    private var serverSocket: ServerSocket? = null
    private var job: Job? = null
    @Volatile
    private var running = false

    // Camera2 resources
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    @Volatile
    private var latestJpeg: ByteArray? = null

    fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun start(scope: CoroutineScope) {
        if (running || !hasPermission()) return
        running = true

        job = scope.launch(Dispatchers.IO) {
            try {
                val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
                serverSocket = server
                val port = server.localPort

                writeDescriptor(port)

                while (running && !server.isClosed) {
                    val client = server.accept()
                    launch(Dispatchers.IO) {
                        handleClient(client)
                    }
                }
            } catch (e: Exception) {
                if (running) Log.e(TAG, "Server socket error", e)
            } finally {
                stopCamera()
            }
        }
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        job?.cancel()
        job = null
        stopCamera()
        descriptor.delete()
        bslDescriptor.delete()
    }

    private fun writeDescriptor(port: Int) {
        val content = "127.0.0.1:$port\nhttp://127.0.0.1:$port/stream.mjpg\n"
        descriptor.parentFile?.mkdirs()
        descriptor.writeText(content)
        bslDescriptor.parentFile?.mkdirs()
        bslDescriptor.writeText(content)
    }

    private fun handleClient(client: Socket) {
        try {
            viewers.incrementAndGet()
            startCameraIfNeeded()

            val out: OutputStream = client.getOutputStream()
            out.write(
                ("HTTP/1.0 200 OK\r\n" +
                 "Connection: close\r\n" +
                 "Max-Age: 0\r\n" +
                 "Expires: 0\r\n" +
                 "Cache-Control: no-cache, private\r\n" +
                 "Content-Type: multipart/x-mixed-replace; boundary=--$BOUNDARY\r\n\r\n").toByteArray()
            )

            while (running && !client.isClosed) {
                val frame = latestJpeg
                if (frame != null && frame.isNotEmpty()) {
                    out.write(
                        ("--$BOUNDARY\r\n" +
                         "Content-Type: image/jpeg\r\n" +
                         "Content-Length: ${frame.size}\r\n\r\n").toByteArray()
                    )
                    out.write(frame)
                    out.write("\r\n".toByteArray())
                    out.flush()
                }
                Thread.sleep(66) // ~15-20 fps
            }
        } catch (_: Exception) {
        } finally {
            try { client.close() } catch (_: Exception) {}
            if (viewers.decrementAndGet() <= 0) {
                stopCamera()
            }
        }
    }

    @Synchronized
    private fun startCameraIfNeeded() {
        if (cameraDevice != null) return
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return

        try {
            val cameraId = manager.cameraIdList.firstOrNull { id ->
                val chars = manager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                facing == CameraCharacteristics.LENS_FACING_BACK
            } ?: manager.cameraIdList.firstOrNull() ?: return

            val thread = HandlerThread("CameraBridgeThread").apply { start() }
            cameraThread = thread
            val handler = Handler(thread.looper)
            cameraHandler = handler

            val reader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 2)
            reader.setOnImageAvailableListener({ ir ->
                val image = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val planes = image.planes
                    if (planes.isNotEmpty()) {
                        val buffer = planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        latestJpeg = bytes
                    }
                } finally {
                    image.close()
                }
            }, handler)
            imageReader = reader

            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    try {
                        val surface = reader.surface
                        val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(surface)
                        }
                        camera.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                captureSession = session
                                captureBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                                session.setRepeatingRequest(captureBuilder.build(), null, handler)
                            }
                            override fun onConfigureFailed(session: CameraCaptureSession) {}
                        }, handler)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed creating capture session", e)
                    }
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close(); cameraDevice = null }
                override fun onError(camera: CameraDevice, error: Int) { camera.close(); cameraDevice = null }
            }, handler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open camera", e)
        }
    }

    @Synchronized
    private fun stopCamera() {
        try {
            captureSession?.close()
            cameraDevice?.close()
            imageReader?.close()
            cameraThread?.quitSafely()
        } catch (_: Exception) {}
        captureSession = null
        cameraDevice = null
        imageReader = null
        cameraThread = null
        cameraHandler = null
        latestJpeg = null
    }
}
