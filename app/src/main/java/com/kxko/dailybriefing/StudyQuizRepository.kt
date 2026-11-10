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
    val explanation: String,
    /** The exam_questions row this item came from (null for built-in demo questions). */
    val sourceId: String? = null
)

data class ExamProgress(
    val id: String,
    val subject: String,
    val examDate: String,
    val hasTopics: Boolean,
    val total: Int,
    val mastered: Int,
    val shaky: Int,
    val fresh: Int
)

data class WeakSpot(val subject: String, val text: String, val wrong: Int, val right: Int)

data class StudyProgress(val exams: List<ExamProgress>, val weakSpots: List<WeakSpot>)

/**
 * Reads existing exam questions using the user's own Supabase login.
 * Only the public publishable key is embedded; no service-role secret is used.
 */
class StudyQuizRepository(context: Context) {
    private val session = SecureSessionStore(context)
    private val baseUrl = "https://ggewdpazpeoielbqrcgm.supabase.co"
    private val publishableKey = "sb_publishable_RZYBKLqjC9UYLNdqlOKZCA_IFX-5Gvm"

    fun signIn(email: String, password: String) {
        val body = JSONObject().put("email", email.trim()).put("password", password)
        val result = request("POST", "/auth/v1/token?grant_type=password", body, null)
        val sessionResponse = result.optJSONObject(0) ?: throw IllegalStateException("Sign-in returned an invalid session.")
        val access = sessionResponse.optString("access_token")
        val refresh = sessionResponse.optString("refresh_token")
        if (access.isBlank() || refresh.isBlank()) throw IllegalStateException("Sign-in did not return a session.")
        session.save(access, refresh)
    }

    fun signOut() {
        session.clear()
    }

    fun hasSession(): Boolean = !session.accessToken().isNullOrBlank()

    /** Runs [block] with the stored token and retries once with a refreshed token when it has expired. */
    private fun <T> authed(block: (String) -> T): T {
        val token = session.accessToken()
            ?: throw IllegalStateException("Sign in to see your progress.")
        return try {
            block(token)
        } catch (e: IllegalStateException) {
            if (e.message?.contains("HTTP 401") == true) block(refreshSession()) else throw e
        }
    }

    /** Upcoming exams with how well their questions are known, plus the questions missed most. */
    fun loadProgress(): StudyProgress = authed { token ->
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val exams = request("GET",
            "/rest/v1/exams?select=id,subject,exam_date,topics&exam_date=gte.${enc(today)}&order=exam_date.asc",
            null, token)
        if (exams.length() == 0) return@authed StudyProgress(emptyList(), emptyList())
        val ids = (0 until exams.length()).joinToString(",") { exams.getJSONObject(it).getString("id") }
        val rows = request("GET",
            "/rest/v1/exam_questions?select=id,exam_id,kind,payload,right_count,wrong_count&exam_id=in.($ids)",
            null, token)
        val subjectById = HashMap<String, String>()
        for (i in 0 until exams.length()) {
            val e = exams.getJSONObject(i)
            subjectById[e.getString("id")] = e.optString("subject", "Test")
        }
        val counts = HashMap<String, IntArray>() // exam id -> [total, mastered, shaky, fresh]
        val weak = mutableListOf<WeakSpot>()
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val examId = row.optString("exam_id")
            val right = row.optInt("right_count", 0)
            val wrong = row.optInt("wrong_count", 0)
            val c = counts.getOrPut(examId) { IntArray(4) }
            c[0] += 1
            when (StudyStatsLogic.mastery(right, wrong)) {
                Mastery.MASTERED -> c[1] += 1
                Mastery.SHAKY -> c[2] += 1
                Mastery.FRESH -> c[3] += 1
            }
            if (wrong > right) {
                val text = weakText(row.optString("kind"), row.optJSONObject("payload"))
                if (text.isNotEmpty()) weak.add(WeakSpot(subjectById[examId] ?: "Test", text, wrong, right))
            }
        }
        val progress = (0 until exams.length()).map { i ->
            val e = exams.getJSONObject(i)
            val c = counts[e.getString("id")] ?: IntArray(4)
            ExamProgress(
                id = e.getString("id"),
                subject = e.optString("subject", "Test"),
                examDate = e.optString("exam_date").take(10),
                hasTopics = e.optString("topics").isNotBlank() && e.optString("topics") != "null",
                total = c[0], mastered = c[1], shaky = c[2], fresh = c[3]
            )
        }
        StudyProgress(progress, weak.sortedByDescending { it.wrong - it.right }.take(5))
    }

    private fun weakText(kind: String, p: JSONObject?): String {
        if (p == null) return ""
        val text = when (kind) {
            "choice" -> p.optString("question")
            "cover" -> p.optString("front")
            "match" -> {
                val pairs = p.optJSONArray("pairs")
                if (pairs == null) "" else (0 until minOf(pairs.length(), 3))
                    .map { pairs.optJSONObject(it)?.optString("term").orEmpty() }
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
            }
            else -> ""
        }
        return text.trim()
    }

    /** Saves one right/wrong answer on the question (best effort: a failure never interrupts the quiz). */
    fun reportAnswer(questionId: String, correct: Boolean) {
        try {
            authed { token ->
                request("POST", "/rest/v1/rpc/record_exam_answer",
                    JSONObject().put("q_id", questionId).put("was_right", correct), token)
            }
        } catch (_: Exception) {
        }
    }

    fun loadUpcomingQuestions(): List<StudyQuizItem> {
        var token = session.accessToken()
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
            val rowsPath = "/rest/v1/exam_questions?select=id,kind,payload,right_count,wrong_count&exam_id=eq.${enc(examId)}&order=wrong_count.desc,last_seen.asc"
            val rows = try {
                requestWithRetry("GET", rowsPath, null, token)
            } catch (e: Exception) {
                if (e.message?.contains("HTTP 401") == true) {
                    token = refreshSession()
                    requestWithRetry("GET", rowsPath, null, token)
                } else throw e
            }
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
                        output.add(StudyQuizItem(id, subject, prompt, picked, picked.indexOf(correct), p.optString("explain").ifBlank { "Die richtige Antwort ist $correct. Vergleiche den Begriff mit den anderen Antwortmöglichkeiten." }, id))
                    }
                    "cover" -> {
                        val front = p.optString("front").trim()
                        val back = p.optString("back").trim()
                        if (front.isEmpty() || back.isEmpty()) continue
                        val distractors = cards.map { it.second }.filter { it != back }.distinct().shuffled().take(2)
                        if (distractors.size < 2) continue
                        val options = (distractors + back).shuffled()
                        output.add(StudyQuizItem(id, subject, front, options, options.indexOf(back), p.optString("explain").ifBlank { "Die Karte fragt nach „$front“. Die gespeicherte Antwort lautet „$back“." }, id))
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
                            if (options.size == 3) output.add(StudyQuizItem("$id-$k", subject, term, options, options.indexOf(match), pair.optString("explain").ifBlank { "„$term“ gehört zu „$match“. Diese Zuordnung verbindet den Begriff mit seiner passenden Erklärung." }, id))
                        }
                    }
                }
            }
        }
        if (output.isEmpty()) throw IllegalStateException("The saved tests have no questions that can be used in spoken multiple choice.")
        return output.distinctBy { it.id }
    }

    /** Requests ElevenLabs speech. Throws a readable error instead of silently returning null. */
    fun synthesize(text: String, languageTag: String): ByteArray {
        var token = session.accessToken()
            ?: throw IllegalStateException("No saved login. Sign in again to use ElevenLabs.")
        for (attempt in 0..1) {
            val conn = (URL("$baseUrl/functions/v1/quiz-voice").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 20000
                readTimeout = 60000
                doOutput = true
                setRequestProperty("apikey", publishableKey)
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", "application/json")
            }
            try {
                val body = JSONObject().put("text", text).put("lang", languageTag)
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                if (code == 200) {
                    val bytes = conn.inputStream.use { it.readBytes() }
                    if (bytes.size < 1000 || bytes[0] != 0x52.toByte() || bytes[1] != 0x49.toByte() ||
                        bytes[2] != 0x46.toByte() || bytes[3] != 0x46.toByte()) {
                        throw IllegalStateException("ElevenLabs returned an invalid or empty WAV audio file.")
                    }
                    return bytes
                }
                val errorBody = (conn.errorStream ?: conn.inputStream).bufferedReader().use { it.readText() }
                val detail = try {
                    val json = JSONObject(errorBody)
                    json.optString("reason").ifBlank { json.optString("error").ifBlank { errorBody.take(220) } }
                } catch (_: Exception) { errorBody.take(220) }
                if (code == 401 && attempt == 0) {
                    token = refreshSession()
                    continue
                }
                throw IllegalStateException("Voice server HTTP $code: ${detail.ifBlank { "No details returned" }}")
            } catch (error: Exception) {
                if (error is IllegalStateException) throw error
                throw IllegalStateException("Could not contact ElevenLabs voice server: ${error.message ?: error.javaClass.simpleName}")
            } finally {
                conn.disconnect()
            }
        }
        throw IllegalStateException("Your sign-in expired. Sign in again.")
    }

    private fun loadExams(token: String): JSONArray {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        return requestWithRetry("GET",
            "/rest/v1/exams?select=id,subject,exam_date,topics&exam_date=gte.${enc(today)}&topics=not.is.null&order=exam_date.asc",
            null, token)
    }

    /**
     * A first request after opening the app can race a temporary connection or Supabase
     * cold-start response. Retry only transient failures; auth errors are handled by the
     * caller so we never hide a genuinely expired or invalid login.
     */
    private fun requestWithRetry(method: String, path: String, body: JSONObject?, token: String?): JSONArray {
        var lastError: Exception? = null
        for (attempt in 0..2) {
            try {
                return request(method, path, body, token)
            } catch (error: Exception) {
                lastError = error
                val message = error.message.orEmpty()
                val transientHttp = listOf("HTTP 408", "HTTP 425", "HTTP 429", "HTTP 500", "HTTP 502", "HTTP 503", "HTTP 504")
                    .any { message.contains(it) }
                val transientNetwork = error !is IllegalStateException
                if (attempt == 2 || (!transientHttp && !transientNetwork)) throw error
                Thread.sleep(350L * (attempt + 1))
            }
        }
        throw lastError ?: IllegalStateException("Could not load saved tests.")
    }

    private fun refreshSession(): String {
        val refresh = session.refreshToken()
            ?: throw IllegalStateException("Your session expired. Please sign in again.")
        val result = request("POST", "/auth/v1/token?grant_type=refresh_token",
            JSONObject().put("refresh_token", refresh), null).optJSONObject(0)
            ?: throw IllegalStateException("Your session expired. Please sign in again.")
        val access = result.optString("access_token")
        val nextRefresh = result.optString("refresh_token", refresh)
        if (access.isBlank()) throw IllegalStateException("Your session expired. Please sign in again.")
        session.save(access, nextRefresh)
        return access
    }

    private fun request(method: String, path: String, body: JSONObject?, token: String?): JSONArray {
        val conn = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("apikey", publishableKey)
            if (token != null) setRequestProperty("Authorization", "Bearer " + token)
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
