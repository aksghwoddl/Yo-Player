package com.yohan.yoplayersdk.player

import android.view.Surface

/**
 * M3U8 다운로드 → FFmpeg 디먹싱 → ExoPlayer 렌더링 파이프라인을 제공합니다.
 */
interface YoPlayer {

    /**
     * 비디오 렌더링을 위한 Surface 설정
     *
     * @param surface 비디오를 렌더링할 Surface, null이면 비디오 출력 비활성화
     */
    fun setSurface(surface: Surface?)

    /**
     * 미디어 재생 시작
     *
     * @param url M3U8 URL
     */
    fun play(url: String)

    /**
     * 재생 중지 및 리소스 초기화
     */
    fun stop()

    /**
     * 재생 일시정지
     */
    fun pause()

    /**
     * 일시정지된 재생 재개
     */
    fun resume()

    /**
     * 리소스 해제
     * 더 이상 플레이어를 사용하지 않을 때 호출
     */
    fun release()
}
