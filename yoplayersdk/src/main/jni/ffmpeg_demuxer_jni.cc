/*
 * FFmpeg Demuxer JNI Implementation
 *
 * MPEG-TS 세그먼트를 메모리에서 디먹싱하여 오디오/비디오 샘플 추출
 */
#include <android/log.h>
#include <jni.h>
#include <stdlib.h>
#include <string.h>

extern "C" {
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libavutil/error.h>
#include <libavutil/mem.h>
}

#define LOG_TAG "ffmpeg_demuxer_jni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// 트랙 타입 상수 (Media3 C.TRACK_TYPE_* 와 호환)
static const int TRACK_TYPE_VIDEO = 2;
static const int TRACK_TYPE_AUDIO = 1;

// 샘플 플래그 상수
static const int SAMPLE_FLAG_KEY_FRAME = 1;
static const int SAMPLE_FLAG_DECODE_ONLY = 2;

// 에러 코드
static const int DEMUXER_ERROR_INIT_FAILED = -1;
static const int DEMUXER_ERROR_OPEN_FAILED = -2;
static const int DEMUXER_ERROR_NO_STREAMS = -3;
static const int DEMUXER_ERROR_READ_FAILED = -4;

// 메모리 버퍼에서 읽기 위한 구조체
struct BufferData {
    const uint8_t* ptr;
    size_t size;
    size_t pos;
};

// 디먹서 컨텍스트
struct DemuxerContext {
    AVFormatContext* fmt_ctx;
    AVIOContext* avio_ctx;
    uint8_t* avio_buffer;
    BufferData buffer_data;
    int video_stream_idx;
    int audio_stream_idx;
    bool initialized;
};

// AVIOContext read 콜백 - 메모리 버퍼에서 읽기
static int read_packet(void* opaque, uint8_t* buf, int buf_size) {
    BufferData* bd = (BufferData*)opaque;

    if (bd->pos >= bd->size) {
        return AVERROR_EOF;
    }

    size_t remaining = bd->size - bd->pos;
    size_t to_read = (size_t)buf_size < remaining ? (size_t)buf_size : remaining;

    memcpy(buf, bd->ptr + bd->pos, to_read);
    bd->pos += to_read;

    return (int)to_read;
}

// AVIOContext seek 콜백
static int64_t seek_packet(void* opaque, int64_t offset, int whence) {
    BufferData* bd = (BufferData*)opaque;

    switch (whence) {
        case SEEK_SET:
            bd->pos = (size_t)offset;
            break;
        case SEEK_CUR:
            bd->pos += (size_t)offset;
            break;
        case SEEK_END:
            bd->pos = bd->size + (size_t)offset;
            break;
        case AVSEEK_SIZE:
            return (int64_t)bd->size;
        default:
            return -1;
    }

    if (bd->pos > bd->size) {
        bd->pos = bd->size;
    }

    return (int64_t)bd->pos;
}

// 에러 메시지 로깅
static void log_error(const char* func, int error) {
    char errbuf[256];
    av_strerror(error, errbuf, sizeof(errbuf));
    LOGE("%s failed: %s", func, errbuf);
}

// 코덱 ID를 MIME 타입으로 변환
static const char* codec_id_to_mime(AVCodecID codec_id, int track_type) {
    switch (codec_id) {
        // 비디오 코덱
        case AV_CODEC_ID_H264:
            return "video/avc";
        case AV_CODEC_ID_HEVC:
            return "video/hevc";
        case AV_CODEC_ID_VP9:
            return "video/x-vnd.on2.vp9";
        case AV_CODEC_ID_AV1:
            return "video/av01";
        case AV_CODEC_ID_MPEG2VIDEO:
            return "video/mpeg2";
        case AV_CODEC_ID_MPEG4:
            return "video/mp4v-es";

        // 오디오 코덱
        case AV_CODEC_ID_AAC:
            return "audio/mp4a-latm";
        case AV_CODEC_ID_MP3:
            return "audio/mpeg";
        case AV_CODEC_ID_AC3:
            return "audio/ac3";
        case AV_CODEC_ID_EAC3:
            return "audio/eac3";
        case AV_CODEC_ID_DTS:
            return "audio/vnd.dts";
        case AV_CODEC_ID_OPUS:
            return "audio/opus";
        case AV_CODEC_ID_VORBIS:
            return "audio/vorbis";
        case AV_CODEC_ID_FLAC:
            return "audio/flac";

        default:
            return track_type == TRACK_TYPE_VIDEO ? "video/unknown" : "audio/unknown";
    }
}

// JNI 매크로
#define DEMUXER_FUNC(RETURN_TYPE, NAME, ...)                                    \
    extern "C" {                                                                \
    JNIEXPORT RETURN_TYPE JNICALL                                               \
    Java_com_yohan_yoplayersdk_demuxer_FfmpegDemuxer_##NAME(JNIEnv* env,        \
                                                            jobject thiz,       \
                                                            ##__VA_ARGS__);     \
    }                                                                           \
    JNIEXPORT RETURN_TYPE JNICALL                                               \
    Java_com_yohan_yoplayersdk_demuxer_FfmpegDemuxer_##NAME(                    \
        JNIEnv* env, jobject thiz, ##__VA_ARGS__)

/**
 * 디먹서 초기화
 * @return 네이티브 컨텍스트 포인터 (0이면 실패)
 */
DEMUXER_FUNC(jlong, nativeInit) {
    DemuxerContext* ctx = (DemuxerContext*)av_mallocz(sizeof(DemuxerContext));
    if (!ctx) {
        LOGE("Failed to allocate DemuxerContext");
        return 0;
    }

    ctx->fmt_ctx = nullptr;
    ctx->avio_ctx = nullptr;
    ctx->avio_buffer = nullptr;
    ctx->video_stream_idx = -1;
    ctx->audio_stream_idx = -1;
    ctx->initialized = false;

    LOGI("Demuxer initialized");
    return (jlong)ctx;
}

/**
 * 세그먼트 데이터를 디먹싱하여 트랙 정보 반환
 * @param context 네이티브 컨텍스트
 * @param data TS 세그먼트 바이트 배열
 * @return TrackInfo 배열 (jobjectArray)
 */
DEMUXER_FUNC(jobjectArray, nativeProbeSegment, jlong context, jbyteArray data) {
    DemuxerContext* ctx = (DemuxerContext*)context;
    if (!ctx) {
        LOGE("Invalid context");
        return nullptr;
    }

    jsize data_size = env->GetArrayLength(data);
    jbyte* data_ptr = env->GetByteArrayElements(data, nullptr);
    if (!data_ptr) {
        LOGE("Failed to get byte array elements");
        return nullptr;
    }

    // 버퍼 데이터 설정
    ctx->buffer_data.ptr = (const uint8_t*)data_ptr;
    ctx->buffer_data.size = (size_t)data_size;
    ctx->buffer_data.pos = 0;

    // AVIO 버퍼 할당
    const int avio_buffer_size = 32768;
    ctx->avio_buffer = (uint8_t*)av_malloc(avio_buffer_size);
    if (!ctx->avio_buffer) {
        LOGE("Failed to allocate AVIO buffer");
        env->ReleaseByteArrayElements(data, data_ptr, JNI_ABORT);
        return nullptr;
    }

    // 커스텀 AVIO 컨텍스트 생성
    ctx->avio_ctx = avio_alloc_context(
        ctx->avio_buffer,
        avio_buffer_size,
        0,  // write_flag = 0 (읽기 전용)
        &ctx->buffer_data,
        read_packet,
        nullptr,  // write_packet
        seek_packet
    );

    if (!ctx->avio_ctx) {
        LOGE("Failed to allocate AVIO context");
        av_free(ctx->avio_buffer);
        ctx->avio_buffer = nullptr;
        env->ReleaseByteArrayElements(data, data_ptr, JNI_ABORT);
        return nullptr;
    }

    // AVFormatContext 생성
    ctx->fmt_ctx = avformat_alloc_context();
    if (!ctx->fmt_ctx) {
        LOGE("Failed to allocate format context");
        avio_context_free(&ctx->avio_ctx);
        ctx->avio_buffer = nullptr;
        env->ReleaseByteArrayElements(data, data_ptr, JNI_ABORT);
        return nullptr;
    }

    ctx->fmt_ctx->pb = ctx->avio_ctx;
    ctx->fmt_ctx->flags |= AVFMT_FLAG_CUSTOM_IO;

    // 입력 포맷 열기 (MPEG-TS 자동 감지)
    int ret = avformat_open_input(&ctx->fmt_ctx, nullptr, nullptr, nullptr);
    if (ret < 0) {
        log_error("avformat_open_input", ret);
        avio_context_free(&ctx->avio_ctx);
        ctx->avio_buffer = nullptr;
        env->ReleaseByteArrayElements(data, data_ptr, JNI_ABORT);
        return nullptr;
    }

    // 스트림 정보 찾기
    ret = avformat_find_stream_info(ctx->fmt_ctx, nullptr);
    if (ret < 0) {
        log_error("avformat_find_stream_info", ret);
        avformat_close_input(&ctx->fmt_ctx);
        ctx->avio_buffer = nullptr;
        env->ReleaseByteArrayElements(data, data_ptr, JNI_ABORT);
        return nullptr;
    }

    // 비디오/오디오 스트림 인덱스 찾기
    int track_count = 0;
    for (unsigned int i = 0; i < ctx->fmt_ctx->nb_streams; i++) {
        AVStream* stream = ctx->fmt_ctx->streams[i];
        if (stream->codecpar->codec_type == AVMEDIA_TYPE_VIDEO) {
            ctx->video_stream_idx = i;
            track_count++;
        } else if (stream->codecpar->codec_type == AVMEDIA_TYPE_AUDIO) {
            ctx->audio_stream_idx = i;
            track_count++;
        }
    }

    LOGI("Found %d tracks (video_idx=%d, audio_idx=%d)",
         track_count, ctx->video_stream_idx, ctx->audio_stream_idx);

    // TrackFormat 클래스 참조
    jclass trackFormatClass = env->FindClass("com/yohan/yoplayersdk/demuxer/TrackFormat");
    if (!trackFormatClass) {
        LOGE("Failed to find TrackFormat class");
        avformat_close_input(&ctx->fmt_ctx);
        ctx->avio_buffer = nullptr;
        env->ReleaseByteArrayElements(data, data_ptr, JNI_ABORT);
        return nullptr;
    }

    // TrackFormat 생성자
    jmethodID trackFormatConstructor = env->GetMethodID(
        trackFormatClass, "<init>",
        "(ILjava/lang/String;II[BII)V"
    );

    // 결과 배열 생성
    jobjectArray result = env->NewObjectArray(track_count, trackFormatClass, nullptr);
    int idx = 0;

    // 비디오 트랙 정보 추가
    if (ctx->video_stream_idx >= 0) {
        AVStream* stream = ctx->fmt_ctx->streams[ctx->video_stream_idx];
        AVCodecParameters* codecpar = stream->codecpar;

        const char* mime = codec_id_to_mime(codecpar->codec_id, TRACK_TYPE_VIDEO);
        jstring mimeStr = env->NewStringUTF(mime);

        // extradata (SPS/PPS 등)
        jbyteArray extraData = nullptr;
        if (codecpar->extradata && codecpar->extradata_size > 0) {
            extraData = env->NewByteArray(codecpar->extradata_size);
            env->SetByteArrayRegion(extraData, 0, codecpar->extradata_size,
                                    (jbyte*)codecpar->extradata);
        }

        jobject trackFormat = env->NewObject(
            trackFormatClass, trackFormatConstructor,
            TRACK_TYPE_VIDEO,
            mimeStr,
            codecpar->width,
            codecpar->height,
            extraData,
            0,  // sampleRate (비디오는 0)
            0   // channelCount (비디오는 0)
        );

        env->SetObjectArrayElement(result, idx++, trackFormat);
        env->DeleteLocalRef(mimeStr);
        if (extraData) env->DeleteLocalRef(extraData);
        env->DeleteLocalRef(trackFormat);
    }

    // 오디오 트랙 정보 추가
    if (ctx->audio_stream_idx >= 0) {
        AVStream* stream = ctx->fmt_ctx->streams[ctx->audio_stream_idx];
        AVCodecParameters* codecpar = stream->codecpar;

        const char* mime = codec_id_to_mime(codecpar->codec_id, TRACK_TYPE_AUDIO);
        jstring mimeStr = env->NewStringUTF(mime);

        // extradata (AudioSpecificConfig 등)
        jbyteArray extraData = nullptr;
        if (codecpar->extradata && codecpar->extradata_size > 0) {
            extraData = env->NewByteArray(codecpar->extradata_size);
            env->SetByteArrayRegion(extraData, 0, codecpar->extradata_size,
                                    (jbyte*)codecpar->extradata);
        }

        jobject trackFormat = env->NewObject(
            trackFormatClass, trackFormatConstructor,
            TRACK_TYPE_AUDIO,
            mimeStr,
            0,  // width (오디오는 0)
            0,  // height (오디오는 0)
            extraData,
            codecpar->sample_rate,
            codecpar->ch_layout.nb_channels
        );

        env->SetObjectArrayElement(result, idx++, trackFormat);
        env->DeleteLocalRef(mimeStr);
        if (extraData) env->DeleteLocalRef(extraData);
        env->DeleteLocalRef(trackFormat);
    }

    ctx->initialized = true;

    // 바이트 배열 릴리즈 (JNI_ABORT: 변경 없이 해제)
    env->ReleaseByteArrayElements(data, data_ptr, JNI_ABORT);

    return result;
}

/**
 * 세그먼트에서 샘플 추출
 * @param context 네이티브 컨텍스트
 * @param data TS 세그먼트 바이트 배열
 * @return DemuxedSample 배열
 */
DEMUXER_FUNC(jobjectArray, nativeDemuxSegment, jlong context, jbyteArray data) {
    DemuxerContext* ctx = (DemuxerContext*)context;
    if (!ctx) {
        LOGE("Invalid context");
        return nullptr;
    }

    jsize data_size = env->GetArrayLength(data);
    jbyte* data_ptr = env->GetByteArrayElements(data, nullptr);
    if (!data_ptr) {
        LOGE("Failed to get byte array elements");
        return nullptr;
    }

    // 이전 컨텍스트 정리
    if (ctx->fmt_ctx) {
        avformat_close_input(&ctx->fmt_ctx);
        ctx->avio_buffer = nullptr;  // avformat_close_input이 해제함
    }
    if (ctx->avio_ctx) {
        avio_context_free(&ctx->avio_ctx);
    }

    // 버퍼 데이터 설정
    ctx->buffer_data.ptr = (const uint8_t*)data_ptr;
    ctx->buffer_data.size = (size_t)data_size;
    ctx->buffer_data.pos = 0;

    // AVIO 버퍼 할당
    const int avio_buffer_size = 32768;
    ctx->avio_buffer = (uint8_t*)av_malloc(avio_buffer_size);
    if (!ctx->avio_buffer) {
        env->ReleaseByteArrayElements(data, data_ptr, JNI_ABORT);
        return nullptr;
    }

    // 커스텀 AVIO 컨텍스트 생성
    ctx->avio_ctx = avio_alloc_context(
        ctx->avio_buffer, avio_buffer_size, 0,
        &ctx->buffer_data, read_packet, nullptr, seek_packet
    );

    if (!ctx->avio_ctx) {
        av_free(ctx->avio_buffer);
        ctx->avio_buffer = nullptr;
        env->ReleaseByteArrayElements(data, data_ptr, JNI_ABORT);
        return nullptr;
    }

    // AVFormatContext 생성
    ctx->fmt_ctx = avformat_alloc_context();
    ctx->fmt_ctx->pb = ctx->avio_ctx;
    ctx->fmt_ctx->flags |= AVFMT_FLAG_CUSTOM_IO;

    if (avformat_open_input(&ctx->fmt_ctx, nullptr, nullptr, nullptr) < 0) {
        avio_context_free(&ctx->avio_ctx);
        ctx->avio_buffer = nullptr;
        env->ReleaseByteArrayElements(data, data_ptr, JNI_ABORT);
        return nullptr;
    }

    avformat_find_stream_info(ctx->fmt_ctx, nullptr);

    // 스트림 인덱스 업데이트
    ctx->video_stream_idx = -1;
    ctx->audio_stream_idx = -1;
    for (unsigned int i = 0; i < ctx->fmt_ctx->nb_streams; i++) {
        if (ctx->fmt_ctx->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_VIDEO) {
            ctx->video_stream_idx = i;
        } else if (ctx->fmt_ctx->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_AUDIO) {
            ctx->audio_stream_idx = i;
        }
    }

    // DemuxedSample 클래스 참조
    jclass sampleClass = env->FindClass("com/yohan/yoplayersdk/demuxer/DemuxedSample");
    jmethodID sampleConstructor = env->GetMethodID(
        sampleClass, "<init>", "(IJI[B)V"
    );

    // 먼저 패킷 수를 세기 위해 임시 벡터 사용 (최대 1000개로 제한)
    const int MAX_SAMPLES = 2000;
    jobject samples[MAX_SAMPLES];
    int sample_count = 0;

    AVPacket* pkt = av_packet_alloc();

    while (av_read_frame(ctx->fmt_ctx, pkt) >= 0 && sample_count < MAX_SAMPLES) {
        int stream_idx = pkt->stream_index;

        // 비디오 또는 오디오 스트림만 처리
        if (stream_idx != ctx->video_stream_idx && stream_idx != ctx->audio_stream_idx) {
            av_packet_unref(pkt);
            continue;
        }

        AVStream* stream = ctx->fmt_ctx->streams[stream_idx];
        int track_type = (stream_idx == ctx->video_stream_idx) ? TRACK_TYPE_VIDEO : TRACK_TYPE_AUDIO;

        // PTS를 마이크로초로 변환
        int64_t time_us = 0;
        if (pkt->pts != AV_NOPTS_VALUE) {
            time_us = av_rescale_q(pkt->pts, stream->time_base, {1, 1000000});
        } else if (pkt->dts != AV_NOPTS_VALUE) {
            time_us = av_rescale_q(pkt->dts, stream->time_base, {1, 1000000});
        }

        // 플래그 설정
        int flags = 0;
        if (pkt->flags & AV_PKT_FLAG_KEY) {
            flags |= SAMPLE_FLAG_KEY_FRAME;
        }

        // 패킷 데이터를 ByteArray로 복사
        jbyteArray sampleData = env->NewByteArray(pkt->size);
        env->SetByteArrayRegion(sampleData, 0, pkt->size, (jbyte*)pkt->data);

        // DemuxedSample 객체 생성
        jobject sample = env->NewObject(
            sampleClass, sampleConstructor,
            track_type,
            time_us,
            flags,
            sampleData
        );

        samples[sample_count++] = sample;

        env->DeleteLocalRef(sampleData);
        av_packet_unref(pkt);
    }

    av_packet_free(&pkt);

    // 결과 배열 생성
    jobjectArray result = env->NewObjectArray(sample_count, sampleClass, nullptr);
    for (int i = 0; i < sample_count; i++) {
        env->SetObjectArrayElement(result, i, samples[i]);
        env->DeleteLocalRef(samples[i]);
    }

    env->ReleaseByteArrayElements(data, data_ptr, JNI_ABORT);

    LOGI("Demuxed %d samples", sample_count);
    return result;
}

/**
 * 디먹서 리소스 해제
 */
DEMUXER_FUNC(void, nativeRelease, jlong context) {
    DemuxerContext* ctx = (DemuxerContext*)context;
    if (!ctx) {
        return;
    }

    if (ctx->fmt_ctx) {
        avformat_close_input(&ctx->fmt_ctx);
        ctx->avio_buffer = nullptr;
    }

    if (ctx->avio_ctx) {
        avio_context_free(&ctx->avio_ctx);
    }

    av_free(ctx);
    LOGI("Demuxer released");
}

/**
 * FFmpeg 버전 반환
 */
DEMUXER_FUNC(jstring, nativeGetVersion) {
    char version[128];
    snprintf(version, sizeof(version), "libavformat %d.%d.%d, libavcodec %d.%d.%d",
             LIBAVFORMAT_VERSION_MAJOR, LIBAVFORMAT_VERSION_MINOR, LIBAVFORMAT_VERSION_MICRO,
             LIBAVCODEC_VERSION_MAJOR, LIBAVCODEC_VERSION_MINOR, LIBAVCODEC_VERSION_MICRO);
    return env->NewStringUTF(version);
}
