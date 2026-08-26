package com.aiphoneagent

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class LottoSnapshot(
    val game: String,
    val gameName: String,
    val drawDate: String,
    val numbers: List<Int>,
    val special: Int,
    val jackpot: String?,
    val cashValue: String?,
    val nextDrawDate: String?,
    val nextDrawTime: String?,
    val prediction: String?
) {
    val drawKey: String get() = "$drawDate|${numbers.joinToString("-")}|$special"
}

object LottoMindClient {
    private fun getJson(url: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/json")
        connection.useCaches = false
        return try {
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    fun snapshot(baseUrl: String, game: String): LottoSnapshot {
        val base = baseUrl.trimEnd('/')
        val data = getJson("$base/api/data/$game?t=${System.currentTimeMillis()}")
        val schedule = getJson("$base/api/schedule/$game?t=${System.currentTimeMillis()}")
        val auto = try { getJson("$base/api/auto/$game?t=${System.currentTimeMillis()}") } catch (_: Exception) { JSONObject() }

        val draws = data.optJSONArray("draws") ?: throw IllegalStateException("Sin sorteos")
        if (draws.length() == 0) throw IllegalStateException("Sin sorteos")
        val latest = draws.getJSONObject(0)
        val numsArray = latest.getJSONArray("numbers")
        val nums = (0 until numsArray.length()).map { numsArray.getInt(it) }

        val next = schedule.optJSONObject("nextDraw")
        val combinations = auto.optJSONArray("combinations")
        var prediction: String? = null
        if (combinations != null && combinations.length() > 0) {
            val p = combinations.getJSONObject(0)
            val pn = p.optJSONArray("numbers")
            if (pn != null) {
                val main = (0 until pn.length()).joinToString(" ") { pn.getInt(it).toString().padStart(2, '0') }
                prediction = "$main + ${p.optInt("special")}" 
            }
        }

        return LottoSnapshot(
            game = game,
            gameName = if (game == "powerball") "Powerball" else "Mega Millions",
            drawDate = latest.optString("drawDate"),
            numbers = nums,
            special = latest.optInt("special"),
            jackpot = data.optString("currentJackpot").takeIf { it.isNotBlank() && it != "null" },
            cashValue = data.optString("currentCashValue").takeIf { it.isNotBlank() && it != "null" },
            nextDrawDate = next?.optString("date")?.takeIf { it.isNotBlank() },
            nextDrawTime = next?.optString("displayTime")?.takeIf { it.isNotBlank() },
            prediction = prediction
        )
    }
}
