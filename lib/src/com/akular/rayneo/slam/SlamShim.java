package com.akular.rayneo.slam;

/**
 * Native bridge to the API-layer calls the RayNeo Java SDK never binds.
 * All methods return &lt;0 on failure (see slamshim.c for the codes).
 */
final class SlamShim {

    static final int E_NOLIB = -1, E_NOLOADER = -2, E_TABLE = -3, E_NOSYM = -4;

    private SlamShim() {}

    static boolean load() {
        try { System.loadLibrary("slamshim"); return true; }
        catch (Throwable t) { return false; }
    }

    static native int setPropInt(String key, int value);
    static native int enableSlam();
    static native int disableSlam();
    /** Latest head pose into pos[3] / rot[4] (x,y,z,w). 0 = OK. */
    static native int pose(float[] pos, float[] rot);

    static String err(int code) {
        switch (code) {
            case E_NOLIB:    return "libRayNeoXRApiLayerClient.so not loaded by the runtime";
            case E_NOLOADER: return "no GetXRRuntimeLoader";
            case E_TABLE:    return "runtime vtable not populated";
            case E_NOSYM:    return "symbol missing";
            default:         return "code " + code;
        }
    }
}
