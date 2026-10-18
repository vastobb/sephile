#include <jni.h>
#include <string>
#include <cstring>

#include "ProcessMemory.h"
#include "MemoryScanner.h"

using namespace sephile;

// We keep a single global scanner instance for simplicity.
// A production app would use a per-session registry (jlong handles).
static MemoryScanner* g_scanner = nullptr;

extern "C" JNIEXPORT jlong JNICALL
Java_com_sephile_app_root_NativeBridge_nativeInit(JNIEnv* env, jobject /*thiz*/) {
    if (!g_scanner) g_scanner = new MemoryScanner();
    return reinterpret_cast<jlong>(g_scanner);
}

extern "C" JNIEXPORT void JNICALL
Java_com_sephile_app_root_NativeBridge_nativeDestroy(JNIEnv* env, jobject /*thiz*/, jlong handle) {
    auto* s = reinterpret_cast<MemoryScanner*>(handle);
    if (s) {
        s->detach();
        delete s;
    }
    if (g_scanner == s) g_scanner = nullptr;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_sephile_app_root_NativeBridge_nativeAttach(JNIEnv* env, jobject /*thiz*/, jlong handle, jint pid) {
    auto* s = reinterpret_cast<MemoryScanner*>(handle);
    if (!s) return JNI_FALSE;
    return s->attach(static_cast<pid_t>(pid)) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_sephile_app_root_NativeBridge_nativeDetach(JNIEnv* env, jobject /*thiz*/, jlong handle) {
    auto* s = reinterpret_cast<MemoryScanner*>(handle);
    if (s) s->detach();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_sephile_app_root_NativeBridge_nativeIsAttached(JNIEnv* env, jobject /*thiz*/, jlong handle) {
    auto* s = reinterpret_cast<MemoryScanner*>(handle);
    if (!s) return JNI_FALSE;
    return s->isAttached() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_sephile_app_root_NativeBridge_nativeFirstScan(JNIEnv* env, jobject /*thiz*/,
                                                       jlong handle, jint pid, jint type,
                                                       jint searchType, jlong targetValue,
                                                       jdouble targetValueF, jlong fuzzyDelta,
                                                       jboolean onlyWritable) {
    auto* s = reinterpret_cast<MemoryScanner*>(handle);
    if (!s) return -1;

    ScanOptions opts;
    opts.targetPid = pid;
    opts.type = static_cast<ValueType>(type);
    opts.searchType = static_cast<SearchType>(searchType);
    opts.targetValue = targetValue;
    opts.targetValueF = targetValueF;
    opts.fuzzyDelta = fuzzyDelta;
    opts.onlyWritable = onlyWritable == JNI_TRUE;

    return s->firstScan(opts);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_sephile_app_root_NativeBridge_nativeNextScan(JNIEnv* env, jobject /*thiz*/,
                                                      jlong handle, jint type, jint searchType,
                                                      jlong targetValue, jdouble targetValueF,
                                                      jlong fuzzyDelta, jboolean onlyWritable) {
    auto* s = reinterpret_cast<MemoryScanner*>(handle);
    if (!s) return -1;

    ScanOptions opts;
    opts.targetPid = s->memory().pid();
    opts.type = static_cast<ValueType>(type);
    opts.searchType = static_cast<SearchType>(searchType);
    opts.targetValue = targetValue;
    opts.targetValueF = targetValueF;
    opts.fuzzyDelta = fuzzyDelta;
    opts.onlyWritable = onlyWritable == JNI_TRUE;

    return s->nextScan(opts);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_sephile_app_root_NativeBridge_nativeCandidateCount(JNIEnv* env, jobject /*thiz*/, jlong handle) {
    auto* s = reinterpret_cast<MemoryScanner*>(handle);
    if (!s) return 0;
    return static_cast<jint>(s->candidates().size());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_sephile_app_root_NativeBridge_nativeCandidateAddress(JNIEnv* env, jobject /*thiz*/,
                                                              jlong handle, jint index) {
    auto* s = reinterpret_cast<MemoryScanner*>(handle);
    if (!s) return 0;
    const auto& c = s->candidates();
    if (index < 0 || index >= static_cast<jint>(c.size())) return 0;
    return static_cast<jlong>(c[static_cast<size_t>(index)].address);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_sephile_app_root_NativeBridge_nativeCandidateHex(JNIEnv* env, jobject /*thiz*/,
                                                          jlong handle, jint index) {
    auto* s = reinterpret_cast<MemoryScanner*>(handle);
    if (!s) return env->NewStringUTF("");
    const auto& c = s->candidates();
    if (index < 0 || index >= static_cast<jint>(c.size())) return env->NewStringUTF("");
    return env->NewStringUTF(c[static_cast<size_t>(index)].hexDump.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_sephile_app_root_NativeBridge_nativeEditValue(JNIEnv* env, jobject /*thiz*/,
                                                       jlong handle, jlong address, jint type,
                                                       jlong intVal, jdouble floatVal) {
    auto* s = reinterpret_cast<MemoryScanner*>(handle);
    if (!s) return JNI_FALSE;
    return s->editValue(static_cast<uintptr_t>(address),
                        static_cast<ValueType>(type),
                        intVal, floatVal) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_sephile_app_root_NativeBridge_nativeFreeze(JNIEnv* env, jobject /*thiz*/,
                                                    jlong handle, jlong address, jboolean enabled,
                                                    jstring value) {
    auto* s = reinterpret_cast<MemoryScanner*>(handle);
    if (!s || !value) return;
    const char* cstr = env->GetStringUTFChars(value, nullptr);
    std::string val(cstr ? cstr : "");
    if (cstr) env->ReleaseStringUTFChars(value, cstr);
    s->freeze(static_cast<uintptr_t>(address), enabled == JNI_TRUE, val);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_sephile_app_root_NativeBridge_nativeHasRoot(JNIEnv* env, jobject /*thiz*/) {
    // Simple root probe: try running "id" via su. Real check happens in Kotlin RootManager.
    FILE* f = popen("su -c id 2>/dev/null", "r");
    if (!f) return JNI_FALSE;
    char buf[128];
    bool ok = false;
    if (fgets(buf, sizeof(buf), f)) {
        ok = std::strstr(buf, "uid=0") != nullptr;
    }
    pclose(f);
    return ok ? JNI_TRUE : JNI_FALSE;
}
