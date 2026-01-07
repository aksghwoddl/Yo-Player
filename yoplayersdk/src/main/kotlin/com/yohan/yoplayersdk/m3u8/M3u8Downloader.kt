package com.yohan.yoplayersdk.m3u8

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * M3U8 HLS 스트림 다운로더
 *
 * M3U8 주소를 입력받아 파싱하고, 모든 세그먼트를 메모리에 다운로드합니다.
 * 내부적으로 SupervisorJob을 사용하여 코루틴을 관리합니다.
 *
 * @property httpClient OkHttp 클라이언트 (커스텀 설정 가능)
 */
class M3u8Downloader(
    private val httpClient: OkHttpClient = defaultHttpClient()
) {
    private val supervisorJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + supervisorJob)
    private val isCancelled = AtomicBoolean(false)
    private var currentJob: Job? = null
    private val downloadedSegments: List<DownloadedSegment> = mutableListOf()

    companion object {
        private const val BUFFER_SIZE = 8192
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        fun defaultHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                        .header("User-Agent", USER_AGENT)
                        .build()
                    chain.proceed(request)
                }
                .build()
        }
    }

    /**
     * M3U8 URL에서 다운로드를 시작합니다.
     * 내부적으로 코루틴을 실행하며, 결과는 리스너를 통해 전달됩니다.
     *
     * @param m3u8Url M3U8 플레이리스트 URL
     * @param listener 다운로드 진행 리스너
     * @param variantSelector 마스터 플레이리스트인 경우 variant 선택 함수 (기본: 가장 높은 bandwidth)
     */
    fun download(
        m3u8Url: String,
        listener: M3u8DownloadListener? = null,
    ) {
        isCancelled.set(false)
        currentJob?.cancel()
        currentJob = scope.launch {
            val startTime = System.currentTimeMillis()
            val totalBytesDownloaded = AtomicLong(0)

            try {
                // 1. M3U8 플레이리스트 다운로드 및 파싱
                val playlistContent = fetchContent(m3u8Url)
                var playlist = M3u8Parser.parse(playlistContent, m3u8Url)

                // 2. 마스터 플레이리스트인 경우 미디어 플레이리스트 선택
                if (playlist is M3u8Playlist.Master) {
                    val selectedVariant = playlist.variants.getMaxBandWidth()
                    val mediaPlaylistContent = fetchContent(selectedVariant.url)
                    playlist = M3u8Parser.parse(mediaPlaylistContent, selectedVariant.url)
                }

                // 3. 미디어 플레이리스트 확인
                val mediaPlaylist = playlist as? M3u8Playlist.Media
                if (mediaPlaylist == null) {
                    listener?.onDownloadError(M3u8DownloadException("미디어 플레이리스트를 찾을 수 없습니다."))
                    return@launch
                }

                if (mediaPlaylist.segments.isEmpty()) {
                    listener?.onDownloadError(M3u8DownloadException("다운로드할 세그먼트가 없습니다."))
                    return@launch
                }

                listener?.onDownloadStarted(mediaPlaylist, mediaPlaylist.segmentCount)

                // 4. 세그먼트 다운로드
                downloadSegments(
                    mediaPlaylist.segments,
                    listener,
                    totalBytesDownloaded,
                )

                if (isCancelled.get()) {
                    listener?.onDownloadCancelled()
                    return@launch
                }

                val elapsedTime = System.currentTimeMillis() - startTime
                listener?.onDownloadCompleted(
                    downloadedSegments,
                    totalBytesDownloaded.get(),
                    elapsedTime
                )

            } catch (e: CancellationException) {
                listener?.onDownloadCancelled()
            } catch (e: Exception) {
                listener?.onDownloadError(e)
            }
        }
    }

    /**
     * 세그먼트들을 다운로드합니다.
     */
    private suspend fun downloadSegments(
        segments: List<M3u8Segment>,
        listener: M3u8DownloadListener?,
        totalBytesDownloaded: AtomicLong,
    ): List<DownloadedSegment> {
        val downloadedSegments = mutableListOf<DownloadedSegment>()
        val totalSegments = segments.size

        segments.forEachIndexed { index, segment ->
            if (isCancelled.get()) return@forEachIndexed

            try {
                val data = downloadSegmentAndToByteArray(segment)
                totalBytesDownloaded.addAndGet(data.size.toLong())
                listener?.onSegmentDownloaded(segment, data, index, totalSegments)

                val progress = (index + 1).toFloat() / totalSegments
                listener?.onProgressUpdate(progress, index + 1, totalSegments)

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                listener?.onDownloadError(e, segment)
                throw M3u8DownloadException("세그먼트 다운로드 실패: ${segment.url}", e)
            }
        }

        return downloadedSegments
    }

    /**
     * 단일 세그먼트를 메모리에 다운로드합니다.
     */
    private suspend fun downloadSegmentAndToByteArray(
        segment: M3u8Segment
    ): ByteArray = withContext(Dispatchers.IO) {
        val requestBuilder = Request.Builder().url(segment.url).get()

        // 바이트 범위 설정
        if (segment.byteRangeLength != null && segment.byteRangeOffset != null) {
            val rangeEnd = segment.byteRangeOffset + segment.byteRangeLength - 1
            requestBuilder.header("Range", "bytes=${segment.byteRangeOffset}-$rangeEnd")
        }

        val request = requestBuilder.build()
        val response = httpClient.newCall(request).execute()

        if (response.isSuccessful.not()) {
            throw IOException("HTTP 오류: ${response.code} - ${response.message}")
        }

        val body = response.body ?: throw IOException("응답 본문이 비어있습니다.")

        ByteArrayOutputStream().use { outputStream ->
            body.byteStream().use { inputStream ->
                val buffer = ByteArray(BUFFER_SIZE)
                var bytesRead: Int

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    if (isCancelled.get()) {
                        throw CancellationException("다운로드가 취소되었습니다.")
                    }
                    outputStream.write(buffer, 0, bytesRead)
                }
            }
            outputStream.toByteArray()
        }
    }

    /**
     * URL에서 텍스트 콘텐츠를 가져옵니다.
     */
    private suspend fun fetchContent(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).get().build()
        val response = httpClient.newCall(request).execute()

        if (response.isSuccessful.not()) {
            throw IOException("HTTP 오류: ${response.code} - ${response.message}")
        }

        response.body?.string() ?: throw IOException("응답 본문이 비어있습니다.")
    }

    private fun List<M3u8Playlist.Master.Variant>.getMaxBandWidth(): M3u8Playlist.Master.Variant =
        this.maxByOrNull { it.bandwidth } ?: this.first()

    /**
     * 다운로드를 취소합니다.
     */
    fun cancel() {
        isCancelled.set(true)
        currentJob?.cancel()
    }

    /**
     * M3U8 URL을 파싱하여 플레이리스트 정보만 가져옵니다.
     * (다운로드 없이 정보 확인용)
     *
     * @param m3u8Url M3U8 플레이리스트 URL
     * @param onResult 결과 콜백
     */
    fun fetchPlaylist(m3u8Url: String, onResult: (Result<M3u8Playlist>) -> Unit) {
        scope.launch {
            try {
                val content = fetchContent(m3u8Url)
                val playlist = M3u8Parser.parse(content, m3u8Url)
                onResult(Result.success(playlist))
            } catch (e: Exception) {
                onResult(Result.failure(e))
            }
        }
    }

    /**
     * 다운로더를 종료하고 리소스를 해제합니다.
     * 더 이상 사용하지 않을 때 호출해야 합니다.
     */
    fun release() {
        cancel()
        supervisorJob.cancel()
    }
}

/**
 * M3U8 다운로드 중 발생하는 예외
 */
class M3u8DownloadException(message: String, cause: Throwable? = null) : Exception(message, cause)
