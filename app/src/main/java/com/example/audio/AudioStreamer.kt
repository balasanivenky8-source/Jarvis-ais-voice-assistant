package com.example.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * Handles bidirectional raw PCM16 audio streaming:
 * - Captures microphone audio at 16 kHz mono (little-endian PCM16)
 * - Plays model response audio at 24 kHz mono (little-endian PCM16, with 48 kHz HAL fallback)
 * - Exposes normalized real-time input/output RMS levels for HUD visualizers
 * - Supports instant playback interruption flushing queued buffers
 */
class AudioStreamer(
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "AudioStreamer"
        const val INPUT_SAMPLE_RATE = 16_000
        const val OUTPUT_SAMPLE_RATE = 24_000
        private const val FALLBACK_OUTPUT_SAMPLE_RATE = 48_000
        private const val INPUT_CHUNK_BYTES = 1280 // 40ms @ 16kHz 16-bit mono
    }

    private var audioRecord: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null

    private var audioTrack: AudioTrack? = null
    private var playbackUpsampleFactor: Int = 1
    private var captureJob: Job? = null
    private var playbackJob: Job? = null

    private var outputQueue = Channel<ByteArray>(capacity = Channel.UNLIMITED)

    private val _inputAmplitude = MutableStateFlow(0f)
    val inputAmplitude: StateFlow<Float> = _inputAmplitude.asStateFlow()

    private val _outputAmplitude = MutableStateFlow(0f)
    val outputAmplitude: StateFlow<Float> = _outputAmplitude.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    @SuppressLint("MissingPermission")
    fun startCapture(onAudioChunk: suspend (ByteArray) -> Unit) {
        stopCapture()

        val record = createAudioRecord()
            ?: throw IllegalStateException("Microphone audio capture failed to initialize.")

        val sessionId = record.audioSessionId
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
            }
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Audio effects unavailable: ${e.message}")
        }

        audioRecord = record
        record.startRecording()

        captureJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(INPUT_CHUNK_BYTES)
            try {
                while (isActive && record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val readBytes = record.read(buffer, 0, buffer.size)
                    if (readBytes > 0) {
                        val chunk = buffer.copyOf(readBytes)
                        _inputAmplitude.value = computeNormalizedRms(chunk)
                        onAudioChunk(chunk)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Audio capture loop ended: ${e.message}")
            } finally {
                _inputAmplitude.value = 0f
            }
        }
    }

    fun startPlayback() {
        stopPlayback()
        outputQueue = Channel(capacity = Channel.UNLIMITED)

        val (track, upsampleFactor) = createAudioTrack()
        audioTrack = track
        playbackUpsampleFactor = upsampleFactor

        if (track == null) {
            Log.w(TAG, "AudioTrack unavailable on this device; audio playback muted.")
            return
        }

        try {
            track.play()
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack.play() failed during startPlayback", e)
            try {
                track.release()
            } catch (_: Exception) {
            }
            audioTrack = null
            return
        }

        playbackJob = scope.launch(Dispatchers.IO) {
            try {
                for (pcmBytes in outputQueue) {
                    if (!isActive) break
                    _isPlaying.value = true
                    _outputAmplitude.value = computeNormalizedRms(pcmBytes)
                    val activeTrack = audioTrack ?: continue
                    if (activeTrack.state != AudioTrack.STATE_INITIALIZED) continue
                    if (activeTrack.playState != AudioTrack.PLAYSTATE_PLAYING) {
                        activeTrack.play()
                    }
                    val dataToWrite = if (playbackUpsampleFactor == 2) {
                        upsamplePcm16Mono2x(pcmBytes)
                    } else {
                        pcmBytes
                    }
                    var offset = 0
                    while (offset < dataToWrite.size && isActive) {
                        val written = activeTrack.write(
                            dataToWrite,
                            offset,
                            dataToWrite.size - offset
                        )
                        if (written <= 0) break
                        offset += written
                    }
                    if (outputQueue.isEmpty) {
                        _outputAmplitude.value = 0f
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Audio playback loop ended: ${e.message}")
            } finally {
                _isPlaying.value = false
                _outputAmplitude.value = 0f
            }
        }
    }

    fun enqueuePlayback(pcm24kBytes: ByteArray) {
        if (pcm24kBytes.isEmpty()) return
        if (audioTrack == null) return
        outputQueue.trySend(pcm24kBytes)
    }

    /**
     * Immediately halts current playback and clears any buffered PCM chunks
     * so user interruptions never overlap with stale assistant audio.
     */
    fun interruptPlayback() {
        while (outputQueue.tryReceive().isSuccess) {
            // Drain queued chunks
        }
        try {
            audioTrack?.let { track ->
                if (track.state == AudioTrack.STATE_INITIALIZED) {
                    track.pause()
                    track.flush()
                    track.play()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error flushing AudioTrack on interrupt: ${e.message}")
        }
        _isPlaying.value = false
        _outputAmplitude.value = 0f
    }

    fun markTurnComplete() {
        if (outputQueue.isEmpty) {
            _isPlaying.value = false
            _outputAmplitude.value = 0f
        }
    }

    fun stopCapture() {
        captureJob?.cancel()
        captureJob = null
        try {
            echoCanceler?.release()
        } catch (_: Exception) {
        }
        echoCanceler = null
        try {
            noiseSuppressor?.release()
        } catch (_: Exception) {
        }
        noiseSuppressor = null
        try {
            audioRecord?.let {
                if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    it.stop()
                }
                it.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioRecord: ${e.message}")
        }
        audioRecord = null
        _inputAmplitude.value = 0f
    }

    fun stopPlayback() {
        playbackJob?.cancel()
        playbackJob = null
        outputQueue.close()
        try {
            audioTrack?.let {
                if (it.state == AudioTrack.STATE_INITIALIZED) {
                    it.pause()
                    it.flush()
                    it.stop()
                }
                it.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioTrack: ${e.message}")
        }
        audioTrack = null
        playbackUpsampleFactor = 1
        _isPlaying.value = false
        _outputAmplitude.value = 0f
    }

    fun release() {
        stopCapture()
        stopPlayback()
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(): AudioRecord? {
        val rawMinBuf = AudioRecord.getMinBufferSize(
            INPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = if (rawMinBuf > 0) {
            rawMinBuf.coerceAtLeast(INPUT_CHUNK_BYTES * 4)
        } else {
            INPUT_CHUNK_BYTES * 4
        }

        val sources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.MIC
        )

        for (source in sources) {
            try {
                val candidate = AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(INPUT_SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .build()

                if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                    return candidate
                }
                Log.w(TAG, "AudioRecord not initialized for source=$source (state=${candidate.state})")
                candidate.release()
            } catch (e: Exception) {
                Log.w(TAG, "AudioRecord creation failed for source=$source: ${e.message}")
            }
        }
        return null
    }

    private fun createAudioTrack(): Pair<AudioTrack?, Int> {
        val configs = listOf(
            OUTPUT_SAMPLE_RATE to 1,
            FALLBACK_OUTPUT_SAMPLE_RATE to 2
        )

        for ((sampleRate, upsampleFactor) in configs) {
            val rawMinBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = if (rawMinBuf > 0) {
                (rawMinBuf * 2).coerceAtLeast(4096)
            } else {
                8192
            }

            try {
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                if (track.state == AudioTrack.STATE_INITIALIZED) {
                    return track to upsampleFactor
                }
                Log.w(TAG, "AudioTrack uninitialized at ${sampleRate}Hz (state=${track.state})")
                track.release()
            } catch (e: Exception) {
                Log.w(TAG, "AudioTrack creation failed at ${sampleRate}Hz: ${e.message}")
            }
        }
        return null to 1
    }

    private fun upsamplePcm16Mono2x(pcm24k: ByteArray): ByteArray {
        val sampleCount = pcm24k.size / 2
        if (sampleCount == 0) return pcm24k
        val out = ByteArray(sampleCount * 4)
        for (i in 0 until sampleCount) {
            val low = pcm24k[i * 2]
            val high = pcm24k[i * 2 + 1]
            val outIdx = i * 4
            out[outIdx] = low
            out[outIdx + 1] = high
            out[outIdx + 2] = low
            out[outIdx + 3] = high
        }
        return out
    }

    private fun computeNormalizedRms(pcm16LittleEndian: ByteArray): Float {
        if (pcm16LittleEndian.size < 2) return 0f
        var sumSquares = 0.0
        var sampleCount = 0
        var i = 0
        while (i + 1 < pcm16LittleEndian.size) {
            val low = pcm16LittleEndian[i].toInt() and 0xFF
            val high = pcm16LittleEndian[i + 1].toInt()
            val sample = (high shl 8) or low
            val normalized = sample / 32768.0
            sumSquares += normalized * normalized
            sampleCount++
            i += 2
        }
        if (sampleCount == 0) return 0f
        val rms = sqrt(sumSquares / sampleCount).toFloat()
        return (rms * 3.5f).coerceIn(0f, 1f)
    }
}
