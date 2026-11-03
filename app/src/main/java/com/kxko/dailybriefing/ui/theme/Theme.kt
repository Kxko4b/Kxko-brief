package com.kxko.dailybriefing.ui.theme

import androidx.compose.runtime.Composable

/** App-wide theme entry point. All screens use the same Kxko charcoal/brass palette. */
@Composable
fun KxkoDailyBriefingTheme(content: @Composable () -> Unit) {
    KxkoTheme(content = content)
}
