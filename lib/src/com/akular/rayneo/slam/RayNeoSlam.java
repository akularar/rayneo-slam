package com.akular.rayneo.slam;

import android.app.Activity;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import com.rayneo.xr.client.FFalconXRClient;
import com.rayneo.xr.sdk.listener.XRLifecycleListener;

/**
 * 6DoF (SLAM) head tracking on RayNeo X3 Pro from a plain Android app.
 *
 * <pre>
 *   // Activity.onCreate (main thread)
 *   slam = RayNeoSlam.create(this);
 *   slam.start(listener);
 *   // any thread, e.g. GLSurfaceView.Renderer.onDrawFrame
 *   if (slam.getPose(pose)) { camera.update(pose); ... }
 *   // Activity.onDestroy
 *   slam.stop();
 * </pre>
 *
 * Poses become valid only after SLAM localizes, which needs the wearer to move
 * (parallax). The runtime's tracker status is not usable for this -- it reads 0
 * even while tracking -- so validity is judged from the pose itself.
 */
public final class RayNeoSlam {

    /** Callbacks arrive on the main thread. */
    public interface Listener {
        /** SLAM is enabled; {@link #getPose} turns true once it localizes. */
        void onStarted();
        /** Bring-up failed; {@code reason} names the step. */
        void onError(String reason);
    }

    private static final String TAG = "RayNeoSlam";

    /** trackerAlgorithm values are magic constants (DOF3 = 0x1001 is the default);
     *  small ints are silently ignored. */
    private static final int ALGO_SLAM = 0x2001;

    private final FFalconXRClient client;
    private final HandlerThread thread = new HandlerThread("rayneo-slam");
    private final Handler bg;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Listener listener;
    private boolean started;               // guarded by this
    private volatile boolean stopped;
    private volatile boolean slamOn;

    /**
     * Must be called on the main thread with the Activity itself (not the app
     * context): that path starts the runtime's vsync pump, without which the
     * pose never advances.
     */
    public static RayNeoSlam create(Activity activity) {
        if (Looper.myLooper() != Looper.getMainLooper())
            throw new IllegalStateException("RayNeoSlam.create must run on the main thread");
        return new RayNeoSlam(activity);
    }

    private RayNeoSlam(Activity activity) {
        thread.start();
        bg = new Handler(thread.getLooper());
        client = FFalconXRClient.Get();
        Log.i(TAG, "initAndroid=" + client.initAndroid(activity));
    }

    /** Binds the runtime and enables SLAM in the background. Call once. */
    public void start(Listener l) {
        listener = l;
        client.registerLifecycleListener(new XRLifecycleListener() {
            public void onReady()   { startOnce(); }
            public void onStart()   {}
            public void onStop()    {}
            public void onSuspend() {}
        });
        bg.post(() -> {
            try {
                Log.i(TAG, "bindService=" + client.bindService());   // blocks up to 10 s
            } catch (Throwable t) {
                fail("bindService", t);
                return;
            }
            startOnce();
        });
    }

    private synchronized void startOnce() {
        if (started) return;
        started = true;
        bg.post(() -> {
            if (stopped) return;
            if (!SlamShim.load()) { fail("load libslamshim", null); return; }
            // before StartXR: the runtime reads the algorithm at startup
            int r = SlamShim.setPropInt("trackerAlgorithm", ALGO_SLAM);
            if (r != 0) { fail("SetProp(trackerAlgorithm): " + SlamShim.err(r), null); return; }
            try {
                client.StartXR();
                client.SwitchTo3DMode();
                client.OpenIMU();
            } catch (Throwable t) {
                fail("StartXR", t);
                return;
            }
            r = SlamShim.enableSlam();
            if (r != 0) { fail("EnableSlamHeadTracker: " + SlamShim.err(r), null); return; }
            slamOn = true;
            Log.i(TAG, "SLAM enabled");
            main.post(() -> { if (listener != null) listener.onStarted(); });
        });
    }

    private void fail(String step, Throwable t) {
        Log.e(TAG, step + " FAILED", t);
        String reason = t == null ? step : step + ": " + t;
        main.post(() -> { if (listener != null) listener.onError(reason); });
    }

    /**
     * Reads the latest head pose into {@code out}. Allocation-free; safe to call
     * every frame from any thread.
     *
     * @return true if {@code out} holds a valid, localized pose
     */
    public boolean getPose(Pose out) {
        if (!slamOn) return false;
        if (SlamShim.pose(out.position, out.rotation) != 0) return false;
        float[] p = out.position, q = out.rotation;
        return !Float.isNaN(p[0]) && !Float.isNaN(p[1]) && !Float.isNaN(p[2])
                && !Float.isNaN(q[3])
                && !(q[0] == 0 && q[1] == 0 && q[2] == 0 && q[3] == 0);
    }

    /** Disables SLAM and releases the runtime. Call from Activity.onDestroy. */
    public void stop() {
        stopped = true;
        listener = null;
        if (slamOn) {
            slamOn = false;
            try { SlamShim.disableSlam(); } catch (Throwable ignore) {}
        }
        try { client.uninitAndroid(); } catch (Throwable ignore) {}
        thread.quitSafely();
    }
}
