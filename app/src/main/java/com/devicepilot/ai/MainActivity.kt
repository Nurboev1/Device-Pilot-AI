package com.devicepilot.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.devicepilot.ai.shizuku.ShizukuManager
import com.devicepilot.ai.ui.ChatScreen
import com.devicepilot.ai.ui.ChatViewModel
import com.devicepilot.ai.ui.theme.DevicePilotTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            DevicePilotTheme {
                val chatViewModel: ChatViewModel = viewModel()
                ChatScreen(viewModel = chatViewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ShizukuManager.updateStatus()
    }
}
