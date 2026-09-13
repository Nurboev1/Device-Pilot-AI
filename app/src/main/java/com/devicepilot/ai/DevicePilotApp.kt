package com.devicepilot.ai

import android.app.Application
import android.content.Context
import android.util.Log
import com.devicepilot.ai.agent.AgentSession
import com.devicepilot.ai.service.AgentForegroundService
import com.devicepilot.ai.shizuku.ShizukuManager

class DevicePilotApp : Application() {

    lateinit var agentSession: AgentSession
        private set

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "DevicePilotApp initialized")
        ShizukuManager.init(this)
        AgentForegroundService.ensureChannel(this)
        agentSession = AgentSession(this)
    }

    override fun onTerminate() {
        super.onTerminate()
        ShizukuManager.destroy()
    }

    companion object {
        private const val TAG = "DevicePilotApp"
        fun session(ctx: Context): AgentSession = (ctx.applicationContext as DevicePilotApp).agentSession
    }
}
