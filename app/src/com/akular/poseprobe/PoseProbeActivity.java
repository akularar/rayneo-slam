package com.akular.poseprobe;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.widget.FrameLayout;

import com.rayneo.xr.client.FFalconXRClient;
import com.rayneo.xr.sdk.base.FXRPoseInfo;
import com.rayneo.xr.sdk.listener.XRLifecycleListener;

import java.lang.reflect.Field;

/**
 * World-locked scene on the RayNeo X3 Pro from a plain (non-Unity) app.
 *
 * GLES2 build: the Unity Slam.unity replica grid + a GLB model, rendered by
 * {@link GlRenderer} into a GLSurfaceView (stereo side-by-side), with a Canvas
 * HUD overlay for text. Pose convention is the SOLVED preset 3.
 */
public class PoseProbeActivity extends Activity {

    private static final String TAG = "PoseProbe";

    private FFalconXRClient client;
    private GLSurfaceView glView;
    private GlRenderer renderer;
    private HudView hud;
    private HandlerThread bgThread;
    private Handler bg;
    private volatile boolean started = false;
    private Field posField;
    private long lastPoseLog = 0;
    private volatile boolean slamOn = false;
    private final float[] npos = new float[3], nrot = new float[4];

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
        hud = new HudView(this, renderer);
        FrameLayout root = new FrameLayout(this);
        root.addView(glView);
        root.addView(hud);
        setContentView(root);
        root.setSystemUiVisibility(
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
     */
    private void selectSlamAlgorithm() {
        int r = SlamShim.setPropInt("trackerAlgorithm", SlamShim.SLAM);
        int back = SlamShim.getPropInt("trackerAlgorithm");
        Log.i(TAG, String.format("trackerAlgorithm: set->%d readback=0x%x (want 0x%x)",
                r, back, SlamShim.SLAM));
    }

    /** Enable 6DoF SLAM via the native shim (unreachable from the Java SDK). */
    private void enableSlam() {
        int r = SlamShim.enableSlam();
        if (r == 0) { slamOn = true; Log.i(TAG, "EnableSlamHeadTracker OK"); }
        else Log.e(TAG, "EnableSlamHeadTracker FAILED: " + SlamShim.err(r));

        // If the prop only takes once XR is up, this second attempt catches it.
        new Thread(() -> {
            nap(1500);
            Log.i(TAG, "post-XR readback=0x" + Integer.toHexString(SlamShim.getPropInt("trackerAlgorithm")));
            for (int i = 0; i < 10; i++) { nap(1000); Log.i(TAG, "PROBE st=" + SlamShim.status()); }
            Log.i(TAG, "PROBE done");
        }, "probe").start();
    }

    private static void nap(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignore) {}
    }

    private int status() {
        try { return client.GetHeadTrackerStatus(); } catch (Throwable t) { return -99; }
    }

    private void call(String name, Runnable r) {
        try { r.run(); Log.i(TAG, name + " ok"); }
        catch (Throwable t) { Log.e(TAG, name + " FAILED", t); }
    }

    private void poll() {
        try {
            float[] pos = null, quat = null;
            // NB: the native XRWarp_GetHeadTrackerPose(float[3],float[4]) read returns
            // unit quaternions that jump randomly frame-to-frame and an all-zero position
            // -- it evidently wants a timestamp arg we don't have. Use the Java read.
            {
                FXRPoseInfo p = null;
                try { p = client.GetHeadTrackerPose(); } catch (Throwable ignore) {}
                if (p != null) {
                    try { pos = (float[]) posField.get(p); } catch (Throwable ignore) {}
                    try { quat = p.GetQuaternion(); } catch (Throwable ignore) {}
                }
            }
            renderer.setPose(pos, quat);
            hud.postInvalidate();
            long now = System.currentTimeMillis();
            if (pos != null && quat != null && now - lastPoseLog > 200) {
                lastPoseLog = now;
                // A/B: is the native read (the path Unity uses) less drifty than the
                // Java one? Log both from the same tick so one walk decides it.
                int nr = -1;
                if (slamOn) { try { nr = SlamShim.pose(0L, npos, nrot); } catch (Throwable ignore) {} }
                Log.i(TAG, String.format(
                        "pose t=[%+.3f %+.3f %+.3f] q=[%+.4f %+.4f %+.4f %+.4f] st=%d"
                                + " | nat r=%d t=[%+.3f %+.3f %+.3f] q=[%+.4f %+.4f %+.4f %+.4f]",
                        pos[0], pos[1], pos[2], quat[0], quat[1], quat[2], quat[3],
                        slamOn ? SlamShim.status() : status(),
                        nr, npos[0], npos[1], npos[2], nrot[0], nrot[1], nrot[2], nrot[3]));
            }
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

    // ------------------------------------------------------------------
    // Canvas HUD overlay: crosshair + text per eye-half. World content is
    // GL (GlRenderer) underneath; identical halves fuse at optical infinity.
    // ------------------------------------------------------------------
    static class HudView extends View {

        /** false = clean view for showcase recordings (no text, no crosshair). */
        private static final boolean SHOW_HUD = false;

        private final GlRenderer gl;

        /** Head position at the last tap, for the on-screen movement ruler. */
        private float[] origin;

        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint cross = new Paint();

        HudView(Context c, GlRenderer gl) {
            super(c);
            this.gl = gl;                 // transparent background: GL shows through
            text.setColor(Color.WHITE);   text.setTextSize(30f);
            label.setColor(Color.YELLOW); label.setTextSize(34f);
            cross.setColor(Color.DKGRAY);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                origin = gl.tw();      // zero the movement ruler
                postInvalidate();
                return true;
            }
            return super.onTouchEvent(e);
        }

        @Override
        protected void onDraw(Canvas cv) {
            int w = getWidth(), h = getHeight(), half = w / 2;
            float[] t = gl.tw();
            if (t != null && origin == null) origin = t;
            if (!SHOW_HUD) return;

            String hud;
            if (t == null || origin == null) {
                hud = "SLAM: waiting for localization\n(wear + move to bootstrap)";
            } else {
                float dx = t[0] - origin[0], dy = t[1] - origin[1], dz = t[2] - origin[2];
                hud = String.format("moved  X%+.2f  Y%+.2f  Z%+.2f", dx, dy, dz)
                        + String.format("\ndist %.2f m   (grid centre %.2f m)",
                                Math.sqrt(dx*dx + dy*dy + dz*dz),
                                Math.sqrt(t[0]*t[0] + t[1]*t[1] + t[2]*t[2]))
                        + "\n" + gl.modelStatus;
            }
            drawHalf(cv, 0, half, h, hud);
            drawHalf(cv, half, half, h, hud);
        }

        private void drawHalf(Canvas cv, int xoff, int halfW, int h, String hud) {
            cv.save();
            cv.clipRect(xoff, 0, xoff + halfW, h);

            float ccx = xoff + halfW / 2f, ccy = h / 2f;
            cv.drawLine(ccx - 12, ccy, ccx + 12, ccy, cross);
            cv.drawLine(ccx, ccy - 12, ccx, ccy + 12, cross);

            cv.drawText("tap = zero ruler", xoff + 8, h * 0.08f + label.getTextSize(), label);
            float ty = h * 0.08f + label.getTextSize() + text.getTextSize() * 1.6f;
            for (String ln : hud.split("\n")) {
                cv.drawText(ln, xoff + 8, ty, text);
                ty += text.getTextSize() * 1.3f;
            }
            cv.restore();
        }
    }
}
