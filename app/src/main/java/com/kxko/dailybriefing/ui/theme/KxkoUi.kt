package com.kxko.dailybriefing.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The Kxko look: warm dark surfaces with a brass accent. One place so every native screen matches. */
object Kx {
    val Bg = Color(0xFF111315)
    val Surface = Color(0xFF1A1D20)
    val SurfaceHi = Color(0xFF22262A)
    val Line = Color(0xFF2C3034)
    val Brass = Color(0xFFC5A16B)
    val BrassSoft = Color(0x26C5A16B)
    val OnBrass = Color(0xFF201C15)
    val Cream = Color(0xFFF0EEE9)
    val Muted = Color(0xFFB9B5AB)
    val Dim = Color(0xFF7D7A72)
    val Good = Color(0xFF7BC47F)
    val Bad = Color(0xFFE57373)
    val Warn = Color(0xFFF0C541)
    val Ember = Color(0xFFFF8A3D)
}

@Composable
fun KxkoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Kx.Brass,
            background = Kx.Bg,
            surface = Kx.Surface,
            onBackground = Kx.Cream,
            onSurface = Kx.Cream
        ),
        content = content
    )
}

@Composable
fun KxCard(
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(22.dp)
    var m = modifier
        .fillMaxWidth()
        .clip(shape)
        .background(if (accent) Kx.SurfaceHi else Kx.Surface)
        .border(BorderStroke(1.dp, if (accent) Kx.Brass.copy(alpha = 0.45f) else Kx.Line), shape)
    if (onClick != null) m = m.clickable(onClick = onClick)
    Column(modifier = m.padding(18.dp), content = content)
}

@Composable
fun KxLabel(text: String, modifier: Modifier = Modifier, color: Color = Kx.Brass) {
    Text(
        text.uppercase(),
        modifier = modifier,
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 2.sp
    )
}

@Composable
fun KxChip(text: String, background: Color, foreground: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(background)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text, color = foreground, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun KxButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    enabled: Boolean = true
) {
    val shape = RoundedCornerShape(16.dp)
    val bg = when {
        !enabled -> Kx.SurfaceHi
        primary -> Kx.Brass
        else -> Kx.SurfaceHi
    }
    val fg = when {
        !enabled -> Kx.Dim
        primary -> Kx.OnBrass
        else -> Kx.Cream
    }
    Box(
        modifier = modifier
            .clip(shape)
            .background(bg)
            .then(if (!primary && enabled) Modifier.border(BorderStroke(1.dp, Kx.Line), shape) else Modifier)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    }
}

/** A ring that animates to [fraction] (0..1) with [center] drawn inside it. */
@Composable
fun ProgressRing(
    fraction: Float,
    size: Dp,
    stroke: Dp,
    color: Color,
    modifier: Modifier = Modifier,
    track: Color = Kx.Line,
    center: @Composable () -> Unit = {}
) {
    val animated by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 700),
        label = "ring"
    )
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(size)) {
            val strokePx = stroke.toPx()
            val inset = strokePx / 2f
            val arcSize = Size(this.size.width - strokePx, this.size.height - strokePx)
            drawArc(
                color = track,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = strokePx)
            )
            if (animated > 0f) {
                drawArc(
                    color = color,
                    startAngle = -90f,
                    sweepAngle = 360f * animated,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round)
                )
            }
        }
        center()
    }
}

/** Mastered / shaky / new split of an exam's questions as one rounded bar. */
@Composable
fun StackedBar(mastered: Int, shaky: Int, fresh: Int, modifier: Modifier = Modifier) {
    val total = mastered + shaky + fresh
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(50))
            .background(Kx.Line),
        horizontalArrangement = Arrangement.Start
    ) {
        if (total > 0) {
            if (mastered > 0) Box(Modifier.weight(mastered.toFloat()).height(10.dp).background(Kx.Good))
            if (shaky > 0) Box(Modifier.weight(shaky.toFloat()).height(10.dp).background(Kx.Warn))
            if (fresh > 0) Box(Modifier.weight(fresh.toFloat()).height(10.dp).background(Kx.Dim.copy(alpha = 0.55f)))
        }
    }
}

/** Bottom navigation shared by the dashboard and the study hub. 0 = Dashboard, 1 = Lernen, 2 = Quiz. */
@Composable
fun KxBottomBar(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val items = listOf("📊" to "Dashboard", "🎓" to "Lernen", "🚲" to "Quiz")
    Column(modifier = modifier.fillMaxWidth().background(Kx.Surface)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Kx.Line))
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            items.forEachIndexed { index, item ->
                val active = index == selected
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onSelect(index) }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (active) Kx.BrassSoft else Color.Transparent)
                            .padding(horizontal = 18.dp, vertical = 2.dp)
                    ) {
                        Text(item.first, fontSize = 18.sp)
                    }
                    Text(
                        item.second,
                        color = if (active) Kx.Brass else Kx.Muted,
                        fontSize = 11.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
}
