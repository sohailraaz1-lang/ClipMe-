package com.clipme.app.ui.myclips

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clipme.app.model.ClipItem
import com.clipme.app.ui.theme.Ink
import com.clipme.app.ui.theme.InkElevated
import com.clipme.app.ui.theme.Lime
import com.clipme.app.ui.theme.PinkCam
import com.clipme.app.ui.theme.TextFaint
import com.clipme.app.ui.theme.TextSecondary
import com.clipme.app.util.FileUtils
import kotlinx.coroutines.flow.StateFlow

/** "My clips" — the grid library of every exported clip. */
@Composable
fun MyClipsScreen(
    state: StateFlow<MyClipsUiState>,
    onRefresh: () -> Unit,
    onRename: (ClipItem, String) -> Unit,
    onDelete: (ClipItem) -> Unit,
    onShare: (ClipItem) -> Unit
) {
    val s by state.collectAsState()

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "MY CLIPS",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onRefresh) {
                Text("REFRESH", color = Lime, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }

        when {
            s.loading && s.clips.isEmpty() -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Lime, strokeWidth = 3.dp)
            }

            s.clips.isEmpty() -> EmptyClips()

            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(s.clips, key = { it.id }) { clip ->
                    ClipCard(
                        clip = clip,
                        onPlay = { /* open in a player — external apps handle the uri */ },
                        onRename = { onRename(clip, it) },
                        onDelete = { onDelete(clip) },
                        onShare = { onShare(clip) }
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyClips() {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("🎬", fontSize = 48.sp)
        Spacer(Modifier.height(16.dp))
        Text(
            "No clips yet",
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Create your first clip from the + tab",
            color = TextSecondary,
            fontSize = 13.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
private fun ClipCard(
    clip: ClipItem,
    onPlay: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit
) {
    var showActions by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .background(InkElevated, RoundedCornerShape(14.dp))
            .clickable { showActions = true }
            .padding(10.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(9f / 16f)
                .background(Ink, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            ThumbnailFor(clip)
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = "Play",
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.align(Alignment.Center)
            )
            Text(
                FileUtils.formatDuration(clip.durationMs),
                color = Color.White,
                fontSize = 11.sp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            clip.name,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
        Spacer(Modifier.height(2.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "${FileUtils.formatSize(clip.sizeBytes)} · ${FileUtils.relativeTime(androidx.compose.ui.platform.LocalContext.current, clip.createdAt)}",
                color = TextFaint,
                fontSize = 11.sp
            )
            Row {
                IconButton(onClick = { showRename = true }) {
                    Icon(Icons.Filled.Edit, contentDescription = "Rename", tint = TextSecondary, modifier = Modifier.height(16.dp).width(16.dp))
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = PinkCam, modifier = Modifier.height(16.dp).width(16.dp))
                }
            }
        }
    }

    if (showActions) {
        AlertDialog(
            onDismissRequest = { showActions = false },
            containerColor = InkElevated,
            title = { Text(clip.name, color = MaterialTheme.colorScheme.onBackground, fontSize = 15.sp, maxLines = 1) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    DialogAction("Play in player") { showActions = false; onPlay() }
                    DialogAction("Share") { showActions = false; onShare() }
                    DialogAction("Rename") { showActions = false; showRename = true }
                    DialogAction("Delete", danger = true) { showActions = false; onDelete() }
                }
            },
            confirmButton = {
                TextButton(onClick = { showActions = false }) {
                    Text("CLOSE", color = TextSecondary, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    if (showRename) {
        RenameDialog(
            initial = clip.name,
            onConfirm = { name -> onRename(name); showRename = false },
            onDismiss = { showRename = false }
        )
    }
}

@Composable
private fun DialogAction(label: String, danger: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = if (danger) PinkCam else Lime,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp
        )
    }
}

@Composable
private fun RenameDialog(
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = InkElevated,
        title = { Text("RENAME CLIP", color = Lime, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name) },
                colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Ink)
            ) {
                Text("SAVE", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = TextSecondary)
            }
        }
    )
}

/** Coil-powered thumbnail; falls back to a placeholder. */
@Composable
private fun ThumbnailFor(clip: ClipItem) {
    coil.compose.AsyncImage(
        model = clip.uri,
        contentDescription = clip.name,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop
    )
}
