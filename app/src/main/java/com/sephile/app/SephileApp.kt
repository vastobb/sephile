package com.sephile.app

import android.app.Application
import com.sephile.app.root.RootManager

class SephileApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Keep the UI responsive; battery optimizations can be requested by the user.
        // RootManager.requestIgnoreBatteryOptimizations(this)
    }
}
