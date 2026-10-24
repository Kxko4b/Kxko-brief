package com.kxko.dailybriefing

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class StudyQuizItem(
    val id: String,
    val subject: String,
    val prompt: String,
    val options: List<String>,
    val correctIndex: Int,
    val explanation: String
)

/**
 * Reads existing exam questions using the user's own Supabase login.
 * Only the public publishable key is embedded; no service-role secret is used.
 */
class StudyQuizRepository(context: Context) {
    private val prefs = context.getSharedPreferences("study_quiz_auth", Context.MODE_PRIVATE)
    private val baseUrl = "https://ggewdpazpeoielbqrcgm.supabase.co"
    private val publishableKey = "sb_publishable_RZYBKLqjC9UYLNdqlOKZCA_IFX-5Gvm"

    fun signIn(email: String, password: String) {
        val body = JSONObject().put("email", email.trim()).put("password", password)
        val result = request("POST", "/auth/v1/token?grant_type=password", body, null)
        val session = result.optJSONObject(0) ?: throw IllegalStateException("Sign-in returned an invalid session.")
        val access = session.optString("access_token")
        val refresh = session.optString("refresh_token")
        if (access.isBlank() || refresh.isBlank()) throw IllegalStateException("Sign-in did not return a session.")
        prefs.edit().putString("access_token", access).putString("refresh_token", refresh).apply()
    }

    fun signOut() {
        prefs.edit().clear().apply()
    }

    fun loadUpcomingQuestions(): List<StudyQuizItem> {
        var token = prefs.getString("access_token", null)
            ?: throw IllegalStateException("Sign in to load your saved school tests.")
        var exams = try {
            loadExams(token)
        } catch (e: Exception) {
            if (e.message?.contains("401") == true) {
                token = refreshSession()
                loadExams(token)
            } else throw e
        }
        val withTopics = JSONArray()
        for (i in 0 until exams.length()) {
            val exam = exams.getJSONObject(i)
            if (exam.optString("topics").isNotBlank()) withTopics.put(exam)
        }
        exams = withTopics
        if (exams.length() == 0) throw IllegalStateException("No upcoming tests with saved topics were found.")
        val output = mutableListOf<StudyQuizItem>()
        for (i in 0 until exams.length()) {
            val exam = exams.getJSONObject(i)
            val examId = exam.getString("id")
            val subject = exam.optString("subject", "Schulfach")
            val rows = request("GET",
                "/rest/v1/exam_questions?select=id,kind,payload,right_count,wrong_count&exam_id=eq.${enc(examId)}&order=wrong_count.desc,last_seen.asc",
                null, token)
            val cards = mutableListOf<Pair<String, String>>()
            for (j in 0 until rows.length()) {
                val row = rows.getJSONObject(j)
                val p = row.optJSONObject("payload") ?: continue
                if (row.optString("kind") == "cover") {
                    val front = p.optString("front").trim()
                    val back = p.optString("back").trim()
                    if (front.isNotEmpty() && back.isNotEmpty()) cards.add(front to back)
                }
            }
            for (j in 0 until rows.length()) {
                val row = rows.getJSONObject(j)
                val p = row.optJSONObject("payload") ?: continue
                val kind = row.optString("kind")
                val id = row.optString("id")
                val wrongCount = row.optInt("wrong_count", 0)
                when (kind) {
                    "choice" -> {
                        val prompt = p.optString("question").trim()
                        val raw = p.optJSONArray("options") ?: continue
                        val answer = p.optInt("answer", -1)
                        if (prompt.isEmpty() || answer !in 0 until raw.length() || raw.length() < 3) continue
                        val options = (0 until raw.length()).map { raw.optString(it) }
                        val correct = options[answer]
                        val picked = mutableListOf(correct)
                        options.filter { it != correct }.shuffled().take(2).forEach { picked.add(it) }
                        if (picked.size < 3) continue
                        picked.shuffle()
                        output.add(StudyQuizItem(id, subject, prompt, picked, picked.indexOf(correct), p.optString("explain")))
                    }
                    "cover" -> {
                        val front = p.optString("front").trim()
                        val back = p.optString("back").trim()
                        if (front.isEmpty() || back.isEmpty()) continue
                        val distractors = cards.map { it.second }.filter { it != back }.distinct().shuffled().take(2)
                        if (distractors.size < 2) continue
                        val options = (distractors + back).shuffled()
                        output.add(StudyQuizItem(id, subject, front, options, options.indexOf(back), back))
                    }
                    "match" -> {
                        val pairs = p.optJSONArray("pairs") ?: continue
                        for (k in 0 until pairs.length()) {
                            val pair = pairs.optJSONObject(k) ?: continue
                            val term = pair.optString("term").trim()
                            val match = pair.optString("match").trim()
                            if (term.isEmpty() || match.isEmpty()) continue
                            val distractors = mutableListOf<String>()
                            for (m in 0 until pairs.length()) {
                                val other = pairs.optJSONObject(m)?.optString("match")?.trim().orEmpty()
                                if (other.isNotEmpty() && other != match) distractors.add(other)
                            }
                            val options = (distractors.distinct().shuffled().take(2) + match).shuffled()
                            if (options.size == 3) output.add(StudyQuizItem("$id-$k", subject, term, options, options.indexOf(match), "Richtig: $match"))
                        }
                    }
                }
            }
        }
        if (output.isEmpty()) throw IllegalStateException("The saved tests have no questions that can be used in spoken multiple choice.")
        return output.distinctBy { it.id }
    }

    private fun loadExams(token: String): JSONArray {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        return request("GET",
            "/rest/v1/exams?select=id,subject,exam_date,topics&exam_date=gte.${enc(today)}&topics=not.is.null&order=exam_date.asc",
            null, token)
    }

    private fun refreshSession(): String {
        val refresh = prefs.getString("refresh_token", null)
            ?: throw IllegalStateException("Your session expired. Please sign in again.")
        val result = request("POST", "/auth/v1/token?grant_type=refresh_token",
            JSONObject().put("refresh_token", refresh), null).optJSONObject(0)
            ?: throw IllegalStateException("Your session expired. Please sign in again.")
        val access = result.optString("access_token")
        val nextRefresh = result.optString("refresh_token", refresh)
        if (access.isBlank()) throw IllegalStateException("Your session expired. Please sign in again.")
        prefs.edit().putString("access_token", access).putString("refresh_token", nextRefresh).apply()
        return access
    }

    private fun request(method: String, path: String, body: JSONObject?, token: String?): JSONArray {
        val conn = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("apikey", publishableKey)
            setRequestProperty("Authorization", "Bearer ${token ?: publishableKey}")
            setRequestProperty("Content-Type", "application/json")
            if (body != null) doOutput = true
        }
        try {
            if (body != null) conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val message = try { JSONObject(text).optString("message").ifBlank { JSONObject(text).optString("msg") } } catch (_: Exception) { text.take(180) }
                throw IllegalStateException("Supabase HTTP $code: ${message.ifBlank { "Request failed" }}")
            }
            val trimmed = text.trim()
            return when {
                trimmed.startsWith("[") -> JSONArray(trimmed)
                trimmed.startsWith("{") -> JSONArray().put(JSONObject(trimmed))
                else -> JSONArray()
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}
