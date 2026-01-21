package com.lee.yohan.yoplayer

import android.os.Bundle
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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
                    YoPlayerTestScreen(
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun YoPlayerTestScreen(
    modifier: Modifier = Modifier,
    viewModel: MainViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    YoPlayerContent(
        uiState = uiState,
        onPlay = viewModel::play,
        onStop = viewModel::stop,
        onPause = viewModel::pause,
        onResume = viewModel::resume,
        onSurfaceCreated = { surface -> viewModel.setSurface(surface) },
        onSurfaceDestroyed = { viewModel.setSurface(null) },
        modifier = modifier
    )
}

@Composable
private fun YoPlayerContent(
    uiState: M3u8DownloadUiState,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onSurfaceCreated: (Surface) -> Unit,
    onSurfaceDestroyed: () -> Unit,
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
            text = "YoPlayer Test",
            style = MaterialTheme.typography.headlineMedium
        )

        // Video Surface
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                factory = { context ->
                    SurfaceView(context).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                onSurfaceCreated(holder.surface)
                            }

                            override fun surfaceChanged(
                                holder: SurfaceHolder,
                                format: Int,
                                width: Int,
                                height: Int
                            ) {
                                // 크기 변경 시 처리
                            }

                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                onSurfaceDestroyed()
                            }
                        })
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onPlay,
                enabled = !uiState.isDownloading
            ) {
                Text(if (uiState.isDownloading) "Playing..." else "Play")
            }

            OutlinedButton(
                onClick = { if (uiState.isPaused) onResume() else onPause() },
                enabled = uiState.isDownloading
            ) {
                Text(if (uiState.isPaused) "Resume" else "Pause")
            }

            OutlinedButton(
                onClick = onStop,
                enabled = uiState.isDownloading
            ) {
                Text("Stop")
            }
        }

        // Status
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    uiState.isError -> MaterialTheme.colorScheme.errorContainer
                    uiState.isCompleted -> MaterialTheme.colorScheme.primaryContainer
                    uiState.isPaused -> MaterialTheme.colorScheme.secondaryContainer
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
private fun YoPlayerContentPreview_Ready() {
    YoPlayerTheme {
        YoPlayerContent(
            uiState = M3u8DownloadUiState(),
            onPlay = {},
            onStop = {},
            onPause = {},
            onResume = {},
            onSurfaceCreated = {},
            onSurfaceDestroyed = {}
        )
    }
}

@Preview(showBackground = true, name = "Playing State")
@Composable
private fun YoPlayerContentPreview_Playing() {
    YoPlayerTheme {
        YoPlayerContent(
            uiState = M3u8DownloadUiState(
                isDownloading = true,
                statusMessage = "Starting playback...",
                logs = listOf(
                    "=== Starting YoPlayer ===",
                    "URL: https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
                )
            ),
            onPlay = {},
            onStop = {},
            onPause = {},
            onResume = {},
            onSurfaceCreated = {},
            onSurfaceDestroyed = {}
        )
    }
}

@Preview(showBackground = true, name = "Paused State")
@Composable
private fun YoPlayerContentPreview_Paused() {
    YoPlayerTheme {
        YoPlayerContent(
            uiState = M3u8DownloadUiState(
                isDownloading = true,
                isPaused = true,
                statusMessage = "Paused",
                logs = listOf(
                    "=== Starting YoPlayer ===",
                    "YoPlayer paused"
                )
            ),
            onPlay = {},
            onStop = {},
            onPause = {},
            onResume = {},
            onSurfaceCreated = {},
            onSurfaceDestroyed = {}
        )
    }
}

@Preview(showBackground = true, name = "Error State")
@Composable
private fun YoPlayerContentPreview_Error() {
    YoPlayerTheme {
        YoPlayerContent(
            uiState = M3u8DownloadUiState(
                isDownloading = false,
                statusMessage = "Error: Connection timeout",
                isError = true,
                logs = listOf(
                    "=== Starting YoPlayer ===",
                    "Error: Connection timeout"
                )
            ),
            onPlay = {},
            onStop = {},
            onPause = {},
            onResume = {},
            onSurfaceCreated = {},
            onSurfaceDestroyed = {}
        )
    }
}
