package com.kxko.dailybriefing

import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import java.io.File
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

private data class QuizQuestion(
    val id: String,
    val subject: String,
    val prompt: String,
    val options: List<String>,
    val correctIndex: Int,
    val explanation: String,
    val languageTag: String = "de-DE",
    val sourceId: String? = null
)

private data class ScheduledReview(val question: QuizQuestion, val dueAfterAnswer: Int)

private const val SHERPA_TTS_PACKAGE = "org.woheller69.ttsengine"

class CyclingQuizActivity : ComponentActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var mediaSession: MediaSessionCompat? = null
    private var ttsReady by mutableStateOf(false)
    private var studyLoaded by mutableStateOf(false)
    private var isLoadingStudy by mutableStateOf(false)
    private var studyEmail by mutableStateOf("")
    private var studyPassword by mutableStateOf("")
    private val studyRepository by lazy { StudyQuizRepository(applicationContext) }
    private val studyStats by lazy { StudyStats(applicationContext) }
    private var statsSnapshot by mutableStateOf(
        StudySnapshot("", 0, 0, 10, 0, 0, false, emptyList(), 0, 0, 0, 0)
    )
    private var signedIn by mutableStateOf(false)
    private var pickedIndex by mutableStateOf<Int?>(null)
    private var sessionRecorded = false
    private var started by mutableStateOf(false)
    private var acceptingAnswers by mutableStateOf(false)
    private var finished by mutableStateOf(false)
    private var currentQuestion by mutableStateOf<QuizQuestion?>(null)
    private var statusText by mutableStateOf("Bitte nur im Stillstand starten. Achte beim Radfahren immer auf den Verkehr.")
    private var answerCount by mutableStateOf(0)
    private var correctCount by mutableStateOf(0)
    private val utteranceCallbacks = mutableMapOf<String, () -> Unit>()
    private var utteranceNumber = 0

    // Lively AI voice (server-made speech). Falls back to the phone's own text-to-speech whenever it is unavailable.
    private val voiceExecutor: ExecutorService = Executors.newFixedThreadPool(3)
    private val voiceCache = HashMap<String, ByteArray>()
    private val voiceInflight = HashMap<String, Future<ByteArray?>>()
    private var voicePlayer: MediaPlayer? = null
    private var speakToken = 0
    private var aiVoiceFailures = 0
    private var aiVoiceEnabled by mutableStateOf(true)

    private var questionBank = listOf(
        QuizQuestion("bio-mito", "Biologie", "Welches Zellorganell erzeugt den größten Teil der Energie, die eine Zelle benötigt?", listOf("Zellkern", "Mitochondrium", "Ribosom"), 1, "Mitochondrien erzeugen den größten Teil des ATP, das Zellen als Energiequelle nutzen."),
        QuizQuestion("bio-nucleus", "Biologie", "Welche Zellstruktur enthält den größten Teil der DNA einer Zelle?", listOf("Zellkern", "Zellmembran", "Ribosom"), 0, "Der Zellkern enthält den größten Teil der genetischen Information einer eukaryotischen Zelle."),
        QuizQuestion("physics-force", "Physik", "In welcher Einheit wird die Kraft gemessen?", listOf("Watt", "Newton", "Joule"), 1, "Kraft wird in Newton gemessen. Watt ist die Einheit der Leistung und Joule die der Energie."),
        QuizQuestion("physics-current", "Physik", "In welcher Einheit wird die elektrische Stromstärke gemessen?", listOf("Volt", "Ohm", "Ampere"), 2, "Die elektrische Stromstärke wird in Ampere gemessen."),
        QuizQuestion("html-link", "HTML", "Welches HTML-Element erstellt einen Hyperlink?", listOf("<img>", "<a>", "<p>"), 1, "Das a-Element erstellt einen Link. Das Attribut href enthält das Linkziel."),
        QuizQuestion("html-href", "HTML", "Welches Attribut legt die Zieladresse eines Links fest?", listOf("src", "alt", "href"), 2, "Das Attribut href legt fest, auf welches Ziel ein Link verweist."),
        QuizQuestion("css-color", "CSS", "Welche CSS-Regel färbt den Text eines Absatzes rot?", listOf("p { color: red; }", "p { font: red; }", "p { text: red; }"), 0, "Die Eigenschaft color legt die Textfarbe fest. background-color ändert die Hintergrundfarbe."),
        QuizQuestion("js-variable", "JavaScript", "Welches Schlüsselwort deklariert eine Variable, der später ein neuer Wert zugewiesen werden kann?", listOf("const", "let", "style"), 1, "let deklariert eine Variable, deren Wert später geändert werden kann. const ist für Variablen gedacht, die nicht neu zugewiesen werden sollen."),
        QuizQuestion("dutch-thanks", "Niederländisch", "Was bedeutet „Dank je wel“?", listOf("Guten Morgen", "Vielen Dank", "Ich verstehe es nicht"), 1, "„Dank je wel“ bedeutet „Vielen Dank“.", "nl-NL"),
        QuizQuestion("dutch-come", "Niederländisch", "Vul de zin aan: „Ik ___ uit Duitsland.“", listOf("ben", "kom", "heb"), 1, "„Ik kom uit Duitsland“ betekent dat je uit Duitsland komt. In het Nederlands gebruik je hiervoor „komen“.", "nl-NL"),
        QuizQuestion("english-noun", "Englisch", "Which word is a noun in this sentence: “The signal changed quickly”?", listOf("quickly", "changed", "signal"), 2, "Signal is a noun here. Changed is a verb and quickly is an adverb.", "en-GB"),
        QuizQuestion("biology-membrane", "Biologie", "Was ist eine wichtige Aufgabe der Zellmembran?", listOf("Regeln, was in die Zelle hinein- und hinausgelangt", "Die gesamte DNA der Zelle herstellen", "Sonnenlicht erzeugen"), 0, "Die Zellmembran ist eine selektive Barriere und reguliert, welche Stoffe in die Zelle hinein- und hinausgelangen.")
    )

    private var remainingQuestions = mutableListOf<QuizQuestion>()
    private val scheduledReviews = mutableListOf<ScheduledReview>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        signedIn = studyRepository.hasSession()
        statsSnapshot = studyStats.snapshot()

        val ttsInitListener = TextToSpeech.OnInitListener { result ->
            if (result == TextToSpeech.SUCCESS) {
                val engine = tts
                val languageResult = engine?.setLanguage(Locale.GERMANY)
                if (languageResult == TextToSpeech.LANG_MISSING_DATA || languageResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    engine?.language = Locale.GERMANY
                }
                engine?.setSpeechRate(0.9f)
                engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onError(utteranceId: String?) {
                        utteranceId?.let { handler.post { utteranceCallbacks.remove(it)?.invoke() } }
                    }
                    override fun onDone(utteranceId: String?) {
                        utteranceId?.let { id -> handler.post { utteranceCallbacks.remove(id)?.invoke() } }
                    }
                })
                ttsReady = true
            } else {
                statusText = "Speech could not start. Check that a text-to-speech voice is installed."
            }
        }
        // Prefer the free, offline neural SherpaTTS engine when installed; otherwise keep the device default.
        tts = if (isSherpaTtsInstalled()) {
            TextToSpeech(this, ttsInitListener, SHERPA_TTS_PACKAGE)
        } else {
            TextToSpeech(this, ttsInitListener)
        }

        mediaSession = MediaSessionCompat(this, "KxkoCyclingQuiz").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onSkipToPrevious() = sendAnswerFromWatch(0)
                override fun onPlay() = sendAnswerFromWatch(1)
                override fun onPause() = sendAnswerFromWatch(1)
                override fun onSkipToNext() = sendAnswerFromWatch(2)
            })
            setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
        }

        setContent {
            val card = currentQuestion?.let { QuizCardUi(it.subject, it.prompt, it.options, it.correctIndex) }
            val snap = statsSnapshot
            CyclingQuizScreen(
                state = QuizScreenState(
                    started = started,
                    finished = finished,
                    ttsReady = ttsReady,
                    studyLoaded = studyLoaded,
                    isLoadingStudy = isLoadingStudy,
                    signedIn = signedIn,
                    email = studyEmail,
                    password = studyPassword,
                    card = card,
                    pickedIndex = pickedIndex,
                    statusText = statusText,
                    answerCount = answerCount,
                    correctCount = correctCount,
                    acceptingAnswers = acceptingAnswers,
                    aiVoiceEnabled = aiVoiceEnabled,
                    sherpaInstalled = isSherpaTtsInstalled(),
                    streak = snap.streak,
                    answeredToday = snap.answeredToday,
                    goal = snap.goal
                ),
                actions = QuizScreenActions(
                    onEmail = { studyEmail = it },
                    onPassword = { studyPassword = it },
                    onLoad = { loadStudyQuestions() },
                    onStart = { startQuiz() },
                    onAnswer = { submitAnswer(it) },
                    onFinish = { finishQuiz() },
                    onReset = { resetQuiz() },
                    onSherpa = {
                        if (isSherpaTtsInstalled()) {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        } else {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/org.woheller69.ttsengine/")))
                        }
                    },
                    onBack = { finish() }
                )
            )
        }
    }

    private fun isSherpaTtsInstalled(): Boolean = try {
        packageManager.getPackageInfo(SHERPA_TTS_PACKAGE, 0)
        true
    } catch (_: Exception) {
        false
    }

    private fun sendAnswerFromWatch(index: Int) {
        runOnUiThread { submitAnswer(index) }
    }

    private fun loadStudyQuestions() {
        if (isLoadingStudy) return
        isLoadingStudy = true
        statusText = "Anmeldung und Tests werden geladen …"
        Thread {
            try {
                if (!(signedIn && studyEmail.isBlank())) studyRepository.signIn(studyEmail, studyPassword)
                val loaded = studyRepository.loadUpcomingQuestions()
                runOnUiThread {
                    questionBank = loaded.map { item ->
                        QuizQuestion(item.id, item.subject, item.prompt, item.options, item.correctIndex, item.explanation, sourceId = item.sourceId)
                    }
                    studyLoaded = true
                    signedIn = true
                    isLoadingStudy = false
                    studyPassword = ""
                    statusText = "${questionBank.size} Fragen aus deinen gespeicherten Tests geladen. Der nächste Test kommt zuerst."
                }
            } catch (error: Exception) {
                runOnUiThread {
                    isLoadingStudy = false
                    statusText = error.message ?: "Tests konnten nicht geladen werden."
                }
            }
        }.start()
    }

    private fun startQuiz() {
        if (!ttsReady || !studyLoaded || started) return
        started = true
        finished = false
        sessionRecorded = false
        pickedIndex = null
        answerCount = 0
        correctCount = 0
        remainingQuestions = questionBank.toMutableList()
        scheduledReviews.clear()
        mediaSession?.isActive = true
        updateMediaSession()
        nextQuestion()
    }

    private fun nextQuestion() {
        acceptingAnswers = false
        val dueIndex = scheduledReviews.indexOfFirst { it.dueAfterAnswer <= answerCount }
        val next = when {
            dueIndex >= 0 -> scheduledReviews.removeAt(dueIndex).question
            remainingQuestions.isNotEmpty() -> remainingQuestions.removeAt(0)
            scheduledReviews.isNotEmpty() -> scheduledReviews.removeAt(0).question
            else -> null
        }
        if (next == null) {
            finished = true
            started = false
            currentQuestion = null
            pickedIndex = null
            recordSessionOnce()
            statusText = "You got $correctCount out of $answerCount questions right. Missed concepts were revisited during the session."
            speak(statusText, null)
            mediaSession?.isActive = false
            return
        }
        currentQuestion = next
        pickedIndex = null
        statusText = "Höre dir alle drei Antworten an und drücke dann eine Taste an deiner Uhr."
        updateMediaSession()
        val spokenSubject = when (next.languageTag) {
            "nl-NL" -> "Nederlands"
            "en-GB" -> "English"
            else -> next.subject
        }
        val optionWord = when (next.languageTag) {
            "nl-NL" -> "Optie"
            "en-GB" -> "Option"
            else -> "Antwortmöglichkeit"
        }
        val spokenQuestion = buildString {
            append(spokenSubject).append(". ")
            append(next.prompt).append(" ")
            next.options.forEachIndexed { index, option -> append("$optionWord ${index + 1}. ").append(option).append(". ") }
        }
        speak(spokenQuestion, onDone = {
            acceptingAnswers = true
            statusText = "Wähle 1, 2 oder 3 mit deiner Uhr."
        })
        // While the question is read, prepare both possible reactions so the answer feels instant.
        prefetchVoice(feedbackText(next, true), next.languageTag)
        prefetchVoice(feedbackText(next, false), next.languageTag)
    }

    private fun feedbackText(question: QuizQuestion, correct: Boolean): String = when (question.languageTag) {
            "nl-NL" -> if (correct) {
                "Goed gedaan. ${question.explanation}"
            } else {
                "Niet helemaal. Het juiste antwoord is optie ${question.correctIndex + 1}: ${question.options[question.correctIndex]}. ${question.explanation} Ik stel deze vraag later opnieuw."
            }
            "en-GB" -> if (correct) {
                "Correct. ${question.explanation}"
            } else {
                "Not quite. The correct answer is option ${question.correctIndex + 1}: ${question.options[question.correctIndex]}. ${question.explanation} I'll ask about this again later."
            }
            else -> if (correct) {
                "Richtig. ${question.explanation}"
            } else {
                "Nicht ganz. Die richtige Antwort ist ${question.correctIndex + 1}: ${question.options[question.correctIndex]}. ${question.explanation} Ich frage später noch einmal danach."
            }
    }

    private fun submitAnswer(index: Int) {
        val question = currentQuestion ?: return
        if (!started || !acceptingAnswers || finished || index !in 0..2) return

        acceptingAnswers = false
        answerCount += 1
        val correct = index == question.correctIndex
        pickedIndex = index
        studyStats.recordAnswer(correct)
        statsSnapshot = studyStats.snapshot()
        question.sourceId?.let { id -> Thread { studyRepository.reportAnswer(id, correct) }.start() }
        val feedback = feedbackText(question, correct)
        if (correct) {
            correctCount += 1
            statusText = feedback
        } else {
            statusText = feedback
            scheduledReviews.add(ScheduledReview(question, answerCount + 3))
        }
        speak(feedback, onDone = {
            handler.postDelayed({ if (started && !isFinishing) nextQuestion() }, 800L)
        })
    }

    private fun updateMediaSession() {
        val question = currentQuestion
        mediaSession?.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, question?.prompt ?: "Kxko Cycling Quiz")
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Kxko Cycling Quiz")
                .build()
        )
        mediaSession?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                )
                .setState(PlaybackStateCompat.STATE_PLAYING, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build()
        )
    }

    private fun speak(text: String, onDone: (() -> Unit)? = null, languageTag: String? = currentQuestion?.languageTag) {
        val lang = languageTag ?: "de-DE"
        if (!aiVoiceEnabled || !studyLoaded) {
            speakWithDevice(text, onDone, lang)
            return
        }
        val token = ++speakToken
        tts?.stop()
        stopVoicePlayer()
        Thread {
            val bytes = fetchVoice(text, lang)
            runOnUiThread {
                if (token != speakToken || isFinishing) return@runOnUiThread
                if (bytes == null) {
                    aiVoiceFailures += 1
                    if (aiVoiceFailures >= 2) aiVoiceEnabled = false
                    speakWithDevice(text, onDone, lang)
                } else {
                    aiVoiceFailures = 0
                    playVoice(bytes, onFail = { speakWithDevice(text, onDone, lang) }, onDone = onDone)
                }
            }
        }.start()
    }

    private fun fetchVoice(text: String, lang: String): ByteArray? {
        val key = "$lang|$text"
        synchronized(voiceCache) { voiceCache[key]?.let { return it } }
        val future = synchronized(voiceInflight) {
            voiceInflight.getOrPut(key) {
                voiceExecutor.submit(Callable<ByteArray?> {
                    val result = try { studyRepository.synthesize(text, lang) } catch (_: Exception) { null }
                    if (result != null) synchronized(voiceCache) { voiceCache[key] = result }
                    synchronized(voiceInflight) { voiceInflight.remove(key) }
                    result
                })
            }
        }
        return try { future.get() } catch (_: Exception) { null }
    }

    private fun prefetchVoice(text: String, lang: String) {
        if (!aiVoiceEnabled || !studyLoaded) return
        Thread { fetchVoice(text, lang) }.start()
    }

    private fun playVoice(bytes: ByteArray, onFail: () -> Unit, onDone: (() -> Unit)?) {
        try {
            val file = File(cacheDir, "kxko-voice-${++utteranceNumber}.wav")
            file.writeBytes(bytes)
            val player = MediaPlayer()
            voicePlayer = player
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            player.setDataSource(file.absolutePath)
            player.setOnPreparedListener { it.start() }
            player.setOnCompletionListener { mp ->
                mp.release()
                if (voicePlayer === mp) voicePlayer = null
                file.delete()
                onDone?.invoke()
            }
            player.setOnErrorListener { mp, _, _ ->
                mp.release()
                if (voicePlayer === mp) voicePlayer = null
                file.delete()
                onFail()
                true
            }
            player.prepareAsync()
        } catch (_: Exception) {
            stopVoicePlayer()
            onFail()
        }
    }

    private fun stopVoicePlayer() {
        val player = voicePlayer ?: return
        voicePlayer = null
        try { player.stop() } catch (_: Exception) { }
        try { player.release() } catch (_: Exception) { }
    }

    private fun speakWithDevice(text: String, onDone: (() -> Unit)?, languageTag: String) {
        val engine = tts
        if (engine == null || !ttsReady) {
            onDone?.invoke()
            return
        }
        val requestedLocale = Locale.forLanguageTag(languageTag)
        val languageResult = engine.setLanguage(requestedLocale)
        if (languageResult == TextToSpeech.LANG_MISSING_DATA || languageResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            engine.setLanguage(Locale.GERMANY)
        }
        val id = "kxko-quiz-${++utteranceNumber}"
        if (onDone != null) utteranceCallbacks[id] = onDone
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
        if (result == TextToSpeech.ERROR) {
            utteranceCallbacks.remove(id)?.invoke()
        }
    }

    private fun finishQuiz() {
        acceptingAnswers = false
        started = false
        finished = true
        pickedIndex = null
        recordSessionOnce()
        mediaSession?.isActive = false
        speakToken++
        stopVoicePlayer()
        tts?.stop()
        statusText = "Session stopped. Your score was $correctCount out of $answerCount."
    }

    private fun recordSessionOnce() {
        if (sessionRecorded) return
        sessionRecorded = true
        studyStats.recordSession(answerCount, correctCount)
        statsSnapshot = studyStats.snapshot()
    }

    private fun resetQuiz() {
        finished = false
        pickedIndex = null
        started = false
        currentQuestion = null
        statusText = if (studyLoaded) "Deine gespeicherten Tests sind bereit. Starte nur im Stillstand." else "Melde dich an, um deine gespeicherten Tests zu laden. Starte nur im Stillstand."
        answerCount = 0
        correctCount = 0
        scheduledReviews.clear()
        remainingQuestions.clear()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
        speakToken++
        stopVoicePlayer()
        voiceExecutor.shutdownNow()
        tts?.stop()
        tts?.shutdown()
        tts = null
        utteranceCallbacks.clear()
        super.onDestroy()
    }
}
