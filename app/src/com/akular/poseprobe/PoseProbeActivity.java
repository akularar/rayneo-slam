package com.akular.poseprobe;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;

import com.rayneo.xr.client.FFalconXRClient;
import com.rayneo.xr.sdk.base.FXRPoseInfo;
import com.rayneo.xr.sdk.listener.XRLifecycleListener;

import java.lang.reflect.Field;

/**
 * World-locked wireframe cube on the RayNeo X3 Pro from a plain (non-Unity) app.
 *
 * Calibration build: the pose->camera convention (quaternion head->world vs
 * world->head, and the body->camera Y/Z axis signs) is unknown, so we cycle all
 * 8 candidates automatically (~6s each, or tap to advance). The wearer reports
 * which preset keeps the cube fixed in the room while turning the head.
 */
public class PoseProbeActivity extends Activity {

    private static final String TAG = "PoseProbe";

    private FFalconXRClient client;
    private CubeView view;
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

        view = new CubeView(this);
        setContentView(view);
        view.setSystemUiVisibility(
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
            view.setPose(pos, quat);
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
    protected void onDestroy() {
        super.onDestroy();
        if (slamOn) { try { SlamShim.disableSlam(); } catch (Throwable ignore) {} }
        try { if (client != null) client.uninitAndroid(); } catch (Throwable ignore) {}
        if (bgThread != null) bgThread.quitSafely();
    }

    // ------------------------------------------------------------------
    // Stereo cube renderer with auto-cycling convention presets
    // ------------------------------------------------------------------
    static class CubeView extends View {

        // preset i: Q_W2H = bit0, SY = bit1?-1:+1, SZ = bit2?-1:+1
        private static final float AXIS = 0.4f;   // triad arm length, metres

        private final float[] kL = {1494.03f, 1505.34f, 320f, 240f};
        private final float[] kR = {1494.51f, 1501.43f, 320f, 240f};

        private static final float S = 0.25f;     // cube half-size -> 0.5 m cube
        private static final float DIST = 2.0f;   // anchor distance (0.5 m cube ~fits 24deg FOV)
        private static final float NEAR = 0.10f;

        private static final int[][] EDGES = {
                {0,1},{0,2},{0,4},{1,3},{1,5},{2,3},
                {2,6},{3,7},{4,5},{4,6},{5,7},{6,7}};

        // quads; vertex i has x=(i&1), y=(i&2), z=(i&4) sign
        private static final int[][] FACES = {
                {0,1,3,2},   // -Z  dark blue
                {4,5,7,6},   // +Z  blue
                {0,1,5,4},   // -Y  dark green
                {2,3,7,6},   // +Y  green
                {0,2,6,4},   // -X  dark red
                {1,3,7,5}};  // +X  red
        private static final int[] FACE_COLORS = {
                0xFF1A1A66, 0xFF3333CC,
                0xFF1A661A, 0xFF33CC33,
                0xFF661A1A, 0xFFCC3333};

        private volatile float[] tHead, qHead;
        private float[] anchor;
        private final int preset = 3;   // SOLVED: q is world->head, flip Y, no flip Z

        /**
         * Sign of the SLAM position's Z relative to the rotation's world frame.
         * -1 confirmed on hardware: at +1 the cube fled the wearer on approach.
         */
        private static final int tz = -1;

        /** Head position when the cube was last anchored, for the on-screen ruler. */
        private float[] origin;

        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint face = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path facePath = new Path();
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint cross = new Paint();
        private final Paint axis = new Paint(Paint.ANTI_ALIAS_FLAG);

        CubeView(Context c) {
            super(c);
            setBackgroundColor(Color.BLACK);
            edge.setColor(Color.GRAY);    edge.setStrokeWidth(3f);
            face.setStyle(Paint.Style.FILL);
            text.setColor(Color.WHITE);   text.setTextSize(30f);
            label.setColor(Color.YELLOW); label.setTextSize(34f);
            cross.setColor(Color.DKGRAY);
            axis.setStrokeWidth(6f);      axis.setTextSize(30f);
        }

        private static boolean qW2H(int p) { return (p & 1) != 0; }
        private static float   sy(int p)   { return (p & 2) != 0 ? -1f : 1f; }
        private static float   sz(int p)   { return (p & 4) != 0 ? -1f : 1f; }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                reanchor();         // re-place cube ahead and zero the ruler
                postInvalidate();
                return true;
            }
            return super.onTouchEvent(e);
        }

        void setPose(float[] t, float[] q) {
            boolean valid = t != null && q != null && t.length >= 3 && q.length >= 4
                    && !Float.isNaN(t[0]) && !Float.isNaN(t[1]) && !Float.isNaN(t[2])
                    && !(q[0] == 0 && q[1] == 0 && q[2] == 0 && q[3] == 0);
            tHead = t; qHead = q;
            if (valid && anchor == null) reanchor();
            postInvalidate();
        }

        /** Head position in the rotation's world frame (see {@link #tz}). */
        private float[] tw() {
            float[] t = tHead;
            return t == null ? null : new float[]{t[0], t[1], tz * t[2]};
        }

        /** Place cube DIST metres ahead of current gaze, using current preset. */
        private void reanchor() {
            float[] t = tw(), q = qHead;
            if (t == null || q == null) return;
            float[] Rhw = transpose(worldToHead(q, preset)); // head->world
            float fx = Rhw[2], fy = Rhw[5], fz = Rhw[8];     // body +Z in world
            float s = sz(preset);
            anchor = new float[]{t[0] + s*fx*DIST, t[1] + s*fy*DIST, t[2] + s*fz*DIST};
            origin = t;
        }

        @Override
        protected void onDraw(Canvas cv) {
            cv.drawColor(Color.BLACK);
            int w = getWidth(), h = getHeight(), half = w / 2;
            drawEye(cv, 0, half, h, kL);
            drawEye(cv, half, half, h, kR);
        }

        private void drawEye(Canvas cv, int xoff, int halfW, int h, float[] K) {
            cv.save();
            cv.clipRect(xoff, 0, xoff + halfW, h);

            float ccx = xoff + halfW / 2f, ccy = h / 2f;
            cv.drawLine(ccx - 12, ccy, ccx + 12, ccy, cross);
            cv.drawLine(ccx, ccy - 12, ccx, ccy + 12, cross);

            float[] t = tw(), q = qHead;
            String hud;
            if (t == null || q == null || anchor == null) {
                hud = "SLAM: waiting for localization\n(wear + move to bootstrap)";
            } else {
                float[] Rwh = worldToHead(q, preset);
                float[][] uv = new float[8][];
                for (int i = 0; i < 8; i++) {
                    float wx = anchor[0] + ((i & 1) != 0 ? S : -S);
                    float wy = anchor[1] + ((i & 2) != 0 ? S : -S);
                    float wz = anchor[2] + ((i & 4) != 0 ? S : -S);
                    uv[i] = project(wx, wy, wz, t, Rwh, K, xoff);
                }
                // solid faces, painter's algorithm: farthest first. A face with any
                // vertex behind the near plane is skipped (no clipping).
                float[] depth = new float[6];
                int[] order = {0, 1, 2, 3, 4, 5};
                for (int f = 0; f < 6; f++) {
                    float d = 0; boolean ok = true;
                    for (int vi : FACES[f]) {
                        if (uv[vi] == null) { ok = false; break; }
                        d += uv[vi][2];
                    }
                    depth[f] = ok ? d : Float.NEGATIVE_INFINITY;
                }
                for (int i = 0; i < 6; i++)          // tiny N: bubble sort, desc depth
                    for (int j = i + 1; j < 6; j++)
                        if (depth[order[j]] > depth[order[i]]) {
                            int tmp = order[i]; order[i] = order[j]; order[j] = tmp;
                        }
                for (int f : order) {
                    if (depth[f] == Float.NEGATIVE_INFINITY) continue;
                    int[] vs = FACES[f];
                    facePath.reset();
                    facePath.moveTo(uv[vs[0]][0], uv[vs[0]][1]);
                    for (int k = 1; k < 4; k++) facePath.lineTo(uv[vs[k]][0], uv[vs[k]][1]);
                    facePath.close();
                    face.setColor(FACE_COLORS[f]);
                    cv.drawPath(facePath, face);
                }
                for (int[] e : EDGES) {
                    float[] a = uv[e[0]], b = uv[e[1]];
                    if (a != null && b != null) cv.drawLine(a[0], a[1], b[0], b[1], edge);
                }
                float[] c = project(anchor[0], anchor[1], anchor[2], t, Rwh, K, xoff);
                // world-axis triad at the anchor: +X red, +Y green, +Z blue
                int[] cols = {Color.RED, Color.GREEN, Color.BLUE};
                String[] nms = {"X", "Y", "Z"};
                for (int a = 0; a < 3; a++) {
                    float[] tip = project(anchor[0] + (a == 0 ? AXIS : 0),
                                          anchor[1] + (a == 1 ? AXIS : 0),
                                          anchor[2] + (a == 2 ? AXIS : 0), t, Rwh, K, xoff);
                    if (c == null || tip == null) continue;
                    axis.setColor(cols[a]);
                    cv.drawLine(c[0], c[1], tip[0], tip[1], axis);
                    cv.drawText(nms[a], tip[0] + 6, tip[1], axis);
                }
                // ruler: how far the app thinks you have walked since the last tap
                float dx = t[0] - origin[0], dy = t[1] - origin[1], dz = t[2] - origin[2];
                hud = String.format("moved  X%+.2f  Y%+.2f  Z%+.2f", dx, dy, dz)
                        + String.format("\ndist %.2f m   (cube %.2f m)",
                                Math.sqrt(dx*dx + dy*dy + dz*dz), dist(t, anchor))
                        + (c == null ? "\ncube BEHIND" : "");
            }

            // big preset label
            String lbl = "tap = re-anchor + zero";
            cv.drawText(lbl, xoff + 8, h * 0.08f + label.getTextSize(), label);

            float ty = h * 0.08f + label.getTextSize() + text.getTextSize() * 1.6f;
            for (String ln : hud.split("\n")) {
                cv.drawText(ln, xoff + 8, ty, text);
                ty += text.getTextSize() * 1.3f;
            }
            cv.restore();
        }

        private static double dist(float[] a, float[] b) {
            float dx = a[0]-b[0], dy = a[1]-b[1], dz = a[2]-b[2];
            return Math.sqrt(dx*dx + dy*dy + dz*dz);
        }

        private float[] project(float wx, float wy, float wz,
                                float[] t, float[] Rwh, float[] K, int xoff) {
            float dx = wx - t[0], dy = wy - t[1], dz = wz - t[2];
            float bx = Rwh[0]*dx + Rwh[1]*dy + Rwh[2]*dz;
            float by = Rwh[3]*dx + Rwh[4]*dy + Rwh[5]*dz;
            float bz = Rwh[6]*dx + Rwh[7]*dy + Rwh[8]*dz;
            float Xc = bx;
            float Yc = sy(preset) * by;
            float Zc = sz(preset) * bz;
            if (Zc <= NEAR) return null;
            float u = K[0] * Xc / Zc + K[2];
            float v = K[1] * Yc / Zc + K[3];
            return new float[]{xoff + u, v, Zc};   // Zc: camera depth for face sorting
        }

        /** world->head rotation for the given preset. */
        private static float[] worldToHead(float[] q, int p) {
            // q is head->world in a Z-flipped frame (same flip as tz):
            // world->head (render frame) = Fz * R(q)^T * Fz = R([x, y, -z, w]).
            // Pure yaw / front-pitch are unchanged vs raw R(q) (why preset 3
            // passed the original test), but this fixes roll (was doubled) and
            // pitch when facing away from the anchor (was reversed).
            float[] Rq = quatToR(new float[]{q[0], q[1], -q[2], q[3]});
            return qW2H(p) ? Rq : transpose(Rq);
        }

        private static float[] transpose(float[] m) {
            return new float[]{ m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8] };
        }

        private static float[] quatToR(float[] q) {
            float x = q[0], y = q[1], z = q[2], w = q[3];
            float n = (float) Math.sqrt(x*x + y*y + z*z + w*w);
            if (n > 1e-6f) { x/=n; y/=n; z/=n; w/=n; }
            float xx=x*x, yy=y*y, zz=z*z, xy=x*y, xz=x*z, yz=y*z, wx=w*x, wy=w*y, wz=w*z;
            return new float[]{
                    1-2*(yy+zz), 2*(xy-wz),   2*(xz+wy),
                    2*(xy+wz),   1-2*(xx+zz), 2*(yz-wx),
                    2*(xz-wy),   2*(yz+wx),   1-2*(xx+yy)};
        }
    }
}
