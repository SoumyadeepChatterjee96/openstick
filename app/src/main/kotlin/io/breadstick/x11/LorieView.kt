package io.breadstick.x11

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.hardware.HardwareBuffer
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.HandlerThread
import android.util.AttributeSet
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

open class LorieView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr) {

    interface WindowListener {
        fun onWindowBuffer(windowId: Int, buffer: HardwareBuffer?, x: Int, y: Int, w: Int, h: Int)
        fun onWindowState(
            windowId: Int, x: Int, y: Int, w: Int, h: Int,
            visible: Boolean, focused: Boolean, title: String?,
            desktop: Int, role: String?, minW: Int, minH: Int,
            maxW: Int, maxH: Int, flags: Int, type: Int
        )
    }

    companion object {
        private const val TAG = "LorieView"
        private const val OUTPUT_NAME = "OpenStick"

        init {
            try {
                System.loadLibrary("Xlorie")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load libXlorie.so", e)
            }
        }

        @JvmStatic external fun connect(fd: Int)
        @JvmStatic external fun connected(): Boolean
        @JvmStatic external fun requestConnection(): Boolean
        @JvmStatic external fun requestStylusEnabled(enabled: Boolean)
        @JvmStatic external fun sendWindowChange(w: Int, h: Int, rate: Int, name: String)
        @JvmStatic external fun setClipboardSyncEnabled(read: Boolean, write: Boolean)
        @JvmStatic external fun setRendererZoom(zoom: Int)
        @JvmStatic external fun setViewport(x: Int, y: Int, w: Int, h: Int, screenW: Int, screenH: Int)
        @JvmStatic external fun startLogcat(fd: Int)

        @Volatile
        var windowListener: WindowListener? = null
        @Volatile
        var afterNextKey: Runnable? = null
    }

    private val nativeThread: HandlerThread = HandlerThread("LorieView-native").apply { start() }
    val nativeHandler: Handler = Handler(nativeThread.looper)

    @Volatile
    private var surfaceW: Int = 0
    @Volatile
    private var surfaceH: Int = 0

    external fun nativeInit()
    external fun surfaceChanged(surface: Surface?)
    external fun sendClipboardAnnounce()
    external fun sendClipboardEvent(bytes: ByteArray)
    external fun sendKeyEvent(keyCode: Int, scanCode: Int, down: Boolean, metaState: Int): Boolean
    external fun sendMouseEvent(x: Float, y: Float, button: Int, down: Boolean, relative: Boolean)
    external fun sendStylusEvent(x: Float, y: Float, pressure: Int, tiltX: Int, tiltY: Int, orientation: Int, buttons: Int, down: Boolean, eraser: Boolean)
    external fun sendTextEvent(utf8: ByteArray)
    external fun sendTouchEvent(action: Int, id: Int, x: Int, y: Int)
    external fun setFiltering(filter: Int)

    init {
        isFocusable = true
        isFocusableInTouchMode = true

        holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                // nativeInit is executed in nativeThread looper to ensure proper ALooper context
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                val surface = holder.surface
                surfaceW = width
                surfaceH = height
                nativeHandler.post {
                    try {
                        surfaceChanged(surface)
                        applyScreenSizeWithRetries()
                    } catch (e: Throwable) {
                        Log.e(TAG, "Error in surfaceChanged", e)
                    }
                }
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                val latch = CountDownLatch(1)
                nativeHandler.post {
                    try {
                        surfaceChanged(null)
                    } catch (e: Throwable) {
                        Log.e(TAG, "Error in surfaceDestroyed", e)
                    } finally {
                        latch.countDown()
                    }
                }
                try {
                    latch.await(1, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {}
            }
        })

        // Post native initialization to dedicated looper
        nativeHandler.post {
            try {
                nativeInit()
            } catch (e: Throwable) {
                Log.e(TAG, "Error during nativeInit", e)
            }
        }
    }

    fun connectAsync(fd: Int) {
        nativeHandler.post {
            try {
                Log.i(TAG, "Connecting LorieView to X11 client fd: $fd")
                connect(fd)
                applyScreenSizeWithRetries()
                setClipboardSyncEnabled(true, true)
                sendClipboardAnnounce()
            } catch (e: Throwable) {
                Log.e(TAG, "Error in connectAsync", e)
            }
        }
    }

    fun reportSurfaceSize() {
        nativeHandler.post {
            applyScreenSize()
        }
    }

    fun applyScreenSizeWithRetries() {
        applyScreenSize()
        nativeHandler.postDelayed({ applyScreenSize() }, 250)
        nativeHandler.postDelayed({ applyScreenSize() }, 800)
    }

    private fun applyScreenSize() {
        if (surfaceW <= 0 || surfaceH <= 0) return
        var targetW = surfaceW
        var targetH = surfaceH

        val largest = largestDisplay()
        if (largest != null) {
            targetW = maxOf(targetW, largest[0])
            targetH = maxOf(targetH, largest[1])
        }

        try {
            sendWindowChange(targetW, targetH, 60, OUTPUT_NAME)
            setViewport(0, 0, surfaceW, surfaceH, targetW, targetH)
        } catch (e: Throwable) {
            Log.e(TAG, "Error applying screen size", e)
        }
    }

    private fun largestDisplay(): IntArray? {
        return try {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return null
            var maxDim = 0
            for (d in dm.displays) {
                val mode = d.mode
                maxDim = maxOf(maxDim, maxOf(mode.physicalWidth, mode.physicalHeight))
            }
            if (maxDim <= 0) null else intArrayOf(minOf(maxDim, 4096), minOf(maxDim, 4096))
        } catch (_: Throwable) {
            null
        }
    }

    // Callbacks invoked directly by native JNI in libXlorie.so
    fun resetIme() {
        // Reset IME state requested by native X server
    }

    fun setClipboardText(str: String?) {
        if (str.isNullOrEmpty()) return
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("OpenStick", str))
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to set clipboard text", e)
        }
    }

    fun requestClipboard() {
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = cm?.primaryClip
            val text = if (clip != null && clip.itemCount > 0) {
                clip.getItemAt(0).coerceToText(context)?.toString() ?: ""
            } else ""
            val bytes = text.toByteArray(StandardCharsets.UTF_8)
            nativeHandler.post {
                try {
                    sendClipboardEvent(bytes)
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to send clipboard event", e)
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to request clipboard", e)
        }
    }

    fun onWindowState(
        id: Int, x: Int, y: Int, w: Int, h: Int,
        v: Boolean, f: Boolean, title: String?,
        d: Int, role: String?, minW: Int, minH: Int,
        maxW: Int, maxH: Int, flags: Int, type: Int
    ) {
        windowListener?.onWindowState(id, x, y, w, h, v, f, title, d, role, minW, minH, maxW, maxH, flags, type)
    }

    fun onWindowBuffer(id: Int, buffer: HardwareBuffer?, x: Int, y: Int, w: Int, h: Int) {
        windowListener?.onWindowBuffer(id, buffer, x, y, w, h)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        nativeThread.quitSafely()
    }
}
