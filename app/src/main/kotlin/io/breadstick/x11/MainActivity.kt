package io.breadstick.x11

class MainActivity {
    interface ConnectionListener {
        fun onClientConnectionChanged()
    }

    companion object {
        private val INSTANCE = MainActivity()
        @Volatile
        private var listener: ConnectionListener? = null

        @JvmStatic
        fun getInstance(): MainActivity = INSTANCE

        @JvmStatic
        fun setListener(l: ConnectionListener?) {
            listener = l
        }
    }

    fun clientConnectedStateChanged() {
        listener?.onClientConnectionChanged()
    }
}
