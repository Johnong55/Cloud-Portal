package com.trijohn.cloudportal

import android.annotation.SuppressLint
import android.app.Activity
import android.app.KeyguardManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.os.SystemClock
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit

internal class AppLockController(private val activity: Activity) {
    private val preferences = activity.getSharedPreferences(PREFERENCES, Activity.MODE_PRIVATE)
    private val biometricManager = activity.getSystemService(BiometricManager::class.java)
    private val keyguardManager = activity.getSystemService(KeyguardManager::class.java)
    private var cancellationSignal: CancellationSignal? = null
    private var promptOpen = false
    private var lastBackgroundAt = 0L

    var onLockStateChanged: ((Boolean) -> Unit)? = null

    var isEnabled by mutableStateOf(preferences.getBoolean(KEY_ENABLED, false))
        private set
    var isLocked by mutableStateOf(isEnabled)
        private set
    var lastError by mutableStateOf<String?>(null)
        private set

    init {
        updateSecureWindow()
    }

    fun onResume() {
        if (!isEnabled) return
        val awayLongEnough = lastBackgroundAt > 0L &&
            SystemClock.elapsedRealtime() - lastBackgroundAt >= LOCK_AFTER_MILLIS
        if (isLocked || awayLongEnough) {
            updateLockedState(true)
            requestUnlock()
        }
    }

    fun onStop() {
        if (isEnabled && !promptOpen) lastBackgroundAt = SystemClock.elapsedRealtime()
    }

    fun enable(onResult: (String) -> Unit) {
        if (isEnabled) return
        authenticate(
            title = "Bật khóa Cloud Portal",
            subtitle = "Xác nhận bằng sinh trắc học hoặc khóa màn hình để bảo vệ phiên iCloud.",
            onSuccess = {
                preferences.edit(commit = true) { putBoolean(KEY_ENABLED, true) }
                isEnabled = true
                updateLockedState(false)
                lastError = null
                updateSecureWindow()
                onResult("Đã bật khóa sinh trắc học và bảo vệ màn hình Recent Apps.")
            },
            onFailure = onResult,
        )
    }

    fun disable(onResult: (String) -> Unit) {
        if (!isEnabled) return
        authenticate(
            title = "Tắt khóa Cloud Portal",
            subtitle = "Xác nhận để cho phép mở ứng dụng không cần sinh trắc học.",
            onSuccess = {
                preferences.edit(commit = true) { putBoolean(KEY_ENABLED, false) }
                isEnabled = false
                updateLockedState(false)
                lastError = null
                updateSecureWindow()
                onResult("Đã tắt khóa sinh trắc học.")
            },
            onFailure = onResult,
        )
    }

    fun requestUnlock() {
        if (!isEnabled || promptOpen) return
        authenticate(
            title = "Mở khóa Cloud Portal",
            subtitle = "Dùng sinh trắc học hoặc khóa màn hình để truy cập phiên iCloud.",
            onSuccess = {
                updateLockedState(false)
                lastError = null
                lastBackgroundAt = 0L
            },
            onFailure = { lastError = it },
        )
    }

    fun destroy() {
        cancellationSignal?.cancel()
        cancellationSignal = null
        promptOpen = false
        onLockStateChanged = null
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun authenticate(
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit,
    ) {
        if (promptOpen) return
        val availability = biometricManager?.canAuthenticate()
            ?: BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE
        val deviceCredentialAvailable = keyguardManager?.isDeviceSecure == true
        if (availability != BiometricManager.BIOMETRIC_SUCCESS && !deviceCredentialAvailable) {
            onFailure(availabilityMessage(availability))
            return
        }

        promptOpen = true
        lastError = null
        val executor = activity.mainExecutor
        val signal = CancellationSignal()
        cancellationSignal = signal
        var completed = false

        fun finish(success: Boolean, message: String? = null) {
            if (completed) return
            completed = true
            promptOpen = false
            cancellationSignal = null
            if (success) onSuccess() else onFailure(message ?: "Chưa xác thực sinh trắc học.")
        }

        try {
            val prompt = BiometricPrompt.Builder(activity)
                .setTitle(title)
                .setSubtitle(subtitle)
                .setConfirmationRequired(false)
                .apply {
                    if (deviceCredentialAvailable) {
                        setDeviceCredentialAllowed(true)
                    } else {
                        setNegativeButton("Hủy", executor) { _, _ -> finish(false, "Đã hủy xác thực.") }
                    }
                }
                .build()
            prompt.authenticate(
                signal,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                        finish(true)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                        finish(false, errString?.toString()?.takeIf(String::isNotBlank))
                    }

                    override fun onAuthenticationFailed() {
                        lastError = "Không nhận diện được. Hãy thử lại."
                    }
                },
            )
        } catch (error: RuntimeException) {
            signal.cancel()
            finish(false, error.message ?: "Không thể mở xác thực sinh trắc học.")
        }
    }

    private fun updateLockedState(value: Boolean) {
        if (isLocked == value) return
        isLocked = value
        onLockStateChanged?.invoke(value)
    }

    private fun updateSecureWindow() {
        if (isEnabled) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private fun availabilityMessage(code: Int): String = when (code) {
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "Thiết bị không có cảm biến sinh trắc học."
        BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "Cảm biến sinh trắc học đang không khả dụng."
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "Hãy đăng ký vân tay hoặc khuôn mặt trong Cài đặt Android trước."
        else -> "Không thể sử dụng sinh trắc học trên thiết bị này."
    }

    private companion object {
        const val PREFERENCES = "cloud_portal_app_lock"
        const val KEY_ENABLED = "biometric_lock_enabled"
        const val LOCK_AFTER_MILLIS = 20_000L
    }
}

@Composable
internal fun AppLockedScreen(controller: AppLockController) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF15112E), Color(0xFF071725)),
                ),
            )
            .padding(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Surface(
                modifier = Modifier.size(82.dp),
                color = Color(0xFF6F64FF).copy(alpha = 0.2f),
                contentColor = Color.White,
                shape = RoundedCornerShape(27.dp),
            ) {
                Box(contentAlignment = Alignment.Center) { Text("☁", fontSize = 40.sp) }
            }
            Spacer(Modifier.height(24.dp))
            Text(
                "Cloud Portal đã khóa",
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Black,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                controller.lastError ?: "Phiên iCloud và thư viện tải về đang được bảo vệ.",
                color = Color.White.copy(alpha = 0.68f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(28.dp))
            Surface(
                modifier = Modifier
                    .clickable { controller.requestUnlock() },
                color = Color.White,
                contentColor = Color(0xFF101426),
                shape = RoundedCornerShape(18.dp),
            ) {
                Text(
                    "Mở khóa an toàn",
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp),
                    fontWeight = FontWeight.Black,
                )
            }
        }
    }
}
