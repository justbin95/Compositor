#include <jni.h>
#include <cstddef>
#include <cstdint>
#include <limits>

extern "C" {
#include "BrushPixels.h"
}

namespace {

void throwIllegalArgument(JNIEnv *env, const char *message) {
    jclass exception = env->FindClass("java/lang/IllegalArgumentException");
    if (exception != nullptr) env->ThrowNew(exception, message);
}

jintArray nativeAlphaBounds(JNIEnv *env, jobject, jobject buffer, jint width, jint height, jint stride) {
    if (width <= 0 || height <= 0 || stride <= 0) {
        throwIllegalArgument(env, "Pixel buffer dimensions and row stride must be positive.");
        return nullptr;
    }

    const auto pixelWidth = static_cast<std::size_t>(width);
    const auto pixelHeight = static_cast<std::size_t>(height);
    const auto rowStride = static_cast<std::size_t>(stride);
    if (pixelWidth > std::numeric_limits<std::size_t>::max() / 4) {
        throwIllegalArgument(env, "Pixel row size overflows the native address range.");
        return nullptr;
    }
    const std::size_t rowBytes = pixelWidth * 4;
    if (rowStride < rowBytes) {
        throwIllegalArgument(env, "Row stride is shorter than one pixel row.");
        return nullptr;
    }
    if (pixelHeight - 1 > (std::numeric_limits<std::size_t>::max() - rowBytes) / rowStride) {
        throwIllegalArgument(env, "Pixel buffer size overflows the native address range.");
        return nullptr;
    }

    const std::size_t requiredBytes = (pixelHeight - 1) * rowStride + rowBytes;
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    auto *bytes = static_cast<std::uint8_t *>(env->GetDirectBufferAddress(buffer));
    if (bytes == nullptr || capacity < 0 || requiredBytes > static_cast<std::size_t>(capacity)) {
        throwIllegalArgument(env, "Pixel data must be a direct buffer containing the complete image.");
        return nullptr;
    }

    std::size_t bounds[4] = {};
    brush_alpha_bounds(bytes, pixelWidth, pixelHeight, rowStride, bounds);
    const jint resultValues[4] = {
        static_cast<jint>(bounds[0]), static_cast<jint>(bounds[1]),
        static_cast<jint>(bounds[2]), static_cast<jint>(bounds[3])
    };
    jintArray result = env->NewIntArray(4);
    if (result != nullptr) env->SetIntArrayRegion(result, 0, 4, resultValues);
    return result;
}

}  // namespace

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;

    jclass bridge = env->FindClass("com/wonderassembly/compositor/NativePixelCore");
    if (bridge == nullptr) return JNI_ERR;
    const JNINativeMethod methods[] = {
        {const_cast<char *>("nativeAlphaBounds"), const_cast<char *>("(Ljava/nio/ByteBuffer;III)[I"),
         reinterpret_cast<void *>(nativeAlphaBounds)}
    };
    if (env->RegisterNatives(bridge, methods, 1) != JNI_OK) return JNI_ERR;
    return JNI_VERSION_1_6;
}
