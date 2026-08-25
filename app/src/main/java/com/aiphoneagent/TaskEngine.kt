package com.aiphoneagent

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

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
        if (normalized.isBlank()) return "Escribe una orden."

        return when {
            normalized.contains("abrir facebook") || normalized.contains("open facebook") -> {
                openPackage(context, "com.facebook.katana", "Facebook")
            }
            normalized.contains("abrir mensajes") || normalized.contains("abrir sms") ||
                normalized.contains("open sms") || normalized.contains("open messages") -> {
                openSms(context)
            }
            isDirectSmsCommand(normalized) -> executeDirectSms(context, trimmed)
            isNaturalSmsCommand(normalized) -> executeNaturalSms(context, trimmed)
            normalized.startsWith("click ") || normalized.startsWith("toca ") || normalized.startsWith("pulsa ") -> {
                val text = trimmed.substringAfter(" ").trim()
                val clicked = AgentAccessibilityService.instance?.findTextAndClick(text) == true
                if (clicked) "Toqué $text" else "No pude encontrar $text en la pantalla."
            }
            normalized == "stop" || normalized == "stop everything" || normalized == "detente" || normalized == "parar" -> {
                stop()
                "Agente detenido."
            }
            else -> {
                ActionLog.add("Unsupported command: $command")
                "No entendí la orden. Ejemplos: Abrir Facebook; SMS to 6155551234: hola; Mándale un mensaje a Juan diciendo que llego mañana."
            }
        }
    }

    private fun isDirectSmsCommand(normalized: String): Boolean {
        return normalized.startsWith("sms to ") || normalized.startsWith("send sms to ")
    }

    private fun isNaturalSmsCommand(normalized: String): Boolean {
        val starters = listOf(
            "mandale un mensaje a ", "mándale un mensaje a ",
            "manda un mensaje a ", "enviale un mensaje a ", "envíale un mensaje a ",
            "escribele a ", "escríbele a "
        )
        return starters.any { normalized.startsWith(it) }
    }

    private fun executeDirectSms(context: Context, command: String): String {
        val payload = command
            .replace(Regex("^(?i)send\\s+sms\\s+to\\s+"), "")
            .replace(Regex("^(?i)sms\\s+to\\s+"), "")
            .trim()

        val match = Regex("^([+0-9() .-]{7,25})\\s*[:,-]?\\s+(.+)$", RegexOption.DOT_MATCHES_ALL)
            .find(payload)
            ?: return "Usa: SMS to 6155551234: tu mensaje"

        return sendSms(context, match.groupValues[1].trim(), match.groupValues[2].trim())
    }

    private fun executeNaturalSms(context: Context, command: String): String {
        val regex = Regex(
            "^(?i)(?:m[aá]ndale un mensaje a|manda un mensaje a|env[ií]ale un mensaje a|escr[ií]bele a)\\s+(.+?)\\s+(?:diciendo(?:le)? que|que diga|y dile|dile que|:|,)?\\s*(.+)$",
            RegexOption.DOT_MATCHES_ALL
        )
        val match = regex.find(command.trim())
            ?: return "Prueba: Mándale un mensaje a Juan diciendo que llego mañana."

        val recipient = match.groupValues[1].trim()
        val message = match.groupValues[2].trim()
        if (message.isBlank()) return "Dime también qué mensaje quieres enviar."

        val number = if (recipient.any { it.isDigit() }) {
            normalizePhoneNumber(recipient)
        } else {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                return "Necesito permiso para leer tus contactos y encontrar a $recipient."
            }
            ContactResolver.findPhoneNumber(context, recipient)
                ?: return "No encontré un contacto llamado $recipient."
        }

        return sendSms(context, number, message)
    }

    private fun sendSms(context: Context, rawNumber: String, message: String): String {
        val destination = normalizePhoneNumber(rawNumber)
        if (destination.length < 7) return "El número no parece válido."
        if (message.isBlank()) return "El mensaje no puede estar vacío."

        val result = SmsSender.send(context, destination, message)
        return if (result.isSuccess) {
            ActionLog.add("SMS command completed for $destination")
            "SMS enviado a $destination."
        } else {
            val reason = result.exceptionOrNull()?.message ?: "Error desconocido"
            ActionLog.add("SMS command failed for $destination: $reason")
            "No se envió el SMS: $reason"
        }
    }

    private fun normalizePhoneNumber(value: String): String {
        val trimmed = value.trim()
        val keepPlus = trimmed.startsWith("+")
        val digits = trimmed.filter { it.isDigit() }
        return if (keepPlus) "+$digits" else digits
    }

    private fun openPackage(context: Context, packageName: String, label: String): String {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return "$label no está instalado o no se puede abrir."
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        ActionLog.add("Opened $label")
        return "$label abierto."
    }

    private fun openSms(context: Context): String {
        val packages = listOf("com.google.android.apps.messaging", "com.samsung.android.messaging")
        for (pkg in packages) {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                ActionLog.add("Opened SMS app")
                return "Mensajes abierto."
            }
        }
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_APP_MESSAGING)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            ActionLog.add("Opened messaging application")
            "Mensajes abierto."
        } catch (_: Exception) {
            "No pude abrir una aplicación de mensajes."
        }
    }
}
