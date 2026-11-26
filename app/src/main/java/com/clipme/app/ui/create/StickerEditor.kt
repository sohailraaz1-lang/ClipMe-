package com.clipme.app.ui.create

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.clipme.app.model.StickerPlacement
import com.clipme.app.ui.theme.Ink
import com.clipme.app.ui.theme.InkElevated
import com.clipme.app.ui.theme.Lime
import com.clipme.app.ui.theme.PinkCam
import com.clipme.app.ui.theme.TextSecondary
import com.clipme.app.util.ImageLoaders
import java.io.File

private val EMOJI_STICKERS = listOf("🔥", "😱", "😎", "❤️", "💀", "👀", "🤣", "🏆", "🚀", "🎮")

/** A modal sticker editor: emoji palette + custom PNG import, positioned on a mini 9:16 canvas. */
@Composable
fun StickerEditor(
    current: List<StickerPlacement>,
    onSave: (List<StickerPlacement>) -> Unit,
    onDismiss: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val canvasW = 240f
    val canvasH = 426f
    var stickers by remember { mutableStateOf(current) }
    var selectedId by remember { mutableStateOf(current.lastOrNull()?.id) }
    var importError by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val saved = importPng(context, uri)
            if (saved != null) {
                val placement = StickerPlacement(
                    id = saved.first,
                    label = saved.second,
                    isEmoji = false,
                    cx = 0.7f,
                    cy = 0.35f,
                    scale = 0.22f,
                    rotationDeg = 0f
                )
                stickers = stickers + placement
                selectedId = placement.id
            } else {
                importError = true
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .background(InkElevated, RoundedCornerShape(20.dp))
                .padding(18.dp)
        ) {
            Text(
                "STICKERS",
                color = Lime,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Drag to move · tap a sticker to select · rotate with the dial",
                color = TextSecondary,
                fontSize = 11.sp
            )

            Spacer(Modifier.height(12.dp))

            // Preview canvas (9:16).
            Box(
                Modifier
                    .size(width = canvasW.dp, height = canvasH.dp)
                    .background(Ink, RoundedCornerShape(10.dp))
                    .align(Alignment.CenterHorizontally)
            ) {
                stickers.forEach { s ->
                    StickerItem(
                        placement = s,
                        canvasW = canvasW,
                        canvasH = canvasH,
                        selected = s.id == selectedId,
                        onSelect = { selectedId = s.id },
                        onMove = { new -> stickers = stickers.map { if (it.id == s.id) new else it } }
                    )
                }
                if (stickers.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No stickers yet", color = TextSecondary, fontSize = 12.sp)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Emoji palette.
            Column(
                Modifier
                    .height(120.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                EMOJI_STICKERS.chunked(5).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        row.forEach { emoji ->
                            Text(
                                emoji,
                                fontSize = 26.sp,
                                modifier = Modifier
                                    .padding(6.dp)
                                    .clickable {
                                        val placement = StickerPlacement(
                                            id = "emoji_${emoji}_${System.currentTimeMillis()}",
                                            label = emoji,
                                            isEmoji = true,
                                            cx = 0.7f,
                                            cy = 0.35f,
                                            scale = 0.25f,
                                            rotationDeg = 0f
                                        )
                                        stickers = stickers + placement
                                        selectedId = placement.id
                                    }
                            )
                        }
                    }
                }
            }

            // Rotation + scale + remove for the selected sticker.
            val sel = stickers.firstOrNull { it.id == selectedId }
            if (sel != null) {
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "SIZE ${(sel.scale * 100).toInt()}%",
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                    Row {
                        SmallButton("-", enabled = sel.scale > 0.08f) {
                            stickers = stickers.map {
                                if (it.id == sel.id) it.copy(scale = (it.scale - 0.03f).coerceAtLeast(0.05f)) else it
                            }
                        }
                        SmallButton("+", enabled = sel.scale < 0.8f) {
                            stickers = stickers.map {
                                if (it.id == sel.id) it.copy(scale = (it.scale + 0.03f).coerceAtMost(0.85f)) else it
                            }
                        }
                    }
                    Row {
                        SmallButton("⟲", enabled = true) {
                            stickers = stickers.map {
                                if (it.id == sel.id) it.copy(rotationDeg = it.rotationDeg - 15f) else it
                            }
                        }
                        SmallButton("⟳", enabled = true) {
                            stickers = stickers.map {
                                if (it.id == sel.id) it.copy(rotationDeg = it.rotationDeg + 15f) else it
                            }
                        }
                    }
                }
            }

            if (importError) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Could not import that image",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 11.sp
                )
            }

            Spacer(Modifier.height(12.dp))

            // Actions.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { launcher.launch("image/*") },
                    modifier = Modifier.weight(1f),
                    border = BorderStroke(1.dp, PinkCam)
                ) {
                    Text("IMPORT PNG", color = PinkCam, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                if (selectedId != null) {
                    OutlinedButton(
                        onClick = {
                            stickers = stickers.filterNot { it.id == selectedId }
                            selectedId = stickers.lastOrNull()?.id
                        },
                        modifier = Modifier.weight(1f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)
                    ) {
                        Text("REMOVE", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("CANCEL", color = TextSecondary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                Button(
                    onClick = { onSave(stickers) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Ink)
                ) {
                    Text("SAVE STICKERS", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun SmallButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.padding(horizontal = 2.dp),
        border = BorderStroke(1.dp, Lime.copy(alpha = 0.5f))
    ) {
        Text(label, color = if (enabled) Lime else TextSecondary, fontSize = 13.sp)
    }
}

@Composable
private fun StickerItem(
    placement: StickerPlacement,
    canvasW: Float,
    canvasH: Float,
    selected: Boolean,
    onSelect: () -> Unit,
    onMove: (StickerPlacement) -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val canvasWPx = with(density) { canvasW.dp.toPx() }.coerceAtLeast(1f)
    val canvasHPx = with(density) { canvasH.dp.toPx() }.coerceAtLeast(1f)
    val currentPlacement by rememberUpdatedState(placement)
    val currentOnMove by rememberUpdatedState(onMove)
    val currentOnSelect by rememberUpdatedState(onSelect)

    val xDp = placement.cx * canvasW
    val yDp = placement.cy * canvasH
    val sizeDp = placement.scale * canvasW

    Box(
        Modifier
            .offset(
                x = (xDp - sizeDp / 2f).dp,
                y = (yDp - sizeDp / 2f).dp
            )
            .size(sizeDp.dp)
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) PinkCam else Color.Transparent,
                RoundedCornerShape(8.dp)
            )
            .pointerInput(Unit) {
                detectTapGestures(onTap = { currentOnSelect() })
            }
            .pointerInput(canvasWPx, canvasHPx) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    val p = currentPlacement
                    val dx = dragAmount.x / canvasWPx
                    val dy = dragAmount.y / canvasHPx
                    currentOnMove(
                        p.copy(
                            cx = (p.cx + dx).coerceIn(0f, 1f),
                            cy = (p.cy + dy).coerceIn(0f, 1f)
                        )
                    )
                }
            }
    ) {
        if (placement.isEmoji) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(placement.label, fontSize = (sizeDp * 0.72f).sp)
            }
        } else {
            val bmp = remember(placement.id) {
                ImageLoaders.decodeFile(File(File(context.cacheDir, "cm_stickers"), "${placement.id}.png"))
            }
            if (bmp != null) {
                androidx.compose.foundation.Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = placement.label,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            } else {
                Box(Modifier.fillMaxSize().background(Lime.copy(alpha = 0.3f)), contentAlignment = Alignment.Center) {
                    Text("?", color = Lime)
                }
            }
        }
    }
}

/** Imports the picked image into the sticker cache and returns (id, label). */
private fun importPng(context: Context, uri: Uri): Pair<String, String>? {
    return runCatching {
        val dir = File(context.cacheDir, "cm_stickers")
        if (!dir.exists()) dir.mkdirs()
        val id = "st_${System.currentTimeMillis()}"
        val label = uri.lastPathSegment?.substringAfterLast('/')?.take(40) ?: "sticker"
        val target = File(dir, "$id.png")
        context.contentResolver.openInputStream(uri).let { input ->
            target.outputStream().use { out -> input?.use { it.copyTo(out) } }
        }
        id to label
    }.getOrNull()
}
