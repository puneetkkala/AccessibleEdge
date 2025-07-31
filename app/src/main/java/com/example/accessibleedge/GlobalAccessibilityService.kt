package com.example.accessibleedge

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi

class GlobalAccessibilityService : AccessibilityService() {

    companion object {
        const val ACTION_GESTURE_DETECTED = "com.example.accessibleedge.GESTURE_DETECTED"
        const val EXTRA_GESTURE_NAME = "gesture_name"

        const val GESTURE_SCROLL_FORWARD = "SCROLL_FORWARD"
        const val GESTURE_BACK = "BACK"
        const val GESTURE_HOME = "HOME"

        const val EXTRA_TEXT_TO_SUMMARIZE = "text_to_summarize"
        const val ACTION_REQUEST_LLM_SUMMARY = "com.example.accessibleedge.REQUEST_LLM_SUMMARY"
        const val ACTION_LLM_SUMMARY_RESULT = "com.example.accessibleedge.LLM_SUMMARY_RESULT"
        const val EXTRA_SUMMARY_TEXT = "summary_text"
        const val EXTRA_IS_ERROR = "is_error"

        const val ACTION_SERVICE_FEEDBACK = "com.example.accessibleedge.SERVICE_FEEDBACK"
        const val EXTRA_FEEDBACK_MESSAGE = "feedback_message"
    }

    private lateinit var windowManager: WindowManager
    private lateinit var floatingButton: Button
    private lateinit var rootLayout: FrameLayout

    private var summaryPopUpView: View? = null
    private val popupHandler = Handler(Looper.getMainLooper())

    private var isSummarizationInProgress = false
    private var isLlmServiceReady = false

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_GESTURE_DETECTED -> {
                    val gestureName = intent.getStringExtra(EXTRA_GESTURE_NAME)
                    when (gestureName) {
                        "Closed_Fist" -> handleAccessibilityAction(GESTURE_SCROLL_FORWARD)
                        "Thumb_Up" -> handleAccessibilityAction(GESTURE_BACK)
                        "Open_Palm" -> handleAccessibilityAction(GESTURE_HOME)
                    }
                }
                ACTION_LLM_SUMMARY_RESULT -> {
                    val summaryText = intent.getStringExtra(EXTRA_SUMMARY_TEXT)
                    isSummarizationInProgress = false
                    floatingButton.isEnabled = true
                    displaySummaryPopup(summaryText ?: "Summary not available.")
                }
                ACTION_SERVICE_FEEDBACK -> {
                    val feedbackMessage = intent.getStringExtra(EXTRA_FEEDBACK_MESSAGE)
                    feedbackMessage?.let { displayFeedbackToast(it) }
                }
                LLMProcessingService.ACTION_LLM_SERVICE_READY -> {
                    val isReady = intent.getBooleanExtra(LLMProcessingService.EXTRA_LLM_READY_STATUS, false)
                    isLlmServiceReady = isReady
                    floatingButton.isEnabled = isReady
                    if (isReady) {
                        displayFeedbackToast("LLM service is ready.")
                        isSummarizationInProgress = false
                    } else {
                        displayFeedbackToast("LLM service reported an error.")
                        isSummarizationInProgress = false
                    }
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    }

    override fun onInterrupt() {
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        setupFloatingButton()

        startService(Intent(this, LLMProcessingService::class.java))

        val intentFilter = IntentFilter().apply {
            addAction(ACTION_GESTURE_DETECTED)
            addAction(ACTION_LLM_SUMMARY_RESULT)
            addAction(ACTION_SERVICE_FEEDBACK)
        }

        registerReceiver(commandReceiver, intentFilter, RECEIVER_EXPORTED)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        unregisterReceiver(commandReceiver)
        windowManager.removeView(rootLayout)
        stopService(Intent(this, LLMProcessingService::class.java))
        dismissSummaryPopup()
        return super.onUnbind(intent)
    }

    private fun setupFloatingButton() {
        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 100
        }

        rootLayout = FrameLayout(this)
        val inflater = LayoutInflater.from(this)
        inflater.inflate(R.layout.floating_button_layout, rootLayout)
        floatingButton = rootLayout.findViewById(R.id.summaryButton)
        floatingButton.setOnClickListener {
            if (!isSummarizationInProgress) {
                isSummarizationInProgress = true
                floatingButton.isEnabled = false
                displayFeedbackToast("Getting text from screen, please wait...")
                collectScreenTextAndSendToLlmService()
            } else {
                displayFeedbackToast("Summary already in progress.")
            }
        }
        windowManager.addView(rootLayout, layoutParams)
    }

    private fun handleAccessibilityAction(action: String) {
        when (action) {
            GESTURE_SCROLL_FORWARD -> findAndScroll(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            GESTURE_BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            GESTURE_HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    private fun collectScreenTextAndSendToLlmService() {
        val rootNode = rootInActiveWindow
        if (rootNode == null) {
            displayFeedbackToast("Cannot access screen content.")
            isSummarizationInProgress = false
            floatingButton.isEnabled = true
            return
        }

        val allText = StringBuilder()
        traverseNodesAndCollectText(rootNode, allText)
        rootNode.recycle()

        val textToSummarize = allText.toString().trim()
        if (textToSummarize.isBlank() || textToSummarize.length < 50) {
            displayFeedbackToast("Insufficient text to summarize.")
            isSummarizationInProgress = false
            floatingButton.isEnabled = true
            return
        }
        if (textToSummarize.length > 4000) {
            displayFeedbackToast("Text is too long to summarize. Summarizing a portion.")
            val portionToSummarize = textToSummarize.substring(0, 4000)
            sendTextToLlmService(portionToSummarize)
        } else {
            sendTextToLlmService(textToSummarize)
        }
    }

    private fun traverseNodesAndCollectText(node: AccessibilityNodeInfo, text: StringBuilder) {
        if (node.text != null && node.text.isNotBlank()) {
            text.append(node.text.toString().trim()).append(" ")
        }
        if (node.contentDescription != null && node.contentDescription.isNotBlank()) {
            text.append(node.contentDescription.toString().trim()).append(" ")
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                traverseNodesAndCollectText(child, text)
                child.recycle()
            }
        }
    }

    private fun sendTextToLlmService(text: String) {
        val intent = Intent(ACTION_REQUEST_LLM_SUMMARY).apply {
            putExtra(EXTRA_TEXT_TO_SUMMARIZE, text)
            setPackage(packageName)
        }
        applicationContext.sendBroadcast(intent)
    }

    private fun findAndScroll(scrollAction: Int): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        return findScrollableNodeAndPerformAction(rootNode, scrollAction)
    }

    private fun findScrollableNodeAndPerformAction(node: AccessibilityNodeInfo, scrollAction: Int): Boolean {
        if (node.isScrollable) {
            if (node.performAction(scrollAction)) {
                return true
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                if (findScrollableNodeAndPerformAction(child, scrollAction)) {
                    child.recycle()
                    return true
                }
                child.recycle()
            }
        }
        return false
    }

    private fun displayFeedbackToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun displaySummaryPopup(summary: String) {
        if (summaryPopUpView == null) {
            dismissSummaryPopup()
        }
        summaryPopUpView = LayoutInflater.from(this).inflate(R.layout.summary_popup_layout, null)
        val summaryTextView: TextView = summaryPopUpView!!.findViewById(R.id.summaryTextView)
        summaryTextView.text = summary

        val dismissButton: Button = summaryPopUpView!!.findViewById(R.id.dismissButton)
        dismissButton.setOnClickListener {
            dismissSummaryPopup()
        }

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            x = 0
            y = 0
        }

        try {
            windowManager.addView(summaryPopUpView, layoutParams)
        } catch (e: Exception) {
            displayFeedbackToast("Failed to display summary popup.")
        }
    }

    private fun dismissSummaryPopup() {
        if (summaryPopUpView != null) {
            try {
                windowManager.removeView(summaryPopUpView)
                summaryPopUpView = null
                popupHandler.removeCallbacksAndMessages(null)
            } catch (e: Exception) {
                displayFeedbackToast("Failed to dismiss summary popup.")
            }
        }
    }
}