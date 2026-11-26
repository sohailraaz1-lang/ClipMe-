package com.clipme.app.ui.create

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.clipme.app.model.CaptionStyle
import com.clipme.app.model.ExportQuality
import com.clipme.app.model.LayoutPreset
import com.clipme.app.model.NormRect
import com.clipme.app.ui.theme.InkElevated
import com.clipme.app.ui.theme.Lime

/** Hosts the 3-step create flow and wires it to [CreateViewModel]. */
@Composable
fun CreateFlowScreen(vm: CreateViewModel = viewModel()) {
    val s by vm.state.collectAsState()
    var showError by remember { mutableStateOf(false) }

    val sError = s.error
    if (sError != null && !showError) {
        showError = true
    }
    if (showError && sError != null) {
        AlertDialog(
            onDismissRequest = { showError = false; vm.clearError() },
            containerColor = InkElevated,
            title = { Text("CLIPME", color = Lime, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
            text = { Text(sError, color = MaterialTheme.colorScheme.onBackground, fontSize = 14.sp) },
            confirmButton = {
                TextButton(onClick = { showError = false; vm.clearError() }) {
                    Text("OK", color = Lime, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                }
            }
        )
    }

    when (s.step) {
        Step.STREAM -> StepStream(
            url = s.url,
            loadState = s.loadState,
            stream = s.stream,
            onUrlChange = vm::onUrlChange,
            onCheck = vm::checkLink,
            onDownload = vm::downloadWindow,
            onContinue = vm::makeClips,
            downloading = s.downloading,
            downloadProgress = s.downloadProgress,
            frame = s.frame,
            gameRect = s.gameRect,
            camRect = s.camRect,
            onGameRect = vm::setGameRect,
            onCamRect = { rect: NormRect -> vm.setCamRect(rect) },
            sourceLoaded = s.sourceFile != null
        )

        Step.CLIPS -> StepClips(
            windowDurationMs = s.windowDurationMs,
            ranges = s.ranges,
            selectedRange = s.selectedRange,
            makingClips = s.makingClips,
            onAddRange = vm::addRange,
            onUpdateRange = vm::updateRange,
            onRemoveRange = vm::removeRange,
            onSelectRange = vm::selectRange,
            onContinue = vm::makeClips,
            onBack = vm::backToStream,
            onExportNow = vm::export
        )

        Step.LAYOUT -> {
            val export by vm.exportState.collectAsState()
            StepLayout(
                settings = s.settings,
                clipCount = s.ranges.size,
                transcribing = s.transcribing,
                captionProgress = s.captionProgress,
                exportState = export,
                onPreset = { p: LayoutPreset -> vm.setPreset(p) },
                onHideCam = vm::setHideCam,
                onHookText = vm::setHookText,
                onHookVisible = vm::setHookVisible,
                onCaptionsOn = vm::setCaptionsOn,
                onCaptionStyle = { st: CaptionStyle -> vm.setCaptionStyle(st) },
                onTranscribe = vm::transcribeCaptions,
                onQuality = { q: ExportQuality -> vm.setQuality(q) },
                onStickers = vm::setStickers,
                onExport = vm::export,
                onCancelExport = vm::cancelExport,
                onBack = vm::backToClips
            )
        }
    }
}
