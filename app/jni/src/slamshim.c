// JNI shim: reach XRWarp_EnableSlamHeadTracker, which libRayNeoXRSDKClient.so
// (the only lib with Java_* entry points) never binds -- so 6DoF is otherwise
// unreachable from Java. See "Enabling 6DoF" in CLAUDE.md.
//
// XRWarp_EnableSlamHeadTracker is a vtable thunk: GetXRRuntimeLoader() returns a
// 156-slot global that stays zeroed until the runtime's GenerateXRRuntimeLoader()
// fills it. That bootstrap already ran via FFalconXRClient.loadLibrary(). We must
// use the runtime's already-loaded mapping: RTLD_NOLOAD, never a fresh dlopen of
// our bundled copy (that would be a second, zeroed table -> br on NULL -> SIGSEGV).

#include <jni.h>
#include <dlfcn.h>
#include <stdint.h>
#include <android/log.h>

#define LIB  "libRayNeoXRApiLayerClient.so"
#define SLOT 58   /* 0x1D0 / 8 */
#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "PoseProbeShim", __VA_ARGS__)

// negative = failure, so Java can log the exact reason
enum { OK = 0, E_NOLIB = -1, E_NOLOADER = -2, E_TABLE = -3, E_NOSYM = -4 };

static void *lib(void) {
    static void *h;   // cached: each NOLOAD dlopen bumps the refcount, never dlclose'd
    if (!h) h = dlopen(LIB, RTLD_NOW | RTLD_NOLOAD);   // NOLOAD: runtime's mapping only
    return h;
}

static int table_ready(void *h) {
    void **(*getLoader)(void) = (void **(*)(void)) dlsym(h, "GetXRRuntimeLoader");
    if (!getLoader) return 0;
    void **vt = getLoader();
    return vt && vt[0] && vt[SLOT];
}

static int call_void(const char *sym) {
    void *h = lib();
    if (!h) { LOG("%s: %s not loaded (RTLD_NOLOAD)", sym, LIB); return E_NOLIB; }
    if (!dlsym(h, "GetXRRuntimeLoader")) { LOG("%s: no GetXRRuntimeLoader", sym); return E_NOLOADER; }
    if (!table_ready(h)) { LOG("%s: runtime table not populated", sym); return E_TABLE; }
    void (*fn)(void) = (void (*)(void)) dlsym(h, sym);
    if (!fn) { LOG("%s: symbol missing", sym); return E_NOSYM; }
    fn();
    LOG("%s: called", sym);
    return OK;
}

/**
 * The 3DoF/6DoF switch. Values are magic constants, NOT small ints:
 * DOF3 = 0x1001 (the default), SLAM = 0x2001. Must go through the API-layer
 * XRWarp_SetProp -- the SDK's FxrApi.SetConfiguration does not know this key.
 */
JNIEXPORT jint JNICALL
Java_com_akular_poseprobe_SlamShim_setPropInt(JNIEnv *e, jclass c, jstring jkey, jint val) {
    void *h = lib();
    if (!h || !table_ready(h)) return E_TABLE;
    int (*fn)(const char *, void *, int) =
        (int (*)(const char *, void *, int)) dlsym(h, "XRWarp_SetProp");
    if (!fn) return E_NOSYM;

    const char *key = (*e)->GetStringUTFChars(e, jkey, 0);
    int32_t v = (int32_t) val;
    int r = fn(key, &v, (int) sizeof v);
    LOG("SetProp(%s, 0x%x) -> %d", key, v, r);
    (*e)->ReleaseStringUTFChars(e, jkey, key);
    return r;
}

JNIEXPORT jint JNICALL
Java_com_akular_poseprobe_SlamShim_enableSlam(JNIEnv *e, jclass c) {
    return call_void("XRWarp_EnableSlamHeadTracker");
}

JNIEXPORT jint JNICALL
Java_com_akular_poseprobe_SlamShim_disableSlam(JNIEnv *e, jclass c) {
    return call_void("XRWarp_DisableSlamHeadTracker");
}

JNIEXPORT jint JNICALL
Java_com_akular_poseprobe_SlamShim_status(JNIEnv *e, jclass c) {
    void *h = lib();
    if (!h || !table_ready(h)) return E_TABLE;
    int (*fn)(void) = (int (*)(void)) dlsym(h, "XRWarp_GetHeadTrackerStatus");
    return fn ? fn() : E_NOSYM;
}

/**
 * Native pose read. Real signature (from Java_..._FxrApi_GetHeadTrackerPose):
 *   int XRWarp_GetHeadTrackerPose(int64_t timestampNs, float rot[4], float pos[3])
 * -- timestamp first, ROTATION BEFORE POSITION, 0 == OK. The C# DllImport
 * declares two args with position first; it is vestigial and wrong. Calling it
 * that way feeds the pos pointer in as a timestamp: pos never gets written and
 * rot comes back unit-norm but uncorrelated frame to frame.
 */
JNIEXPORT jint JNICALL
Java_com_akular_poseprobe_SlamShim_pose(JNIEnv *e, jclass c, jlong ts,
                                        jfloatArray jpos, jfloatArray jrot) {
    void *h = lib();
    if (!h || !table_ready(h)) return E_TABLE;
    int (*fn)(int64_t, float *, float *) =
        (int (*)(int64_t, float *, float *)) dlsym(h, "XRWarp_GetHeadTrackerPose");
    if (!fn) return E_NOSYM;

    float pos[3] = {0}, rot[4] = {0};
    int r = fn((int64_t) ts, rot, pos);   /* ts 0 == "latest" */
    (*e)->SetFloatArrayRegion(e, jpos, 0, 3, pos);
    (*e)->SetFloatArrayRegion(e, jrot, 0, 4, rot);
    return r;
}
