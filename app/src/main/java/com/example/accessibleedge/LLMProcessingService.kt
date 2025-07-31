package com.example.accessibleedge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import java.util.concurrent.Executors

class LLMProcessingService : LifecycleService() {

    private val backgroundExecutor = Executors.newSingleThreadExecutor()

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "llm_service_channel"
        private const val NOTIFICATION_ID = 102
        private const val LLM_MODEL_FILE_NAME = "gemma3-1b-it-int4.task"

        const val ACTION_LLM_SERVICE_READY = "com.example.accessibleedge.LLM_SERVICE_READY"
        const val EXTRA_LLM_READY_STATUS = "llm_ready_status"
    }

    private var isProcessingRequest = false
    private lateinit var llmModelPath: String
    private var llmInference: LlmInference? = null

    private val llmRequestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == GlobalAccessibilityService.ACTION_REQUEST_LLM_SUMMARY) {
                val textToSummarize = intent?.getStringExtra(GlobalAccessibilityService.EXTRA_TEXT_TO_SUMMARIZE)
                if (!textToSummarize.isNullOrBlank()) {
                    if (isProcessingRequest) {
                        sendSummaryResultToAccessibilityService("I'm already summarizing, please wait...", isError = false)
                    }
                    isProcessingRequest = true
                    processTextWithLlm("summarize", textToSummarize)
                } else {
                    sendSummaryResultToAccessibilityService("No text to summarize", isError = true)
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification().build())
        llmModelPath = "${getExternalFilesDir(null)?.absolutePath}/$LLM_MODEL_FILE_NAME}"
        setupLlmInference()
        val llmRequestFilter = IntentFilter(GlobalAccessibilityService.ACTION_REQUEST_LLM_SUMMARY)
        registerReceiver(llmRequestReceiver, llmRequestFilter, RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        backgroundExecutor.shutdown()
        unregisterReceiver(llmRequestReceiver)
        llmInference?.close()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "LLM Summarizing Service Channel",
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
            .setContentTitle("LLM Processing Active")
            .setContentText("Processing text summarization in the background")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
    }

    private fun setupLlmInference() {
        try {
            if (llmInference != null) {
                sendLlmServiceReadyBroadcast(true)
                return
            }
            val options = LlmInferenceOptions.builder()
                .setModelPath(llmModelPath)
                .setMaxTokens(512)
                .setMaxTopK(40)
                .build()

            llmInference = LlmInference.createFromOptions(this, options)
            sendLlmServiceReadyBroadcast(true)
        } catch (e: Exception) {
            sendSummaryResultToAccessibilityService("LLM Model not loaded. Please ensure it's placed correctly.", isError = true)
            sendLlmServiceReadyBroadcast(false)
        }
    }

    private fun processTextWithLlm(commandType: String, textToProcess: String) {
        if (llmInference == null) {
            sendSummaryResultToAccessibilityService("LLM is not ready, cannot summarize.", isError = true)
            isProcessingRequest = false
            return
        }

        if (textToProcess.isBlank() || textToProcess.length < 50) {
            sendSummaryResultToAccessibilityService("Text to summarize is too short.", isError = true)
            isProcessingRequest = false
            return
        }

        if (textToProcess.length > 4000) {
            sendSummaryResultToAccessibilityService("Text to summarize is too long. Summarizing a portion.", isError = true)
            val truncatedText = textToProcess.substring(0, 4000)
            processLlmPrompt(commandType, truncatedText)
        } else {
            processLlmPrompt(commandType, textToProcess)
        }
    }

    private fun processLlmPrompt(commandType: String, textToProcess: String) {
        val prompt = when (commandType) {
            "summarize" -> "Summarize the following text concisely, focusing on key information: \n$textToProcess"
            else -> textToProcess
        }
        sendSummaryResultToAccessibilityService("Summarizing...", isError = false)

        backgroundExecutor.execute {
            try {
                val result = llmInference!!.generateResponse(prompt)
                val finalMessage = result.ifBlank { "Could not generate a summary." }
                sendSummaryResultToAccessibilityService(finalMessage, isError = false)
                isProcessingRequest = false
            } catch (e: Exception) {
                sendSummaryResultToAccessibilityService("Failed to summarize text", isError = true)
                isProcessingRequest = false
            }
        }
    }

    private fun sendSummaryResultToAccessibilityService(summary: String, isError: Boolean) {
        val intent = Intent(GlobalAccessibilityService.ACTION_LLM_SUMMARY_RESULT).apply {
            putExtra(GlobalAccessibilityService.EXTRA_SUMMARY_TEXT, summary)
            putExtra(GlobalAccessibilityService.EXTRA_IS_ERROR, isError)
            setPackage(packageName)
        }
        applicationContext.sendBroadcast(intent)
    }

    private fun sendLlmServiceReadyBroadcast(isReady: Boolean) {
        val intent = Intent(ACTION_LLM_SERVICE_READY).apply {
            putExtra(EXTRA_LLM_READY_STATUS, isReady)
            setPackage(packageName)
        }
        applicationContext.sendBroadcast(intent)
    }
}