package com.aiphoneagent

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ActionLog {
    private val entries = mutableListOf<String>()

    @Synchronized
    fun add(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        entries.add("$time  $message")
        if (entries.size > 100) entries.removeAt(0)
    }

    @Synchronized
    fun all(): String = if (entries.isEmpty()) "Action log\n" else "Action log\n" + entries.joinToString("\n")
}
