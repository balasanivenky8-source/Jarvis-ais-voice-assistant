package com.example.ai

import android.content.Context
import android.util.Log
import com.example.audio.AudioStreamer
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.ContextWindowCompressionConfig
import com.google.firebase.ai.type.FunctionCallPart
import com.google.firebase.ai.type.FunctionResponsePart
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.InlineData
import com.google.firebase.ai.type.InlineDataPart
import com.google.firebase.ai.type.LiveServerContent
import com.google.firebase.ai.type.LiveServerGoAway
import com.google.firebase.ai.type.LiveServerToolCall
import com.google.firebase.ai.type.LiveServerToolCallCancellation
import com.google.firebase.ai.type.LiveSession
import com.google.firebase.ai.type.LiveSessionResumptionUpdate
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.SessionResumptionConfig
import com.google.firebase.ai.type.SlidingWindow
import com.google.firebase.ai.type.SpeechConfig
import com.google.firebase.ai.type.Voice
import com.google.firebase.ai.type.liveGenerationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

enum class JarvisConnectionState {
    DISCONNECTED,
    CONNECTING,
    LISTENING,
    SPEAKING
}

/**
 * Manages the Gemini Live API WebSocket session (`LiveSession`) independently of UI.
 * Handles bidirectional audio streaming, vision frame streaming, interruption signals,
 * session resumption, and instant function-calling responses.
 */
@OptIn(PublicPreviewAPI::class)
class LiveSessionManager(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val audioStreamer: AudioStreamer,
    private val onToolCall: suspend (FunctionCallPart) -> FunctionResponsePart,
    private val onError: (String) -> Unit
) {
    companion object {
        private const val TAG = "LiveSessionManager"
        private const val CONNECT_TIMEOUT_MS = 30_000L
    }

    private val liveModel by lazy {
        if (!ensureFirebaseInitialized(appContext)) {
            throw IllegalStateException(
                "Firebase is not configured in this APK build. Ensure app/google-services.json is included before building."
            )
        }
        Firebase.ai(backend = GenerativeBackend.googleAI()).liveModel(
            modelName = JarvisPersona.MODEL_NAME,
            generationConfig = liveGenerationConfig {
                responseModality = ResponseModality.AUDIO
                speechConfig = SpeechConfig(voice = Voice(JarvisPersona.VOICE_NAME))
                contextWindowCompression = ContextWindowCompressionConfig(
                    slidingWindow = SlidingWindow()
                )
            },
            tools = JarvisPersona.tools,
            systemInstruction = JarvisPersona.systemInstruction
        )
    }

    private var session: LiveSession? = null
    private var receiveJob: Job? = null
    private var playbackStateJob: Job? = null
    private var activeSessionHandle: String? = null
    private var isModelTurnStreaming = false

    private val _connectionState = MutableStateFlow(JarvisConnectionState.DISCONNECTED)
    val connectionState: StateFlow<JarvisConnectionState> = _connectionState.asStateFlow()

    fun connect() {
        if (_connectionState.value == JarvisConnectionState.CONNECTING ||
            _connectionState.value == JarvisConnectionState.LISTENING ||
            _connectionState.value == JarvisConnectionState.SPEAKING
        ) {
            return
        }

        scope.launch(Dispatchers.IO) {
            _connectionState.value = JarvisConnectionState.CONNECTING
            try {
                if (!ensureFirebaseInitialized(appContext)) {
                    throw IllegalStateException(
                        "Firebase is not configured in this APK build. Ensure app/google-services.json is included before building."
                    )
                }
                val liveSession = withTimeout(CONNECT_TIMEOUT_MS) {
                    val resumption = activeSessionHandle?.let { SessionResumptionConfig(it) }
                        ?: SessionResumptionConfig()
                    liveModel.connect(resumption)
                }
                session = liveSession
                audioStreamer.startPlayback()
                audioStreamer.startCapture { pcmChunk ->
                    try {
                        session?.sendAudioRealtime(
                            InlineData(pcmChunk, "audio/pcm;rate=16000")
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed sending audio chunk: ${e.message}")
                    }
                }

                _connectionState.value = JarvisConnectionState.LISTENING
                observePlaybackState()
                startReceiving(liveSession)
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Connection timed out", e)
                cleanupSession()
                onError("Voice link timed out. Check your connection and try again.")
            } catch (e: CancellationException) {
                cleanupSession()
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect Gemini Live session", e)
                cleanupSession()
                onError(e.localizedMessage ?: "Failed to establish voice link with Jarvis.")
            }
        }
    }

    fun disconnect() {
        scope.launch(Dispatchers.IO) {
            cleanupSession()
        }
    }

    fun sendVisionFrame(jpegBytes: ByteArray) {
        val activeSession = session ?: return
        if (_connectionState.value == JarvisConnectionState.DISCONNECTED ||
            _connectionState.value == JarvisConnectionState.CONNECTING
        ) {
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                activeSession.sendVideoRealtime(InlineData(jpegBytes, "image/jpeg"))
            } catch (e: Exception) {
                Log.w(TAG, "Failed sending vision frame: ${e.message}")
            }
        }
    }

    private fun observePlaybackState() {
        playbackStateJob?.cancel()
        playbackStateJob = scope.launch {
            audioStreamer.isPlaying.collect { playing ->
                if (_connectionState.value == JarvisConnectionState.DISCONNECTED ||
                    _connectionState.value == JarvisConnectionState.CONNECTING
                ) {
                    return@collect
                }
                _connectionState.value = if (playing || isModelTurnStreaming) {
                    JarvisConnectionState.SPEAKING
                } else {
                    JarvisConnectionState.LISTENING
                }
            }
        }
    }

    private fun startReceiving(liveSession: LiveSession) {
        receiveJob?.cancel()
        receiveJob = scope.launch(Dispatchers.IO) {
            try {
                liveSession.receive().collect { message ->
                    if (!isActive) return@collect
                    when (message) {
                        is LiveServerContent -> handleServerContent(message)
                        is LiveServerToolCall -> handleToolCalls(liveSession, message)
                        is LiveServerToolCallCancellation -> {
                            Log.d(TAG, "Tool call cancelled: ${message.functionIds}")
                        }
                        is LiveSessionResumptionUpdate -> {
                            if (message.resumable == true && !message.newHandle.isNullOrBlank()) {
                                activeSessionHandle = message.newHandle
                                Log.d(TAG, "Updated session resumption handle")
                            }
                        }
                        is LiveServerGoAway -> {
                            Log.i(TAG, "Received GoAway notice; resuming session seamlessly")
                            activeSessionHandle?.let { handle ->
                                try {
                                    liveSession.resumeSession(SessionResumptionConfig(handle))
                                } catch (e: Exception) {
                                    Log.w(TAG, "Session resume attempt failed: ${e.message}")
                                }
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Live session receive stream closed with error", e)
                if (_connectionState.value != JarvisConnectionState.DISCONNECTED) {
                    cleanupSession()
                    onError(e.localizedMessage ?: "Voice connection interrupted.")
                }
            }
        }
    }

    private fun handleServerContent(contentMsg: LiveServerContent) {
        if (contentMsg.interrupted) {
            isModelTurnStreaming = false
            audioStreamer.interruptPlayback()
            _connectionState.value = JarvisConnectionState.LISTENING
            return
        }

        val parts = contentMsg.content?.parts.orEmpty()
        var receivedAudio = false
        for (part in parts) {
            if (part is InlineDataPart && part.mimeType.startsWith("audio/pcm")) {
                receivedAudio = true
                isModelTurnStreaming = true
                _connectionState.value = JarvisConnectionState.SPEAKING
                audioStreamer.enqueuePlayback(part.inlineData)
            }
        }

        if (contentMsg.turnComplete) {
            isModelTurnStreaming = false
            audioStreamer.markTurnComplete()
            if (!receivedAudio && !audioStreamer.isPlaying.value) {
                _connectionState.value = JarvisConnectionState.LISTENING
            }
        }
    }

    private fun handleToolCalls(
        liveSession: LiveSession,
        toolCallMsg: LiveServerToolCall
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                val responses = toolCallMsg.functionCalls.map { call ->
                    onToolCall(call)
                }
                if (responses.isNotEmpty()) {
                    liveSession.sendFunctionResponse(responses)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed handling tool call", e)
            }
        }
    }

    private suspend fun cleanupSession() {
        isModelTurnStreaming = false
        playbackStateJob?.cancel()
        playbackStateJob = null
        receiveJob?.cancel()
        receiveJob = null
        audioStreamer.release()
        try {
            session?.stopReceiving()
            session?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing LiveSession: ${e.message}")
        }
        session = null
        _connectionState.value = JarvisConnectionState.DISCONNECTED
    }

    fun release() {
        scope.launch(Dispatchers.IO) {
            cleanupSession()
        }
    }
}
