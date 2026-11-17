package com.chase.tidetoolvoicelog.ai

import com.chase.tidetoolvoicelog.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Optional. Runs only when the user taps Summarize with his own endpoint and key
 * filled in. Sends that one transcript and nothing else.
 */
class Summarizer(private val prefs: Prefs) {

    private val systemText = listOf(
        "You are helping a one-man handyman business in Hawaii turn a voice note from a job site into something he can act on.",
        "Summarize ONLY what is in the text. Never invent prices, dates or promises.",
        "Answer with plain text (no markdown symbols, no bullet characters other than \"-\").",
        "Use exactly these three headings, each on its own line:",
        "KEY POINTS",
        "PRICES & DATES",
        "ACTION ITEMS",
        "Under each heading use short \"- \" lines. If the text does not mention prices, dates or action items, write \"- not mentioned\".",
        "Keep the whole answer under 140 words."
    ).joinToString(" ")

    suspend fun summarize(jobName: String, whenText: String, transcript: String, notes: String): String =
        withContext(Dispatchers.IO) {
            if (!prefs.aiReady) throw IOException("AI summary is not set up. Add a base URL, key and model in Settings.")

            val base = prefs.aiBaseUrl.trimEnd('/')
            val url = if (base.endsWith("/chat/completions")) base else "$base/chat/completions"

            val user = buildString {
                append("Client / job: ").append(jobName).append('\n')
                append("When: ").append(whenText).append("\n\n")
                append("Transcript:\n").append(transcript)
                if (notes.isNotBlank()) append("\n\nNotes I typed myself:\n").append(notes)
            }

            val body = JSONObject().apply {
                put("model", prefs.aiModel)
                put("temperature", 0.2)
                put("max_tokens", 400)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply { put("role", "system"); put("content", systemText) })
                    put(JSONObject().apply { put("role", "user"); put("content", user) })
                })
            }

            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 20_000
                readTimeout = 60_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer " + prefs.aiKey)
            }

            try {
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() } ?: ""

                if (code !in 200..299) {
                    val detail = runCatching {
                        JSONObject(text).optJSONObject("error")?.optString("message")
                    }.getOrNull().orEmpty()
                    when (code) {
                        401, 403 -> throw IOException("The endpoint rejected the API key (HTTP $code).")
                        404 -> throw IOException("Endpoint not found (404). The base URL usually ends in /v1.")
                        else -> throw IOException("The endpoint returned HTTP $code" + if (detail.isNotEmpty()) " - $detail" else "")
                    }
                }

                val content = runCatching {
                    JSONObject(text).getJSONArray("choices").getJSONObject(0)
                        .getJSONObject("message").getString("content")
                }.getOrNull()
                if (content.isNullOrBlank()) throw IOException("The endpoint returned an empty summary.")
                content.trim()
            } catch (e: IOException) {
                throw e
            } catch (e: Exception) {
                throw IOException("Could not reach that endpoint. Check the base URL and your connection.")
            } finally {
                conn.disconnect()
            }
        }
}
