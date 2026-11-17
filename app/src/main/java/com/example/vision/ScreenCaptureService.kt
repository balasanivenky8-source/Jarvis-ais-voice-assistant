package com.example.vision

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.R
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import kotlin.math.max

object ScreenCaptureBridge {
    private val _frames = MutableSharedFlow<ByteArray>(extraBufferCapacity = 2)
    val frames: SharedFlow<ByteArray> = _frames.asSharedFlow()

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    internal fun emitFrame(jpegBytes: ByteArray) {
        _frames.tryEmit(jpegBytes)
    }

    internal fun setActive(active: Boolean) {
        _isActive.value = active
    }
}

/**
 * Foreground service compliant with Android 14+ mediaProjection FGS requirements.
 * Captures the screen at ~1 FPS downscaled to <= 768px JPEG for Gemini Live vision.
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val CHANNEL_ID = "jarvis_screen_vision"
        private const val NOTIFICATION_ID = 4102
        private const val EXTRA_RESULT_CODE = "extra_result_code"
        private const val EXTRA_RESULT_DATA = "extra_result_data"
        private const val FRAME_INTERVAL_MS = 1000L
        private const val MAX_DIMENSION_PX = 768
        private const val JPEG_QUALITY = 65

        fun start(context: Context, resultCode: Int, data: Intent) {
            val serviceIntent = Intent(context, ScreenCaptureService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            ContextCompat.startForegroundService(context, serviceIntent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScreenCaptureService::class.java))
        }
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var lastFrameTimestampMs = 0L

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.d(TAG, "MediaProjection stopped by system")
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureNotificationChannel()
        val notification = buildNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode != Activity.RESULT_OK || resultData == null) {
            Log.e(TAG, "Invalid MediaProjection permission payload")
            stopSelf()
            return START_NOT_STICKY
        }

        startCapture(resultCode, resultData)
        return START_NOT_STICKY
    }

    private fun startCapture(resultCode: Int, resultData: Intent) {
        releaseProjection()

        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = projectionManager.getMediaProjection(resultCode, resultData)
        if (projection == null) {
            Log.e(TAG, "getMediaProjection returned null")
            stopSelf()
            return
        }

        val metrics = resources.displayMetrics
        val srcWidth = metrics.widthPixels.coerceAtLeast(360)
        val srcHeight = metrics.heightPixels.coerceAtLeast(640)
        val maxSide = max(srcWidth, srcHeight)
        val scale = if (maxSide > MAX_DIMENSION_PX) {
            MAX_DIMENSION_PX.toFloat() / maxSide.toFloat()
        } else {
            1f
        }
        val captureWidth = (srcWidth * scale).toInt().coerceAtLeast(240)
        val captureHeight = (srcHeight * scale).toInt().coerceAtLeast(240)
        val densityDpi = metrics.densityDpi

        val thread = HandlerThread("JarvisScreenCapture").apply { start() }
        val captureHandler = Handler(thread.looper)
        handlerThread = thread
        handler = captureHandler

        projection.registerCallback(projectionCallback, captureHandler)

        val reader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ imgReader ->
            val image = imgReader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val now = System.currentTimeMillis()
                if (now - lastFrameTimestampMs < FRAME_INTERVAL_MS) {
                    return@setOnImageAvailableListener
                }
                lastFrameTimestampMs = now

                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * captureWidth

                val fullBitmap = Bitmap.createBitmap(
                    captureWidth + rowPadding / pixelStride,
                    captureHeight,
                    Bitmap.Config.ARGB_8888
                )
                fullBitmap.copyPixelsFromBuffer(buffer)

                val cropped = if (rowPadding > 0) {
                    Bitmap.createBitmap(fullBitmap, 0, 0, captureWidth, captureHeight).also {
                        fullBitmap.recycle()
                    }
                } else {
                    fullBitmap
                }

                val out = ByteArrayOutputStream()
                cropped.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                cropped.recycle()
                ScreenCaptureBridge.emitFrame(out.toByteArray())
            } catch (e: Exception) {
                Log.w(TAG, "Error capturing screen frame: ${e.message}")
            } finally {
                image.close()
            }
        }, captureHandler)

        val display = projection.createVirtualDisplay(
            "JarvisScreenVision",
            captureWidth,
            captureHeight,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            captureHandler
        )

        mediaProjection = projection
        imageReader = reader
        virtualDisplay = display
        ScreenCaptureBridge.setActive(true)
    }

    private fun releaseProjection() {
        ScreenCaptureBridge.setActive(false)
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null
        try {
            imageReader?.close()
        } catch (_: Exception) {
        }
        imageReader = null
        try {
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
        } catch (_: Exception) {
        }
        mediaProjection = null
        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
    }

    override fun onDestroy() {
        releaseProjection()
        super.onDestroy()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.screen_capture_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.screen_capture_notif_title))
            .setContentText(getString(R.string.screen_capture_notif_body))
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
    }
}
