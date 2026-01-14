package com.lee.yohan.yoplayer.viewmodel

import android.util.Log
import android.view.Surface
import androidx.lifecycle.ViewModel
import com.yohan.yoplayersdk.player.YoPlayer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

// TODO: 테스트할 M3U8 URL
private const val TEST_M3U8_URL = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
private const val TAG = "MainViewModel"

data class M3u8DownloadUiState(
    val isDownloading: Boolean = false,
    val progress: Float = 0f,
    val downloadedSegments: Int = 0,
    val totalSegments: Int = 0,
    val statusMessage: String = "Ready",
    val logs: List<String> = emptyList(),
    val isCompleted: Boolean = false,
    val isError: Boolean = false
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val yoPlayer: YoPlayer
) : ViewModel() {

    private val _uiState = MutableStateFlow(M3u8DownloadUiState())
    val uiState: StateFlow<M3u8DownloadUiState> = _uiState.asStateFlow()

    private fun addLog(message: String) {
        Log.d(TAG, message)
        _uiState.update { it.copy(logs = it.logs + message) }
    }

    fun setSurface(surface: Surface?) {
        yoPlayer.setSurface(surface)
    }

    fun play() {
        _uiState.update {
            it.copy(
                isDownloading = true,
                progress = 0f,
                logs = emptyList(),
                statusMessage = "Starting playback...",
                isCompleted = false,
                isError = false
            )
        }
        addLog("=== Starting YoPlayer ===")
        addLog("URL: $TEST_M3U8_URL")
        yoPlayer.play(TEST_M3U8_URL)
    }

    fun stop() {
        yoPlayer.stop()
        _uiState.update {
            it.copy(
                isDownloading = false,
                statusMessage = "Stopped"
            )
        }
        addLog("YoPlayer stopped")
    }

    override fun onCleared() {
        super.onCleared()
        yoPlayer.release()
    }
}
