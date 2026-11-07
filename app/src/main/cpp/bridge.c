/*
 * Kanki Warp native bridge.
 *
 * - aether: the Rust WARP core. aether_core_start(json args) answers at once with
 *   {"ok":true,"job":N}; the job is then polled until it ends.
 * - hev: hev-socks5-tunnel. hev_socks5_tunnel_main_from_file(config, tun_fd)
 *   blocks while it moves TUN packets to the local SOCKS5 port.
 * - log: both libraries write their log to stderr, which goes nowhere on Android.
 *   stderr is turned into a pipe; a thread keeps the last lines in a ring that
 *   Kotlin drains for the log screen.
 */
#include <jni.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <android/log.h>

#define TAG "KankiWarp"

typedef char *(*fn_str)(void);
typedef void (*fn_free)(char *);
typedef char *(*fn_start)(const char *);
typedef char *(*fn_job)(uint64_t);
typedef int (*fn_hev_main)(const char *, int);
typedef void (*fn_hev_stop)(void);
typedef void (*fn_hev_stats)(size_t *, size_t *, size_t *, size_t *);

static void *aether, *hev;
static fn_str a_version;
static fn_free a_free;
static fn_start a_start;
static fn_job a_poll, a_cancel, a_jfree;
static fn_hev_main h_main;
static fn_hev_stop h_stop;
static fn_hev_stats h_stats;
static char load_error[512];

static jstring take(JNIEnv *env, char *raw) {
    if (raw == NULL) return NULL;
    jstring s = (*env)->NewStringUTF(env, raw);
    if (a_free) a_free(raw);
    return s;
}

#define SYM(handle, var, type, name)                                              \
    do {                                                                          \
        var = (type) dlsym(handle, name);                                         \
        if (!var) {                                                               \
            snprintf(load_error, sizeof load_error, "missing symbol %s", name);   \
            return 0;                                                             \
        }                                                                         \
    } while (0)

static int load(void) {
    if (aether && hev) return 1;
    if (!aether) {
        aether = dlopen("libaether.so", RTLD_NOW | RTLD_GLOBAL);
        if (!aether) {
            snprintf(load_error, sizeof load_error, "libaether.so: %s", dlerror());
            return 0;
        }
    }
    SYM(aether, a_version, fn_str, "aether_version");
    SYM(aether, a_free, fn_free, "aether_string_free");
    SYM(aether, a_start, fn_start, "aether_core_start");
    SYM(aether, a_poll, fn_job, "aether_job_poll");
    SYM(aether, a_cancel, fn_job, "aether_job_cancel");
    SYM(aether, a_jfree, fn_job, "aether_job_free");
    if (!hev) {
        hev = dlopen("libhev-socks5-tunnel.so", RTLD_NOW | RTLD_GLOBAL);
        if (!hev) {
            snprintf(load_error, sizeof load_error, "libhev-socks5-tunnel.so: %s", dlerror());
            return 0;
        }
    }
    SYM(hev, h_main, fn_hev_main, "hev_socks5_tunnel_main_from_file");
    SYM(hev, h_stop, fn_hev_stop, "hev_socks5_tunnel_stop");
    SYM(hev, h_stats, fn_hev_stats, "hev_socks5_tunnel_stats");
    load_error[0] = 0;
    return 1;
}

/* ------------------------------------------------------------------ log pipe */

#define RING 65536
static char ring[RING];
static size_t ring_len;
static pthread_mutex_t ring_lock = PTHREAD_MUTEX_INITIALIZER;
static int log_started;

static void ring_put(const char *data, size_t n) {
    pthread_mutex_lock(&ring_lock);
    if (n >= RING) {
        data += n - (RING - 1);
        n = RING - 1;
    }
    if (ring_len + n >= RING) {
        size_t drop = ring_len + n - (RING - 1);
        memmove(ring, ring + drop, ring_len - drop);
        ring_len -= drop;
    }
    memcpy(ring + ring_len, data, n);
    ring_len += n;
    pthread_mutex_unlock(&ring_lock);
}

static void *log_reader(void *arg) {
    int fd = (int) (intptr_t) arg;
    char buf[2048];
    for (;;) {
        ssize_t n = read(fd, buf, sizeof buf - 1);
        if (n <= 0) break;
        ring_put(buf, (size_t) n);
        buf[n] = 0;
        __android_log_write(ANDROID_LOG_INFO, TAG, buf);
    }
    return NULL;
}

static void start_log(void) {
    if (log_started) return;
    int p[2];
    if (pipe(p) != 0) return;
    setvbuf(stderr, NULL, _IOLBF, 0);
    if (dup2(p[1], STDERR_FILENO) < 0) {
        close(p[0]);
        close(p[1]);
        return;
    }
    close(p[1]);
    pthread_t t;
    if (pthread_create(&t, NULL, log_reader, (void *) (intptr_t) p[0]) == 0) {
        pthread_detach(t);
        log_started = 1;
    }
}

/* ---------------------------------------------------------------------- JNI */

#define FN(name) Java_cafe_kankipro_warp_core_Native_##name

JNIEXPORT jstring JNICALL FN(nativeLoad)(JNIEnv *env, jclass c) {
    start_log();
    if (load()) return NULL;
    return (*env)->NewStringUTF(env, load_error);
}

JNIEXPORT jstring JNICALL FN(coreVersion)(JNIEnv *env, jclass c) {
    if (!load()) return NULL;
    return take(env, a_version());
}

JNIEXPORT jstring JNICALL FN(coreStart)(JNIEnv *env, jclass c, jstring args) {
    if (!load()) return (*env)->NewStringUTF(env, "{\"ok\":false,\"error\":\"core not loaded\"}");
    const char *raw = (*env)->GetStringUTFChars(env, args, NULL);
    jstring out = take(env, a_start(raw));
    (*env)->ReleaseStringUTFChars(env, args, raw);
    return out;
}

JNIEXPORT jstring JNICALL FN(jobPoll)(JNIEnv *env, jclass c, jlong id) {
    return load() ? take(env, a_poll((uint64_t) id)) : NULL;
}

JNIEXPORT jstring JNICALL FN(jobCancel)(JNIEnv *env, jclass c, jlong id) {
    return load() ? take(env, a_cancel((uint64_t) id)) : NULL;
}

JNIEXPORT jstring JNICALL FN(jobFree)(JNIEnv *env, jclass c, jlong id) {
    return load() ? take(env, a_jfree((uint64_t) id)) : NULL;
}

JNIEXPORT jint JNICALL FN(hevRun)(JNIEnv *env, jclass c, jstring config, jint fd) {
    if (!load()) return -100;
    const char *path = (*env)->GetStringUTFChars(env, config, NULL);
    int rc = h_main(path, fd);
    (*env)->ReleaseStringUTFChars(env, config, path);
    return rc;
}

JNIEXPORT void JNICALL FN(hevStop)(JNIEnv *env, jclass c) {
    if (load()) h_stop();
}

JNIEXPORT jlongArray JNICALL FN(hevStats)(JNIEnv *env, jclass c) {
    if (!load()) return NULL;
    size_t tp = 0, tb = 0, rp = 0, rb = 0;
    h_stats(&tp, &tb, &rp, &rb);
    jlong v[4] = {(jlong) tp, (jlong) tb, (jlong) rp, (jlong) rb};
    jlongArray arr = (*env)->NewLongArray(env, 4);
    if (arr) (*env)->SetLongArrayRegion(env, arr, 0, 4, v);
    return arr;
}

JNIEXPORT jstring JNICALL FN(drainLog)(JNIEnv *env, jclass c) {
    pthread_mutex_lock(&ring_lock);
    if (ring_len == 0) {
        pthread_mutex_unlock(&ring_lock);
        return NULL;
    }
    char *copy = malloc(ring_len + 1);
    if (!copy) {
        pthread_mutex_unlock(&ring_lock);
        return NULL;
    }
    memcpy(copy, ring, ring_len);
    copy[ring_len] = 0;
    ring_len = 0;
    pthread_mutex_unlock(&ring_lock);
    /* NewStringUTF wants modified UTF-8; the core logs ASCII, but be safe. */
    for (char *p = copy; *p; p++)
        if ((unsigned char) *p >= 0x80) *p = '?';
    jstring s = (*env)->NewStringUTF(env, copy);
    free(copy);
    return s;
}
