package com.kxko.dailybriefing

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kxko.dailybriefing.ui.theme.Kx
import com.kxko.dailybriefing.ui.theme.KxButton
import com.kxko.dailybriefing.ui.theme.KxCard
import com.kxko.dailybriefing.ui.theme.KxChip
import com.kxko.dailybriefing.ui.theme.KxLabel
import com.kxko.dailybriefing.ui.theme.KxkoTheme
import com.kxko.dailybriefing.ui.theme.ProgressRing

/** What the quiz screen shows for the current question. */
data class QuizCardUi(
    val subject: String,
    val prompt: String,
    val options: List<String>,
    val correctIndex: Int
)

/** Everything the quiz screen needs, so the activity keeps the audio/quiz logic and this file only draws. */
data class QuizScreenState(
    val started: Boolean,
    val finished: Boolean,
    val ttsReady: Boolean,
    val studyLoaded: Boolean,
    val isLoadingStudy: Boolean,
    val signedIn: Boolean,
    val email: String,
    val password: String,
    val card: QuizCardUi?,
    val pickedIndex: Int?,
    val statusText: String,
    val answerCount: Int,
    val correctCount: Int,
    val acceptingAnswers: Boolean,
    val aiVoiceEnabled: Boolean,
    val sherpaInstalled: Boolean,
    val streak: Int,
    val answeredToday: Int,
    val goal: Int
)

class QuizScreenActions(
    val onEmail: (String) -> Unit,
    val onPassword: (String) -> Unit,
    val onLoad: () -> Unit,
    val onStart: () -> Unit,
    val onAnswer: (Int) -> Unit,
    val onFinish: () -> Unit,
    val onReset: () -> Unit,
    val onSherpa: () -> Unit,
    val onBack: () -> Unit
)

@Composable
fun CyclingQuizScreen(state: QuizScreenState, actions: QuizScreenActions) {
    KxkoTheme {
        Surface(modifier = Modifier.fillMaxSize().safeDrawingPadding(), color = Kx.Bg) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                TopRow(state, actions)
                when {
                    state.finished -> FinishedView(state, actions)
                    state.started -> RunningView(state, actions)
                    else -> SetupView(state, actions)
                }
            }
        }
    }
}

@Composable
private fun TopRow(state: QuizScreenState, actions: QuizScreenActions) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "‹ Back",
            color = Kx.Brass,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable { actions.onBack() }
                .padding(horizontal = 6.dp, vertical = 8.dp)
        )
        Spacer(Modifier.weight(1f))
        if (state.streak > 0) KxChip("🔥 ${state.streak} day streak", Kx.Ember.copy(alpha = 0.16f), Kx.Ember)
    }
}

@Composable
private fun SetupView(state: QuizScreenState, actions: QuizScreenActions) {
    Column {
        KxLabel("KXKO  ·  Cycling Quiz")
        Spacer(Modifier.height(6.dp))
        Text("Radfahr-Quiz", color = Kx.Cream, fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Questions are read out loud. Answer with the media buttons on your watch. Wrong answers come back later.",
            color = Kx.Muted,
            fontSize = 14.sp,
            lineHeight = 20.sp
        )
    }

    KxCard {
        KxLabel("Watch buttons")
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            KeyTile("1", "Back", Modifier.weight(1f))
            KeyTile("2", "Play / Pause", Modifier.weight(1f))
            KeyTile("3", "Next", Modifier.weight(1f))
        }
    }

    if (state.signedIn) {
        KxCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("✓", color = Kx.Good, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(10.dp))
                Text("Signed in to your study app", color = Kx.Cream, fontSize = 15.sp, modifier = Modifier.weight(1f))
            }
        }
    } else {
        KxCard {
            KxLabel("Sign in")
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = state.email,
                onValueChange = actions.onEmail,
                label = { Text("Study app email") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.password,
                onValueChange = actions.onPassword,
                label = { Text("Study app password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    val canLoad = state.ttsReady && !state.isLoadingStudy &&
        (state.signedIn || (state.email.isNotBlank() && state.password.isNotBlank()))
    KxButton(
        when {
            state.isLoadingStudy -> "Loading saved tests …"
            state.studyLoaded -> "Reload tests"
            state.signedIn -> "Load my tests"
            else -> "Sign in & load tests"
        },
        onClick = actions.onLoad,
        enabled = canLoad,
        primary = !state.studyLoaded,
        modifier = Modifier.fillMaxWidth()
    )
    KxButton(
        when {
            !state.ttsReady -> "Getting the voice ready …"
            !state.studyLoaded -> "Load tests first"
            else -> "Start quiz"
        },
        onClick = actions.onStart,
        enabled = state.ttsReady && state.studyLoaded,
        modifier = Modifier.fillMaxWidth()
    )

    Text(state.statusText, color = Kx.Muted, fontSize = 13.sp, textAlign = TextAlign.Center, lineHeight = 19.sp, modifier = Modifier.fillMaxWidth())

    KxCard {
        KxLabel("Voice")
        Spacer(Modifier.height(8.dp))
        Text(
            if (state.aiVoiceEnabled) "Lively AI voice is on (needs internet). Offline, the app reads with your phone's own voice."
            else "The AI voice isn't available right now. The app reads with your phone's own voice.",
            color = Kx.Cream,
            fontSize = 14.sp,
            lineHeight = 20.sp
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (state.sherpaInstalled) "SherpaTTS is installed. Pick it as your speech engine and download a German voice for an offline neural voice."
            else "For a free offline neural voice, install SherpaTTS and a German voice model.",
            color = Kx.Dim,
            fontSize = 12.sp,
            lineHeight = 18.sp
        )
        Spacer(Modifier.height(10.dp))
        KxButton(
            if (state.sherpaInstalled) "Choose neural voice" else "Get free neural voice",
            onClick = actions.onSherpa,
            primary = false,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun KeyTile(number: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Kx.SurfaceHi)
            .padding(vertical = 12.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(number, color = Kx.Brass, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Kx.Muted, fontSize = 11.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun RunningView(state: QuizScreenState, actions: QuizScreenActions) {
    val accuracy = if (state.answerCount > 0) Math.round(100f * state.correctCount / state.answerCount) else 0
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatTile("Answered", "${state.answerCount}", Modifier.weight(1f))
        StatTile("Correct", "${state.correctCount}", Modifier.weight(1f))
        StatTile("Accuracy", if (state.answerCount > 0) "$accuracy%" else "–", Modifier.weight(1f))
    }

    val card = state.card
    if (card != null) {
        KxCard(accent = true) {
            KxChip(card.subject, Kx.BrassSoft, Kx.Brass)
            Spacer(Modifier.height(12.dp))
            Text(card.prompt, color = Kx.Cream, fontSize = 20.sp, fontWeight = FontWeight.Medium, lineHeight = 28.sp)
        }
        card.options.forEachIndexed { index, option ->
            OptionTile(
                number = index + 1,
                text = option,
                enabled = state.acceptingAnswers,
                result = optionResult(index, card.correctIndex, state.pickedIndex),
                onClick = { actions.onAnswer(index) }
            )
        }
    } else {
        KxCard { Text("Next question …", color = Kx.Muted, fontSize = 15.sp) }
    }

    Text(state.statusText, color = Kx.Muted, fontSize = 14.sp, textAlign = TextAlign.Center, lineHeight = 21.sp, modifier = Modifier.fillMaxWidth())
    KxButton("End session", onClick = actions.onFinish, primary = false, modifier = Modifier.fillMaxWidth())
}

/** null = not answered yet, otherwise how this option should look after the answer. */
private enum class OptionResult { CORRECT, WRONG_PICK, OTHER }

private fun optionResult(index: Int, correctIndex: Int, picked: Int?): OptionResult? = when {
    picked == null -> null
    index == correctIndex -> OptionResult.CORRECT
    index == picked -> OptionResult.WRONG_PICK
    else -> OptionResult.OTHER
}

@Composable
private fun OptionTile(number: Int, text: String, enabled: Boolean, result: OptionResult?, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    val border = when (result) {
        OptionResult.CORRECT -> Kx.Good
        OptionResult.WRONG_PICK -> Kx.Bad
        else -> Kx.Line
    }
    val fill = when (result) {
        OptionResult.CORRECT -> Kx.Good.copy(alpha = 0.14f)
        OptionResult.WRONG_PICK -> Kx.Bad.copy(alpha = 0.14f)
        else -> Kx.Surface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(fill)
            .border(if (result == null) 1.dp else 2.dp, border, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(
                    when (result) {
                        OptionResult.CORRECT -> Kx.Good
                        OptionResult.WRONG_PICK -> Kx.Bad
                        else -> Kx.BrassSoft
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                when (result) {
                    OptionResult.CORRECT -> "✓"
                    OptionResult.WRONG_PICK -> "✕"
                    else -> "$number"
                },
                color = if (result == null || result == OptionResult.OTHER) Kx.Brass else Kx.OnBrass,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text,
            color = if (result == OptionResult.OTHER) Kx.Dim else Kx.Cream,
            fontSize = 16.sp,
            lineHeight = 22.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Kx.Surface)
            .border(1.dp, Kx.Line, RoundedCornerShape(16.dp))
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, color = Kx.Cream, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Kx.Dim, fontSize = 11.sp)
    }
}

@Composable
private fun FinishedView(state: QuizScreenState, actions: QuizScreenActions) {
    val total = state.answerCount
    val fraction = if (total > 0) state.correctCount.toFloat() / total else 0f
    val percent = Math.round(fraction * 100f)
    val headline = when {
        total == 0 -> "No answers this time"
        percent == 100 && total >= 3 -> "Flawless run"
        percent >= 80 -> "Big brain energy"
        percent >= 50 -> "Solid session"
        else -> "Good practice, run it back"
    }
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        KxLabel("Session complete")
        Spacer(Modifier.height(14.dp))
        ProgressRing(
            fraction = fraction,
            size = 150.dp,
            stroke = 13.dp,
            color = if (percent >= 50) Kx.Good else Kx.Warn
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("$percent%", color = Kx.Cream, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Text("${state.correctCount} of $total", color = Kx.Muted, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(headline, color = Kx.Cream, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    }

    KxCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (state.streak > 0) "🔥" else "💤", fontSize = 30.sp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("${state.streak}-day streak", color = Kx.Cream, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "${state.answeredToday} of ${state.goal} questions today" +
                        if (state.answeredToday >= state.goal) " · goal done" else "",
                    color = Kx.Muted,
                    fontSize = 13.sp
                )
            }
        }
    }

    Text(state.statusText, color = Kx.Muted, fontSize = 13.sp, textAlign = TextAlign.Center, lineHeight = 19.sp, modifier = Modifier.fillMaxWidth())
    KxButton("Start another session", onClick = actions.onReset, modifier = Modifier.fillMaxWidth())
    KxButton("Back to Lernen", onClick = actions.onBack, primary = false, modifier = Modifier.fillMaxWidth())
}
