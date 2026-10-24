package com.kxko.dailybriefing

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
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
    val explanation: String
)

private data class ScheduledReview(val question: QuizQuestion, val dueAfterAnswer: Int)

class CyclingQuizActivity : ComponentActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var mediaSession: MediaSessionCompat? = null
    private var ttsReady by mutableStateOf(false)
    private var started by mutableStateOf(false)
    private var acceptingAnswers by mutableStateOf(false)
    private var finished by mutableStateOf(false)
    private var currentQuestion by mutableStateOf<QuizQuestion?>(null)
    private var statusText by mutableStateOf("Start while stationary. Keep your attention on the road while cycling.")
    private var answerCount by mutableStateOf(0)
    private var correctCount by mutableStateOf(0)
    private val utteranceCallbacks = mutableMapOf<String, () -> Unit>()
    private var utteranceNumber = 0

    private val questionBank = listOf(
        QuizQuestion("bio-mito", "Biology", "Which organelle produces most of the energy a cell uses?", listOf("Nucleus", "Mitochondrion", "Ribosome"), 1, "Mitochondria make most of the ATP that cells use as an energy source."),
        QuizQuestion("bio-nucleus", "Biology", "Which cell structure contains most of a cell's DNA?", listOf("Nucleus", "Cell membrane", "Ribosome"), 0, "The nucleus stores most of a eukaryotic cell's genetic information."),
        QuizQuestion("physics-force", "Physics", "What is the unit of force?", listOf("Watt", "Newton", "Joule"), 1, "Force is measured in newtons. Watts measure power and joules measure energy."),
        QuizQuestion("physics-current", "Physics", "Which unit is used to measure electric current?", listOf("Volt", "Ohm", "Ampere"), 2, "Electric current is measured in amperes, often shortened to amps."),
        QuizQuestion("html-link", "HTML", "Which HTML element creates a hyperlink?", listOf("<img>", "<a>", "<p>"), 1, "The a element creates a link. Its href attribute contains the destination."),
        QuizQuestion("html-href", "HTML", "Which attribute gives a link its destination URL?", listOf("src", "alt", "href"), 2, "The href attribute specifies where a link points."),
        QuizQuestion("css-color", "CSS", "Which CSS declaration makes paragraph text red?", listOf("p { color: red; }", "p { font: red; }", "p { text: red; }"), 0, "The color property sets text colour. The background-color property changes the background."),
        QuizQuestion("js-variable", "JavaScript", "Which keyword declares a variable that can be reassigned?", listOf("const", "let", "style"), 1, "let declares a block-scoped variable that can be reassigned. const is for bindings that should not be reassigned."),
        QuizQuestion("dutch-thanks", "Dutch", "What does 'Dank je wel' mean?", listOf("Good morning", "Thank you", "I don't understand"), 1, "Dank je wel means thank you."),
        QuizQuestion("dutch-come", "Dutch", "Complete the sentence: 'Ik ___ uit Duitsland.'", listOf("ben", "kom", "heb"), 1, "Ik kom uit Duitsland means I come from Germany. Dutch uses komen for this expression."),
        QuizQuestion("english-noun", "English", "Which word is a noun in this sentence: 'The signal changed quickly'?", listOf("quickly", "changed", "signal"), 2, "Signal is a noun here. Changed is a verb and quickly is an adverb."),
        QuizQuestion("biology-membrane", "Biology", "What is a main function of the cell membrane?", listOf("Control what enters and leaves the cell", "Make all the cell's DNA", "Produce sunlight"), 0, "The cell membrane acts as a selective barrier, helping regulate what enters and leaves the cell.")
    )

    private var remainingQuestions = mutableListOf<QuizQuestion>()
    private val scheduledReviews = mutableListOf<ScheduledReview>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tts = TextToSpeech(this) { result ->
            if (result == TextToSpeech.SUCCESS) {
                val engine = tts
                val languageResult = engine?.setLanguage(Locale.UK)
                if (languageResult == TextToSpeech.LANG_MISSING_DATA || languageResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    engine?.language = Locale.getDefault()
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
                        modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("KXKO", color = Color(0xFFC5A16B), fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Cycling Quiz",
                            color = Color(0xFFF0EEE9),
                            fontSize = 30.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Previous = 1  ·  Play/Pause = 2  ·  Next = 3",
                            color = Color(0xFFC5A16B),
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(20.dp))
                        Text(
                            currentQuestion?.let { "${it.subject}\n\n${it.prompt}\n\n1. ${it.options[0]}\n2. ${it.options[1]}\n3. ${it.options[2]}" }
                                ?: "Questions are read aloud. Choose with your watch's media buttons. Missed questions return later.",
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
                            Button(
                                onClick = { startQuiz() },
                                enabled = ttsReady,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC5A16B), contentColor = Color(0xFF201C15))
                            ) {
                                Text(if (ttsReady) "Start quiz" else "Preparing voice…", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            }
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

    private fun sendAnswerFromWatch(index: Int) {
        runOnUiThread { submitAnswer(index) }
    }

    private fun startQuiz() {
        if (!ttsReady || started) return
        started = true
        finished = false
        answerCount = 0
        correctCount = 0
        remainingQuestions = questionBank.shuffled().toMutableList()
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
        statusText = "Listen to all three options, then press a watch button."
        updateMediaSession()
        val spokenQuestion = buildString {
            append(next.subject).append(". ")
            append(next.prompt).append(" ")
            next.options.forEachIndexed { index, option -> append("Option ${index + 1}. ").append(option).append(". ") }
        }
        speak(spokenQuestion) {
            acceptingAnswers = true
            statusText = "Choose 1, 2, or 3 using your watch."
        }
    }

    private fun submitAnswer(index: Int) {
        val question = currentQuestion ?: return
        if (!started || !acceptingAnswers || finished || index !in 0..2) return

        acceptingAnswers = false
        answerCount += 1
        val correct = index == question.correctIndex
        if (correct) {
            correctCount += 1
            statusText = "Correct. ${question.explanation}"
        } else {
            statusText = "Not quite. The answer is ${question.correctIndex + 1}, ${question.options[question.correctIndex]}. ${question.explanation}"
            scheduledReviews.add(ScheduledReview(question, answerCount + 3))
        }
        val feedback = if (correct) {
            "Correct. ${question.explanation}"
        } else {
            "Not quite. The correct answer is option ${question.correctIndex + 1}: ${question.options[question.correctIndex]}. ${question.explanation} I'll ask about this again later."
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

    private fun speak(text: String, onDone: (() -> Unit)? = null) {
        val engine = tts
        if (engine == null || !ttsReady) {
            onDone?.invoke()
            return
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
        statusText = "Start while stationary. Keep your attention on the road while cycling."
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
