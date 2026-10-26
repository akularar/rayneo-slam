package com.akular.poseprobe;

import android.app.Activity;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.View;
import android.view.Window;

import com.rayneo.xr.client.FFalconXRClient;
import com.rayneo.xr.sdk.base.FXRPoseInfo;
import com.rayneo.xr.sdk.listener.XRLifecycleListener;

import java.lang.reflect.Field;

/**
 * World-locked scene on the RayNeo X3 Pro from a plain (non-Unity) app.
 *
 * GLES2 build: the Unity Slam.unity replica grid + a GLB model, rendered by
 * {@link GlRenderer} into a GLSurfaceView (stereo side-by-side).
 * Pose convention is the SOLVED preset 3.
 */
public class PoseProbeActivity extends Activity {

    private static final String TAG = "PoseProbe";

    private FFalconXRClient client;
    private GLSurfaceView glView;
    private GlRenderer renderer;
    private HandlerThread bgThread;
    private Handler bg;
    private volatile boolean started = false;
    private Field posField;
    private volatile boolean slamOn = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (getActionBar() != null) getActionBar().hide();

        glView = new GLSurfaceView(this);
        glView.setEGLContextClientVersion(2);
        glView.setEGLConfigChooser(8, 8, 8, 0, 16, 0);
        renderer = new GlRenderer(this);
        glView.setRenderer(renderer);
        // draw only when a new pose arrives (poll rate), not every vsync
        glView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);

        setContentView(glView);
        glView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);

        try {
            posField = FXRPoseInfo.class.getDeclaredField("position");
            posField.setAccessible(true);
        } catch (Exception e) {
            Log.e(TAG, "no 'position' field on FXRPoseInfo", e);
        }

        bgThread = new HandlerThread("probe");
        bgThread.start();
        bg = new Handler(bgThread.getLooper());

        try {
            client = FFalconXRClient.Get();
            boolean init = client.initAndroid(this); // main thread => vsync pump
            Log.i(TAG, "initAndroid=" + init);
            client.registerLifecycleListener(new XRLifecycleListener() {
                public void onReady()   { startXrOnce(); }
                public void onStart()   {}
                public void onStop()    {}
                public void onSuspend() {}
            });
            bg.post(this::bindAndStart);
        } catch (Throwable t) {
            Log.e(TAG, "init FAILED", t);
        }
    }

    private void bindAndStart() {
        try {
            boolean bound = client.bindService();
            Log.i(TAG, "bindService=" + bound);
            startXrOnce();
        } catch (Throwable t) {
            Log.e(TAG, "bind FAILED", t);
        }
    }

    private synchronized void startXrOnce() {
        if (started || client == null) return;
        started = true;
        bg.post(() -> {
            if (!SlamShim.load()) Log.e(TAG, "slamshim: System.loadLibrary FAILED");
            selectSlamAlgorithm();            // before StartXR: the runtime reads it at startup
            call("StartXR",        () -> client.StartXR());
            call("SwitchTo3DMode", () -> client.SwitchTo3DMode());
            call("OpenIMU",        () -> client.OpenIMU());
            enableSlam();
            poll();
        });
    }

    /**
     * Pick the 6DoF algorithm. Enabling SLAM is not enough -- the runtime defaults to
     * DOF3 (0x1001) and must be told to use SLAM (0x2001) via the API-layer prop.
     * Small ints are not valid values here and are silently ignored.
     * (XRWarp_GetProp can't read this key back -- returns -2 -- even when it took.)
     */
    private void selectSlamAlgorithm() {
        int r = SlamShim.setPropInt("trackerAlgorithm", SlamShim.SLAM);
        Log.i(TAG, "trackerAlgorithm=0x" + Integer.toHexString(SlamShim.SLAM) + " -> " + r);
    }

    /** Enable 6DoF SLAM via the native shim (unreachable from the Java SDK). */
    private void enableSlam() {
        int r = SlamShim.enableSlam();
        if (r == 0) { slamOn = true; Log.i(TAG, "EnableSlamHeadTracker OK"); }
        else Log.e(TAG, "EnableSlamHeadTracker FAILED: " + SlamShim.err(r));
    }

    private void call(String name, Runnable r) {
        try { r.run(); Log.i(TAG, name + " ok"); }
        catch (Throwable t) { Log.e(TAG, name + " FAILED", t); }
    }

    private void poll() {
        try {
            float[] pos = null, quat = null;
            FXRPoseInfo p = null;
            try { p = client.GetHeadTrackerPose(); } catch (Throwable ignore) {}
            if (p != null) {
                try { pos = (float[]) posField.get(p); } catch (Throwable ignore) {}
                try { quat = p.GetQuaternion(); } catch (Throwable ignore) {}
            }
            if (renderer.setPose(pos, quat)) glView.requestRender();
        } catch (Throwable t) {
            Log.e(TAG, "poll error", t);
        }
        bg.postDelayed(this::poll, 33);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (glView != null) glView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (glView != null) glView.onResume();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (slamOn) { try { SlamShim.disableSlam(); } catch (Throwable ignore) {} }
        try { if (client != null) client.uninitAndroid(); } catch (Throwable ignore) {}
        if (bgThread != null) bgThread.quitSafely();
    }
}
