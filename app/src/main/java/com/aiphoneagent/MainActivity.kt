package com.aiphoneagent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
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
    private lateinit var lottoStatusText: TextView
    private lateinit var lottoUrlInput: EditText

    companion object {
        private const val PERMISSION_REQUEST = 1001
    }

    private fun id(name: String): Int = resources.getIdentifier(name, "id", packageName)
    private fun layout(name: String): Int = resources.getIdentifier(name, "layout", packageName)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(layout("activity_main"))

        statusText = findViewById(id("statusText"))
        logText = findViewById(id("logText"))
        commandInput = findViewById(id("commandInput"))
        lottoStatusText = findViewById(id("lottoStatusText"))
        lottoUrlInput = findViewById(id("lottoUrlInput"))
        lottoUrlInput.setText(LottoMindConfig.baseUrl(this))

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

        findViewById<Button>(id("lottoStartButton")).setOnClickListener {
            val url = lottoUrlInput.text.toString().trim()
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                Toast.makeText(this, "Escribe una dirección como http://192.168.1.243:3000", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            LottoMindConfig.setBaseUrl(this, url)
            LottoMindConfig.setEnabled(this, true)
            val intent = Intent(this, LottoMindService::class.java)
            ContextCompat.startForegroundService(this, intent)
            Toast.makeText(this, "LottoMind Alerts activado", Toast.LENGTH_SHORT).show()
            refresh()
        }

        findViewById<Button>(id("lottoRefreshButton")).setOnClickListener {
            LottoMindConfig.setBaseUrl(this, lottoUrlInput.text.toString().trim())
            LottoMindConfig.setEnabled(this, true)
            val intent = Intent(this, LottoMindService::class.java).apply { action = LottoMindService.ACTION_REFRESH }
            ContextCompat.startForegroundService(this, intent)
            Toast.makeText(this, "Revisando LottoMind…", Toast.LENGTH_SHORT).show()
            refresh()
        }

        findViewById<Button>(id("lottoStopButton")).setOnClickListener {
            LottoMindConfig.setEnabled(this, false)
            stopService(Intent(this, LottoMindService::class.java))
            Toast.makeText(this, "LottoMind Alerts detenido", Toast.LENGTH_SHORT).show()
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
        val permissions = mutableListOf(
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_CONTACTS
        )
        if (Build.VERSION.SDK_INT >= 33) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
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
        lottoStatusText.text = if (LottoMindConfig.enabled(this)) {
            "Alerts: ON — ${LottoMindConfig.baseUrl(this)}"
        } else {
            "Alerts: OFF"
        }
        logText.text = ActionLog.all()
    }

    private fun isAccessibilityEnabled(): Boolean {
        val manager = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        return manager.isEnabled && AgentAccessibilityService.instance != null
    }
}
