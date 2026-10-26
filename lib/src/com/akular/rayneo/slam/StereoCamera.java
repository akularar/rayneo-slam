package com.akular.rayneo.slam;

import android.opengl.GLES20;
import android.opengl.Matrix;

/**
 * Per-eye view-projection matrices for the X3 Pro's side-by-side display
 * (one 1280x480 surface: left 640 px = {@link #LEFT}, right 640 px = {@link #RIGHT}).
 *
 * <p>World frame: +X right, +Y up, +Z away from where tracking started, metres.
 * Content placed there stays fixed in the room.
 *
 * <p>The view flips Y (CV-style camera folded into GL), which reverses visible
 * winding: with CCW-outward meshes use {@code glFrontFace(GL_CW)} for culling.
 *
 * <pre>
 *   onSurfaceChanged: camera.setSurfaceSize(w, h);
 *   onDrawFrame:      camera.update(pose);
 *                     glEnable(GL_SCISSOR_TEST);
 *                     for (int eye = 0; eye &lt; 2; eye++) {
 *                         camera.applyViewport(eye);
 *                         camera.getViewProjection(eye, vp);
 *                         draw(vp);
 *                     }
 * </pre>
 *
 * Not thread-safe: use it from the GL thread.
 */
public final class StereoCamera {

    public static final int LEFT = 0, RIGHT = 1;

    // per-eye intrinsics {fx, fy, cx, cy} at 640x480, from getRenderInstriParameters
    private static final float[][] K = {
            {1494.03f, 1505.34f, 320f, 240f},
            {1494.51f, 1501.43f, 320f, 240f}};
    private static final float NEAR = 0.10f, FAR = 100f;

    private final float[][] proj = {new float[16], new float[16]};
    private final float[] view = new float[16];
    private final float[] rwh = new float[9];
    private final float[] t = new float[3];
    private int surfW, surfH;

    /** Call from onSurfaceChanged with the full (both-eye) surface size. */
    public void setSurfaceSize(int width, int height) {
        surfW = width; surfH = height;
        proj(K[LEFT], width / 2, height, proj[LEFT]);
        proj(K[RIGHT], width / 2, height, proj[RIGHT]);
    }

    /**
     * Sets the camera from a head pose. Convention solved on hardware: the
     * runtime's frame is Z-flipped relative to the world, so rotation is
     * R([x, y, -z, w]) and position (x, y, -z).
     */
    public void update(Pose pose) {
        float[] p = pose.position, q = pose.rotation;
        t[0] = p[0]; t[1] = p[1]; t[2] = -p[2];
        quatToR(q[0], q[1], -q[2], q[3], rwh);
        view(rwh, t, view);
    }

    /** World -> clip for {@code eye}, column-major, into out[16]. */
    public void getViewProjection(int eye, float[] out) {
        Matrix.multiplyMM(out, 0, proj[eye], 0, view, 0);
    }

    /** glViewport + glScissor to {@code eye}'s half (enable GL_SCISSOR_TEST yourself). */
    public void applyViewport(int eye) {
        int half = surfW / 2, x = eye * half;
        GLES20.glViewport(x, 0, half, surfH);
        GLES20.glScissor(x, 0, half, surfH);
    }

    /** CV intrinsics -> GL projection (column-major), into m. cx,cy == half centre.
     *  m keeps its zero entries between calls (they are never written elsewhere). */
    private static void proj(float[] K, int w, int h, float[] m) {
        m[0] = 2f * K[0] / w;
        m[5] = -2f * K[1] / h;                    // v grows down -> NDC y up
        m[10] = (FAR + NEAR) / (FAR - NEAR);
        m[11] = 1f;                               // w_clip = +camZ (Z forward)
        m[14] = -2f * FAR * NEAR / (FAR - NEAR);
    }

    /** World -> camera: cam = S*Rwh*(p - t), S = diag(1,-1,1). Column-major, into v. */
    private static void view(float[] Rwh, float[] t, float[] v) {
        v[0] = Rwh[0]; v[4] = Rwh[1]; v[8]  = Rwh[2];
        v[1] = -Rwh[3]; v[5] = -Rwh[4]; v[9] = -Rwh[5];
        v[2] = Rwh[6]; v[6] = Rwh[7]; v[10] = Rwh[8];
        v[12] = -(v[0]*t[0] + v[4]*t[1] + v[8]*t[2]);
        v[13] = -(v[1]*t[0] + v[5]*t[1] + v[9]*t[2]);
        v[14] = -(v[2]*t[0] + v[6]*t[1] + v[10]*t[2]);
        v[15] = 1f;
    }

    private static void quatToR(float x, float y, float z, float w, float[] r) {
        float n = (float) Math.sqrt(x*x + y*y + z*z + w*w);
        if (n > 1e-6f) { x/=n; y/=n; z/=n; w/=n; }
        float xx=x*x, yy=y*y, zz=z*z, xy=x*y, xz=x*z, yz=y*z, wx=w*x, wy=w*y, wz=w*z;
        r[0]=1-2*(yy+zz); r[1]=2*(xy-wz);   r[2]=2*(xz+wy);
        r[3]=2*(xy+wz);   r[4]=1-2*(xx+zz); r[5]=2*(yz-wx);
        r[6]=2*(xz-wy);   r[7]=2*(yz+wx);   r[8]=1-2*(xx+yy);
    }
}
