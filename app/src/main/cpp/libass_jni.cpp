// JNI bridge exposing a minimal libass renderer to Kotlin. libass renders each
// frame to a linked list of single-channel alpha images (each with one solid
// colour); we composite them onto an Android ARGB_8888 Bitmap with a standard
// "source-over" blend, synced to the player's current position.
#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <cstring>
#include <ass/ass.h>

#define LOG_TAG "libass_jni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct Context {
    ASS_Library *library = nullptr;
    ASS_Renderer *renderer = nullptr;
    ASS_Track *track = nullptr;
};

inline Context *ctx(jlong handle) { return reinterpret_cast<Context *>(handle); }

// Blend one libass image (alpha coverage + single colour) over an RGBA8888 buffer.
void blendImage(uint8_t *dst, int frameW, int frameH, ASS_Image *img) {
    const uint32_t color = img->color;
    const uint32_t sr = (color >> 24) & 0xFF;
    const uint32_t sg = (color >> 16) & 0xFF;
    const uint32_t sb = (color >> 8) & 0xFF;
    const uint32_t opacity = 255 - (color & 0xFF); // libass encodes transparency

    for (int y = 0; y < img->h; y++) {
        const int dy = img->dst_y + y;
        if (dy < 0 || dy >= frameH) continue;
        const uint8_t *srcRow = img->bitmap + y * img->stride;
        for (int x = 0; x < img->w; x++) {
            const int dx = img->dst_x + x;
            if (dx < 0 || dx >= frameW) continue;
            const uint32_t k = srcRow[x];
            if (k == 0) continue;
            const uint32_t srcA = k * opacity / 255;
            if (srcA == 0) continue;

            uint8_t *p = dst + (dy * frameW + dx) * 4;
            const uint32_t dstA = p[3];
            const uint32_t outA = srcA + dstA * (255 - srcA) / 255;
            if (outA == 0) continue;
            p[0] = (sr * srcA + p[0] * dstA * (255 - srcA) / 255) / outA;
            p[1] = (sg * srcA + p[1] * dstA * (255 - srcA) / 255) / outA;
            p[2] = (sb * srcA + p[2] * dstA * (255 - srcA) / 255) / outA;
            p[3] = static_cast<uint8_t>(outA);
        }
    }
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_org_jellyfin_mobile_player_ui_libass_LibassRenderer_nativeInit(JNIEnv *, jobject) {
    auto *c = new Context();
    c->library = ass_library_init();
    if (!c->library) { delete c; return 0; }
    ass_set_extract_fonts(c->library, 1);
    c->renderer = ass_renderer_init(c->library);
    if (!c->renderer) { ass_library_done(c->library); delete c; return 0; }
    // AUTODETECT + no fontconfig: relies on embedded fonts and the fallback set below.
    ass_set_fonts(c->renderer, nullptr, "sans-serif", ASS_FONTPROVIDER_AUTODETECT, nullptr, 1);
    return reinterpret_cast<jlong>(c);
}

JNIEXPORT void JNICALL
Java_org_jellyfin_mobile_player_ui_libass_LibassRenderer_nativeSetFrameSize(
        JNIEnv *, jobject, jlong handle, jint width, jint height) {
    auto *c = ctx(handle);
    if (!c || !c->renderer) return;
    ass_set_frame_size(c->renderer, width, height);
    ass_set_storage_size(c->renderer, width, height);
}

JNIEXPORT void JNICALL
Java_org_jellyfin_mobile_player_ui_libass_LibassRenderer_nativeAddFont(
        JNIEnv *env, jobject, jlong handle, jstring name, jbyteArray data) {
    auto *c = ctx(handle);
    if (!c || !c->library || !data) return;
    const char *n = name ? env->GetStringUTFChars(name, nullptr) : "fallback";
    jsize len = env->GetArrayLength(data);
    jbyte *bytes = env->GetByteArrayElements(data, nullptr);
    ass_add_font(c->library, const_cast<char *>(n), reinterpret_cast<char *>(bytes), len);
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    if (name) env->ReleaseStringUTFChars(name, n);
    // Re-init font provider so the newly added font is picked up.
    if (c->renderer) ass_set_fonts(c->renderer, nullptr, "sans-serif", ASS_FONTPROVIDER_AUTODETECT, nullptr, 1);
}

JNIEXPORT jboolean JNICALL
Java_org_jellyfin_mobile_player_ui_libass_LibassRenderer_nativeSetTrack(
        JNIEnv *env, jobject, jlong handle, jbyteArray data) {
    auto *c = ctx(handle);
    if (!c || !c->library || !data) return JNI_FALSE;
    if (c->track) { ass_free_track(c->track); c->track = nullptr; }
    jsize len = env->GetArrayLength(data);
    jbyte *bytes = env->GetByteArrayElements(data, nullptr);
    c->track = ass_read_memory(c->library, reinterpret_cast<char *>(bytes), len, nullptr);
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    return c->track ? JNI_TRUE : JNI_FALSE;
}

// Returns: -1 no change since last render, 0 cleared (no subtitle now), 1 rendered.
JNIEXPORT jint JNICALL
Java_org_jellyfin_mobile_player_ui_libass_LibassRenderer_nativeRenderFrame(
        JNIEnv *env, jobject, jlong handle, jlong timeMs, jobject bitmap) {
    auto *c = ctx(handle);
    if (!c || !c->renderer || !c->track) return -1;

    int changed = 0;
    ASS_Image *img = ass_render_frame(c->renderer, c->track, timeMs, &changed);
    if (changed == 0) return -1;

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return -1;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return -1;

    void *pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) return -1;
    auto *dst = static_cast<uint8_t *>(pixels);
    std::memset(dst, 0, info.stride * info.height);

    jint result = 0;
    for (ASS_Image *cur = img; cur != nullptr; cur = cur->next) {
        blendImage(dst, static_cast<int>(info.width), static_cast<int>(info.height), cur);
        result = 1;
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return result;
}

JNIEXPORT void JNICALL
Java_org_jellyfin_mobile_player_ui_libass_LibassRenderer_nativeRelease(JNIEnv *, jobject, jlong handle) {
    auto *c = ctx(handle);
    if (!c) return;
    if (c->track) ass_free_track(c->track);
    if (c->renderer) ass_renderer_done(c->renderer);
    if (c->library) ass_library_done(c->library);
    delete c;
}

} // extern "C"
