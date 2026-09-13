package com.devicepilot.ai.shizuku

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

sealed class ShizukuState {
    data object Connected : ShizukuState()
    data object Unauthorized : ShizukuState()
    data object NotRunning : ShizukuState()
    data class Unsupported(val reason: String) : ShizukuState()
}

object ShizukuManager {

    private const val TAG = "ShizukuManager"
    const val SHIZUKU_REQUEST_CODE = 1001

    private val _shizukuState = MutableStateFlow<ShizukuState>(ShizukuState.NotRunning)
    val shizukuState: StateFlow<ShizukuState> = _shizukuState.asStateFlow()

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.i(TAG, "Shizuku binder received")
        updateStatus()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.w(TAG, "Shizuku binder dead")
        _shizukuState.value = ShizukuState.NotRunning
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_REQUEST_CODE) {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    Log.i(TAG, "Shizuku permission granted by user")
                    _shizukuState.value = ShizukuState.Connected
                } else {
                    Log.w(TAG, "Shizuku permission denied by user")
                    _shizukuState.value = ShizukuState.Unauthorized
                }
            }
        }

    fun init(context: Context) {
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
            updateStatus()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initialize Shizuku listeners", e)
            _shizukuState.value = ShizukuState.Unsupported(e.message ?: "Failed to connect to Shizuku")
        }
    }

    fun updateStatus() {
        try {
            if (Shizuku.isPreV11()) {
                _shizukuState.value = ShizukuState.Unsupported("Shizuku pre-v11 is not supported")
                return
            }

            val ping = Shizuku.pingBinder()
            if (!ping) {
                _shizukuState.value = ShizukuState.NotRunning
                return
            }

            val permission = Shizuku.checkSelfPermission()
            if (permission == PackageManager.PERMISSION_GRANTED) {
                _shizukuState.value = ShizukuState.Connected
            } else {
                _shizukuState.value = ShizukuState.Unauthorized
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error checking Shizuku status", t)
            _shizukuState.value = ShizukuState.NotRunning
        }
    }

    fun requestPermission() {
        try {
            if (Shizuku.isPreV11()) {
                Log.e(TAG, "Cannot request permission on pre-v11 Shizuku")
                return
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                _shizukuState.value = ShizukuState.Connected
                return
            }
            Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to request Shizuku permission", t)
        }
    }

    fun isReady(): Boolean = _shizukuState.value is ShizukuState.Connected

    fun destroy() {
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        } catch (e: Throwable) {
            Log.e(TAG, "Error removing Shizuku listeners", e)
        }
    }
}
