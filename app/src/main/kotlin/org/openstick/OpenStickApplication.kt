package org.openstick

import android.app.Application
import android.content.Context

class OpenStickApplication : Application() {
    companion object {
        lateinit var instance: OpenStickApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }
}
