package io.breadstick.x11

import android.os.ParcelFileDescriptor

object CmdEntryPoint {
    init {
        try {
            System.loadLibrary("Xlorie")
        } catch (e: UnsatisfiedLinkError) {
            e.printStackTrace()
        }
    }

    @JvmStatic
    external fun connected(): Boolean

    @JvmStatic
    external fun start(args: Array<String>): Boolean

    @JvmStatic
    external fun getXConnection(): ParcelFileDescriptor?

    @JvmStatic
    external fun listenForConnections(): Boolean

    @JvmStatic
    external fun getLogcatOutput(): String?
}
