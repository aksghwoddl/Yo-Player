package com.yohan.yoplayersdk.m3u8

import java.net.URI

/**
 * M3U8 플레이리스트 파서
 *
 * M3U8 형식의 텍스트를 파싱하여 [M3u8Playlist] 객체로 변환합니다.
 */
object M3u8Parser {

    private const val TAG_EXTM3U = "#EXTM3U"
    private const val TAG_EXT_X_STREAM_INF = "#EXT-X-STREAM-INF"
    private const val TAG_EXT_X_TARGETDURATION = "#EXT-X-TARGETDURATION"
    private const val TAG_EXT_X_MEDIA_SEQUENCE = "#EXT-X-MEDIA-SEQUENCE"
    private const val TAG_EXTINF = "#EXTINF"
    private const val TAG_EXT_X_KEY = "#EXT-X-KEY"
    private const val TAG_EXT_X_BYTERANGE = "#EXT-X-BYTERANGE"
    private const val TAG_EXT_X_DISCONTINUITY = "#EXT-X-DISCONTINUITY"
    private const val TAG_EXT_X_ENDLIST = "#EXT-X-ENDLIST"
    private const val TAG_EXT_X_PLAYLIST_TYPE = "#EXT-X-PLAYLIST-TYPE"
    private const val KEY_BANDWIDTH = "BANDWIDTH"
    private const val KEY_RESOLUTION = "RESOLUTION"
    private const val KEY_CODECS = "CODECS"
    private const val KEY_NAME = "NAME"
    private const val KEY_METHOD = "METHOD"
    private const val KEY_URI = "URI"
    private const val KEY_IV = "IV"

    /**
     * M3U8 텍스트를 파싱하여 플레이리스트 객체로 변환합니다.
     *
     * @param content M3U8 파일 내용
     * @param baseUrl 플레이리스트의 기본 URL (상대 경로 해석에 사용)
     * @return 파싱된 플레이리스트 (마스터 또는 미디어)
     * @throws M3u8ParserException 파싱 실패 시
     */
    fun parse(content: String, baseUrl: String): M3u8Playlist {
        val lines = content.lines().map { it.trim() }.filter { it.isNotEmpty() }

        if (lines.isEmpty() || lines[0].startsWith(TAG_EXTM3U).not()) {
            throw M3u8ParserException("유효한 M3U8 파일이 아닙니다. #EXTM3U 태그가 없습니다.")
        }

        // 마스터 플레이리스트인지 확인 (EXT-X-STREAM-INF 태그 존재 여부)
        val isMasterPlaylist = lines.any { it.startsWith(TAG_EXT_X_STREAM_INF) }

        return if (isMasterPlaylist) {
            parseMasterPlaylist(lines, baseUrl)
        } else {
            parseMediaPlaylist(lines, baseUrl)
        }
    }

    /**
     * 마스터 플레이리스트 파싱
     */
    private fun parseMasterPlaylist(lines: List<String>, baseUrl: String): M3u8Playlist.Master {
        val variants = buildList {
            lines.forEachIndexed { index, line ->
                if (line.startsWith(TAG_EXT_X_STREAM_INF).not()) return@forEachIndexed

                val attributes = parseAttributes(line.substringAfter(":"))
                // 다음 라인이 URL
                lines.getOrNull(index + 1)?.let { nextLine ->
                    if (nextLine.startsWith("#").not()) {
                        add(
                            M3u8Playlist.Master.Variant(
                                url = resolveUrl(baseUrl, nextLine),
                                bandwidth = attributes[KEY_BANDWIDTH]?.toLongOrNull() ?: 0L,
                                resolution = attributes[KEY_RESOLUTION],
                                codecs = attributes[KEY_CODECS],
                                name = attributes[KEY_NAME]
                            )
                        )
                    }
                }
            }
        }

        return M3u8Playlist.Master(baseUrl = baseUrl, variants = variants)
    }

    /**
     * 미디어 플레이리스트 파싱
     */
    private fun parseMediaPlaylist(lines: List<String>, baseUrl: String): M3u8Playlist.Media {
        var targetDuration = 10
        var mediaSequence = 0
        var playlistType: String? = null
        var isEndList = false

        var currentEncryptionInfo: M3u8Segment.EncryptionInfo? = null
        var hasDiscontinuity = false
        var byteRangeOffset: Long? = null
        var byteRangeLength: Long? = null
        var segmentDuration = 0.0
        var segmentTitle: String? = null
        var sequenceNumber = 0

        val segments = buildList {
            lines.forEachIndexed { index, line ->
                when {
                    line.startsWith(TAG_EXT_X_TARGETDURATION) -> {
                        targetDuration = line.substringAfter(":").trim().toIntOrNull() ?: 10
                    }

                    line.startsWith(TAG_EXT_X_MEDIA_SEQUENCE) -> {
                        mediaSequence = line.substringAfter(":").trim().toIntOrNull() ?: 0
                        sequenceNumber = mediaSequence
                    }

                    line.startsWith(TAG_EXT_X_PLAYLIST_TYPE) -> {
                        playlistType = line.substringAfter(":").trim()
                    }

                    line.startsWith(TAG_EXT_X_ENDLIST) -> {
                        isEndList = true
                    }

                    line.startsWith(TAG_EXT_X_KEY) -> {
                        currentEncryptionInfo = parseEncryptionKey(line, baseUrl)
                    }

                    line.startsWith(TAG_EXT_X_DISCONTINUITY) -> {
                        hasDiscontinuity = true
                    }

                    line.startsWith(TAG_EXT_X_BYTERANGE) -> {
                        val byteRange = parseByteRange(line.substringAfter(":"))
                        byteRangeLength = byteRange.first
                        byteRangeOffset = byteRange.second
                    }

                    line.startsWith(TAG_EXTINF) -> {
                        val infoPart = line.substringAfter(":").trim()
                        val parts = infoPart.split(",", limit = 2)
                        segmentDuration = parts[0].toDoubleOrNull() ?: 0.0
                        segmentTitle =
                            if (parts.size > 1) parts[1].takeIf { it.isNotBlank() } else null

                        // 다음 라인이 세그먼트 URL
                        lines.getOrNull(index + 1)?.let { nextLine ->
                            if (nextLine.startsWith("#").not()) {
                                add(
                                    M3u8Segment(
                                        url = resolveUrl(baseUrl, nextLine),
                                        duration = segmentDuration,
                                        sequenceNumber = sequenceNumber++,
                                        title = segmentTitle,
                                        byteRangeOffset = byteRangeOffset,
                                        byteRangeLength = byteRangeLength,
                                        encryptionInfo = currentEncryptionInfo,
                                        hasDiscontinuity = hasDiscontinuity
                                    )
                                )
                                // Reset per-segment values
                                hasDiscontinuity = false
                                byteRangeOffset = null
                                byteRangeLength = null
                            }
                        }
                    }
                }
            }
        }

        return M3u8Playlist.Media(
            baseUrl = baseUrl,
            targetDuration = targetDuration,
            mediaSequence = mediaSequence,
            segments = segments,
            isEndList = isEndList,
            playlistType = playlistType
        )
    }

    /**
     * 암호화 키 정보 파싱
     */
    private fun parseEncryptionKey(line: String, baseUrl: String): M3u8Segment.EncryptionInfo? {
        val attributes = parseAttributes(line.substringAfter(":"))
        val method = attributes[KEY_METHOD] ?: return null

        if (method == "NONE") return null

        val keyUri = attributes[KEY_URI]?.removeSurrounding("\"") ?: return null
        val iv = attributes[KEY_IV]

        return M3u8Segment.EncryptionInfo(
            method = method,
            keyUrl = resolveUrl(baseUrl, keyUri),
            iv = iv
        )
    }

    /**
     * BYTERANGE 파싱
     * @return Pair(length, offset)
     */
    private fun parseByteRange(value: String): Pair<Long, Long?> {
        val trimmed = value.trim()
        val parts = trimmed.split("@")
        val length = parts[0].toLongOrNull() ?: 0L
        val offset = if (parts.size > 1) parts[1].toLongOrNull() else null
        return Pair(length, offset)
    }

    /**
     * 속성 파싱 (예: BANDWIDTH=140800,RESOLUTION=1920x1080)
     */
    private fun parseAttributes(attributeString: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val regex = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")

        regex.findAll(attributeString).forEach { matchResult ->
            val key = matchResult.groupValues[1]
            val value = matchResult.groupValues[2].removeSurrounding("\"")
            result[key] = value
        }

        return result
    }

    /**
     * 상대 URL을 절대 URL로 변환
     */
    private fun resolveUrl(baseUrl: String, relativeUrl: String): String {
        // 이미 절대 URL인 경우 그대로 반환
        if (relativeUrl.startsWith("http://") || relativeUrl.startsWith("https://")) {
            return relativeUrl
        }

        return try {
            val baseUri = URI(baseUrl)
            baseUri.resolve(relativeUrl).toString()
        } catch (e: Exception) {
            // URI 파싱 실패시 직접 처리
            val baseWithoutFile = baseUrl.substringBeforeLast("/")
            if (relativeUrl.startsWith("/")) {
                // 루트 기준 상대 경로
                val scheme = baseUrl.substringBefore("://")
                val host = baseUrl.substringAfter("://").substringBefore("/")
                "$scheme://$host$relativeUrl"
            } else {
                // 현재 디렉토리 기준 상대 경로
                "$baseWithoutFile/$relativeUrl"
            }
        }
    }
}

/**
 * M3U8 파싱 중 발생하는 예외
 */
class M3u8ParserException(message: String, cause: Throwable? = null) : Exception(message, cause)
