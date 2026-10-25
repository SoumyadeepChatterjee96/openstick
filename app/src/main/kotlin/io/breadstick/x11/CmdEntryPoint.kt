package io.breadstick.x11

import android.os.ParcelFileDescriptor

class CmdEntryPoint {
    companion object {
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
    }

    external fun getXConnection(): ParcelFileDescriptor?
}
