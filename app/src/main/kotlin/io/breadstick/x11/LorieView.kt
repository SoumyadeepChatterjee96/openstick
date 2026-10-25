package io.breadstick.x11

import android.content.Context
import android.hardware.HardwareBuffer
import android.util.AttributeSet
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView

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
        init {
            try {
                System.loadLibrary("Xlorie")
            } catch (e: UnsatisfiedLinkError) {
                e.printStackTrace()
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
    }

    external fun nativeInit()
    external fun surfaceChanged(surface: Surface)
    external fun sendClipboardAnnounce()
    external fun sendClipboardEvent(bytes: ByteArray)
    external fun sendKeyEvent(keyCode: Int, scanCode: Int, down: Boolean, metaState: Int): Boolean
    external fun sendMouseEvent(x: Float, y: Float, button: Int, down: Boolean, relative: Boolean)
    external fun sendStylusEvent(x: Float, y: Float, pressure: Int, tiltX: Int, tiltY: Int, orientation: Int, buttons: Int, down: Boolean, eraser: Boolean)
    external fun sendTextEvent(utf8: ByteArray)
    external fun sendTouchEvent(action: Int, id: Int, x: Int, y: Int)
    external fun setFiltering(filter: Int)

    init {
        holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                try {
                    nativeInit()
                } catch (e: Throwable) {
                    e.printStackTrace()
                }
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                try {
                    surfaceChanged(holder.surface)
                    setViewport(0, 0, width, height, width, height)
                } catch (e: Throwable) {
                    e.printStackTrace()
                }
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {}
        })
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

    fun requestClipboard() {}
    fun setClipboardText(text: String?) {}
}
