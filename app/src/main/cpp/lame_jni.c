#include <jni.h>
#include <stdlib.h>
#include <android/log.h>
#include "lame.h"

#define TAG "LameJni"

/* Bitrate modes, must match EncodeMode in Settings.kt */
#define MODE_CBR 0
#define MODE_VBR 1
#define MODE_ABR 2

/* Channel modes, must match ChannelOut in Settings.kt */
#define CH_MONO 0
#define CH_STEREO 1
#define CH_JOINT 2

#define HIGHPASS_HZ 80
#define LOWPASS_HZ 15000

/* Approximate LAME 3.100 average bitrates for -V0..-V9 (44.1 kHz stereo). */
static const float V_KBPS[10] = {245, 225, 190, 175, 165, 130, 115, 100, 85, 65};

/* Maps a target average bitrate to a (fractional) VBR quality, 0 = best. */
static float vbr_quality_for_kbps(int kbps) {
    if (kbps >= V_KBPS[0]) return 0.0f;
    for (int i = 1; i < 10; i++) {
        if (kbps >= V_KBPS[i]) {
            float span = V_KBPS[i - 1] - V_KBPS[i];
            return (float) i - (kbps - V_KBPS[i]) / span;
        }
    }
    return 9.0f;
}

static lame_t configure(int inRate, int channels, int outRate, int mode, int kbps,
                        int channelMode, int highpass, int lowpassHz) {
    lame_t gf = lame_init();
    if (!gf) return NULL;

    lame_set_in_samplerate(gf, inRate);
    lame_set_num_channels(gf, channels);
    lame_set_out_samplerate(gf, outRate);
    lame_set_mode(gf, channels == 1 ? MONO : (channelMode == CH_STEREO ? STEREO : JOINT_STEREO));
    lame_set_quality(gf, 3);
    lame_set_write_id3tag_automatic(gf, 0);

    switch (mode) {
        case MODE_VBR:
            lame_set_VBR(gf, vbr_mtrh);
            lame_set_VBR_quality(gf, vbr_quality_for_kbps(kbps));
            /* Below V9's natural rate, cap peaks so the target is honoured. */
            if (kbps < V_KBPS[9]) lame_set_VBR_max_bitrate_kbps(gf, kbps * 3 / 2);
            lame_set_bWriteVbrTag(gf, 1);
            break;
        case MODE_ABR:
            lame_set_VBR(gf, vbr_abr);
            lame_set_VBR_mean_bitrate_kbps(gf, kbps);
            lame_set_bWriteVbrTag(gf, 1);
            break;
        default:
            lame_set_VBR(gf, vbr_off);
            lame_set_brate(gf, kbps);
            lame_set_bWriteVbrTag(gf, 0);
            break;
    }
    if (highpass) lame_set_highpassfreq(gf, HIGHPASS_HZ);
    if (lowpassHz > 0) lame_set_lowpassfreq(gf, lowpassHz);

    if (lame_init_params(gf) < 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "lame_init_params failed");
        lame_close(gf);
        return NULL;
    }
    return gf;
}

JNIEXPORT jlong JNICALL
Java_com_tdeletto_mp3bulk_encoder_LameEncoder_nativeInit(
        JNIEnv *env, jclass clazz, jint inRate, jint channels, jint outRate,
        jint mode, jint kbps, jint channelMode, jboolean highpass, jboolean lowpass) {
    int lowpassHz = 0;
    if (lowpass) {
        /* Never raise the encoder's own cutoff: use min(15 kHz, LAME's default for these settings). */
        lame_t probe = configure(inRate, channels, outRate, mode, kbps, channelMode, 0, 0);
        if (!probe) return 0;
        int def = lame_get_lowpassfreq(probe);
        lame_close(probe);
        lowpassHz = (def > 0 && def < LOWPASS_HZ) ? def : LOWPASS_HZ;
    }
    return (jlong) (intptr_t) configure(inRate, channels, outRate, mode, kbps, channelMode, highpass, lowpassHz);
}

/* pcm: interleaved 16-bit samples; frames = samples per channel. */
JNIEXPORT jint JNICALL
Java_com_tdeletto_mp3bulk_encoder_LameEncoder_nativeEncode(
        JNIEnv *env, jclass clazz, jlong handle, jshortArray pcm, jint frames,
        jbyteArray out) {
    lame_t gf = (lame_t) (intptr_t) handle;
    jsize outLen = (*env)->GetArrayLength(env, out);
    jshort *in = (*env)->GetPrimitiveArrayCritical(env, pcm, NULL);
    jbyte *buf = (*env)->GetPrimitiveArrayCritical(env, out, NULL);
    int n;
    if (lame_get_num_channels(gf) == 1) {
        n = lame_encode_buffer(gf, in, NULL, frames, (unsigned char *) buf, outLen);
    } else {
        n = lame_encode_buffer_interleaved(gf, in, frames, (unsigned char *) buf, outLen);
    }
    (*env)->ReleasePrimitiveArrayCritical(env, out, buf, 0);
    (*env)->ReleasePrimitiveArrayCritical(env, pcm, in, JNI_ABORT);
    return n;
}

JNIEXPORT jint JNICALL
Java_com_tdeletto_mp3bulk_encoder_LameEncoder_nativeFlush(
        JNIEnv *env, jclass clazz, jlong handle, jbyteArray out) {
    lame_t gf = (lame_t) (intptr_t) handle;
    jbyte *buf = (*env)->GetByteArrayElements(env, out, NULL);
    int n = lame_encode_flush(gf, (unsigned char *) buf, (*env)->GetArrayLength(env, out));
    (*env)->ReleaseByteArrayElements(env, out, buf, 0);
    return n;
}

/* Xing/LAME info frame to patch over the first frame (VBR/ABR only). */
JNIEXPORT jint JNICALL
Java_com_tdeletto_mp3bulk_encoder_LameEncoder_nativeTagFrame(
        JNIEnv *env, jclass clazz, jlong handle, jbyteArray out) {
    lame_t gf = (lame_t) (intptr_t) handle;
    if (!lame_get_bWriteVbrTag(gf)) return 0;
    jbyte *buf = (*env)->GetByteArrayElements(env, out, NULL);
    size_t n = lame_get_lametag_frame(gf, (unsigned char *) buf, (size_t) (*env)->GetArrayLength(env, out));
    (*env)->ReleaseByteArrayElements(env, out, buf, 0);
    return (jint) n;
}

JNIEXPORT jint JNICALL
Java_com_tdeletto_mp3bulk_encoder_LameEncoder_nativeOutSampleRate(
        JNIEnv *env, jclass clazz, jlong handle) {
    return lame_get_out_samplerate((lame_t) (intptr_t) handle);
}

JNIEXPORT void JNICALL
Java_com_tdeletto_mp3bulk_encoder_LameEncoder_nativeClose(
        JNIEnv *env, jclass clazz, jlong handle) {
    if (handle) lame_close((lame_t) (intptr_t) handle);
}
