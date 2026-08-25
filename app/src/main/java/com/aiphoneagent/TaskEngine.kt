package com.aiphoneagent

import android.content.Context
import android.content.Intent

object TaskEngine {
    @Volatile
    var stopped: Boolean = true
        private set

    fun stop() {
        stopped = true
        ActionLog.add("STOP EVERYTHING")
    }

    fun start() {
        stopped = false
        ActionLog.add("Agent started")
    }

    fun execute(context: Context, command: String): String {
        start()
        val normalized = command.trim().lowercase()
        if (normalized.isBlank()) return "Enter a command."

        return when {
            normalized == "open facebook" || normalized.contains("open facebook") -> {
                openPackage(context, "com.facebook.katana", "Facebook")
            }
            normalized.contains("open sms") || normalized.contains("open messages") -> {
                openSms(context)
            }
            normalized.startsWith("click ") -> {
                val text = command.substringAfter("click ").trim()
                val clicked = AgentAccessibilityService.instance?.findTextAndClick(text) == true
                if (clicked) "Clicked $text" else "Could not find $text on the active screen."
            }
            normalized == "stop" || normalized == "stop everything" -> {
                stop()
                "Agent stopped."
            }
            else -> {
                ActionLog.add("Unsupported command: $command")
                "I understand the command format, but this V1 does not support it yet. Try: Open Facebook, Open SMS, or Click <text>."
            }
        }
    }

    private fun openPackage(context: Context, packageName: String, label: String): String {
        val manager = context.packageManager
        val intent = manager.getLaunchIntentForPackage(packageName)
            ?: return "$label is not installed or cannot be opened."
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        ActionLog.add("Opened $label")
        return "$label opened."
    }

    private fun openSms(context: Context): String {
        val packages = listOf("com.google.android.apps.messaging", "com.samsung.android.messaging")
        for (pkg in packages) {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                ActionLog.add("Opened SMS app")
                return "SMS app opened."
            }
        }
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_APP_MESSAGING)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            ActionLog.add("Opened messaging application")
            "Messaging app opened."
        } catch (_: Exception) {
            "No messaging application could be opened."
        }
    }
}
