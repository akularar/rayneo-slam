// JNI shim: reach the RayNeo API-layer calls that libRayNeoXRSDKClient.so (the
// only lib with Java_* entry points) never binds -- XRWarp_SetProp and
// XRWarp_EnableSlamHeadTracker -- so 6DoF is otherwise unreachable from Java.
//
// The XRWarp_* functions are vtable thunks: GetXRRuntimeLoader() returns a
// 156-slot global that stays zeroed until the runtime's GenerateXRRuntimeLoader()
// fills it. That bootstrap runs inside FFalconXRClient's init/bind. We must use
// the runtime's already-loaded mapping: RTLD_NOLOAD, never a fresh dlopen of the
// app's bundled copy (that would be a second, zeroed table -> br on NULL -> SIGSEGV).

#include <jni.h>
#include <dlfcn.h>
#include <stdint.h>
#include <android/log.h>

#define LIB  "libRayNeoXRApiLayerClient.so"
#define SLOT 58   /* XRWarp_EnableSlamHeadTracker, 0x1D0 / 8 */
#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "RayNeoSlam", __VA_ARGS__)

// negative = failure, so Java can report the exact reason
enum { OK = 0, E_NOLIB = -1, E_NOLOADER = -2, E_TABLE = -3, E_NOSYM = -4 };

static void *lib(void) {
    static void *h;   // cached: each NOLOAD dlopen bumps the refcount, never dlclose'd
    if (!h) h = dlopen(LIB, RTLD_NOW | RTLD_NOLOAD);   // NOLOAD: runtime's mapping only
    return h;
}

/** Look up sym in the runtime's mapping, but only once its vtable is populated. */
static int resolve(const char *sym, void **out) {
    void *h = lib();
    if (!h) return E_NOLIB;
    void **(*getLoader)(void) = (void **(*)(void)) dlsym(h, "GetXRRuntimeLoader");
    if (!getLoader) return E_NOLOADER;
    void **vt = getLoader();
    if (!vt || !vt[0] || !vt[SLOT]) return E_TABLE;
    *out = dlsym(h, sym);
    return *out ? OK : E_NOSYM;
}

static int call_void(const char *sym) {
    void *fn;
    int r = resolve(sym, &fn);
    if (r != OK) { LOG("%s: unavailable (%d)", sym, r); return r; }
    ((void (*)(void)) fn)();
    LOG("%s: called", sym);
    return OK;
}

/**
 * The 3DoF/6DoF switch. Values are magic constants, NOT small ints:
 * DOF3 = 0x1001 (the default), SLAM = 0x2001. Must go through the API-layer
 * XRWarp_SetProp -- the SDK's FxrApi.SetConfiguration does not know this key.
 * (XRWarp_GetProp cannot read this key back -- returns -2 -- even when it took.)
 */
JNIEXPORT jint JNICALL
Java_com_akular_rayneo_slam_SlamShim_setPropInt(JNIEnv *e, jclass c, jstring jkey, jint val) {
    void *fn;
    int r = resolve("XRWarp_SetProp", &fn);
    if (r != OK) return r;

    const char *key = (*e)->GetStringUTFChars(e, jkey, 0);
    int32_t v = (int32_t) val;
    r = ((int (*)(const char *, void *, int)) fn)(key, &v, (int) sizeof v);
    LOG("SetProp(%s, 0x%x) -> %d", key, v, r);
    (*e)->ReleaseStringUTFChars(e, jkey, key);
    return r;
}

JNIEXPORT jint JNICALL
Java_com_akular_rayneo_slam_SlamShim_enableSlam(JNIEnv *e, jclass c) {
    return call_void("XRWarp_EnableSlamHeadTracker");
}

JNIEXPORT jint JNICALL
Java_com_akular_rayneo_slam_SlamShim_disableSlam(JNIEnv *e, jclass c) {
    return call_void("XRWarp_DisableSlamHeadTracker");
}

/**
 * Native pose read. Real signature (from Java_..._FxrApi_GetHeadTrackerPose):
 *   int XRWarp_GetHeadTrackerPose(int64_t timestampNs, float rot[4], float pos[3])
 * -- timestamp first, ROTATION BEFORE POSITION, 0 == OK, timestamp 0 == latest.
 * The C# DllImport declares two args with position first; it is wrong. Verified
 * on hardware to match the Java GetHeadTrackerPose read within 0.004.
 */
JNIEXPORT jint JNICALL
Java_com_akular_rayneo_slam_SlamShim_pose(JNIEnv *e, jclass c,
                                          jfloatArray jpos, jfloatArray jrot) {
    static int (*fn)(int64_t, float *, float *);   // resolved once, called per frame
    if (!fn) {
        int r = resolve("XRWarp_GetHeadTrackerPose", (void **) &fn);
        if (r != OK) { fn = 0; return r; }
    }
    float pos[3] = {0}, rot[4] = {0};
    int r = fn(0, rot, pos);
    (*e)->SetFloatArrayRegion(e, jpos, 0, 3, pos);
    (*e)->SetFloatArrayRegion(e, jrot, 0, 4, rot);
    return r;
}
