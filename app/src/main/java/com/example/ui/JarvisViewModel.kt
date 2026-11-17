package com.example.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.R
import com.example.ai.JarvisConnectionState
import com.example.ai.LiveSessionManager
import com.example.audio.AudioStreamer
import com.example.vision.CameraLens
import com.example.vision.ScreenCaptureBridge
import com.example.vision.ScreenCaptureService
import com.google.firebase.ai.type.FunctionCallPart
import com.google.firebase.ai.type.FunctionResponsePart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

data class JarvisUiState(
    val connectionState: JarvisConnectionState = JarvisConnectionState.DISCONNECTED,
    val inputAmplitude: Float = 0f,
    val outputAmplitude: Float = 0f,
    val isCameraVisionActive: Boolean = false,
    val cameraLens: CameraLens = CameraLens.BACK,
    val isScreenVisionActive: Boolean = false,
    val activeToolTelemetry: String? = null,
    val errorMessage: String? = null
)

sealed interface JarvisEffect {
    data class OpenWebsite(val url: String) : JarvisEffect
    data object RequestMicPermissionAndConnect : JarvisEffect
    data object RequestCameraPermission : JarvisEffect
    data object RequestScreenCapturePermission : JarvisEffect
}

class JarvisViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val audioStreamer = AudioStreamer(viewModelScope)
    private val sessionManager = LiveSessionManager(
        scope = viewModelScope,
        audioStreamer = audioStreamer,
        onToolCall = ::executeFunctionCall,
        onError = ::postError
    )

    private val _uiState = MutableStateFlow(JarvisUiState())
    val uiState: StateFlow<JarvisUiState> = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<JarvisEffect>(extraBufferCapacity = 8)
    val effects: SharedFlow<JarvisEffect> = _effects.asSharedFlow()

    private var hasMicPermission = false
    private var hasCameraPermission = false

    init {
        viewModelScope.launch {
            combine(
                sessionManager.connectionState,
                audioStreamer.inputAmplitude,
                audioStreamer.outputAmplitude,
                ScreenCaptureBridge.isActive
            ) { connState, inAmp, outAmp, screenActive ->
                QuadState(connState, inAmp, outAmp, screenActive)
            }.collect { (connState, inAmp, outAmp, screenActive) ->
                _uiState.update { current ->
                    current.copy(
                        connectionState = connState,
                        inputAmplitude = inAmp,
                        outputAmplitude = outAmp,
                        isScreenVisionActive = screenActive
                    )
                }
            }
        }

        viewModelScope.launch {
            ScreenCaptureBridge.frames.collect { jpegBytes ->
                if (_uiState.value.isScreenVisionActive) {
                    sessionManager.sendVisionFrame(jpegBytes)
                }
            }
        }
    }

    fun updatePermissionFlags(micGranted: Boolean, cameraGranted: Boolean) {
        hasMicPermission = micGranted
        hasCameraPermission = cameraGranted
    }

    fun onPowerMicTapped(micGranted: Boolean) {
        hasMicPermission = micGranted
        _uiState.update { it.copy(errorMessage = null) }

        when (_uiState.value.connectionState) {
            JarvisConnectionState.DISCONNECTED -> {
                if (micGranted) {
                    sessionManager.connect()
                } else {
                    _effects.tryEmit(JarvisEffect.RequestMicPermissionAndConnect)
                }
            }
            JarvisConnectionState.CONNECTING,
            JarvisConnectionState.LISTENING,
            JarvisConnectionState.SPEAKING -> {
                stopAllVision()
                sessionManager.disconnect()
            }
        }
    }

    fun onMicPermissionResult(granted: Boolean) {
        hasMicPermission = granted
        if (granted) {
            _uiState.update { it.copy(errorMessage = null) }
            sessionManager.connect()
        } else {
            postError(getApplication<Application>().getString(R.string.permission_mic_required))
        }
    }

    fun onToggleCameraVision(cameraGranted: Boolean) {
        hasCameraPermission = cameraGranted
        if (_uiState.value.isCameraVisionActive) {
            _uiState.update { it.copy(isCameraVisionActive = false) }
            postTelemetry("CAMERA EYES // OFFLINE")
        } else if (cameraGranted) {
            _uiState.update { it.copy(isCameraVisionActive = true, errorMessage = null) }
            postTelemetry("CAMERA EYES // LIVE")
        } else {
            _effects.tryEmit(JarvisEffect.RequestCameraPermission)
        }
    }

    fun onCameraPermissionResult(granted: Boolean) {
        hasCameraPermission = granted
        if (granted) {
            _uiState.update { it.copy(isCameraVisionActive = true, errorMessage = null) }
            postTelemetry("CAMERA EYES // LIVE")
        } else {
            postError(getApplication<Application>().getString(R.string.permission_camera_required))
        }
    }

    fun onFlipCamera() {
        _uiState.update { state ->
            val next = if (state.cameraLens == CameraLens.BACK) CameraLens.FRONT else CameraLens.BACK
            state.copy(cameraLens = next)
        }
        val lensLabel = if (_uiState.value.cameraLens == CameraLens.FRONT) "FRONT" else "REAR"
        postTelemetry("OPTIC SWITCH // $lensLabel")
    }

    fun onCameraFrameCaptured(jpegBytes: ByteArray) {
        if (_uiState.value.isCameraVisionActive) {
            sessionManager.sendVisionFrame(jpegBytes)
        }
    }

    fun onToggleScreenVision() {
        if (_uiState.value.isScreenVisionActive) {
            ScreenCaptureService.stop(getApplication())
            postTelemetry("SCREEN EYES // OFFLINE")
        } else {
            _effects.tryEmit(JarvisEffect.RequestScreenCapturePermission)
        }
    }

    fun onScreenCaptureResult(resultCode: Int, data: Intent?) {
        if (data != null) {
            ScreenCaptureService.start(getApplication(), resultCode, data)
            postTelemetry("SCREEN EYES // LIVE")
        } else {
            postError(getApplication<Application>().getString(R.string.screen_vision_denied))
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    private fun stopAllVision() {
        if (_uiState.value.isCameraVisionActive) {
            _uiState.update { it.copy(isCameraVisionActive = false) }
        }
        if (_uiState.value.isScreenVisionActive) {
            ScreenCaptureService.stop(getApplication())
        }
    }

    private suspend fun executeFunctionCall(call: FunctionCallPart): FunctionResponsePart {
        return when (call.name) {
            "openWebsite" -> handleOpenWebsite(call)
            "cameraVision" -> handleCameraVisionTool(call)
            "screenVision" -> handleScreenVisionTool(call)
            else -> FunctionResponsePart(
                name = call.name,
                response = JsonObject(
                    mapOf("error" to JsonPrimitive("Unsupported tool: ${call.name}"))
                ),
                id = call.id
            )
        }
    }

    private fun handleOpenWebsite(call: FunctionCallPart): FunctionResponsePart {
        val rawUrl = call.args["url"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (rawUrl.isEmpty()) {
            return FunctionResponsePart(
                name = call.name,
                response = JsonObject(
                    mapOf(
                        "success" to JsonPrimitive(false),
                        "error" to JsonPrimitive("Missing URL parameter")
                    )
                ),
                id = call.id
            )
        }

        val normalizedUrl = if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
            rawUrl
        } else {
            "https://$rawUrl"
        }

        _effects.tryEmit(JarvisEffect.OpenWebsite(normalizedUrl))
        postTelemetry("LAUNCHING // ${normalizedUrl.removePrefix("https://").removePrefix("www.").take(26).uppercase()}")

        return FunctionResponsePart(
            name = call.name,
            response = JsonObject(
                mapOf(
                    "success" to JsonPrimitive(true),
                    "openedUrl" to JsonPrimitive(normalizedUrl)
                )
            ),
            id = call.id
        )
    }

    private fun handleCameraVisionTool(call: FunctionCallPart): FunctionResponsePart {
        val action = call.args["action"]?.jsonPrimitive?.contentOrNull ?: "start"
        val lensArg = call.args["lensFacing"]?.jsonPrimitive?.contentOrNull

        val requestedLens = when (lensArg) {
            "front" -> CameraLens.FRONT
            "back" -> CameraLens.BACK
            else -> null
        }

        return when (action) {
            "stop" -> {
                _uiState.update { it.copy(isCameraVisionActive = false) }
                postTelemetry("CAMERA EYES // OFFLINE")
                FunctionResponsePart(
                    name = call.name,
                    response = JsonObject(
                        mapOf(
                            "success" to JsonPrimitive(true),
                            "cameraActive" to JsonPrimitive(false)
                        )
                    ),
                    id = call.id
                )
            }
            "switch_camera" -> {
                val targetLens = requestedLens ?: if (_uiState.value.cameraLens == CameraLens.BACK) {
                    CameraLens.FRONT
                } else {
                    CameraLens.BACK
                }
                _uiState.update { it.copy(cameraLens = targetLens) }
                if (!_uiState.value.isCameraVisionActive) {
                    if (hasCameraPermission) {
                        _uiState.update { it.copy(isCameraVisionActive = true) }
                    } else {
                        _effects.tryEmit(JarvisEffect.RequestCameraPermission)
                    }
                }
                postTelemetry("OPTIC SWITCH // ${targetLens.name}")
                FunctionResponsePart(
                    name = call.name,
                    response = JsonObject(
                        mapOf(
                            "success" to JsonPrimitive(true),
                            "lensFacing" to JsonPrimitive(targetLens.name.lowercase()),
                            "permissionGranted" to JsonPrimitive(hasCameraPermission)
                        )
                    ),
                    id = call.id
                )
            }
            else -> {
                val targetLens = requestedLens ?: _uiState.value.cameraLens
                _uiState.update { it.copy(cameraLens = targetLens) }
                if (hasCameraPermission) {
                    _uiState.update { it.copy(isCameraVisionActive = true) }
                    postTelemetry("CAMERA EYES // LIVE")
                    FunctionResponsePart(
                        name = call.name,
                        response = JsonObject(
                            mapOf(
                                "success" to JsonPrimitive(true),
                                "cameraActive" to JsonPrimitive(true),
                                "lensFacing" to JsonPrimitive(targetLens.name.lowercase())
                            )
                        ),
                        id = call.id
                    )
                } else {
                    _effects.tryEmit(JarvisEffect.RequestCameraPermission)
                    FunctionResponsePart(
                        name = call.name,
                        response = JsonObject(
                            mapOf(
                                "success" to JsonPrimitive(true),
                                "cameraActive" to JsonPrimitive(false),
                                "awaitingPermission" to JsonPrimitive(true)
                            )
                        ),
                        id = call.id
                    )
                }
            }
        }
    }

    private fun handleScreenVisionTool(call: FunctionCallPart): FunctionResponsePart {
        val action = call.args["action"]?.jsonPrimitive?.contentOrNull ?: "start"
        return when (action) {
            "stop" -> {
                ScreenCaptureService.stop(getApplication())
                postTelemetry("SCREEN EYES // OFFLINE")
                FunctionResponsePart(
                    name = call.name,
                    response = JsonObject(
                        mapOf(
                            "success" to JsonPrimitive(true),
                            "screenVisionActive" to JsonPrimitive(false)
                        )
                    ),
                    id = call.id
                )
            }
            else -> {
                if (_uiState.value.isScreenVisionActive) {
                    FunctionResponsePart(
                        name = call.name,
                        response = JsonObject(
                            mapOf(
                                "success" to JsonPrimitive(true),
                                "screenVisionActive" to JsonPrimitive(true)
                            )
                        ),
                        id = call.id
                    )
                } else {
                    _effects.tryEmit(JarvisEffect.RequestScreenCapturePermission)
                    FunctionResponsePart(
                        name = call.name,
                        response = JsonObject(
                            mapOf(
                                "success" to JsonPrimitive(true),
                                "awaitingSystemConsent" to JsonPrimitive(true)
                            )
                        ),
                        id = call.id
                    )
                }
            }
        }
    }

    private fun postTelemetry(label: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(activeToolTelemetry = label) }
            delay(3200L)
            _uiState.update { state ->
                if (state.activeToolTelemetry == label) state.copy(activeToolTelemetry = null) else state
            }
        }
    }

    fun postError(message: String) {
        _uiState.update { it.copy(errorMessage = message) }
    }

    override fun onCleared() {
        stopAllVision()
        sessionManager.release()
        super.onCleared()
    }

    private data class QuadState(
        val connectionState: JarvisConnectionState,
        val inputAmplitude: Float,
        val outputAmplitude: Float,
        val screenActive: Boolean
    )
}
