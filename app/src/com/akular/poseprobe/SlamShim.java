package com.akular.poseprobe;

/**
 * Native bridge to the SLAM enable call that the Java SDK never binds.
 * All methods return <0 on failure (see slamshim.c for the codes).
 */
final class SlamShim {

    static final int E_NOLIB = -1, E_NOLOADER = -2, E_TABLE = -3, E_NOSYM = -4;

    private SlamShim() {}

    static boolean load() {
        try { System.loadLibrary("slamshim"); return true; }
        catch (Throwable t) { return false; }
    }

    /** trackerAlgorithm values -- magic constants, small ints are silently ignored. */
    static final int DOF3 = 0x1001, SLAM = 0x2001;

    static native int setPropInt(String key, int value);
    static native int getPropInt(String key);
    static native int enableSlam();
    static native int disableSlam();
    static native int status();
    /** Corrected signature: timestamp first, rotation before position. 0 = latest. */
    static native int pose(long timestampNs, float[] pos, float[] rot);

    static String err(int code) {
        switch (code) {
            case E_NOLIB:    return "ApiLayerClient not loaded (RTLD_NOLOAD)";
            case E_NOLOADER: return "no GetXRRuntimeLoader";
            case E_TABLE:    return "runtime vtable not populated";
            case E_NOSYM:    return "symbol missing";
            default:         return "code " + code;
        }
    }
}
