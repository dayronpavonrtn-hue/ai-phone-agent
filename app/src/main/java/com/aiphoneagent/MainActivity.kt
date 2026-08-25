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
        private const val PERMISSION_REQUEST = 1001
    }

    private fun id(name: String): Int =
        resources.getIdentifier(name, "id", packageName)

    private fun layout(name: String): Int =
        resources.getIdentifier(name, "layout", packageName)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(layout("activity_main"))

        statusText = findViewById(id("statusText"))
        logText = findViewById(id("logText"))
        commandInput = findViewById(id("commandInput"))

        findViewById<Button>(id("accessibilityButton")).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(id("runButton")).setOnClickListener {
            val command = commandInput.text.toString()
            if (command.isBlank()) {
                Toast.makeText(this, "Escribe una orden primero", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val result = TaskEngine.execute(this, command)
            Toast.makeText(this, result, Toast.LENGTH_LONG).show()
            refresh()
        }

        findViewById<Button>(id("stopButton")).setOnClickListener {
            TaskEngine.stop()
            refresh()
        }

        requestPermissionsIfNeeded()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (::statusText.isInitialized) refresh()
    }

    private fun requestPermissionsIfNeeded() {
        val permissions = arrayOf(
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_CONTACTS
        )
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERMISSION_REQUEST)
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
