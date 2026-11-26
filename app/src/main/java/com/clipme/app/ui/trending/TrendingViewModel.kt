package com.clipme.app.ui.trending

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A trending clip suggestion. Content is curated offline — no network. */
data class TrendingClip(
    val title: String,
    val creator: String,
    val views: String,
    val tag: String,
    val emoji: String,
    val presetId: String
)

data class TrendingUiState(
    val loading: Boolean = true,
    val clips: List<TrendingClip> = emptyList(),
    val error: String? = null
)

/**
 * "Trending" — a lightweight, fully-offline inspiration feed that mirrors the
 * current month's short-video culture (gaming + facecam + captions). Every
 * suggestion maps to one of the built-in layout presets so the user can
 * recreate it in the Create flow.
 */
class TrendingViewModel : ViewModel() {

    private val _state = MutableStateFlow(TrendingUiState())
    val state: StateFlow<TrendingUiState> = _state.asStateFlow()

    init {
        _state.value = TrendingUiState(
            loading = false,
            clips = trendingClips
        )
    }

    private val trendingClips = listOf(
        TrendingClip(
            "Rainbow Six highlight — 1v5 clutch",
            "Ranked Rush",
            "1.2M",
            "GAMING",
            "🎮",
            "split"
        ),
        TrendingClip(
            "Facecam reaction to the final kill",
            "Cam Queen",
            "890K",
            "IRL",
            "😱",
            "big_cam"
        ),
        TrendingClip(
            "Fortnite 360 no-scope montage",
            "BuildKing",
            "2.3M",
            "GAMING",
            "🏆",
            "circle_cam"
        ),
        TrendingClip(
            "Speedrun WR — last 30 seconds",
            "TimerGod",
            "1.7M",
            "SPEEDRUN",
            "⏱️",
            "game_only"
        ),
        TrendingClip(
            "Valorant ace with chat screaming",
            "AgentZero",
            "950K",
            "GAMING",
            "🔥",
            "cam_game"
        ),
        TrendingClip(
            "The jump that broke the physics",
            "Clip Lab",
            "640K",
            "WEIRD",
            "👀",
            "blur"
        ),
        TrendingClip(
            "Dual box stream — game + facecam",
            "Twin Cams",
            "1.1M",
            "DUAL",
            "🎬",
            "rounded_cam"
        ),
        TrendingClip(
            "Rage quit compilation",
            "Rage Room",
            "3.4M",
            "COMPILATION",
            "💀",
            "split"
        )
    )
}
