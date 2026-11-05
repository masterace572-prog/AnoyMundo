/*
 * Harmless placeholder libbgmi.so.
 *
 * The real game library is supplied by the panel owner: build a ZIP containing
 * the real arm64-v8a libbgmi.so and upload it at /lib. This stub only exists so
 * the download -> extract -> ELF validation -> System.load() chain can be
 * tested end to end before the real library is available.
 */
#include <jni.h>
#include <android/log.h>

#define STUB_TAG "AnoyBgmiStub"

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) vm;
    (void) reserved;
    __android_log_print(ANDROID_LOG_INFO, STUB_TAG,
                        "placeholder libbgmi.so loaded; publish the real library at /lib");
    return JNI_VERSION_1_6;
}
