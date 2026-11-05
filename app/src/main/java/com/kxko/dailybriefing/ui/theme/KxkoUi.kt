package com.kxko.dailybriefing.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shared Kxko design system: calm charcoal surfaces, warm brass and clear status colours. */
object Kx {
    // Neutral graphite surfaces with restrained burnt-orange accents.
    val Bg = Color(0xFF151617)
    val Surface = Color(0xFF202224)
    val SurfaceHi = Color(0xFF2A2D30)
    val SurfaceElevated = Color(0xFF34383B)
    val Line = Color(0xFF41464A)
    val Brass = Color(0xFFB85E2E)
    val BrassBright = Color(0xFFD07843)
    val BrassSoft = Color(0x26B85E2E)
    val OnBrass = Color(0xFFFFFFFF)
    val Cream = Color(0xFFE7E9EA)
    val Muted = Color(0xFFB9BEC1)
    val Dim = Color(0xFF858C90)
    val Good = Color(0xFFD0D4D6)
    val Bad = Color(0xFFE18A78)
    val Warn = Color(0xFFD6A06E)
    val Ember = Color(0xFFC66B3B)
    val IconGrey = Color(0xFFD7DBDD)
    val RouteOrange = Color(0xFFB85E2E)
    val MapSurface = Color(0xFF292D30)
    val MapGrid = Color(0xFF3B4145)
    val MapLabel = Color(0xFFB9BEC1)
}

@Composable
fun KxkoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Kx.Brass, onPrimary = Kx.OnBrass,
            secondary = Kx.BrassBright, onSecondary = Kx.OnBrass,
            tertiary = Kx.Good, background = Kx.Bg, onBackground = Kx.Cream,
            surface = Kx.Surface, onSurface = Kx.Cream,
            surfaceVariant = Kx.SurfaceHi, onSurfaceVariant = Kx.Muted,
            outline = Kx.Line, error = Kx.Bad, onError = Color.White
        ),
        typography = Typography,
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
    val shape = RoundedCornerShape(20.dp)
    var m = modifier.fillMaxWidth()
        .shadow(if (accent) 5.dp else 2.dp, shape, clip = false)
        .clip(shape)
        .background(if (accent) Brush.linearGradient(listOf(Kx.SurfaceHi, Kx.Surface)) else Brush.linearGradient(listOf(Kx.Surface, Kx.Surface.copy(alpha = 0.96f))))
        .border(BorderStroke(1.dp, if (accent) Kx.Brass.copy(alpha = 0.48f) else Kx.Line.copy(alpha = 0.85f)), shape)
    if (onClick != null) m = m.clickable(role = Role.Button, onClick = onClick)
    Column(modifier = m.padding(18.dp), content = content)
}

@Composable
fun KxLabel(text: String, modifier: Modifier = Modifier, color: Color = Kx.Brass) {
    Text(text.uppercase(), modifier = modifier, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.65.sp, lineHeight = 14.sp)
}

@Composable
fun KxChip(text: String, background: Color, foreground: Color, modifier: Modifier = Modifier) {
    Box(modifier = modifier.clip(RoundedCornerShape(50)).background(background)
        .border(1.dp, foreground.copy(alpha = 0.13f), RoundedCornerShape(50))
        .padding(horizontal = 11.dp, vertical = 6.dp)) {
        Text(text, color = foreground, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, lineHeight = 14.sp)
    }
}

@Composable
fun KxButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = true, enabled: Boolean = true) {
    val shape = RoundedCornerShape(15.dp)
    val bg = when { !enabled -> Kx.SurfaceHi; primary -> Kx.Brass; else -> Kx.SurfaceHi }
    val fg = when { !enabled -> Kx.Dim; primary -> Kx.OnBrass; else -> Kx.Cream }
    Box(modifier = modifier.defaultMinSize(minHeight = 48.dp).clip(shape).background(bg)
        .then(if (!primary || !enabled) Modifier.border(BorderStroke(1.dp, Kx.Line), shape) else Modifier)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 18.dp, vertical = 13.dp), contentAlignment = Alignment.Center) {
        Text(text, color = fg, fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    }
}

@Composable
fun ProgressRing(fraction: Float, size: Dp, stroke: Dp, color: Color, modifier: Modifier = Modifier, track: Color = Kx.Line, center: @Composable () -> Unit = {}) {
    val animated by animateFloatAsState(targetValue = fraction.coerceIn(0f, 1f), animationSpec = tween(durationMillis = 700), label = "ring")
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(size)) {
            val px = stroke.toPx()
            val inset = px / 2f
            val arc = Size(this.size.width - px, this.size.height - px)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(width = px))
            if (animated > 0f) drawArc(color, -90f, 360f * animated, false, Offset(inset, inset), arc, style = Stroke(width = px, cap = StrokeCap.Round))
        }
        center()
    }
}

@Composable
fun StackedBar(mastered: Int, shaky: Int, fresh: Int, modifier: Modifier = Modifier) {
    val total = mastered + shaky + fresh
    Row(modifier = modifier.fillMaxWidth().height(9.dp).clip(RoundedCornerShape(50)).background(Kx.Line), horizontalArrangement = Arrangement.Start) {
        if (total > 0) {
            if (mastered > 0) Box(Modifier.weight(mastered.toFloat()).height(9.dp).background(Kx.Good))
            if (shaky > 0) Box(Modifier.weight(shaky.toFloat()).height(9.dp).background(Kx.Warn))
            if (fresh > 0) Box(Modifier.weight(fresh.toFloat()).height(9.dp).background(Kx.Dim.copy(alpha = 0.55f)))
        }
    }
}

/** Shared bottom navigation with a restrained selected state and consistent touch targets. */
@Composable
fun KxBottomBar(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val items = listOf("⌂" to "Dashboard", "▤" to "Study", "✓" to "Quiz", "↗" to "Cycling")
    Column(modifier = modifier.fillMaxWidth().background(Kx.Surface)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Kx.Line))
        Row(Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            items.forEachIndexed { index, item ->
                val active = index == selected
                Column(modifier = Modifier.weight(1f).clip(RoundedCornerShape(15.dp))
                    .clickable(role = Role.Tab) { onSelect(index) }.padding(vertical = 5.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Box(Modifier.clip(RoundedCornerShape(11.dp)).background(if (active) Kx.BrassSoft else Color.Transparent).padding(horizontal = 15.dp, vertical = 3.dp)) {
                        Text(item.first, color = if (active) Kx.BrassBright else Kx.Muted, fontSize = 19.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text(item.second, color = if (active) Kx.BrassBright else Kx.Dim, fontSize = 10.sp, lineHeight = 13.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium)
                }
            }
        }
    }
}
