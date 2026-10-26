package org.openstick

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.openstick.engine.LinuxEnvironment
import org.openstick.engine.ProotRunner
import io.breadstick.x11.X11Server

class OpenStickApplication : Application() {
    companion object {
        lateinit var instance: OpenStickApplication
            private set
    }

    val appScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    val env: LinuxEnvironment by lazy { LinuxEnvironment(this) }
    val x11Server: X11Server by lazy { X11Server(env) }
    val runner: ProotRunner by lazy { ProotRunner(env, appScope) }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }
}
