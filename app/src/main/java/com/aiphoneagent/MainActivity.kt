package com.aiphoneagent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var commandInput: EditText

    companion object {
        private const val SMS_PERMISSION_REQUEST = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        logText = findViewById(R.id.logText)
        commandInput = findViewById(R.id.commandInput)

        findViewById<Button>(R.id.accessibilityButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.runButton).setOnClickListener {
            val command = commandInput.text.toString()
            val result = TaskEngine.execute(this, command)
            Toast.makeText(this, result, Toast.LENGTH_SHORT).show()
            refresh()
        }

        findViewById<Button>(R.id.stopButton).setOnClickListener {
            TaskEngine.stop()
            refresh()
        }

        requestSmsPermissionsIfNeeded()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun requestSmsPermissionsIfNeeded() {
        val permissions = arrayOf(
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.RECEIVE_SMS
        )
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), SMS_PERMISSION_REQUEST)
        }
    }

    private fun refresh() {
        statusText.text = if (isAccessibilityEnabled()) {
            "Status: READY — Accessibility enabled"
        } else {
            "Status: PAUSED — Enable phone control"
        }
        logText.text = ActionLog.all()
    }

    private fun isAccessibilityEnabled(): Boolean {
        val manager = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        return manager.isEnabled && AgentAccessibilityService.instance != null
    }
}
