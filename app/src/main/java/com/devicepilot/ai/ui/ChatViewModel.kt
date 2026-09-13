package com.devicepilot.ai.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.devicepilot.ai.DevicePilotApp
import com.devicepilot.ai.agent.AgentSession
import com.devicepilot.ai.model.ModelSpec
import com.devicepilot.ai.shizuku.ShizukuManager

/**
 * Thin adapter: state lives in [AgentSession] (application scope) so it survives
 * the Activity being destroyed while the agent drives other apps.
 */
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    val session: AgentSession = DevicePilotApp.session(application)

    val messages get() = session.messages
    val isRunning get() = session.isRunning
    val modelState get() = session.modelState
    val shizukuState get() = ShizukuManager.shizukuState
    val selectedModel get() = session.models.selected
    val installedModels get() = session.models.installed
    val autoConfirm get() = session.autoConfirm

    fun send(text: String) = session.send(text)
    fun stop() = session.stop()
    fun confirm(approved: Boolean) = session.confirm(approved)
    fun clearChat() = session.clearChat()
    fun selectModel(spec: ModelSpec) = session.selectModel(spec)
    fun retryModel() = session.retryModel()
    fun deleteModel(spec: ModelSpec) = session.deleteModel(spec)
    fun setAutoConfirm(v: Boolean) = session.setAutoConfirm(v)
    fun requestShizuku() = ShizukuManager.requestPermission()
    fun refreshShizuku() = ShizukuManager.updateStatus()
}
