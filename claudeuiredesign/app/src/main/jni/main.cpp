#include <jni.h>
#include <string>
#include <cstdio>
#include <cstdlib>
#include <ctime>
#include <android/log.h>
#include <curl/curl.h>
#include <openssl/evp.h>
#include <openssl/pem.h>
#include <openssl/rsa.h>
#include <openssl/err.h>
#include <openssl/md5.h>
#include <openssl/aes.h>
#include <json.hpp>
#include <obfuscate.h>
#include "oxorany.h"
#include <openssl/sha.h>
#include "StrEnc.h"
#include "panel_config.h"
#include <zlib.h>  // For CRC32
using json = nlohmann::ordered_json;

time_t rng = 0;
std::string Enc;
static char ZENINOP[64];
static std::string exdate = oxorany ("NULL");

std::string g_Token, g_Auth;

/* Real status / "safe text" set by the admin panel at /Server. Exposed to the
   UI so it never shows a hardcoded "Safe" that disagrees with the panel. */
static std::string g_ModStatus = "Safe";
static std::string g_Credit = "";

bool xConnected = false, xServerConnection = false, memek = false;

std::string CalcMD5(std::string s);
/* Stable 36-char id when java.util.UUID is unavailable (never expected). */
static std::string CalcFallbackId(const std::string &seed) {
    std::string h = CalcMD5(seed);
    return h.substr(0, 8) + "-" + h.substr(8, 4) + "-" + h.substr(12, 4) + "-" +
           h.substr(16, 4) + "-" + h.substr(20, 12);
}


extern "C"
JNIEXPORT jstring JNICALL
Java_com_xlox_MAct_exdate(JNIEnv *env, jclass clazz) {
    return env->NewStringUTF(exdate.c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_xlox_MAct_modStatus(JNIEnv *env, jclass clazz) {
    return env->NewStringUTF(g_ModStatus.c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_xlox_MAct_credit(JNIEnv *env, jclass clazz) {
    return env->NewStringUTF(g_Credit.c_str());
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_xlox_MAct_ZENINOP(JNIEnv *env, jobject activityObject) {
    return env->NewStringUTF(ZENINOP);
}
extern "C"
JNIEXPORT jstring JNICALL
Java_com_xlox_LogAct_GetKey(JNIEnv *env, jobject thiz) {
    return env->NewStringUTF(oxorany(ANOY_TELEGRAM_URL)); // Official contact
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_xlox_utils_Downtwo_Version(JNIEnv *env, jclass clazz) {
    // Panel endpoint: plain-text version of the currently published loader ZIP.
    const char *versionUrl = (oxorany(ANOY_VERSION_URL));
    return env->NewStringUTF(versionUrl);
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_xlox_utils_Downtwo_Link(JNIEnv *env, jclass clazz) {
    // Panel endpoint: binary ZIP uploaded at Admin -> Library (/lib).
    const char *downloadUrl = (oxorany(ANOY_DOWNLOAD_URL));
    return env->NewStringUTF(downloadUrl);
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_xlox_BoxApplication_getSdkKey(JNIEnv *env, jclass clazz) {
    return env->NewStringUTF(oxorany("OH8BRKOYC6VZXFGZGHUB"));//sdk key
}


/* JNI helpers. Each returns an owned std::string and never leaves a pending
   Java exception behind, so a missing value can never crash the login. */
static std::string JStringToStd(JNIEnv *env, jstring str) {
    if (env == nullptr || str == nullptr) return std::string();
    const char *chars = env->GetStringUTFChars(str, nullptr);
    if (chars == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return std::string();
    }
    std::string out(chars);
    env->ReleaseStringUTFChars(str, chars);
    return out;
}

static bool ClearJniException(JNIEnv *env) {
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return true;
    }
    return false;
}

std::string GetAndroidID(JNIEnv *env, jobject context) {
    if (context == nullptr) return std::string();
    jclass contextClass = env->FindClass("android/content/Context");
    if (ClearJniException(env) || contextClass == nullptr) return std::string();
    jmethodID getContentResolverMethod = env->GetMethodID(contextClass, "getContentResolver",
                                                          "()Landroid/content/ContentResolver;");
    jclass settingSecureClass = env->FindClass("android/provider/Settings$Secure");
    if (ClearJniException(env) || getContentResolverMethod == nullptr || settingSecureClass == nullptr) {
        return std::string();
    }
    jmethodID getStringMethod = env->GetStaticMethodID(settingSecureClass, "getString",
            "(Landroid/content/ContentResolver;Ljava/lang/String;)Ljava/lang/String;");
    if (ClearJniException(env) || getStringMethod == nullptr) return std::string();
    jobject resolver = env->CallObjectMethod(context, getContentResolverMethod);
    if (ClearJniException(env) || resolver == nullptr) return std::string();
    jstring name = env->NewStringUTF("android_id");
    auto str = (jstring) env->CallStaticObjectMethod(settingSecureClass, getStringMethod, resolver, name);
    if (ClearJniException(env)) return std::string();
    std::string out = JStringToStd(env, str);
    env->DeleteLocalRef(name);
    env->DeleteLocalRef(resolver);
    if (str != nullptr) env->DeleteLocalRef(str);
    return out;
}

static std::string GetBuildField(JNIEnv *env, const char *field) {
    jclass buildClass = env->FindClass("android/os/Build");
    if (ClearJniException(env) || buildClass == nullptr) return std::string();
    jfieldID id = env->GetStaticFieldID(buildClass, field, "Ljava/lang/String;");
    if (ClearJniException(env) || id == nullptr) return std::string();
    auto str = (jstring) env->GetStaticObjectField(buildClass, id);
    if (ClearJniException(env)) return std::string();
    std::string out = JStringToStd(env, str);
    if (str != nullptr) env->DeleteLocalRef(str);
    return out;
}

std::string GetDeviceModel(JNIEnv *env) { return GetBuildField(env, "MODEL"); }

std::string GetDeviceBrand(JNIEnv *env) { return GetBuildField(env, "BRAND"); }

std::string GetDeviceUniqueIdentifier(JNIEnv *env, const std::string &seed) {
    jclass uuidClass = env->FindClass("java/util/UUID");
    if (ClearJniException(env) || uuidClass == nullptr) return CalcFallbackId(seed);
    auto len = (jsize) seed.size();
    jbyteArray bytes = env->NewByteArray(len);
    if (bytes == nullptr) {
        ClearJniException(env);
        return CalcFallbackId(seed);
    }
    env->SetByteArrayRegion(bytes, 0, len, (const jbyte *) seed.data());
    jmethodID fromBytes = env->GetStaticMethodID(uuidClass, "nameUUIDFromBytes", "([B)Ljava/util/UUID;");
    jmethodID toString = env->GetMethodID(uuidClass, "toString", "()Ljava/lang/String;");
    if (ClearJniException(env) || fromBytes == nullptr || toString == nullptr) return CalcFallbackId(seed);
    jobject uuid = env->CallStaticObjectMethod(uuidClass, fromBytes, bytes);
    if (ClearJniException(env) || uuid == nullptr) return CalcFallbackId(seed);
    auto str = (jstring) env->CallObjectMethod(uuid, toString);
    if (ClearJniException(env)) return CalcFallbackId(seed);
    std::string out = JStringToStd(env, str);
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(uuid);
    if (str != nullptr) env->DeleteLocalRef(str);
    return out.empty() ? CalcFallbackId(seed) : out;
}

struct MemoryStruct {
    char *memory;
    size_t size;
};

static size_t WriteMemoryCallback(void *contents, size_t size, size_t nmemb, void *userp) {
    size_t realsize = size * nmemb;
    struct MemoryStruct *mem = (struct MemoryStruct *) userp;

    mem->memory = (char *) realloc(mem->memory, mem->size + realsize + 1);
    if (mem->memory == NULL) {
        return 0;
    }

    memcpy(&(mem->memory[mem->size]), contents, realsize);
    mem->size += realsize;
    mem->memory[mem->size] = 0;

    return realsize;
}
std::string CalcMD5(std::string s) {
    std::string result;

    unsigned char hash[MD5_DIGEST_LENGTH];
    char tmp[4];

    MD5_CTX md5;
    MD5_Init(&md5);
    MD5_Update(&md5, s.c_str(), s.length());
    MD5_Final(hash, &md5);
    for (unsigned char i : hash) {
        snprintf(tmp, sizeof(tmp), "%02x", i);
        result += tmp;
    }
    return result;
}

std::string CalcSHA256(std::string s) {
    std::string result;

    unsigned char hash[SHA256_DIGEST_LENGTH];
    char tmp[4];

    SHA256_CTX sha256;
    SHA256_Init(&sha256);
    SHA256_Update(&sha256, s.c_str(), s.length());
    SHA256_Final(hash, &sha256);
    for (unsigned char i : hash) {
        snprintf(tmp, sizeof(tmp), "%02x", i);
        result += tmp;
    }
    return result;
}


extern "C"
JNIEXPORT jstring JNICALL
Java_com_xlox_LogAct_Check(JNIEnv *env, jclass clazz, jobject mContext, jstring mUserKey) {
    // Never let a previous successful login bleed into a later failed one.
    xConnected = false;
    xServerConnection = false;
    memek = false;
    g_Token.clear();
    g_Auth.clear();
    exdate = oxorany("NULL");
    g_ModStatus = "Safe";
    g_Credit.clear();

    std::string user_key = JStringToStd(env, mUserKey);
    if (user_key.empty() || user_key.size() > 64) {
        return env->NewStringUTF("INVALID PARAMETER");
    }
    std::string hwid = user_key;
    hwid += GetAndroidID(env, mContext);
    hwid += GetDeviceModel(env);
    hwid += GetDeviceBrand(env);
    std::string UUID = GetDeviceUniqueIdentifier(env, hwid);
    std::string errMsg;
    struct MemoryStruct chunk{};
    chunk.memory = (char *) malloc(1);
    chunk.size = 0;
    if (chunk.memory == nullptr) return env->NewStringUTF("Out of memory");
    chunk.memory[0] = 0;

    CURL *curl = curl_easy_init();
    if (curl == nullptr) {
        free(chunk.memory);
        return env->NewStringUTF("Network library unavailable");
    }

    char *keyEsc = curl_easy_escape(curl, user_key.c_str(), (int) user_key.size());
    char *serialEsc = curl_easy_escape(curl, UUID.c_str(), (int) UUID.size());
    std::string data = oxorany("game=PUBG&user_key=");
    data += keyEsc ? keyEsc : "";
    data += "&serial=";
    data += serialEsc ? serialEsc : "";
    if (keyEsc) curl_free(keyEsc);
    if (serialEsc) curl_free(serialEsc);

    struct curl_slist *headers = NULL;
    headers = curl_slist_append(headers, oxorany("Accept: application/json"));
    headers = curl_slist_append(headers, oxorany("Content-Type: application/x-www-form-urlencoded"));
    headers = curl_slist_append(headers, oxorany("Charset: UTF-8"));

    std::string connectUrl = oxorany(ANOY_CONNECT_URL);
    curl_easy_setopt(curl, CURLOPT_URL, connectUrl.c_str());
    curl_easy_setopt(curl, CURLOPT_POST, 1L);
    curl_easy_setopt(curl, CURLOPT_FOLLOWLOCATION, 1L);
    curl_easy_setopt(curl, CURLOPT_MAXREDIRS, 3L);
    curl_easy_setopt(curl, CURLOPT_POSTREDIR, (long) CURL_REDIR_POST_ALL);
    curl_easy_setopt(curl, CURLOPT_CONNECTTIMEOUT, 12L);
    curl_easy_setopt(curl, CURLOPT_TIMEOUT, 25L);
    curl_easy_setopt(curl, CURLOPT_NOSIGNAL, 1L);
    curl_easy_setopt(curl, CURLOPT_DEFAULT_PROTOCOL, oxorany("https"));
    curl_easy_setopt(curl, CURLOPT_HTTPHEADER, headers);
    curl_easy_setopt(curl, CURLOPT_POSTFIELDS, data.c_str());
    curl_easy_setopt(curl, CURLOPT_POSTFIELDSIZE, (long) data.size());
    curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, WriteMemoryCallback);
    curl_easy_setopt(curl, CURLOPT_WRITEDATA, (void *) &chunk);
    curl_easy_setopt(curl, CURLOPT_SSL_VERIFYPEER, 0L);
    curl_easy_setopt(curl, CURLOPT_SSL_VERIFYHOST, 0L);
    curl_easy_setopt(curl, CURLOPT_SSL_VERIFYSTATUS, 0L);
    curl_easy_setopt(curl, CURLOPT_USERAGENT, oxorany(ANOY_USER_AGENT));

    CURLcode res = CURLE_OK;
    long httpCode = 0;
    // One quiet retry for transient transport failures (DNS hiccup, reset).
    for (int attempt = 0; attempt < 2; attempt++) {
        chunk.size = 0;
        chunk.memory[0] = 0;
        res = curl_easy_perform(curl);
        if (res == CURLE_OK) break;
        if (res != CURLE_COULDNT_RESOLVE_HOST && res != CURLE_COULDNT_CONNECT &&
            res != CURLE_SEND_ERROR && res != CURLE_RECV_ERROR && res != CURLE_GOT_NOTHING) {
            break;
        }
    }
    curl_easy_getinfo(curl, CURLINFO_RESPONSE_CODE, &httpCode);

    if (res != CURLE_OK) {
        errMsg = curl_easy_strerror(res);
    } else {
        json result = json::parse(chunk.memory ? chunk.memory : "", nullptr, false);
        if (result.is_discarded() || !result.is_object()) {
            if (httpCode < 200 || httpCode >= 300) {
                char codeMsg[64];
                snprintf(codeMsg, sizeof(codeMsg), "Panel returned HTTP %ld", httpCode);
                errMsg = codeMsg;
            } else {
                errMsg = "JSON parse_error";
            }
        } else {
            std::string reason;
            if (result.contains("reason") && result["reason"].is_string()) {
                reason = result["reason"].get<std::string>();
            }
            bool status = result.contains("status") && result["status"].is_boolean()
                          && result["status"].get<bool>();
            bool hasData = result.contains("data") && result["data"].is_object();
            if (!reason.empty() && !hasData) {
                // Maintenance answers status:true + reason; errors status:false + reason.
                errMsg = reason;
            } else if (status && hasData) {
                json payload = result["data"];
                time_t serverTime = 0;
                if (payload.contains("rng") && payload["rng"].is_number()) {
                    serverTime = (time_t) payload["rng"].get<long long>();
                }
                // Replay guard: the answer must be fresh. Allow the phone clock
                // to run up to 10 minutes ahead of the server.
                if (serverTime + 600 > time(nullptr)) {
                    std::string auth = oxorany("PUBG");
                    auth += "-";
                    auth += user_key;
                    auth += "-";
                    auth += UUID;
                    auth += "-";
                    auth += oxorany(ANOY_STATIC_WORDS);
                    g_Token = payload.contains("token") && payload["token"].is_string()
                              ? payload["token"].get<std::string>() : std::string();
                    g_Auth = CalcMD5(auth);
                    xConnected = !g_Token.empty() && g_Token == g_Auth;
                    xServerConnection = xConnected;
                    memek = xConnected;
                    if (xConnected) {
                        auto str = [&payload](const char *k, const std::string &fallback) {
                            return payload.contains(k) && payload[k].is_string()
                                   ? payload[k].get<std::string>() : fallback;
                        };
                        exdate = str("EXP", str("exdate", "NULL"));
                        g_ModStatus = str("mod_status", "Safe");
                        g_Credit = str("credit", "");
                    } else {
                        errMsg = "INVALID TOKEN";
                    }
                } else {
                    errMsg = "SERVER TIME OUT OF SYNC";
                }
            } else if (httpCode < 200 || httpCode >= 300) {
                char codeMsg[64];
                snprintf(codeMsg, sizeof(codeMsg), "Panel returned HTTP %ld", httpCode);
                errMsg = codeMsg;
            } else {
                errMsg = "USER OR GAME NOT REGISTERED";
            }
        }
    }

    curl_slist_free_all(headers);
    curl_easy_cleanup(curl);
    free(chunk.memory);
    return xConnected ? env->NewStringUTF(StrEnc("8q", "\x77\x3A", 2).c_str())
                      : env->NewStringUTF(errMsg.c_str());
}
