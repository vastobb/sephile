#include <jni.h>
#include <string>
#include <cstring>
#include <cstdio>

#include "ProcessMemory.h"
#include "MemoryScanner.h"

using namespace sephile;

// Scanners are per-session: every nativeInit() returns a fresh instance that
// the Kotlin side owns via its jlong handle (freed by nativeDestroy).
extern "C" JNIEXPORT jlong JNICALL
Java_com_sephile_app_root_NativeBridge_nativeInit(JNIEnv* env, jobject /*thiz*/) {
    return reinterpret_cast<jlong>(new MemoryScanner());
}

extern "C" JNIEXPORT void JNICALL
Java_com_sephile_app_root_NativeBridge_nativeDestroy(JNIEnv* env, jobject /*thiz*/, jlong handle) {
    auto* s = reinterpret_cast<MemoryScanner*>(handle);
    if (s) {
        s->detach();
        delete s;
    }
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

// -------- Lua scripting bindings --------
// NOTE: To enable real Lua 5.4, drop the lua-5.4 src tree into
// app/src/main/jni/lua/, add it to CMakeLists.txt, define SEPHILE_LUA_ENABLED,
// and replace these stubs with calls into luaL_loadstring/lua_pcall.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_sephile_app_root_NativeBridge_nativeLuaLoad(JNIEnv* /*env*/, jobject /*thiz*/) {
    // Stub: real implementation would luaL_newstate() here and store it.
    return JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_sephile_app_root_NativeBridge_nativeLuaExec(JNIEnv* env, jobject /*thiz*/, jstring script) {
    const char* s = env->GetStringUTFChars(script, nullptr);
    std::string out = std::string("Lua engine not bound yet. ")
        + "Add lua-5.4 source under app/src/main/jni/lua/ and implement "
        + "nativeLuaLoad/nativeLuaExec/nativeLuaBindApi in native_bridge.cpp.\n"
        + "Script received (" + std::to_string(std::strlen(s)) + " bytes): "
        + std::string(s);
    env->ReleaseStringUTFChars(script, s);
    return env->NewStringUTF(out.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_sephile_app_root_NativeBridge_nativeLuaBindApi(JNIEnv* /*env*/, jobject /*thiz*/) {
    // Stub: would expose sephile.{scan, edit, freeze, attach, getCandidates} to Lua.
    return JNI_FALSE;
}

// -------- Raw memory peek (for hex editor / live inspector) --------
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_sephile_app_root_NativeBridge_nativePeekMemory(JNIEnv* env, jobject /*thiz*/,
                                                        jlong handle, jlong address, jint length) {
    auto* s = reinterpret_cast<MemoryScanner*>(handle);
    if (!s || length <= 0) return env->NewByteArray(0);
    jbyteArray out = env->NewByteArray(length);
    if (out == nullptr) return nullptr;
    std::vector<uint8_t> buf(length);
    size_t got = s->memory().read(static_cast<uintptr_t>(address), buf.data(), length);
    if (got == 0) return env->NewByteArray(0);
    env->SetByteArrayRegion(out, 0, static_cast<jint>(got),
                            reinterpret_cast<const jbyte*>(buf.data()));
    return out;
}
