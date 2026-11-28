#include "cold_common.h"
#include "cold_overlay.h"
#include <thread>
#include <chrono>

namespace cold {

static void backgroundMonitoringThread() {
    LOGI("CCore Cold in-process agent active on TID: %d, PID: %d", gettid(), getpid());

    // Monitor loaded shared objects in process memory space
    int retryCount = 0;
    while (retryCount < 30) {
        std::this_thread::sleep_for(std::chrono::milliseconds(500));
        
        FILE* fp = fopen("/proc/self/maps", "re");
        if (fp) {
            char line[512];
            bool foundEngine = false;
            while (fgets(line, sizeof(line), fp)) {
                if (strstr(line, "libUE4.so") || strstr(line, "libunity.so") || strstr(line, "libmain.so")) {
                    foundEngine = true;
                    LOGI("Detected target game engine binary in maps: %s", line);
                    break;
                }
            }
            fclose(fp);

            if (foundEngine) {
                LOGI("Target game engine initialized in process space. Cold hooks synchronized.");
                break;
            }
        }
        retryCount++;
    }
}

} // namespace cold

// JNI Entry point called upon System.loadLibrary("cold")
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    LOGI("=== CCore Cold Runtime Module Loaded (libcold.so) ===");
    LOGI("PID: %d | UID: %d", getpid(), getuid());

    // Detach monitoring thread
    std::thread(cold::backgroundMonitoringThread).detach();

    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* vm, void* reserved) {
    LOGI("CCore Cold Runtime Module Unloaded");
    cold::OverlayEngine::get().shutdown();
}
