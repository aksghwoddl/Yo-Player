#!/bin/bash
#
# FFmpeg Demuxer 빌드 스크립트 for YoPlayer SDK
#
# 사용법:
#   ./build_ffmpeg.sh <SDK_MODULE_PATH> <NDK_PATH> <HOST_PLATFORM> <ANDROID_ABI>
#
# 예시:
#   ./build_ffmpeg.sh "$(pwd)/.." "$ANDROID_NDK_HOME" "darwin-x86_64" 24
#
set -eu

FFMPEG_MODULE_PATH="$1"
echo "FFMPEG_MODULE_PATH is ${FFMPEG_MODULE_PATH}"
NDK_PATH="$2"
echo "NDK path is ${NDK_PATH}"
HOST_PLATFORM="$3"
echo "Host platform is ${HOST_PLATFORM}"
ANDROID_ABI="$4"
echo "ANDROID_ABI is ${ANDROID_ABI}"
JOBS="$(nproc 2> /dev/null || sysctl -n hw.ncpu 2> /dev/null || echo 4)"
echo "Using $JOBS jobs for make"

# MPEG-TS 디먹싱 전용 옵션
# - avformat 활성화 (디먹싱 핵심)
# - swresample 비활성화 (디코딩 불필요)
# - 필요한 demuxer, parser, bsf만 활성화
COMMON_OPTIONS="
    --target-os=android
    --enable-static
    --disable-shared
    --disable-doc
    --disable-programs
    --disable-everything
    --disable-avdevice
    --disable-swscale
    --disable-postproc
    --disable-avfilter
    --disable-swresample
    --disable-symver
    --disable-v4l2-m2m
    --disable-vulkan
    --extra-ldexeflags=-pie

    --enable-avformat

    --enable-demuxer=mpegts

    --enable-parser=h264
    --enable-parser=hevc
    --enable-parser=aac
    --enable-parser=aac_latm
    --enable-parser=ac3
    --enable-parser=opus
    --enable-parser=vp9
    --enable-parser=av1
    --enable-parser=flac
    --enable-parser=mp3
    --enable-parser=dca

    --enable-bsf=h264_mp4toannexb
    --enable-bsf=hevc_mp4toannexb

    --enable-protocol=file
    "

TOOLCHAIN_PREFIX="${NDK_PATH}/toolchains/llvm/prebuilt/${HOST_PLATFORM}/bin"
if [[ ! -d "${TOOLCHAIN_PREFIX}" ]]
then
    echo "Please set correct NDK_PATH, $NDK_PATH is incorrect"
    exit 1
fi

ARMV7_CLANG="${TOOLCHAIN_PREFIX}/armv7a-linux-androideabi${ANDROID_ABI}-clang"
if [[ ! -e "$ARMV7_CLANG" ]]
then
    echo "ARMv7 Clang compiler with path $ARMV7_CLANG does not exist"
    echo "It's likely your NDK version doesn't support ANDROID_ABI $ANDROID_ABI"
    exit 1
fi

ANDROID_ABI_64BIT="$ANDROID_ABI"
if [[ "$ANDROID_ABI_64BIT" -lt 21 ]]
then
    echo "Using ANDROID_ABI 21 for 64-bit architectures"
    ANDROID_ABI_64BIT=21
fi

cd "${FFMPEG_MODULE_PATH}/jni/ffmpeg"

echo ""
echo "=========================================="
echo "Building for armeabi-v7a..."
echo "=========================================="
./configure \
    --libdir=android-libs/armeabi-v7a \
    --arch=arm \
    --cpu=armv7-a \
    --cross-prefix="${TOOLCHAIN_PREFIX}/armv7a-linux-androideabi${ANDROID_ABI}-" \
    --nm="${TOOLCHAIN_PREFIX}/llvm-nm" \
    --ar="${TOOLCHAIN_PREFIX}/llvm-ar" \
    --ranlib="${TOOLCHAIN_PREFIX}/llvm-ranlib" \
    --strip="${TOOLCHAIN_PREFIX}/llvm-strip" \
    --extra-cflags="-march=armv7-a -mfloat-abi=softfp" \
    --extra-ldflags="-Wl,--fix-cortex-a8" \
    ${COMMON_OPTIONS}
make -j$JOBS
make install-libs
make clean

echo ""
echo "=========================================="
echo "Building for arm64-v8a..."
echo "=========================================="
./configure \
    --libdir=android-libs/arm64-v8a \
    --arch=aarch64 \
    --cpu=armv8-a \
    --cross-prefix="${TOOLCHAIN_PREFIX}/aarch64-linux-android${ANDROID_ABI_64BIT}-" \
    --nm="${TOOLCHAIN_PREFIX}/llvm-nm" \
    --ar="${TOOLCHAIN_PREFIX}/llvm-ar" \
    --ranlib="${TOOLCHAIN_PREFIX}/llvm-ranlib" \
    --strip="${TOOLCHAIN_PREFIX}/llvm-strip" \
    ${COMMON_OPTIONS}
make -j$JOBS
make install-libs
make clean

echo ""
echo "=========================================="
echo "Building for x86..."
echo "=========================================="
./configure \
    --libdir=android-libs/x86 \
    --arch=x86 \
    --cpu=i686 \
    --cross-prefix="${TOOLCHAIN_PREFIX}/i686-linux-android${ANDROID_ABI}-" \
    --nm="${TOOLCHAIN_PREFIX}/llvm-nm" \
    --ar="${TOOLCHAIN_PREFIX}/llvm-ar" \
    --ranlib="${TOOLCHAIN_PREFIX}/llvm-ranlib" \
    --strip="${TOOLCHAIN_PREFIX}/llvm-strip" \
    --disable-asm \
    ${COMMON_OPTIONS}
make -j$JOBS
make install-libs
make clean

echo ""
echo "=========================================="
echo "Building for x86_64..."
echo "=========================================="
./configure \
    --libdir=android-libs/x86_64 \
    --arch=x86_64 \
    --cpu=x86-64 \
    --cross-prefix="${TOOLCHAIN_PREFIX}/x86_64-linux-android${ANDROID_ABI_64BIT}-" \
    --nm="${TOOLCHAIN_PREFIX}/llvm-nm" \
    --ar="${TOOLCHAIN_PREFIX}/llvm-ar" \
    --ranlib="${TOOLCHAIN_PREFIX}/llvm-ranlib" \
    --strip="${TOOLCHAIN_PREFIX}/llvm-strip" \
    --disable-asm \
    ${COMMON_OPTIONS}
make -j$JOBS
make install-libs
make clean

echo ""
echo "=========================================="
echo "Build completed successfully!"
echo "=========================================="
echo "Output libraries:"
ls -la android-libs/*/lib*.a
