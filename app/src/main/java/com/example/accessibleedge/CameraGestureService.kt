package com.example.accessibleedge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer.GestureRecognizerOptions
import java.util.concurrent.Executors
import androidx.core.graphics.createBitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult

class CameraGestureService : LifecycleService() {

    private var gestureRecognizer: GestureRecognizer? = null
    private val backgroundExecutor = Executors.newSingleThreadExecutor()

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "gesture_service_channel"
        private const val NOTIFICATION_ID = 101
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification().build())
        startCameraAndRecognizer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        backgroundExecutor.shutdown()
        gestureRecognizer?.close()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Gesture Recognition Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): NotificationCompat.Builder {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Gesture Control Active")
            .setContentText("Listening for hand gestures in the background")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
    }

    private fun startCameraAndRecognizer() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also {
                    it.setAnalyzer(backgroundExecutor) {
                        processImageProxy(it)
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, imageAnalyzer)
                setupGestureRecognizer()
            } catch (_: Exception) {

            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun setupGestureRecognizer() {
        val optionsBuilder = GestureRecognizerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("gesture_recognizer.task").build())
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setResultListener { result, _ ->
                processGestureResult(result)
            }

        try {
            gestureRecognizer = GestureRecognizer.createFromOptions(this, optionsBuilder.build())
        } catch (e: Exception) {
            stopSelf()
        }
    }

    @OptIn(ExperimentalGetImage::class)
    private fun processImageProxy(imageProxy: ImageProxy) {
        imageProxy.image?.let {
            val frameTime = SystemClock.uptimeMillis()
            val bitmapBuffer = createBitmap(imageProxy.width, imageProxy.height)
            imageProxy.use { bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer) }
            imageProxy.close()

            val matrix = Matrix().apply {
                postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
                postScale(-1f, 1f, imageProxy.width.toFloat(), imageProxy.height.toFloat())
            }

            val rotatedBitmap = Bitmap.createBitmap(
                bitmapBuffer,
                0,
                0,
                bitmapBuffer.width,
                bitmapBuffer.height,
                matrix,
                true
            )

            val mpImage = BitmapImageBuilder(rotatedBitmap).build()
            gestureRecognizer?.recognizeAsync(mpImage, frameTime)
            imageProxy.close()
        }
    }

    private fun processGestureResult(result: GestureRecognizerResult) {
        if (result.gestures().isNotEmpty()) {
            result.gestures()[0][0].categoryName()?.let { gestureName ->
                val intent = Intent(GlobalAccessibilityService.ACTION_GESTURE_DETECTED).apply {
                    putExtra(GlobalAccessibilityService.EXTRA_GESTURE_NAME, gestureName)
                    setPackage(packageName)
                }
                applicationContext.sendBroadcast(intent)
            }
        }
    }
}