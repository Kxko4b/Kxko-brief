package com.kxko.dailybriefing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kxko.dailybriefing.ui.theme.Kx
import com.kxko.dailybriefing.ui.theme.KxButton
import com.kxko.dailybriefing.ui.theme.KxCard

/** "Update available" card. Draws nothing when there is no newer version. */
@Composable
fun UpdateBanner(controller: UpdateController, modifier: Modifier = Modifier) {
    val info = controller.info ?: return
    KxCard(modifier = modifier, accent = true) {
        Text("Update available", color = Kx.Brass, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(4.dp))
        Text(info.versionName, color = Kx.Cream, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        controller.message?.let {
            Spacer(Modifier.height(2.dp))
            Text(it, color = Kx.Muted, fontSize = 13.sp)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            KxButton(
                when (controller.stage) {
                    1 -> "Downloading …"
                    2 -> "Install"
                    else -> "Update"
                },
                onClick = { if (controller.stage == 2) controller.install() else controller.startDownload() },
                enabled = controller.stage != 1,
                modifier = Modifier.weight(1f)
            )
            KxButton("Later", onClick = { controller.dismiss() }, primary = false)
        }
    }
}
