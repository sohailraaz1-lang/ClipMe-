package com.clipme.app.ui.create

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clipme.app.ui.theme.InkElevated
import com.clipme.app.ui.theme.Lime
import com.clipme.app.ui.theme.TextSecondary

/** The "? HELP" modal shown on each create step. */
@Composable
fun HelpModal(title: String, bullets: List<Pair<String, String>>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = InkElevated,
        title = {
            Text(title, color = Lime, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                bullets.forEach { (head, body) ->
                    Row(Modifier.fillMaxWidth()) {
                        Text("• ", color = Lime, fontWeight = FontWeight.Bold)
                        Column {
                            Text(
                                head,
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                body,
                                color = TextSecondary,
                                fontSize = 13.sp,
                                lineHeight = 18.sp
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("GOT IT", color = Lime, fontWeight = FontWeight.Bold)
            }
        }
    )
}

/** Small "? HELP" affordance that opens [HelpModal]. */
@Composable
fun HelpButton(onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text("? HELP", color = TextSecondary, fontSize = 12.sp)
    }
}

/** Standard step header: step badge, title, and a trailing help button. */
@Composable
fun StepHeader(
    stepLabel: String,
    title: String,
    onHelp: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stepLabel,
                color = Lime,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                modifier = Modifier
                    .background(Lime.copy(alpha = 0.15f))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                title,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }
        HelpButton(onClick = onHelp)
    }
}
