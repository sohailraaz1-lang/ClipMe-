package com.clipme.app.ui.create

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clipme.app.model.ClipRange
import com.clipme.app.ui.theme.Ink
import com.clipme.app.ui.theme.InkElevated
import com.clipme.app.ui.theme.Lime
import com.clipme.app.ui.theme.PinkCam
import com.clipme.app.ui.theme.TextSecondary
import com.clipme.app.util.FileUtils

private val STEP_2_HELP = listOf(
    "Add clips" to "Tap the timeline to add a clip. Drag the handles to resize, or drag the body to move it.",
    "Clip limits" to "Each clip is 5 s – 3 min. Clips may not overlap.",
    "Automatic captions" to "Toggle captions in Step 3 — the model is downloaded once, then runs fully offline.",
    "No re-encoding" to "Clips are cut from the downloaded window at export time — nothing is exported twice."
)

/** Step 2: mark the clip windows on the downloaded stream timeline. */
@Composable
fun StepClips(
    windowDurationMs: Long,
    ranges: List<ClipRange>,
    selectedRange: Int,
    makingClips: Boolean,
    onAddRange: (Long, Long) -> Unit,
    onUpdateRange: (Int, Long, Long) -> Unit,
    onRemoveRange: (Int) -> Unit,
    onSelectRange: (Int) -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
    onExportNow: () -> Unit
) {
    var showHelp by remember { mutableStateOf(false) }
    if (showHelp) {
        HelpModal("STEP 2 — CLIPS", STEP_2_HELP, onDismiss = { showHelp = false })
    }

    Column(Modifier.fillMaxSize()) {
        StepHeader("STEP 2", "Clips", onHelp = { showHelp = true })

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            // Timeline
            TimelineBar(
                windowDurationMs = windowDurationMs,
                ranges = ranges,
                selectedRange = selectedRange,
                onAddRange = onAddRange,
                onUpdateRange = onUpdateRange,
                onSelectRange = onSelectRange
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Drag to move · handles resize · tap a clip to select",
                color = TextSecondary,
                fontSize = 12.sp
            )

            // Clip list
            Spacer(Modifier.height(16.dp))
            if (ranges.isEmpty()) {
                Box(
                    Modifier.fillMaxWidth().background(InkElevated, RoundedCornerShape(14.dp)).padding(28.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Tap the timeline to add your first clip", color = TextSecondary, textAlign = TextAlign.Center)
                }
            } else {
                ranges.forEachIndexed { index, range ->
                    ClipRow(
                        index = index,
                        range = range,
                        selected = index == selectedRange,
                        onSelect = { onSelectRange(index) },
                        onRemove = { onRemoveRange(index) }
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        if (makingClips) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(color = Lime, strokeWidth = 3.dp, modifier = Modifier.height(20.dp).width(20.dp))
                Spacer(Modifier.width(12.dp))
                Text("MAKING CLIPS…", color = Lime, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedAction(
                label = "BACK",
                onClick = onBack,
                modifier = Modifier.weight(0.4f)
            )
            if (ranges.isNotEmpty()) {
                OutlinedAction(
                    label = "EXPORT",
                    onClick = onExportNow,
                    modifier = Modifier.weight(0.4f),
                    accent = PinkCam
                )
            }
            Button(
                onClick = onContinue,
                enabled = ranges.isNotEmpty() && !makingClips,
                modifier = Modifier.weight(1f).height(50.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Ink)
            ) {
                Text("NEXT — LAYOUT", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun OutlinedAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = Lime
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.height(50.dp),
        color = InkElevated,
        border = BorderStroke(1.dp, accent)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, color = accent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }
}

/** Interactive strip showing the whole window with clip segments drawn on it. */
@Composable
private fun TimelineBar(
    windowDurationMs: Long,
    ranges: List<ClipRange>,
    selectedRange: Int,
    onAddRange: (Long, Long) -> Unit,
    onUpdateRange: (Int, Long, Long) -> Unit,
    onSelectRange: (Int) -> Unit
) {
    val trackH = 72.dp
    val duration = windowDurationMs.coerceAtLeast(1L)

    Box(
        Modifier
            .fillMaxWidth()
            .height(trackH)
            .background(InkElevated, RoundedCornerShape(12.dp))
    ) {
        val currentDuration by rememberUpdatedState(duration)
        val currentOnAddRange by rememberUpdatedState(onAddRange)

        // Base "empty" area — tapping adds a 30s clip.
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Transparent)
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val dur = currentDuration
                        val frac = (offset.x / this.size.width.toFloat()).coerceIn(0f, 1f)
                        val start = (frac * dur).toLong()
                        currentOnAddRange(start, minOf(start + 30_000L, dur))
                    }
                }
        )

        ranges.forEachIndexed { index, range ->
            ClipSegment(
                index = index,
                range = range,
                duration = duration,
                selected = index == selectedRange,
                onSelect = { onSelectRange(index) },
                onUpdate = { newStart, newEnd -> onUpdateRange(index, newStart, newEnd) }
            )
        }
    }
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("0:00", color = TextSecondary, fontSize = 11.sp)
        Text(FileUtils.formatClock(windowDurationMs), color = TextSecondary, fontSize = 11.sp)
    }
}

@Composable
private fun ClipSegment(
    index: Int,
    range: ClipRange,
    duration: Long,
    selected: Boolean,
    onSelect: () -> Unit,
    onUpdate: (Long, Long) -> Unit
) {
    val wFrac = (range.durationMs.toFloat() / duration).coerceIn(0f, 1f)
    val lFrac = (range.startMs.toFloat() / duration).coerceIn(0f, 1f)
    val color = if (selected) PinkCam else Lime
    val density = LocalDensity.current
    val currentRange by rememberUpdatedState(range)
    val currentDuration by rememberUpdatedState(duration)
    val currentOnUpdate by rememberUpdatedState(onUpdate)

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val trackW = this.maxWidth
        val leftDp = lFrac * trackW.value
        val widthDp = wFrac * trackW.value
        val trackPx = with(density) { trackW.toPx() }.coerceAtLeast(1f)

        Box(
            Modifier
                .offset(x = leftDp.dp, y = 0.dp)
                .width(widthDp.dp)
                .height(72.dp)
                .background(color.copy(alpha = if (selected) 0.35f else 0.2f))
                .border(2.dp, color, RoundedCornerShape(8.dp))
                .clickable { onSelect() }
                .pointerInput(trackPx) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        val r = currentRange
                        val dur = currentDuration
                        val deltaMs = ((dragAmount.x / trackPx) * dur.toFloat()).toLong()
                        val maxStart = (dur - r.durationMs).coerceAtLeast(0L)
                        val newStart = (r.startMs + deltaMs).coerceIn(0L, maxStart)
                        currentOnUpdate(newStart, newStart + r.durationMs)
                    }
                }
        ) {
            // Left edge handle resizes startMs.
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .width(12.dp)
                    .fillMaxHeight()
                    .background(color)
                    .pointerInput(trackPx) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            val r = currentRange
                            val dur = currentDuration
                            val deltaMs = ((dragAmount.x / trackPx) * dur.toFloat()).toLong()
                            val maxStart = (r.endMs - ClipRange.MIN_MS).coerceAtLeast(0L)
                            val minStart = (r.endMs - ClipRange.MAX_MS).coerceAtLeast(0L)
                            val newStart = (r.startMs + deltaMs).coerceIn(minStart, maxStart)
                            currentOnUpdate(newStart, r.endMs)
                        }
                    }
            )
            // Right edge handle resizes endMs.
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .width(12.dp)
                    .fillMaxHeight()
                    .background(color)
                    .pointerInput(trackPx) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            val r = currentRange
                            val dur = currentDuration
                            val deltaMs = ((dragAmount.x / trackPx) * dur.toFloat()).toLong()
                            val minEnd = (r.startMs + ClipRange.MIN_MS).coerceAtMost(dur)
                            val maxEnd = (r.startMs + ClipRange.MAX_MS).coerceAtMost(dur)
                            val newEnd = (r.endMs + deltaMs).coerceIn(minEnd, maxEnd)
                            currentOnUpdate(r.startMs, newEnd)
                        }
                    }
            )
            Text(
                "#${index + 1}",
                color = color,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

@Composable
private fun ClipRow(
    index: Int,
    range: ClipRange,
    selected: Boolean,
    onSelect: () -> Unit,
    onRemove: () -> Unit
) {
    Surface(
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),        shape = RoundedCornerShape(12.dp),
        color = if (selected) InkElevated.copy(alpha = 0.8f) else InkElevated
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "CLIP ${index + 1}",
                color = if (selected) PinkCam else Lime,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "${FileUtils.formatClock(range.startMs)} — ${FileUtils.formatClock(range.endMs)}",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    "${range.durationMs / 1000}s",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }
            Text(
                "✕",
                color = TextSecondary,
                fontSize = 16.sp,
                modifier = Modifier.padding(8.dp).clickable(onClick = onRemove)
            )
        }
    }
}
