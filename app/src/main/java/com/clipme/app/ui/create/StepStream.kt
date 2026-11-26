package com.clipme.app.ui.create

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clipme.app.model.NormRect
import com.clipme.app.model.Platform
import com.clipme.app.model.StreamInfo
import com.clipme.app.resolve.LoadState
import com.clipme.app.ui.theme.Ink
import com.clipme.app.ui.theme.InkElevated
import com.clipme.app.ui.theme.Lime
import com.clipme.app.ui.theme.PinkCam
import com.clipme.app.ui.theme.TextFaint
import com.clipme.app.ui.theme.TextSecondary
import com.clipme.app.ui.theme.TwitchPurple
import com.clipme.app.ui.theme.YtRed
import kotlin.math.roundToInt

private val STEP_1_HELP = listOf(
    "Paste any link" to "YouTube videos, YouTube Live, Twitch streams or Twitch VODs (twitch.tv/videos/…).",
    "What happens next" to "ClipMe resolves the stream and grabs a preview frame — nothing is downloaded yet.",
    "Private & on-device" to "Everything — extraction, captions and export — runs on your phone. No accounts, no cloud."
)

/** Step 1: paste a link → resolve → see the frame → download the window. */
@Composable
fun StepStream(
    url: String,
    loadState: LoadState,
    stream: StreamInfo?,
    onUrlChange: (String) -> Unit,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onContinue: () -> Unit,
    downloading: Boolean,
    downloadProgress: Float,
    frame: android.graphics.Bitmap?,
    gameRect: NormRect,
    camRect: NormRect?,
    onGameRect: (NormRect) -> Unit,
    onCamRect: (NormRect) -> Unit,
    sourceLoaded: Boolean
) {
    var showHelp by remember { mutableStateOf(false) }
    var selectingCam by remember { mutableStateOf(false) }

    if (showHelp) {
        HelpModal("STEP 1 — STREAM", STEP_1_HELP, onDismiss = { showHelp = false })
    }

    Column(Modifier.fillMaxSize()) {
        StepHeader("STEP 1", "Stream", onHelp = { showHelp = true })

        when (val ls = loadState) {
            is LoadState.Idle -> IdleContent(url, onUrlChange, onCheck)

            is LoadState.CheckingLink -> LoadingRow("CHECKING THE LINK…")

            is LoadState.GettingFrame -> LoadingRow("GETTING A FRAME…")

            is LoadState.Failed -> FailedContent(ls.message, onCheck)

            is LoadState.Done -> StreamContent(
                info = ls.info,
                frame = frame,
                downloading = downloading,
                downloadProgress = downloadProgress,
                onDownload = onDownload,
                onContinue = onContinue,
                gameRect = gameRect,
                camRect = camRect,
                onGameRect = onGameRect,
                onCamRect = onCamRect,
                selectingCam = selectingCam,
                onToggleSelect = { selectingCam = !selectingCam },
                sourceLoaded = sourceLoaded
            )
        }
    }
}

@Composable
private fun IdleContent(url: String, onUrlChange: (String) -> Unit, onCheck: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Paste a YouTube or Twitch link",
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = url,
            onValueChange = onUrlChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("https://youtube.com/… or twitch.tv/…", color = TextFaint) },
            singleLine = true,
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
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onCheck,
            enabled = url.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Ink)
        ) {
            Text("CHECK THE LINK", fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(14.dp))
        Text("100% on-device · no watermark", color = TextFaint, fontSize = 12.sp)
    }
}

@Composable
private fun LoadingRow(label: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Lime, strokeWidth = 3.dp)
            Spacer(Modifier.height(16.dp))
            Text(label, color = TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun FailedContent(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("⚠️", fontSize = 40.sp)
        Spacer(Modifier.height(12.dp))
        Text(message, color = MaterialTheme.colorScheme.error, fontSize = 14.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Ink)
        ) {
            Text("TRY AGAIN", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun StreamContent(
    info: StreamInfo,
    frame: android.graphics.Bitmap?,
    downloading: Boolean,
    downloadProgress: Float,
    onDownload: () -> Unit,
    onContinue: () -> Unit,
    gameRect: NormRect,
    camRect: NormRect?,
    onGameRect: (NormRect) -> Unit,
    onCamRect: (NormRect) -> Unit,
    selectingCam: Boolean,
    onToggleSelect: () -> Unit,
    sourceLoaded: Boolean
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(
            Modifier.fillMaxWidth().background(InkElevated, RoundedCornerShape(16.dp)).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val badgeColor = if (info.platform == Platform.YOUTUBE) YtRed else TwitchPurple
            Text(
                info.platform.label,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                modifier = Modifier
                    .background(badgeColor, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    info.title,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    maxLines = 2
                )
                Spacer(Modifier.height(4.dp))
                Text("${info.uploader} · ${info.badge}", color = TextSecondary, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(14.dp))

        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(InkElevated, RoundedCornerShape(16.dp))
        ) {
            if (frame != null) {
                FrameSelector(
                    frame = frame,
                    gameRect = gameRect,
                    camRect = camRect,
                    onGameRect = onGameRect,
                    onCamRect = onCamRect
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Frame preview", color = TextFaint)
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Chip("GAME", selected = !selectingCam, color = Lime, onClick = { selectingCam = false })
            if (camRect != null) {
                Chip("CAM", selected = selectingCam, color = PinkCam, onClick = { selectingCam = true })
            }
        }
        Spacer(Modifier.height(12.dp))

        if (sourceLoaded) {
            Button(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Ink)
            ) {
                Text("NEXT — MAKE CLIPS", fontWeight = FontWeight.Bold)
            }
        } else if (downloading) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    progress = downloadProgress.coerceIn(0f, 1f),
                    color = Lime,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "DOWNLOADING WINDOW… ${(downloadProgress * 100).roundToInt()}%",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
            }
        } else {
            Button(
                onClick = onDownload,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Ink)
            ) {
                Text("DOWNLOAD STREAM WINDOW", fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, color: Color, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) color.copy(alpha = 0.2f) else InkElevated,
        border = BorderStroke(1.dp, if (selected) color else TextFaint)
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
            color = if (selected) color else TextSecondary,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp
        )
    }
}

/** Frame preview with draggable GAME (lime) and CAM (pink) boxes. */
@Composable
private fun FrameSelector(
    frame: android.graphics.Bitmap,
    gameRect: NormRect,
    camRect: NormRect?,
    onGameRect: (NormRect) -> Unit,
    onCamRect: (NormRect) -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Image(
            bitmap = frame.asImageBitmap(),
            contentDescription = "Stream frame",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )
        DraggableRect(
            rect = gameRect,
            color = Lime,
            label = "GAME",
            onChange = onGameRect
        )
        if (camRect != null) {
            DraggableRect(
                rect = camRect,
                color = PinkCam,
                label = "CAM",
                onChange = onCamRect
            )
        }
    }
}

/** One normalized rect with a drag handle (bottom-right corner resizes, body moves). */
@Composable
private fun DraggableRect(
    rect: NormRect,
    color: Color,
    label: String,
    onChange: (NormRect) -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val boxW = maxWidth
        val boxH = maxHeight
        val wPx = boxW.value
        val hPx = boxH.value

        val leftDp = (rect.left * wPx).dp
        val topDp = (rect.top * hPx).dp
        val widthDp = (rect.width * wPx).dp
        val heightDp = (rect.height * hPx).dp

        Box(
            Modifier
                .offset(x = leftDp, y = topDp)
                .width(widthDp)
                .height(heightDp)
                .border(2.dp, color, RoundedCornerShape(6.dp))
                .background(color.copy(alpha = 0.10f))
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        val dx = dragAmount.x / wPx
                        val dy = dragAmount.y / hPx
                        var l = (rect.left + dx).coerceIn(0f, 0.9f)
                        var t = (rect.top + dy).coerceIn(0f, 0.9f)
                        val w = rect.width
                        val h = rect.height
                        l = l.coerceAtMost(1f - w)
                        t = t.coerceAtMost(1f - h)
                        onChange(NormRect(l, t, l + w, t + h))
                    }
                }
        ) {
            Text(
                label,
                color = color,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .background(color.copy(alpha = 0.35f), RoundedCornerShape(bottomEnd = 6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
            // Bottom-right resize handle.
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(20.dp)
                    .background(color, RoundedCornerShape(topStart = 6.dp))
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            val dx = dragAmount.x / wPx
                            val dy = dragAmount.y / hPx
                            var w = rect.width + dx
                            var h = rect.height + dy
                            val minW = 0.05f
                            val minH = 0.05f
                            w = w.coerceIn(minW, 1f - rect.left)
                            h = h.coerceIn(minH, 1f - rect.top)
                            onChange(NormRect(rect.left, rect.top, rect.left + w, rect.top + h))
                        }
                    }
            )
        }
    }
}
