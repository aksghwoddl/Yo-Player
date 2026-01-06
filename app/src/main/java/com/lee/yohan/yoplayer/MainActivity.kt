package com.lee.yohan.yoplayer

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.lee.yohan.yoplayer.ui.theme.YoPlayerTheme
import com.lee.yohan.yoplayer.viewmodel.M3u8DownloadUiState
import com.lee.yohan.yoplayer.viewmodel.MainViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            YoPlayerTheme {
                val context = LocalContext.current
                DisposableEffect(Unit) {
                    val activity = context as? ComponentActivity
                    activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    onDispose {
                        activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    M3u8DownloaderTestScreen(
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun M3u8DownloaderTestScreen(
    modifier: Modifier = Modifier,
    viewModel: MainViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    M3u8DownloaderContent(
        uiState = uiState,
        onStartDownload = viewModel::startDownload,
        onCancelDownload = viewModel::cancelDownload,
        modifier = modifier
    )
}

@Composable
private fun M3u8DownloaderContent(
    uiState: M3u8DownloadUiState,
    onStartDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "M3U8 Downloader Test",
            style = MaterialTheme.typography.headlineMedium
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onStartDownload,
                enabled = !uiState.isDownloading
            ) {
                Text(if (uiState.isDownloading) "Downloading..." else "Start Download")
            }

            if (uiState.isDownloading) {
                OutlinedButton(onClick = onCancelDownload) {
                    Text("Cancel")
                }
            }
        }

        // Progress
        if (uiState.isDownloading || uiState.progress > 0f) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LinearProgressIndicator(
                    progress = { uiState.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "${(uiState.progress * 100).toInt()}% (${uiState.downloadedSegments}/${uiState.totalSegments} segments)",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        // Status
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    uiState.isError -> MaterialTheme.colorScheme.errorContainer
                    uiState.isCompleted -> MaterialTheme.colorScheme.primaryContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
            )
        ) {
            Text(
                text = uiState.statusMessage,
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        // Logs
        if (uiState.logs.isNotEmpty()) {
            Text(text = "Logs:", style = MaterialTheme.typography.titleSmall)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    uiState.logs.takeLast(15).forEach { log ->
                        Text(text = log, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, name = "Ready State")
@Composable
private fun M3u8DownloaderContentPreview_Ready() {
    YoPlayerTheme {
        M3u8DownloaderContent(
            uiState = M3u8DownloadUiState(),
            onStartDownload = {},
            onCancelDownload = {}
        )
    }
}

@Preview(showBackground = true, name = "Downloading State")
@Composable
private fun M3u8DownloaderContentPreview_Downloading() {
    YoPlayerTheme {
        M3u8DownloaderContent(
            uiState = M3u8DownloadUiState(
                isDownloading = true,
                progress = 0.45f,
                downloadedSegments = 5,
                totalSegments = 11,
                statusMessage = "Downloading...",
                logs = listOf(
                    "Download started",
                    "Playlist: 11 segments, 60.0s",
                    "Segment 1/11: 245.3KB, header=[47 40 11 10]",
                    "Segment 2/11: 312.1KB, header=[47 40 11 10]",
                    "Segment 3/11: 298.5KB, header=[47 40 11 10]"
                )
            ),
            onStartDownload = {},
            onCancelDownload = {}
        )
    }
}

@Preview(showBackground = true, name = "Completed State")
@Composable
private fun M3u8DownloaderContentPreview_Completed() {
    YoPlayerTheme {
        M3u8DownloaderContent(
            uiState = M3u8DownloadUiState(
                isDownloading = false,
                progress = 1f,
                downloadedSegments = 11,
                totalSegments = 11,
                statusMessage = "Completed! 2.45 MB in 3.2s",
                isCompleted = true,
                logs = listOf(
                    "=== Download Complete ===",
                    "Total: 11 segments, 2.45 MB",
                    "Time: 3.2s"
                )
            ),
            onStartDownload = {},
            onCancelDownload = {}
        )
    }
}

@Preview(showBackground = true, name = "Error State")
@Composable
private fun M3u8DownloaderContentPreview_Error() {
    YoPlayerTheme {
        M3u8DownloaderContent(
            uiState = M3u8DownloadUiState(
                isDownloading = false,
                progress = 0.3f,
                downloadedSegments = 3,
                totalSegments = 11,
                statusMessage = "Error: Connection timeout",
                isError = true,
                logs = listOf(
                    "Download started",
                    "Segment 1/11: 245.3KB",
                    "Error: Connection timeout"
                )
            ),
            onStartDownload = {},
            onCancelDownload = {}
        )
    }
}
