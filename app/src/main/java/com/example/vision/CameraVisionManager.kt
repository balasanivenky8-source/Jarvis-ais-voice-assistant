package com.example.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max

enum class CameraLens {
    BACK,
    FRONT
}

/**
 * Manages CameraX Preview + 1 FPS ImageAnalysis downscaled to <= 768px JPEG frames
 * for Gemini Live API multimodal vision.
 */
class CameraVisionManager(
    private val context: Context,
    private val onFrameCaptured: (ByteArray) -> Unit,
    private val onError: (String) -> Unit
) {
    companion object {
        private const val TAG = "CameraVisionManager"
        private const val FRAME_INTERVAL_MS = 1000L
        private const val MAX_DIMENSION_PX = 768
        private const val JPEG_QUALITY = 70
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var lastFrameTimestampMs = 0L

    fun bindCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        lens: CameraLens
    ) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener(
            {
                try {
                    val provider = providerFuture.get()
                    cameraProvider = provider
                    provider.unbindAll()

                    val selector = if (lens == CameraLens.FRONT) {
                        CameraSelector.DEFAULT_FRONT_CAMERA
                    } else {
                        CameraSelector.DEFAULT_BACK_CAMERA
                    }

                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }

                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { imageAnalysis ->
                            imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                                processImageProxy(imageProxy, lens == CameraLens.FRONT)
                            }
                        }

                    provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to bind CameraX use cases", e)
                    onError(e.localizedMessage ?: "Camera failed to start.")
                }
            },
            ContextCompat.getMainExecutor(context)
        )
    }

    fun unbind() {
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.w(TAG, "Error unbinding camera provider: ${e.message}")
        }
    }

    fun release() {
        unbind()
        analysisExecutor.shutdown()
    }

    private fun processImageProxy(imageProxy: ImageProxy, mirrorHorizontal: Boolean) {
        try {
            val now = System.currentTimeMillis()
            if (now - lastFrameTimestampMs < FRAME_INTERVAL_MS) {
                return
            }
            lastFrameTimestampMs = now

            val rawBitmap = imageProxy.toBitmap()
            val transformed = transformBitmap(
                source = rawBitmap,
                rotationDegrees = imageProxy.imageInfo.rotationDegrees,
                mirrorHorizontal = mirrorHorizontal
            )
            if (transformed !== rawBitmap) {
                rawBitmap.recycle()
            }

            val outputStream = ByteArrayOutputStream()
            transformed.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)
            transformed.recycle()
            onFrameCaptured(outputStream.toByteArray())
        } catch (e: Exception) {
            Log.w(TAG, "Error encoding camera frame: ${e.message}")
        } finally {
            imageProxy.close()
        }
    }

    private fun transformBitmap(
        source: Bitmap,
        rotationDegrees: Int,
        mirrorHorizontal: Boolean
    ): Bitmap {
        val maxSide = max(source.width, source.height)
        val scale = if (maxSide > MAX_DIMENSION_PX) {
            MAX_DIMENSION_PX.toFloat() / maxSide.toFloat()
        } else {
            1f
        }

        if (rotationDegrees == 0 && !mirrorHorizontal && scale == 1f) {
            return source
        }

        val matrix = Matrix().apply {
            if (scale != 1f) {
                postScale(scale, scale)
            }
            if (rotationDegrees != 0) {
                postRotate(rotationDegrees.toFloat())
            }
            if (mirrorHorizontal) {
                postScale(-1f, 1f)
            }
        }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }
}
