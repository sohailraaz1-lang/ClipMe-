package com.clipme.app.ui.create

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clipme.app.export.ExportUiState
import com.clipme.app.model.CaptionStyle
import com.clipme.app.model.ExportQuality
import com.clipme.app.model.ExportSettings
import com.clipme.app.model.LayoutPreset
import com.clipme.app.model.StickerPlacement
import com.clipme.app.ui.theme.Ink
import com.clipme.app.ui.theme.InkElevated
import com.clipme.app.ui.theme.InkSoft
import com.clipme.app.ui.theme.Lime
import com.clipme.app.ui.theme.PinkCam
import com.clipme.app.ui.theme.TextFaint
import com.clipme.app.ui.theme.TextSecondary

private val STEP_3_HELP = listOf(
    "Layout presets" to "Pick how the game and cam boxes are composed. GAME boxes are lime, CAM boxes are pink.",
    "Hook text" to "A short attention-grabbing line at the top of the clip. Defaults to the stream title.",
    "Captions" to "Word-by-word captions, rendered fully on-device. Tap 'TRANSCRIBE' once; the model is reused for every clip.",
    "Stickers" to "Add emoji or your own PNG stickers — they are baked into the video."
)

/** Step 3: compose the clip — layout preset, hook, captions, stickers, quality, export. */
@Composable
fun StepLayout(
    settings: ExportSettings,
    clipCount: Int,
    transcribing: Boolean,
    captionProgress: Float,
    exportState: ExportUiState,
    onPreset: (LayoutPreset) -> Unit,
    onHideCam: (Boolean) -> Unit,
    onHookText: (String) -> Unit,
    onHookVisible: (Boolean) -> Unit,
    onCaptionsOn: (Boolean) -> Unit,
    onCaptionStyle: (CaptionStyle) -> Unit,
    onTranscribe: () -> Unit,
    onQuality: (ExportQuality) -> Unit,
    onStickers: (List<StickerPlacement>) -> Unit,
    onExport: () -> Unit,
    onCancelExport: () -> Unit,
    onBack: () -> Unit
) {
    var showHelp by remember { mutableStateOf(false) }
    var showStickers by remember { mutableStateOf(false) }

    if (showHelp) {
        HelpModal("STEP 3 — LAYOUT", STEP_3_HELP, onDismiss = { showHelp = false })
    }

    Column(Modifier.fillMaxSize()) {
        StepHeader("STEP 3", "Layout", onHelp = { showHelp = true })

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            // Preset grid
            Text(
                "LAYOUT PRESET",
                color = TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            PresetGrid(
                selected = settings.preset,
                onSelect = onPreset,
                hideCam = settings.hideCam,
                onHideCam = onHideCam
            )

            // Hook text
            Spacer(Modifier.height(20.dp))
            SectionHeader("HOOK TEXT", toggle = settings.hookVisible, onToggle = onHookVisible)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = settings.hookText ?: "",
                onValueChange = onHookText,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("A line that makes people stop scrolling", color = TextFaint) },
                maxLines = 2,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Lime,
                    unfocusedBorderColor = TextFaint,
                    cursorColor = Lime,
                    focusedTextColor = MaterialTheme.colorScheme.onBackground,
                    unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
                    focusedContainerColor = InkElevated,
                    unfocusedContainerColor = InkElevated
                )
            )

            // Captions
            Spacer(Modifier.height(20.dp))
            SectionHeader("CAPTIONS", toggle = settings.captionsOn, onToggle = onCaptionsOn)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CaptionStyle.values().forEach { style ->
                    StyleChip(
                        label = style.label,
                        selected = settings.captionStyle == style,
                        onClick = { onCaptionStyle(style) }
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onTranscribe,
                enabled = settings.captionsOn && !transcribing,
                modifier = Modifier.fillMaxWidth().height(46.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = InkSoft,
                    contentColor = Lime
                )
            ) {
                if (transcribing) {
                    Text("TRANSCRIBING… ${(captionProgress * 100).toInt()}%", fontWeight = FontWeight.Bold)
                } else {
                    Text(
                        if (settings.captionLines.isNotEmpty()) "RE-TRANSCRIBE CAPTIONS" else "TRANSCRIBE CAPTIONS",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }

            // Stickers
            Spacer(Modifier.height(20.dp))
            SectionHeader("STICKERS", null, null)
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    onClick = { showStickers = true },
                    shape = RoundedCornerShape(12.dp),
                    color = InkSoft,
                    border = BorderStroke(1.dp, Lime)
                ) {
                    Text(
                        if (settings.stickers.isEmpty()) "+ ADD STICKER" else "+ ${settings.stickers.size} STICKER(S)",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        color = Lime,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
                if (settings.stickers.isNotEmpty()) {
                    Surface(
                        onClick = { onStickers(emptyList()) },
                        shape = RoundedCornerShape(12.dp),
                        color = InkSoft,
                        border = BorderStroke(1.dp, PinkCam)
                    ) {
                        Text(
                            "CLEAR",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            color = PinkCam,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            // Quality
            Spacer(Modifier.height(20.dp))
            SectionHeader("EXPORT QUALITY", null, null)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExportQuality.values().forEach { q ->
                    StyleChip(
                        label = q.id,
                        selected = settings.quality == q,
                        onClick = { onQuality(q) },
                        accent = PinkCam
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
        }

        // Export footer
        ExportFooter(
            clipCount = clipCount,
            exportState = exportState,
            onExport = onExport,
            onCancelExport = onCancelExport,
            onBack = onBack
        )
    }

    if (showStickers) {
        StickerEditor(
            current = settings.stickers,
            onSave = { stickers ->
                onStickers(stickers)
                showStickers = false
            },
            onDismiss = { showStickers = false }
        )
    }
}

@Composable
private fun SectionHeader(
    label: String,
    toggle: Boolean?,
    onToggle: ((Boolean) -> Unit)?
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        if (toggle != null && onToggle != null) {
            Switch(
                checked = toggle,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Ink,
                    checkedTrackColor = Lime,
                    uncheckedThumbColor = TextSecondary,
                    uncheckedTrackColor = InkSoft
                )
            )
        }
    }
}

@Composable
private fun PresetGrid(
    selected: LayoutPreset,
    onSelect: (LayoutPreset) -> Unit,
    hideCam: Boolean,
    onHideCam: (Boolean) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LayoutPreset.values().chunked(3).forEach { rowPresets ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowPresets.forEach { preset ->
                    PresetCell(
                        preset = preset,
                        selected = selected == preset,
                        onClick = { onSelect(preset) }
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(6.dp))
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            "HIDE CAM BOX",
            color = TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
        Switch(
            checked = hideCam,
            onCheckedChange = onHideCam,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Ink,
                checkedTrackColor = PinkCam,
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = InkSoft
            )
        )
    }
}

@Composable
private fun PresetCell(
    preset: LayoutPreset,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.weight(1f),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) Lime.copy(alpha = 0.16f) else InkElevated,
        border = BorderStroke(1.dp, if (selected) Lime else TextFaint.copy(alpha = 0.4f))
    ) {
        Column(
            Modifier.padding(vertical = 14.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            PresetGlyph(preset, selected)
            Spacer(Modifier.height(8.dp))
            Text(
                preset.label,
                color = if (selected) Lime else TextSecondary,
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                maxLines = 2
            )
        }
    }
}

/** Tiny visual glyph of the preset layout (game area lime, cam pink). */
@Composable
private fun PresetGlyph(preset: LayoutPreset, selected: Boolean) {
    val border = if (selected) Lime else TextFaint.copy(alpha = 0.6f)
    Box(Modifier.width(44.dp).height(58.dp).background(Ink, RoundedCornerShape(4.dp)).padding(3.dp)) {
        when (preset) {
            LayoutPreset.GAME_ONLY -> Box(
                Modifier.fillMaxSize().background(Lime.copy(alpha = 0.55f), RoundedCornerShape(2.dp))
            )
            LayoutPreset.SPLIT -> {
                Box(Modifier.fillMaxSize().background(Lime.copy(alpha = 0.55f), RoundedCornerShape(2.dp)))
                Box(
                    Modifier.align(Alignment.BottomEnd).size(width = 16.dp, height = 16.dp)
                        .background(PinkCam.copy(alpha = 0.7f), RoundedCornerShape(3.dp))
                        .padding(1.dp)
                )
            }
            LayoutPreset.CIRCLE_CAM -> {
                Box(Modifier.fillMaxSize().background(Lime.copy(alpha = 0.55f), RoundedCornerShape(2.dp)))
                Box(
                    Modifier.align(Alignment.BottomEnd).size(14.dp)
                        .background(PinkCam, CircleShape)
                )
            }
            LayoutPreset.ROUNDED_CAM -> {
                Box(Modifier.fillMaxSize().background(Lime.copy(alpha = 0.55f), RoundedCornerShape(2.dp)))
                Box(
                    Modifier.align(Alignment.BottomEnd).size(width = 16.dp, height = 16.dp)
                        .background(PinkCam, RoundedCornerShape(5.dp))
                )
            }
            LayoutPreset.BLUR -> {
                Box(
                    Modifier.fillMaxSize().background(Lime.copy(alpha = 0.25f), RoundedCornerShape(2.dp))
                )
                Box(
                    Modifier.fillMaxSize().padding(6.dp)
                        .background(Lime.copy(alpha = 0.55f), RoundedCornerShape(2.dp))
                )
                Box(
                    Modifier.align(Alignment.BottomEnd).size(14.dp)
                        .background(PinkCam, CircleShape)
                )
            }
            LayoutPreset.BIG_CAM -> {
                Box(Modifier.fillMaxWidth().height(28.dp).background(PinkCam.copy(alpha = 0.7f), RoundedCornerShape(2.dp)))
                Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(24.dp)
                        .background(Lime.copy(alpha = 0.55f), RoundedCornerShape(2.dp))
                )
            }
            LayoutPreset.CAM_GAME -> {
                Box(Modifier.fillMaxWidth().height(20.dp).background(PinkCam.copy(alpha = 0.7f), RoundedCornerShape(2.dp)))
                Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(32.dp)
                        .background(Lime.copy(alpha = 0.55f), RoundedCornerShape(2.dp))
                )
            }
        }
    }
}

@Composable
private fun StyleChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    accent: Color = Lime
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.weight(1f),
        shape = RoundedCornerShape(10.dp),
        color = if (selected) accent.copy(alpha = 0.16f) else InkElevated,
        border = BorderStroke(1.dp, if (selected) accent else TextFaint.copy(alpha = 0.4f))
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            color = if (selected) accent else TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
private fun ExportFooter(
    clipCount: Int,
    exportState: ExportUiState,
    onExport: () -> Unit,
    onCancelExport: () -> Unit,
    onBack: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        if (exportState.running) {
            Column {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "CLIP IT — $clipCount CLIPS",
                        color = Lime,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    Text(
                        "${exportState.overallPercent}%",
                        color = TextSecondary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
                Spacer(Modifier.height(6.dp))
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { exportState.overallPercent / 100f },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = Lime,
                    trackColor = InkSoft
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Clip ${exportState.current + 1} of ${exportState.total} · ${exportState.message}",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onCancelExport,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = InkSoft, contentColor = PinkCam)
                ) {
                    Text("CANCEL EXPORT", fontWeight = FontWeight.Bold)
                }
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(
                    onClick = onBack,
                    modifier = Modifier.weight(0.4f).height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = InkElevated,
                    border = BorderStroke(1.dp, Lime)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("BACK", color = Lime, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
                Button(
                    onClick = onExport,
                    enabled = clipCount > 0,
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Ink)
                ) {
                    Text("CLIP IT — $clipCount", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
