package com.clipme.app.ui.myclips

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.clipme.app.ClipMeApp
import com.clipme.app.model.ClipItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Library state for the "My clips" tab. */
data class MyClipsUiState(
    val clips: List<ClipItem> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null
)

class MyClipsViewModel(app: Application) : AndroidViewModel(app) {

    private val store = ClipMeApp.from(app).clipStore

    private val _state = MutableStateFlow(MyClipsUiState())
    val state: StateFlow<MyClipsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            store.clips.collect { clips ->
                _state.value = _state.value.copy(clips = clips, loading = false)
            }
        }
        refresh()
    }

    fun refresh() {
        store.refresh()
    }

    fun rename(item: ClipItem, newName: String) {
        viewModelScope.launch {
            store.rename(item, newName)
        }
    }

    fun delete(item: ClipItem) {
        viewModelScope.launch {
            store.delete(item)
            refresh()
        }
    }
}
