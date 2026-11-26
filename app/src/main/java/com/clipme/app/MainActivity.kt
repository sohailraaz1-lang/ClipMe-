package com.clipme.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.clipme.app.model.ClipItem
import com.clipme.app.ui.create.CreateFlowScreen
import com.clipme.app.ui.create.CreateViewModel
import com.clipme.app.ui.myclips.MyClipsScreen
import com.clipme.app.ui.myclips.MyClipsViewModel
import com.clipme.app.ui.theme.ClipMeTheme
import com.clipme.app.ui.theme.Ink
import com.clipme.app.ui.theme.InkElevated
import com.clipme.app.ui.theme.Lime
import com.clipme.app.ui.theme.TextSecondary
import com.clipme.app.ui.trending.TrendingScreen
import com.clipme.app.ui.trending.TrendingViewModel

/** Single-activity host: 3-tab bottom nav, deep-link intake, notification permission. */
class MainActivity : ComponentActivity() {

    /** URL from a youtube/twitch VIEW deep link, consumed by the Create flow. */
    private var pendingUrl: String? by mutableStateOf(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            ClipMeTheme {
                ClipMeRoot(
                    pendingUrl = pendingUrl,
                    onPendingUrlConsumed = { pendingUrl = null },
                    onShare = ::shareClip
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** Grabs a VIEW deep link (youtube/twitch) and routes it to the Create tab. */
    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            val url = intent.data?.toString()
            if (!url.isNullOrBlank()) pendingUrl = url
        }
    }

    /** Shares an exported clip via the system share sheet. */
    private fun shareClip(item: ClipItem) {
        val shareUri = if (item.uri.scheme == "file" && item.filePath.isNotBlank()) {
            runCatching {
                androidx.core.content.FileProvider.getUriForFile(
                    this,
                    "com.clipme.app.fileprovider",
                    java.io.File(item.filePath)
                )
            }.getOrDefault(item.uri)
        } else {
            item.uri
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, shareUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(Intent.createChooser(send, "Share clip")) }
    }
}

private data class TabSpec(val label: String, val icon: ImageVector)

@Composable
private fun ClipMeRoot(
    pendingUrl: String?,
    onPendingUrlConsumed: () -> Unit,
    onShare: (ClipItem) -> Unit
) {
    // Activity-scoped: one instance shared across tab switches, so create-flow
    // step state survives leaving and returning to the "+ Create" tab.
    val createVm: CreateViewModel = viewModel()
    val myClipsVm: MyClipsViewModel = viewModel()
    val trendingVm: TrendingViewModel = viewModel()

    var tab by rememberSaveable { mutableStateOf(0) }

    val tabs = listOf(
        TabSpec("+ Create", Icons.Filled.Add),
        TabSpec("My clips", Icons.Filled.VideoLibrary),
        TabSpec("Trending", Icons.Filled.LocalFireDepartment)
    )

    // Consume a deep-linked URL once it arrives: prefill the create flow and
    // jump to the "+ Create" tab.
    LaunchedEffect(pendingUrl) {
        val url = pendingUrl
        if (!url.isNullOrBlank()) {
            createVm.prefillUrl(url)
            tab = 0
            onPendingUrlConsumed()
        }
    }

    // POST_NOTIFICATIONS — required for the export foreground service on 33+.
    val context = LocalContext.current
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* result irrelevant — export still works without the permission */ }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold(
        containerColor = Ink,
        bottomBar = {
            NavigationBar(containerColor = InkElevated) {
                tabs.forEachIndexed { index, spec ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = {
                            Icon(
                                spec.icon,
                                contentDescription = spec.label,
                                tint = if (tab == index) Ink else TextSecondary
                            )
                        },
                        label = {
                            Text(
                                spec.label,
                                fontWeight = FontWeight.Bold,
                                color = if (tab == index) Lime else TextSecondary
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = Lime,
                            selectedIconColor = Ink,
                            selectedTextColor = Lime,
                            unselectedIconColor = TextSecondary,
                            unselectedTextColor = TextSecondary
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            when (tab) {
                0 -> CreateFlowScreen(createVm)
                1 -> MyClipsScreen(
                    state = myClipsVm.state,
                    onRefresh = myClipsVm::refresh,
                    onRename = myClipsVm::rename,
                    onDelete = myClipsVm::delete,
                    onShare = onShare
                )
                2 -> TrendingScreen(
                    state = trendingVm.state,
                    onOpenCreate = { tab = 0 }
                )
            }
        }
    }
}
