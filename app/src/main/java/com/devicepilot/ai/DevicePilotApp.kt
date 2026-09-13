package com.devicepilot.ai

import android.app.Application
import android.util.Log
import com.devicepilot.ai.shizuku.ShizukuManager

class DevicePilotApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "DevicePilotApp initialized")
        ShizukuManager.init(this)
    }

    override fun onTerminate() {
        super.onTerminate()
        ShizukuManager.destroy()
    }

    companion object {
        private const val TAG = "DevicePilotApp"
    }
}
