package com.kxko.dailybriefing

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kxko.dailybriefing.ui.theme.Kx
import com.kxko.dailybriefing.ui.theme.KxBottomBar
import com.kxko.dailybriefing.ui.theme.KxButton
import com.kxko.dailybriefing.ui.theme.KxCard
import com.kxko.dailybriefing.ui.theme.KxChip
import com.kxko.dailybriefing.ui.theme.KxLabel
import com.kxko.dailybriefing.ui.theme.KxkoTheme
import com.kxko.dailybriefing.ui.theme.ProgressRing
import com.kxko.dailybriefing.ui.theme.StackedBar
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Native study hub: streak, daily goal, exam readiness, weak spots and badges. */
class StudyHubActivity : ComponentActivity() {

    private val repository by lazy { StudyQuizRepository(applicationContext) }
    private val stats by lazy { StudyStats(applicationContext) }

    private var snapshot by mutableStateOf<StudySnapshot?>(null)
    private var progress by mutableStateOf<StudyProgress?>(null)
    private var signedIn by mutableStateOf(false)
    private var loading by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    private var email by mutableStateOf("")
    private var password by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        snapshot = stats.snapshot()
        signedIn = repository.hasSession()

        setContent {
            KxkoTheme {
                Surface(modifier = Modifier.fillMaxSize().safeDrawingPadding(), color = Kx.Bg) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 18.dp, vertical = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Header()
                            snapshot?.let { snap ->
                                StreakCard(snap)
                                GoalCard(snap)
                            }
                            ExamSection()
                            progress?.weakSpots?.takeIf { it.isNotEmpty() }?.let { WeakSpotsCard(it) }
                            snapshot?.let { BadgesRow(it) }
                            KxButton(
                                "Start Cycling Quiz",
                                onClick = { startActivity(Intent(this@StudyHubActivity, CyclingQuizActivity::class.java)) },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                        KxBottomBar(selected = 1, onSelect = { index -> navigate(index) })
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        snapshot = stats.snapshot()
        signedIn = repository.hasSession()
        if (signedIn) loadProgress()
    }

    private fun navigate(index: Int) {
        when (index) {
            0 -> {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
                finish()
            }
            2 -> startActivity(Intent(this, CyclingQuizActivity::class.java))
        }
    }

    private fun loadProgress() {
        if (loading) return
        loading = true
        error = null
        Thread {
            try {
                val result = repository.loadProgress()
                runOnUiThread {
                    progress = result
                    loading = false
                }
            } catch (e: Exception) {
                runOnUiThread {
                    loading = false
                    val message = e.message.orEmpty()
                    if (message.contains("sign in again", ignoreCase = true) || message.contains("Sign in", ignoreCase = true)) {
                        repository.signOut()
                        signedIn = false
                    }
                    error = message.ifBlank { "Could not load your tests." }
                }
            }
        }.start()
    }

    private fun signIn() {
        if (loading) return
        loading = true
        error = null
        Thread {
            try {
                repository.signIn(email, password)
                runOnUiThread {
                    password = ""
                    signedIn = true
                    loading = false
                    loadProgress()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    loading = false
                    error = e.message ?: "Sign-in failed."
                }
            }
        }.start()
    }

    // ---------- UI ----------

    @androidx.compose.runtime.Composable
    private fun Header() {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val greeting = when {
            hour < 5 -> "Late night grind"
            hour < 11 -> "Good morning"
            hour < 17 -> "Good afternoon"
            else -> "Good evening"
        }
        Column {
            KxLabel("KXKO  ·  Lernen")
            Spacer(Modifier.height(6.dp))
            Text(greeting, color = Kx.Cream, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
        }
    }

    @androidx.compose.runtime.Composable
    private fun StreakCard(snap: StudySnapshot) {
        KxCard(accent = snap.streak > 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (snap.streak > 0) "🔥" else "💤", fontSize = 44.sp)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            "${snap.streak}",
                            color = if (snap.streak > 0) Kx.Ember else Kx.Muted,
                            fontSize = 44.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "day streak",
                            color = Kx.Cream,
                            fontSize = 16.sp,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                }
                KxChip("Best ${snap.bestStreak}", Kx.BrassSoft, Kx.Brass)
            }
            Spacer(Modifier.height(14.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                snap.week.forEach { (day, answered) ->
                    val isToday = day == snap.today
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(if (answered > 0) Kx.Brass else Kx.SurfaceHi)
                                .then(
                                    if (isToday) Modifier.border(2.dp, Kx.Brass, CircleShape)
                                    else Modifier.border(1.dp, Kx.Line, CircleShape)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (answered > 0) Text("✓", color = Kx.OnBrass, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            StudyStatsLogic.weekdayLetter(day),
                            color = if (isToday) Kx.Brass else Kx.Dim,
                            fontSize = 11.sp,
                            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                StudyStatsLogic.streakMessage(snap.streak, snap.studiedToday),
                color = Kx.Muted,
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
        }
    }

    @androidx.compose.runtime.Composable
    private fun GoalCard(snap: StudySnapshot) {
        val done = snap.answeredToday >= snap.goal
        KxCard(onClick = {
            stats.setGoal(StudyStatsLogic.nextGoal(snap.goal))
            snapshot = stats.snapshot()
        }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProgressRing(
                    fraction = StudyStatsLogic.goalFraction(snap.answeredToday, snap.goal),
                    size = 92.dp,
                    stroke = 9.dp,
                    color = if (done) Kx.Good else Kx.Brass
                ) {
                    Text(
                        if (done) "✓" else "${snap.answeredToday}",
                        color = Kx.Cream,
                        fontSize = if (done) 30.sp else 26.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    KxLabel("Daily goal")
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (done) "Goal smashed" else "${snap.answeredToday} of ${snap.goal} questions",
                        color = Kx.Cream,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(2.dp))
                    val accuracy = if (snap.answeredToday > 0) " · ${Math.round(100f * snap.correctToday / snap.answeredToday)}% right" else ""
                    Text("Tap to change goal$accuracy", color = Kx.Dim, fontSize = 12.sp)
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun ExamSection() {
        KxLabel("Your tests", modifier = Modifier.padding(top = 4.dp))
        if (!signedIn) {
            KxCard {
                Text("Sign in to see how ready you are for each test.", color = Kx.Cream, fontSize = 15.sp, lineHeight = 21.sp)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Study app email") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Study app password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = Kx.Bad, fontSize = 13.sp)
                }
                Spacer(Modifier.height(12.dp))
                KxButton(
                    if (loading) "Signing in …" else "Sign in",
                    onClick = { signIn() },
                    enabled = !loading && email.isNotBlank() && password.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            return
        }
        val data = progress
        if (data == null) {
            KxCard {
                if (error != null) {
                    Text(error ?: "", color = Kx.Bad, fontSize = 14.sp)
                    Spacer(Modifier.height(10.dp))
                    KxButton("Try again", onClick = { loadProgress() }, primary = false)
                } else {
                    Text("Loading your tests …", color = Kx.Muted, fontSize = 14.sp)
                }
            }
            return
        }
        if (data.exams.isEmpty()) {
            KxCard {
                Text("No upcoming tests saved. Add them in the dashboard and they show up here.", color = Kx.Muted, fontSize = 14.sp, lineHeight = 20.sp)
            }
            return
        }
        data.exams.forEachIndexed { index, exam -> ExamCard(exam, accent = index == 0) }
    }

    @androidx.compose.runtime.Composable
    private fun ExamCard(exam: ExamProgress, accent: Boolean) {
        val today = StudyStatsLogic.dayKey(System.currentTimeMillis())
        val left = StudyStatsLogic.daysBetween(today, exam.examDate)
        val urgency = if (left == null) 0 else StudyStatsLogic.urgency(left)
        val chipColor = when (urgency) {
            3 -> Kx.Bad
            2 -> Kx.Ember
            1 -> Kx.Warn
            else -> Kx.Brass
        }
        val readiness = StudyStatsLogic.readinessPercent(exam.mastered, exam.total)
        KxCard(accent = accent) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(exam.subject, color = Kx.Cream, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                    Text(prettyDate(exam.examDate), color = Kx.Dim, fontSize = 13.sp)
                }
                if (left != null) KxChip(StudyStatsLogic.daysLabel(left), chipColor.copy(alpha = 0.18f), chipColor)
            }
            Spacer(Modifier.height(14.dp))
            if (exam.total == 0) {
                Text(
                    if (exam.hasTopics) "Topics are saved. Make a quiz in the dashboard to start practising."
                    else "No topics yet. Add them in the dashboard so the AI can quiz you.",
                    color = Kx.Muted,
                    fontSize = 13.sp,
                    lineHeight = 19.sp
                )
            } else {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("$readiness%", color = Kx.Cream, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(6.dp))
                    Text("ready", color = Kx.Muted, fontSize = 14.sp, modifier = Modifier.padding(bottom = 4.dp))
                }
                Spacer(Modifier.height(8.dp))
                StackedBar(exam.mastered, exam.shaky, exam.fresh)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Legend(Kx.Good, "${exam.mastered} nailed")
                    Legend(Kx.Warn, "${exam.shaky} shaky")
                    Legend(Kx.Dim, "${exam.fresh} new")
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun Legend(color: Color, text: String) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(5.dp))
            Text(text, color = Kx.Muted, fontSize = 12.sp)
        }
    }

    @androidx.compose.runtime.Composable
    private fun WeakSpotsCard(spots: List<WeakSpot>) {
        KxLabel("Needs another look", modifier = Modifier.padding(top = 4.dp))
        KxCard {
            spots.forEachIndexed { index, spot ->
                if (index > 0) {
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Kx.Line))
                    Spacer(Modifier.height(10.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(spot.text, color = Kx.Cream, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 3)
                        Text(spot.subject, color = Kx.Dim, fontSize = 12.sp)
                    }
                    Spacer(Modifier.width(10.dp))
                    KxChip("missed ${spot.wrong}×", Kx.Bad.copy(alpha = 0.16f), Kx.Bad)
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun BadgesRow(snap: StudySnapshot) {
        val badges = StudyStatsLogic.badges(snap.totalAnswers, snap.bestStreak, snap.perfectSessions, snap.sessions)
        KxLabel("Badges  ·  ${badges.count { it.unlocked }}/${badges.size}", modifier = Modifier.padding(top = 4.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            badges.forEach { badge ->
                Column(
                    modifier = Modifier
                        .width(118.dp)
                        .alpha(if (badge.unlocked) 1f else 0.38f)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Kx.Surface)
                        .border(1.dp, if (badge.unlocked) Kx.Brass.copy(alpha = 0.5f) else Kx.Line, RoundedCornerShape(18.dp))
                        .padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(if (badge.unlocked) badge.emoji else "🔒", fontSize = 28.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(badge.title, color = Kx.Cream, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    Text(badge.hint, color = Kx.Dim, fontSize = 11.sp, textAlign = TextAlign.Center, lineHeight = 14.sp)
                }
            }
        }
    }

    private fun prettyDate(key: String): String = try {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(key)
        SimpleDateFormat("EEEE, d MMMM", Locale.ENGLISH).format(date!!)
    } catch (_: Exception) {
        key
    }
}
