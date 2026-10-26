package com.akular.poseprobe;

import android.app.Activity;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.View;
import android.view.Window;

import com.akular.rayneo.slam.Pose;
import com.akular.rayneo.slam.RayNeoSlam;

/**
 * Sample app for the rayneo-slam library: a world-locked scene on the RayNeo
 * X3 Pro, rendered by {@link GlRenderer} into a GLSurfaceView (stereo side-by-side).
 */
public class PoseProbeActivity extends Activity {

    private static final String TAG = "PoseProbe";
    private static final float POSE_EPS = 1e-5f;

    private RayNeoSlam slam;
    private GLSurfaceView glView;
    private HandlerThread pollThread;
    private Handler poll;

    // pose-change gate for RENDERMODE_WHEN_DIRTY: only redraw on a new pose
    private final Pose pollPose = new Pose();
    private final float[] last = new float[7];
    private boolean havePose;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (getActionBar() != null) getActionBar().hide();

        slam = RayNeoSlam.create(this);

        glView = new GLSurfaceView(this);
        glView.setEGLContextClientVersion(2);
        glView.setEGLConfigChooser(8, 8, 8, 0, 16, 0);
        glView.setRenderer(new GlRenderer(this, slam));
        // draw only when the pose changes, not every vsync
        glView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);

        setContentView(glView);
        glView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);

        pollThread = new HandlerThread("pose-poll");
        pollThread.start();
        poll = new Handler(pollThread.getLooper());

        slam.start(new RayNeoSlam.Listener() {
            public void onStarted()            { poll.post(PoseProbeActivity.this::pollPose); }
            public void onError(String reason) { Log.e(TAG, "SLAM unavailable: " + reason); }
        });
    }

    /** Wakes the renderer when the pose moves; the renderer reads its own fresh pose. */
    private void pollPose() {
        if (slam.getPose(pollPose) && changed(pollPose)) glView.requestRender();
        poll.postDelayed(this::pollPose, 33);
    }

    /** Compared against the last *rendered* pose, so slow sub-epsilon drift still accumulates. */
    private boolean changed(Pose p) {
        boolean c = !havePose;
        for (int i = 0; i < 7 && !c; i++)
            c = Math.abs(val(p, i) - last[i]) > POSE_EPS;
        if (c) {
            for (int i = 0; i < 7; i++) last[i] = val(p, i);
            havePose = true;
        }
        return c;
    }

    private static float val(Pose p, int i) { return i < 3 ? p.position[i] : p.rotation[i - 3]; }

    @Override
    protected void onPause() {
        super.onPause();
        glView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        glView.onResume();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        pollThread.quitSafely();
        slam.stop();
    }
}
