package com.walkietalkie.ptt.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.walkietalkie.ptt.About

/** Informazioni, autore, disclaimer e regole di buon senso (stesso testo dell'app Android). */
@Composable
fun AboutOverlay(version: String, onDismiss: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(WalkieColors.background).pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxSize()) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(72.dp).clip(CircleShape).background(WalkieColors.accent), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.SettingsInputAntenna, contentDescription = null, tint = Color.White, modifier = Modifier.size(40.dp))
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(About.APP_NAME, color = WalkieColors.Text, fontSize = 28.sp, fontWeight = FontWeight.Black)
                    Text("Versione PC $version", color = WalkieColors.Muted, fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    Text("Creata da", color = WalkieColors.Muted, fontSize = 12.sp, letterSpacing = 2.sp)
                    Text(About.AUTHOR, color = WalkieColors.Orange, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(About.credits, color = WalkieColors.Muted, fontSize = 13.sp, textAlign = TextAlign.Center)
                }
                About.sections.forEach { section ->
                    Column(Modifier.fillMaxWidth().glass(20.dp).padding(16.dp)) {
                        Text(section.title, color = WalkieColors.Text, fontWeight = FontWeight.Black, fontSize = 17.sp)
                        Spacer(Modifier.height(6.dp))
                        section.paragraphs.forEach {
                            Text(it, color = WalkieColors.Text.copy(alpha = 0.85f), fontSize = 14.sp, lineHeight = 20.sp)
                            Spacer(Modifier.height(6.dp))
                        }
                        section.bullets.forEach {
                            Row(Modifier.padding(vertical = 3.dp)) {
                                Text("•", color = WalkieColors.Orange, fontWeight = FontWeight.Black, modifier = Modifier.width(16.dp))
                                Text(it, color = WalkieColors.Text.copy(alpha = 0.85f), fontSize = 14.sp, lineHeight = 20.sp)
                            }
                        }
                    }
                }
            }
            Box(
                Modifier.padding(16.dp).fillMaxWidth().height(54.dp).clip(RoundedCornerShape(18.dp))
                    .background(WalkieColors.accent).clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Text("HO LETTO E HO CAPITO", color = Color.White, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
            }
        }
    }
}
