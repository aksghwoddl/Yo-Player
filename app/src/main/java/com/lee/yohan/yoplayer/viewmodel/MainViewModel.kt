package com.lee.yohan.yoplayer.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import com.yohan.yoplayersdk.m3u8.DownloadedSegment
import com.yohan.yoplayersdk.m3u8.M3u8DownloadListener
import com.yohan.yoplayersdk.m3u8.M3u8Downloader
import com.yohan.yoplayersdk.m3u8.M3u8Playlist
import com.yohan.yoplayersdk.m3u8.M3u8Segment
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
    private val downloader: M3u8Downloader
) : ViewModel() {

    private val _uiState = MutableStateFlow(M3u8DownloadUiState())
    val uiState: StateFlow<M3u8DownloadUiState> = _uiState.asStateFlow()

    private fun addLog(message: String) {
        Log.d(TAG, message)
        _uiState.update { it.copy(logs = it.logs + message) }
    }

    fun startDownload() {
        _uiState.update {
            it.copy(
                isDownloading = true,
                progress = 0f,
                logs = emptyList(),
                statusMessage = "Starting download...",
                isCompleted = false,
                isError = false
            )
        }
        addLog("Download started: $TEST_M3U8_URL")

        downloader.download(
            m3u8Url = TEST_M3U8_URL,
            listener = object : M3u8DownloadListener {
                override fun onDownloadStarted(
                    playlist: M3u8Playlist.Media,
                    totalSegments: Int
                ) {
                    _uiState.update { it.copy(totalSegments = totalSegments) }
                    addLog(
                        "Playlist: $totalSegments segments, ${
                            String.format(
                                null,
                                "%.1f",
                                playlist.totalDuration
                            )
                        }s"
                    )
                }

                override fun onSegmentDownloaded(
                    segment: M3u8Segment,
                    data: ByteArray,
                    currentIndex: Int,
                    totalSegments: Int
                ) {
                    val sizeKb = data.size / 1024.0
                    val first4Bytes = data.take(4).joinToString(" ") { "%02X".format(it) }
                    addLog(
                        "Segment ${currentIndex + 1}/$totalSegments: ${
                            String.format(
                                null,
                                "%.1f",
                                sizeKb,
                            )
                        }KB, header=[$first4Bytes]"
                    )
                }

                override fun onProgressUpdate(
                    progress: Float,
                    downloadedSegments: Int,
                    totalSegments: Int
                ) {
                    _uiState.update {
                        it.copy(
                            progress = progress,
                            downloadedSegments = downloadedSegments,
                            totalSegments = totalSegments
                        )
                    }
                }

                override fun onDownloadCompleted(
                    segments: List<DownloadedSegment>,
                    totalBytes: Long,
                    elapsedTimeMs: Long
                ) {
                    val sizeMb = totalBytes / 1024.0 / 1024.0
                    val timeSec = elapsedTimeMs / 1000.0
                    val message = "Completed! %.2f MB in %.1fs".format(sizeMb, timeSec)
                    _uiState.update {
                        it.copy(
                            statusMessage = message,
                            isCompleted = true,
                            isDownloading = false
                        )
                    }
                    addLog("=== Download Complete ===")
                    addLog(
                        "Total: ${_uiState.value.downloadedSegments} segments, %.2f MB".format(
                            sizeMb
                        )
                    )
                    addLog("Time: %.1fs".format(timeSec))
                }

                override fun onDownloadError(error: Throwable, segment: M3u8Segment?) {
                    _uiState.update {
                        it.copy(
                            statusMessage = "Error: ${error.message}",
                            isError = true,
                            isDownloading = false
                        )
                    }
                    addLog("Error: ${error.message}")
                }

                override fun onDownloadCancelled() {
                    _uiState.update {
                        it.copy(
                            statusMessage = "Cancelled",
                            isDownloading = false
                        )
                    }
                    addLog("Download cancelled")
                }
            },
        )
    }

    fun cancelDownload() {
        downloader.cancel()
    }

    override fun onCleared() {
        super.onCleared()
        downloader.release()
    }
}
