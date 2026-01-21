# YoPlayer

커스텀 미디어 파이프라인을 구현한 Android 미디어 플레이어 샘플 앱입니다.

## 프로젝트 개요

YoPlayer는 Media3/ExoPlayer를 기반으로 커스텀 미디어 파이프라인을 구현한 프로젝트입니다. 다음과 같은 파이프라인을 직접 구현합니다:

```
커스텀 다운로더 → FFmpeg 디먹싱 → 샘플 추출 → ExoPlayer 재생
```

### 핵심 구현 요소

- **커스텀 다운로더**: 미디어 데이터를 직접 다운로드/관리
- **FFmpeg 디먹싱**: FFmpeg를 사용하여 컨테이너에서 오디오/비디오 스트림 분리
- **샘플 공급**: 디먹싱된 샘플을 ExoPlayer에 공급
- **ExoPlayer 재생**: 커스텀 파이프라인으로 공급받은 샘플 렌더링

## 모듈 구조

```
app/                    - 메인 애플리케이션 (Compose UI, Material3)
yoplayersdk/           - YoPlayer SDK (커스텀 Kotlin 모듈 - M3U8 다운로더 등)
libraries/
├── common/            - 전체 모듈에서 공유하는 핵심 유틸리티
├── container/         - 미디어 컨테이너 포맷 처리
├── database/          - 미디어 메타데이터 저장소
├── datasource/        - 데이터 소스 추상화 (로컬, HTTP)
├── decoder/           - 디코더 인터페이스 정의
├── decoder_ffmpeg/    - FFmpeg 오디오 디코더 (네이티브 빌드 필요)
├── extractor/         - 미디어 포맷 추출기
└── exoplayer/         - 핵심 플레이어 구현
```

## 빌드 환경

| 항목 | 버전 |
|------|------|
| Min SDK | 24 (Android 7.0) |
| Target/Compile SDK | 36 |
| Java Target | 17 |
| Kotlin | 2.3.0 |
| Compose BOM | 2026.01.00 |

## FFmpeg 네이티브 빌드

`decoder_ffmpeg` 모듈은 수동 FFmpeg 컴파일이 필요합니다. 자세한 내용은 `libraries/decoder_ffmpeg/README.md`를 참조하세요.

### 요구사항

- Android NDK r26b
- FFmpeg 6.0 소스
- Android Studio를 통해 설치된 CMake

## 기술 스택

- **UI 프레임워크**: Jetpack Compose + Material3
- **DI**: Hilt
- **네트워크**: OkHttp
- **비동기 처리**: Kotlin Coroutines
- **테마**: Android 12+ 다이나믹 컬러, 다크/라이트 모드 지원

## 라이선스

이 프로젝트는 Media3 라이브러리 모듈을 포함하고 있습니다.
