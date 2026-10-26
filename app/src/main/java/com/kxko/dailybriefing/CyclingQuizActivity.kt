package com.kxko.dailybriefing

import android.content.Intent
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
import java.util.Locale

private data class QuizQuestion(
    val id: String,
    val subject: String,
    val prompt: String,
    val options: List<String>,
    val correctIndex: Int,
    val explanation: String,
    val languageTag: String = "de-DE"
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
    private var started by mutableStateOf(false)
    private var acceptingAnswers by mutableStateOf(false)
    private var finished by mutableStateOf(false)
    private var currentQuestion by mutableStateOf<QuizQuestion?>(null)
    private var statusText by mutableStateOf("Bitte nur im Stillstand starten. Achte beim Radfahren immer auf den Verkehr.")
    private var answerCount by mutableStateOf(0)
    private var correctCount by mutableStateOf(0)
    private val utteranceCallbacks = mutableMapOf<String, () -> Unit>()
    private var utteranceNumber = 0

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
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFC5A16B),
                    background = Color(0xFF111315),
                    surface = Color(0xFF1A1D20),
                    onBackground = Color(0xFFF0EEE9),
                    onSurface = Color(0xFFF0EEE9)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                    color = Color(0xFF111315)
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("KXKO", color = Color(0xFFC5A16B), fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Radfahr-Quiz",
                            color = Color(0xFFF0EEE9),
                            fontSize = 30.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Zurück = 1  ·  Wiedergabe/Pause = 2  ·  Weiter = 3",
                            color = Color(0xFFC5A16B),
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(20.dp))
                        Text(
                            currentQuestion?.let { "${it.subject}\n\n${it.prompt}\n\n1. ${it.options[0]}\n2. ${it.options[1]}\n3. ${it.options[2]}" }
                                ?: "Die Fragen werden vorgelesen. Wähle mit den Medientasten deiner Uhr. Falsche Antworten kommen später wieder.",
                            color = Color(0xFFF0EEE9),
                            fontSize = 18.sp,
                            textAlign = TextAlign.Center,
                            lineHeight = 27.sp
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(statusText, color = Color(0xFFB9B5AB), fontSize = 14.sp, textAlign = TextAlign.Center, lineHeight = 21.sp)
                        if (started && !finished) {
                            Spacer(Modifier.height(10.dp))
                            Text("Answered: $answerCount  ·  Correct: $correctCount", color = Color(0xFFC5A16B), fontSize = 13.sp)
                            Spacer(Modifier.height(16.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                listOf("1", "2", "3").forEachIndexed { index, label ->
                                    Button(
                                        onClick = { submitAnswer(index) },
                                        enabled = acceptingAnswers,
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(14.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC5A16B), contentColor = Color(0xFF201C15))
                                    ) { Text(label, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            Button(
                                onClick = { finishQuiz() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF292C2F), contentColor = Color(0xFFF0EEE9))
                            ) { Text("End session") }
                        } else if (!finished) {
                            Spacer(Modifier.height(22.dp))
                            OutlinedTextField(
                                value = studyEmail,
                                onValueChange = { studyEmail = it },
                                label = { Text("Study app email") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = studyPassword,
                                onValueChange = { studyPassword = it },
                                label = { Text("Study app password") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = { loadStudyQuestions() },
                                enabled = ttsReady && !isLoadingStudy && studyEmail.isNotBlank() && studyPassword.isNotBlank(),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF292C2F), contentColor = Color(0xFFF0EEE9))
                            ) {
                                Text(if (isLoadingStudy) "Lade gespeicherte Tests …" else "Mit Study-App anmelden & Tests laden")
                            }
                            Spacer(Modifier.height(10.dp))
                            Button(
                                onClick = { startQuiz() },
                                enabled = ttsReady && studyLoaded,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC5A16B), contentColor = Color(0xFF201C15))
                            ) {
                                Text(if (!ttsReady) "Stimme wird vorbereitet …" else if (!studyLoaded) "Zuerst Tests laden" else "Quiz starten", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            }
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "Für eine natürlichere kostenlose Stimme: Installiere SherpaTTS aus F-Droid, lade eine deutsche Stimme herunter und wähle sie danach in den Android-Sprachausgabe-Einstellungen aus. Das Quiz verwendet dann die dort ausgewählte TTS-Engine.",
                                color = Color(0xFFB9B5AB),
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center,
                                lineHeight = 18.sp
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = { startActivity(Intent(Settings.ACTION_TEXT_TO_SPEECH_SETTINGS)) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF292C2F), contentColor = Color(0xFFF0EEE9))
                            ) { Text("KI-Stimme auswählen") }
                        } else {
                            Spacer(Modifier.height(22.dp))
                            Text("Session complete", color = Color(0xFFC5A16B), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(10.dp))
                            Button(onClick = { resetQuiz() }, modifier = Modifier.fillMaxWidth()) { Text("Start another session") }
                        }
                    }
                }
            }
        }
    }

    private fun isSherpaTtsInstalled(): Boolean = try {
        @Suppress("DEPRECATION")
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
                studyRepository.signIn(studyEmail, studyPassword)
                val loaded = studyRepository.loadUpcomingQuestions()
                runOnUiThread {
                    questionBank = loaded.map { item ->
                        QuizQuestion(item.id, item.subject, item.prompt, item.options, item.correctIndex, item.explanation)
                    }
                    studyLoaded = true
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
            statusText = "You got $correctCount out of $answerCount questions right. Missed concepts were revisited during the session."
            speak(statusText, null)
            mediaSession?.isActive = false
            return
        }
        currentQuestion = next
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
        speak(spokenQuestion) {
            acceptingAnswers = true
            statusText = "Wähle 1, 2 oder 3 mit deiner Uhr."
        }
    }

    private fun submitAnswer(index: Int) {
        val question = currentQuestion ?: return
        if (!started || !acceptingAnswers || finished || index !in 0..2) return

        acceptingAnswers = false
        answerCount += 1
        val correct = index == question.correctIndex
        val feedback = when (question.languageTag) {
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
        if (correct) {
            correctCount += 1
            statusText = feedback
        } else {
            statusText = feedback
            scheduledReviews.add(ScheduledReview(question, answerCount + 3))
        }
        speak(feedback) {
            handler.postDelayed({ if (started && !isFinishing) nextQuestion() }, 800L)
        }
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
        val engine = tts
        if (engine == null || !ttsReady) {
            onDone?.invoke()
            return
        }
        val requestedLocale = Locale.forLanguageTag(languageTag ?: "de-DE")
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
        mediaSession?.isActive = false
        tts?.stop()
        statusText = "Session stopped. Your score was $correctCount out of $answerCount."
    }

    private fun resetQuiz() {
        finished = false
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
        tts?.stop()
        tts?.shutdown()
        tts = null
        utteranceCallbacks.clear()
        super.onDestroy()
    }
}
