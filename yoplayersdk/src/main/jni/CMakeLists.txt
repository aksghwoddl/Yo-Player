#
# CMakeLists.txt for YoPlayer SDK FFmpeg Demuxer
#
cmake_minimum_required(VERSION 3.21.0 FATAL_ERROR)

# Enable C++11 features
set(CMAKE_CXX_STANDARD 11)

project(libffmpegDemuxerJNI C CXX)

# FFmpeg 라이브러리 경로 설정
set(ffmpeg_location "${CMAKE_CURRENT_SOURCE_DIR}/ffmpeg")
set(ffmpeg_binaries "${ffmpeg_location}/android-libs/${ANDROID_ABI}")

# FFmpeg static 라이브러리 임포트 (순서 중요: avformat -> avcodec -> avutil)
foreach(ffmpeg_lib avutil avcodec avformat)
    set(ffmpeg_lib_filename lib${ffmpeg_lib}.a)
    set(ffmpeg_lib_file_path ${ffmpeg_binaries}/${ffmpeg_lib_filename})
    add_library(
            ${ffmpeg_lib}
            STATIC
            IMPORTED)
    set_target_properties(
            ${ffmpeg_lib} PROPERTIES
            IMPORTED_LOCATION
            ${ffmpeg_lib_file_path})
endforeach()

# FFmpeg 헤더 포함
include_directories(${ffmpeg_location})

# Android 로그 라이브러리
find_library(android_log_lib log)

# JNI 공유 라이브러리 생성
add_library(ffmpegDemuxerJNI
            SHARED
            ffmpeg_demuxer_jni.cc)

# 라이브러리 링크 (순서 중요: avformat이 avcodec에 의존, avcodec이 avutil에 의존)
target_link_libraries(ffmpegDemuxerJNI
                      PRIVATE android
                      PRIVATE avformat
                      PRIVATE avcodec
                      PRIVATE avutil
                      PRIVATE z
                      PRIVATE ${android_log_lib})

# arm64-v8a 추가 플래그 (NDK 23.1.7779620 이상)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    target_link_options(ffmpegDemuxerJNI PRIVATE "-Wl,-Bsymbolic")
endif()

# 16 KB ELF alignment 활성화
target_link_options(ffmpegDemuxerJNI
                    PRIVATE "-Wl,-z,max-page-size=16384")
