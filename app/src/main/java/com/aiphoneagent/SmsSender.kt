package com.aiphoneagent

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SmsManager
import androidx.core.content.ContextCompat

object SmsSender {
    fun send(context: Context, destination: String, message: String): Result<Unit> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            return Result.failure(SecurityException("SEND_SMS permission is not granted."))
        }
        if (destination.isBlank() || message.isBlank()) {
            return Result.failure(IllegalArgumentException("Destination and message are required."))
        }
        return try {
            val manager = SmsManager.getDefault()
            val parts = manager.divideMessage(message)
            if (parts.size == 1) {
                manager.sendTextMessage(destination, null, message, null, null)
            } else {
                manager.sendMultipartTextMessage(destination, null, parts, null, null)
            }
            ActionLog.add("SMS sent to $destination")
            Result.success(Unit)
        } catch (e: Exception) {
            ActionLog.add("SMS failed: ${e.message}")
            Result.failure(e)
        }
    }
}
