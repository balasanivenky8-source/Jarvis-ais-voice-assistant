package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ai.configureAppCheck
import com.example.ui.JarvisEffect
import com.example.ui.JarvisScreen
import com.example.ui.JarvisViewModel
import com.example.ui.theme.JarvisTheme

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private val viewModel: JarvisViewModel by viewModels()

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.onMicPermissionResult(granted)
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.onCameraPermissionResult(granted)
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        launchScreenCaptureConsent()
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.onScreenCaptureResult(result.resultCode, result.data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // STEP 1: Configure App Check with launch intent before any UI or Firebase operations
        configureAppCheck(this, intent)

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )

        syncPermissions()

        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()

            LaunchedEffect(Unit) {
                viewModel.effects.collect { effect ->
                    when (effect) {
                        is JarvisEffect.OpenWebsite -> openWebsite(effect.url)
                        JarvisEffect.RequestMicPermissionAndConnect -> {
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                        JarvisEffect.RequestCameraPermission -> {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                        JarvisEffect.RequestScreenCapturePermission -> {
                            requestScreenVisionConsent()
                        }
                    }
                }
            }

            JarvisTheme {
                JarvisScreen(
                    uiState = uiState,
                    onPowerMicClick = {
                        viewModel.onPowerMicTapped(hasPermission(Manifest.permission.RECORD_AUDIO))
                    },
                    onToggleCameraVision = {
                        viewModel.onToggleCameraVision(hasPermission(Manifest.permission.CAMERA))
                    },
                    onFlipCamera = viewModel::onFlipCamera,
                    onToggleScreenVision = viewModel::onToggleScreenVision,
                    onCameraFrameCaptured = viewModel::onCameraFrameCaptured,
                    onCameraError = viewModel::postError,
                    onDismissError = viewModel::clearError
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        syncPermissions()
    }

    private fun syncPermissions() {
        viewModel.updatePermissionFlags(
            micGranted = hasPermission(Manifest.permission.RECORD_AUDIO),
            cameraGranted = hasPermission(Manifest.permission.CAMERA)
        )
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun requestScreenVisionConsent() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            launchScreenCaptureConsent()
        }
    }

    private fun launchScreenCaptureConsent() {
        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun openWebsite(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open website: $url", e)
            viewModel.postError("Could not open website: $url")
        }
    }
}
