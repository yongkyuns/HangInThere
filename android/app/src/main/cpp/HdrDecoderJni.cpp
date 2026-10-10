#include "HdrDecoder.h"
#include <jni.h>
#include <cstring>
#include <exception>
extern "C" {
#include <libavutil/frame.h>
}

namespace {
void failure(JNIEnv* env, const char* message) {
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), message);
}
std::function<bool()> cancellation(JNIEnv* env, jobject callback) {
    auto type = env->GetObjectClass(callback);
    auto invoke = env->GetMethodID(type, "invoke", "()Ljava/lang/Object;");
    env->DeleteLocalRef(type);
    auto booleanType = env->FindClass("java/lang/Boolean");
    auto value = env->GetMethodID(booleanType, "booleanValue", "()Z");
    env->DeleteLocalRef(booleanType);
    return [env, callback, invoke, value] {
        if (env->ExceptionCheck()) return true;
        auto result = env->CallObjectMethod(callback, invoke);
        if (!result || env->ExceptionCheck()) return true;
        bool cancelled = env->CallBooleanMethod(result, value);
        env->DeleteLocalRef(result);
        return cancelled || env->ExceptionCheck();
    };
}
}
extern "C" JNIEXPORT jlong JNICALL
Java_dev_hanginthere_android_replay_SoftwareHdrVideo_nativeOpen(JNIEnv* env, jobject, jint fd, jlong offset, jlong length, jint transfer, jobject callback) {
    try { return reinterpret_cast<jlong>(new hanginthere::HdrDecoder(fd, offset, length, transfer, cancellation(env, callback))); }
    catch (const hanginthere::HdrCancelled&) { if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/util/concurrent/CancellationException"), "HDR import cancelled"); return 0; }
    catch (const std::exception& error) { failure(env, error.what()); return 0; }
}
extern "C" JNIEXPORT jobject JNICALL
Java_dev_hanginthere_android_replay_SoftwareHdrVideo_nativeNext(JNIEnv* env, jobject, jlong handle, jobject callback) {
    try {
        auto* decoder = reinterpret_cast<hanginthere::HdrDecoder*>(handle);
        const auto* frame = decoder->next(cancellation(env, callback));
        if (!frame) return nullptr;
        const int area = frame->width * frame->height;
        auto pixels = env->NewByteArray(area * 3 / 2);
        if (!pixels) return nullptr;
        int destination = 0;
        for (int plane = 0; plane < 3; ++plane) {
            const int width = frame->width / (plane ? 2 : 1);
            const int height = frame->height / (plane ? 2 : 1);
            for (int row = 0; row < height; ++row) {
                env->SetByteArrayRegion(pixels, destination, width,
                    reinterpret_cast<const jbyte*>(frame->data[plane] + row * frame->linesize[plane]));
                if (env->ExceptionCheck()) { env->DeleteLocalRef(pixels); return nullptr; }
                destination += width;
            }
        }
        auto type = env->FindClass("dev/hanginthere/android/replay/SoftwareHdrFrame");
        if (!type) { env->DeleteLocalRef(pixels); return nullptr; }
        auto constructor = env->GetMethodID(type, "<init>", "(IIJ[B)V");
        auto result = constructor ? env->NewObject(type, constructor, frame->width, frame->height, decoder->presentationTimeUs(), pixels) : nullptr;
        env->DeleteLocalRef(type); env->DeleteLocalRef(pixels);
        return result;
    } catch (const std::exception& error) { failure(env, error.what()); return nullptr; }
}
extern "C" JNIEXPORT void JNICALL
Java_dev_hanginthere_android_replay_SoftwareHdrVideo_nativeClose(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<hanginthere::HdrDecoder*>(handle);
}
