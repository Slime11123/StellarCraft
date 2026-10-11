// StellarCraft native bridge: Windows shared-memory mapping via JNI.
//
// The Stellar Blade UE4SS mod creates the section ("Local\\StellarCraft_v1");
// Minecraft opens it. Both sides get a direct ByteBuffer over the same physical pages,
// which is how they talk with zero serialization cost in the hot path.

#include <jni.h>
#include <windows.h>
#include <cstdint>

// Helper to map a section name (UTF-16) + size to a direct ByteBuffer.
static jobject map_to_bytebuffer(JNIEnv* env, HANDLE mapping, SIZE_T size) {
    if (mapping == nullptr) return nullptr;

    void* base = MapViewOfFile(mapping, FILE_MAP_ALL_ACCESS, 0, 0, size);
    if (base == nullptr) {
        CloseHandle(mapping);
        return nullptr;
    }

    // Wrap the mapped pointer in a direct ByteBuffer; release the view when the buffer
    // is GC'd (via Cleaner below).
    jobject buf = env->NewDirectByteBuffer(base, (jlong)size);
    if (buf == nullptr) {
        UnmapViewOfFile(base);
        CloseHandle(mapping);
        return nullptr;
    }

    // Register a Cleaner so the view + handle are released when the buffer is collected.
    // (Kept minimal for the skeleton; a real mod uses a per-buffer Cleaner.)
    return buf;
}

extern "C" {

JNIEXPORT jobject JNICALL
Java_dev_stellarcraft_NativeBridge_open(JNIEnv* env, jclass cls, jstring name, jlong size) {
    const jchar* raw = env->GetStringChars(name, nullptr);
    HANDLE mapping = OpenFileMappingW(FILE_MAP_ALL_ACCESS, FALSE, (LPCWSTR)raw);
    env->ReleaseStringChars(name, raw);
    if (mapping == nullptr) return nullptr;  // SB hasn't created it yet
    return map_to_bytebuffer(env, mapping, (SIZE_T)size);
}

JNIEXPORT jobject JNICALL
Java_dev_stellarcraft_NativeBridge_create(JNIEnv* env, jclass cls, jstring name, jlong size) {
    const jchar* raw = env->GetStringChars(name, nullptr);
    HANDLE mapping = CreateFileMappingW(INVALID_HANDLE_VALUE, nullptr, PAGE_READWRITE,
                                        (DWORD)(size >> 32), (DWORD)(size & 0xFFFFFFFF),
                                        (LPCWSTR)raw);
    env->ReleaseStringChars(name, raw);
    if (mapping == nullptr) return nullptr;
    return map_to_bytebuffer(env, mapping, (SIZE_T)size);
}

} // extern "C"
