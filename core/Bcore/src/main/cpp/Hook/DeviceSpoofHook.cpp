//
// Device identity spoof: hooks android/os/SystemProperties.native_get so
// every Java-side property read (SystemProperties.get, and any framework or
// game code built on it) sees the flagship profile. The Java side
// (DeviceSpoof.hookGet -> DeviceProfiles) decides which keys are rewritten;
// unlisted keys fall through to the original native implementation.
//

#include "DeviceSpoofHook.h"
#import "../JniHook/JniHook.h"

// Cached in init(): FindClass during a hooked call would resolve against the
// boot class loader, which cannot see the container's own classes.
static jclass sDeviceSpoofClass = nullptr;
static jmethodID sHookGet = nullptr;

static jstring callJavaSpoof(JNIEnv *env, jstring keyJ) {
    if (sDeviceSpoofClass == nullptr || sHookGet == nullptr) return nullptr;
    jstring value = (jstring) env->CallStaticObjectMethod(sDeviceSpoofClass, sHookGet, keyJ);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return nullptr;
    }
    return value;
}

HOOK_JNI(jstring, nativeGetSpoof, JNIEnv *env, jclass clazz, jstring keyJ) {
    jstring spoofed = callJavaSpoof(env, keyJ);
    if (spoofed != nullptr) return spoofed;
    return orig_nativeGetSpoof(env, clazz, keyJ);
}

HOOK_JNI(jstring, nativeGetSpoofDef, JNIEnv *env, jclass clazz, jstring keyJ, jstring defJ) {
    jstring spoofed = callJavaSpoof(env, keyJ);
    if (spoofed != nullptr) return spoofed;
    return orig_nativeGetSpoofDef(env, clazz, keyJ, defJ);
}

void DeviceSpoofHook::init(JNIEnv *env) {
    jclass local = env->FindClass("com/ccore/spoof/DeviceSpoof");
    if (local == nullptr) {
        env->ExceptionClear();
        return;
    }
    sDeviceSpoofClass = (jclass) env->NewGlobalRef(local);
    env->DeleteLocalRef(local);
    sHookGet = env->GetStaticMethodID(sDeviceSpoofClass, "hookGet",
                                      "(Ljava/lang/String;)Ljava/lang/String;");
    if (sHookGet == nullptr) {
        env->ExceptionClear();
    }
    const char *className = "android/os/SystemProperties";
    JniHook::HookJniFun(env, className, "native_get",
                        "(Ljava/lang/String;)Ljava/lang/String;",
                        (void *) new_nativeGetSpoof,
                        (void **) (&orig_nativeGetSpoof), true);
    JniHook::HookJniFun(env, className, "native_get",
                        "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                        (void *) new_nativeGetSpoofDef,
                        (void **) (&orig_nativeGetSpoofDef), true);
}
