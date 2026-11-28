//
// Device identity spoof: answers SystemProperties.native_get with the
// flagship profile inside virtual processes. Java source of truth:
// com.ccore.spoof.DeviceProfiles.
//

#ifndef DEVICESPOOFHOOK_H
#define DEVICESPOOFHOOK_H

#include <jni.h>

class DeviceSpoofHook {
public:
    static void init(JNIEnv *env);
};

#endif //DEVICESPOOFHOOK_H
