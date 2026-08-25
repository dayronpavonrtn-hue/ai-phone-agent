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
        val trimmed = command.trim()
        val normalized = trimmed.lowercase()
        if (normalized.isBlank()) return "Enter a command."

        return when {
            normalized == "open facebook" || normalized.contains("open facebook") -> {
                openPackage(context, "com.facebook.katana", "Facebook")
            }
            normalized.contains("open sms") || normalized.contains("open messages") -> {
                openSms(context)
            }
            isSmsCommand(normalized) -> {
                executeSms(context, trimmed)
            }
            normalized.startsWith("click ") -> {
                val text = trimmed.substringAfter("click ", "").trim()
                val clicked = AgentAccessibilityService.instance?.findTextAndClick(text) == true
                if (clicked) "Clicked $text" else "Could not find $text on the active screen."
            }
            normalized == "stop" || normalized == "stop everything" -> {
                stop()
                "Agent stopped."
            }
            else -> {
                ActionLog.add("Unsupported command: $command")
                "Unsupported command. Try: Open Facebook, Open SMS, SMS to <number>: <message>, or Click <text>."
            }
        }
    }

    private fun isSmsCommand(normalized: String): Boolean {
        return normalized.startsWith("sms to ") || normalized.startsWith("send sms to ")
    }

    private fun executeSms(context: Context, command: String): String {
        val payload = command
            .replace(Regex("^(?i)send\\s+sms\\s+to\\s+"), "")
            .replace(Regex("^(?i)sms\\s+to\\s+"), "")
            .trim()

        val match = Regex("^([+0-9() .-]{7,25})\\s*[:,-]?\\s+(.+)$", RegexOption.DOT_MATCHES_ALL)
            .find(payload)
            ?: return "Use: SMS to 6155551234: your message"

        val rawNumber = match.groupValues[1].trim()
        val message = match.groupValues[2].trim()
        val destination = normalizePhoneNumber(rawNumber)

        if (destination.length < 7) {
            return "The phone number does not look valid."
        }
        if (message.isBlank()) {
            return "The SMS message cannot be empty."
        }

        val result = SmsSender.send(context, destination, message)
        return if (result.isSuccess) {
            ActionLog.add("SMS command completed for $destination")
            "SMS sent to $destination."
        } else {
            val reason = result.exceptionOrNull()?.message ?: "Unknown SMS error"
            ActionLog.add("SMS command failed for $destination: $reason")
            "SMS was not sent: $reason"
        }
    }

    private fun normalizePhoneNumber(value: String): String {
        val trimmed = value.trim()
        val keepPlus = trimmed.startsWith("+")
        val digits = trimmed.filter { it.isDigit() }
        return if (keepPlus) "+$digits" else digits
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
