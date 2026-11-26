package com.clipme.app.ui.trending

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clipme.app.ui.theme.Ink
import com.clipme.app.ui.theme.InkElevated
import com.clipme.app.ui.theme.Lime
import com.clipme.app.ui.theme.PinkCam
import com.clipme.app.ui.theme.TextFaint
import com.clipme.app.ui.theme.TextSecondary
import kotlinx.coroutines.flow.StateFlow

/** "Trending" — an offline inspiration feed of the week's short-video formats. */
@Composable
fun TrendingScreen(
    state: StateFlow<TrendingUiState>,
    onOpenCreate: () -> Unit
) {
    val s by state.collectAsState()

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "TRENDING",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Formats that are popping off right now",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(s.clips) { clip ->
                TrendingRow(clip = clip, onOpenCreate = onOpenCreate)
            }
            item {
                Spacer(Modifier.height(12.dp))
                Text(
                    "These are local suggestions — the layout tools in the + tab can recreate every one of them.",
                    color = TextFaint,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun TrendingRow(clip: TrendingClip, onOpenCreate: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(InkElevated, RoundedCornerShape(16.dp))
            .clickable(onClick = onOpenCreate)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Mini 9:16 glyph preview.
        Box(
            Modifier
                .width(54.dp)
                .height(96.dp)
                .background(Ink, RoundedCornerShape(8.dp))
                .padding(4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(clip.emoji, fontSize = 26.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                clip.tag,
                color = Lime,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(3.dp))
            Text(
                clip.title,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2
            )
            Spacer(Modifier.height(3.dp))
            Text(
                "${clip.creator} · ${clip.views} views",
                color = TextSecondary,
                fontSize = 12.sp
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                "RE-CREATE",
                color = PinkCam,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .background(PinkCam.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 5.dp)
            )
        }
    }
}
