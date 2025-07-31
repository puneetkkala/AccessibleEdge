package com.example.accessibleedge

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.ActivityManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusTextView: TextView
    private lateinit var startServiceButton: Button
    private lateinit var stopServiceButton: Button
    private lateinit var enableAccessibilityButton: Button

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val cameraGranted = it[Manifest.permission.CAMERA] == true
        if (!cameraGranted) {
            Toast
                .makeText(this, getString(R.string.permission_denied), Toast.LENGTH_LONG)
                .show()
        }
        checkPermissionsAndAccessibility()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast
                .makeText(this, getString(R.string.overlay_permission), Toast.LENGTH_LONG)
                .show()
            val intent =
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri())
            startActivity(intent)
        }

        statusTextView = findViewById(R.id.statusTextView)
        startServiceButton = findViewById(R.id.startServiceButton)
        stopServiceButton = findViewById(R.id.stopServiceButton)
        enableAccessibilityButton = findViewById(R.id.enableAccessibilityButton)

        startServiceButton.setOnClickListener {
            startGestureTrackingService()
        }

        stopServiceButton.setOnClickListener {
            stopGestureTrackingService()
        }
        enableAccessibilityButton.setOnClickListener {
            openAccessibilitySettings()
        }
        checkPermissionsAndAccessibility()
    }

    override fun onResume() {
        super.onResume()
        checkPermissionsAndAccessibility()
    }

    private fun checkPermissionsAndAccessibility() {
        val cameraGranted = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val accessibilityEnabled = isAccessibilityServiceEnabled(this, GlobalAccessibilityService::class.java)

        if (!cameraGranted) {
            statusTextView.text = getString(R.string.camera_permission_required)
            startServiceButton.isEnabled = false
            requestPermissionsLauncher.launch(arrayOf(Manifest.permission.CAMERA))
        } else if (!accessibilityEnabled) {
            statusTextView.text = getString(R.string.accessibility_service_not_enabled)
            startServiceButton.isEnabled = true
            enableAccessibilityButton.isEnabled = true
        } else {
            statusTextView.text = getString(R.string.ready)
            startServiceButton.isEnabled = true
            enableAccessibilityButton.isEnabled = false
        }

        stopServiceButton.isEnabled = isServiceRunning(this, CameraGestureService::class.java)
    }

    private fun startGestureTrackingService() {
        val cameraGranted = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

        if (!cameraGranted) {
            Toast
                .makeText(this, getString(R.string.camera_permission_required), Toast.LENGTH_LONG)
                .show()
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
            return
        }

        if (!isAccessibilityServiceEnabled(this, GlobalAccessibilityService::class.java)) {
            Toast
                .makeText(this, getString(R.string.accessibility_service_not_enabled), Toast.LENGTH_LONG)
                .show()
            return
        }
        val serviceIntent = Intent(this, CameraGestureService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        statusTextView.text = getString(R.string.gesture_service_started)
        startServiceButton.isEnabled = false
        stopServiceButton.isEnabled = true
    }

    private fun stopGestureTrackingService() {
        val serviceIntent = Intent(this, CameraGestureService::class.java)
        stopService(serviceIntent)
        statusTextView.text = getString(R.string.gesture_service_stopped)
        startServiceButton.isEnabled = true
        stopServiceButton.isEnabled = false
    }

    private fun isAccessibilityServiceEnabled(context: AppCompatActivity, serviceClass: Class<out AccessibilityService>): Boolean {
        val accessibilityManager = context.getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = accessibilityManager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
        for (enabledService in enabledServices) {
            if (enabledService.id == "com.example.accessibleedge/.GlobalAccessibilityService") {
                return true
            }
        }
        return false
    }

    private fun isServiceRunning(context: Context, serviceClass: Class<out Service>): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) return true
        }
        return false
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
    }
}