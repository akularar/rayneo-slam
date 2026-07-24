package com.akular.poseprobe;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * GLES2 port of the Canvas renderer: same Unity "Slam.unity" replica scene
 * (12x12 solid cube grid + world-axis triad) plus a GLB model, stereo
 * side-by-side via per-eye viewport/scissor. The pose->camera convention is
 * the SOLVED preset 3 (q world->head via z-flip, flip Y, keep Z, tz=-1) —
 * identical math to the old software `project()`, expressed as matrices:
 *
 *   cam = S * Rwh * (p - t),  S = diag(1,-1,1),  t = [tx, ty, -tz_raw]
 *   u = fx*camX/camZ + cx,  v = fy*camY/camZ + cy   (v down, camZ forward)
 *
 * so the projection matrix maps that CV-style camera to GL NDC (y flip folded
 * in; cx,cy are exactly the eye-half centre so there are no offset terms).
 */
class GlRenderer implements GLSurfaceView.Renderer {

    private static final String TAG = "PoseProbe";

    // per-eye intrinsics {fx, fy, cx, cy} at 640x480, from getRenderInstriParameters
    private static final float[] K_L = {1494.03f, 1505.34f, 320f, 240f};
    private static final float[] K_R = {1494.51f, 1501.43f, 320f, 240f};
    private static final float NEAR = 0.10f, FAR = 100f;

    private static final float AXIS = 0.4f;   // triad arm length, metres

    // --- Unity "Slam.unity" scene replica (see CLAUDE.md): 12x12 grid,
    // 0.8 m spacing, 0.1 m cubes at y=-0.3, shared prefab rotation. ---
    private static final int   GRID    = 12;
    private static final float SPACING = 0.8f;
    private static final float CUBE_Y  = -0.3f;
    private static final float S       = 0.05f;

    // quads; vertex i has x=(i&1), y=(i&2), z=(i&4) sign
    private static final int[][] FACES = {
            {0,1,3,2}, {4,5,7,6}, {0,1,5,4}, {2,3,7,6}, {0,2,6,4}, {1,3,7,5}};
    // per-face brightness, fakes lighting so the shared-rotation cubes read as 3D
    private static final float[] FACE_SHADE = {0.45f, 0.9f, 0.35f, 1f, 0.55f, 0.75f};

    /** Prefab rotation from Slam.unity (euler -60,-30,50), local->world. */
    private static final float[] CUBE_R =
            quatToR(new float[]{-0.53521216f, -0.0027692902f, 0.2438928f, 0.8087377f});

    /** Where the GLB model sits in the world, and its auto-scaled height. */
    private static final float[] MODEL_POS = {0f, -0.1f, 1.5f};
    private static final float MODEL_HEIGHT = 0.4f;
    private static final float[] LIGHT_DIR = norm3(0.35f, 0.85f, -0.4f);

    /**
     * Sign of the SLAM position's Z relative to the rotation's world frame.
     * -1 confirmed on hardware: at +1 the cube fled the wearer on approach.
     */
    private static final int tz = -1;

    private final Context ctx;
    private volatile float[] tHead, qHead;
    volatile String modelStatus = "model: loading...";

    // GL objects — rebuilt on every surface (re)create, so plain fields are fine
    private int colorProg, texProg;
    private int cAPos, cACol, cUMvp;
    private int tAPos, tANrm, tAUv, tUMvp, tUColor, tULight, tUTex;
    private int gridVbo, gridIbo, gridIdxCount;
    private int triadVbo;
    private final List<GlPart> modelParts = new ArrayList<>();
    private final float[] modelM = new float[16];
    private int surfW, surfH;
    private boolean loggedFrame;

    private static class GlPart {
        int vbo, ibo, tex, idxCount;
        float[] baseColor;
    }

    GlRenderer(Context ctx) { this.ctx = ctx; }

    // ------------------------------------------------------------------ pose

    void setPose(float[] t, float[] q) {
        boolean valid = t != null && q != null && t.length >= 3 && q.length >= 4
                && !Float.isNaN(t[0]) && !Float.isNaN(t[1]) && !Float.isNaN(t[2])
                && !(q[0] == 0 && q[1] == 0 && q[2] == 0 && q[3] == 0);
        if (!valid) return;
        tHead = t; qHead = q;
    }

    /** Head position in the rotation's world frame, null until localized. */
    float[] tw() {
        float[] t = tHead;
        return t == null ? null : new float[]{t[0], t[1], tz * t[2]};
    }

    // ------------------------------------------------------------- lifecycle

    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_CULL_FACE);   // depth buffer does the work
        GLES20.glDisable(GLES20.GL_BLEND);

        colorProg = program(
                "attribute vec3 aPos; attribute vec3 aCol; uniform mat4 uMVP;\n" +
                "varying vec3 vCol;\n" +
                "void main() { vCol = aCol; gl_Position = uMVP * vec4(aPos, 1.0); }",
                "precision mediump float; varying vec3 vCol;\n" +
                "void main() { gl_FragColor = vec4(vCol, 1.0); }");
        cAPos = GLES20.glGetAttribLocation(colorProg, "aPos");
        cACol = GLES20.glGetAttribLocation(colorProg, "aCol");
        cUMvp = GLES20.glGetUniformLocation(colorProg, "uMVP");

        texProg = program(
                "attribute vec3 aPos; attribute vec3 aNrm; attribute vec2 aUV;\n" +
                "uniform mat4 uMVP; varying vec3 vNrm; varying vec2 vUV;\n" +
                "void main() { vNrm = aNrm; vUV = aUV;\n" +
                "  gl_Position = uMVP * vec4(aPos, 1.0); }",
                "precision mediump float; varying vec3 vNrm; varying vec2 vUV;\n" +
                "uniform sampler2D uTex; uniform vec4 uColor; uniform vec3 uLight;\n" +
                "void main() {\n" +
                "  float d = max(dot(normalize(vNrm), uLight), 0.0);\n" +
                "  vec4 c = texture2D(uTex, vUV) * uColor;\n" +
                "  gl_FragColor = vec4(c.rgb * (0.35 + 0.65 * d), c.a); }");
        tAPos = GLES20.glGetAttribLocation(texProg, "aPos");
        tANrm = GLES20.glGetAttribLocation(texProg, "aNrm");
        tAUv = GLES20.glGetAttribLocation(texProg, "aUV");
        tUMvp = GLES20.glGetUniformLocation(texProg, "uMVP");
        tUColor = GLES20.glGetUniformLocation(texProg, "uColor");
        tULight = GLES20.glGetUniformLocation(texProg, "uLight");
        tUTex = GLES20.glGetUniformLocation(texProg, "uTex");

        buildGrid();
        buildTriad();
        loadModel();
        loggedFrame = false;
    }

    @Override
    public void onSurfaceChanged(GL10 unused, int w, int h) {
        surfW = w; surfH = h;
        Log.i(TAG, "GL surface " + w + "x" + h);
    }

    @Override
    public void onDrawFrame(GL10 unused) {
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        float[] t = tw(), q = qHead;
        if (t == null || q == null) return;   // HUD shows "waiting"

        // preset 3: q is head->world in a Z-flipped frame -> world->head = R([x,y,-z,w])
        float[] Rwh = quatToR(new float[]{q[0], q[1], -q[2], q[3]});
        float[] V = view(Rwh, t);

        int half = surfW / 2;
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
        float[] vp = new float[16];
        for (int eye = 0; eye < 2; eye++) {
            int xoff = eye * half;
            GLES20.glViewport(xoff, 0, half, surfH);
            GLES20.glScissor(xoff, 0, half, surfH);
            Matrix.multiplyMM(vp, 0, proj(eye == 0 ? K_L : K_R, half, surfH), 0, V, 0);
            drawScene(vp);
        }
        if (!loggedFrame) {
            loggedFrame = true;
            Log.i(TAG, "GL first stereo frame drawn, glGetError=0x"
                    + Integer.toHexString(GLES20.glGetError()));
        }
    }

    // ------------------------------------------------------------- matrices

    /** CV intrinsics -> GL projection (column-major). cx,cy == half centre. */
    private static float[] proj(float[] K, int w, int h) {
        float[] m = new float[16];
        m[0] = 2f * K[0] / w;
        m[5] = -2f * K[1] / h;                    // v grows down -> NDC y up
        m[10] = (FAR + NEAR) / (FAR - NEAR);
        m[11] = 1f;                               // w_clip = +camZ (Z forward)
        m[14] = -2f * FAR * NEAR / (FAR - NEAR);
        return m;
    }

    /** World -> camera: cam = S*Rwh*(p - t), S = diag(1,-1,1). Column-major. */
    private static float[] view(float[] Rwh, float[] t) {
        float[] v = new float[16];
        v[0] = Rwh[0]; v[4] = Rwh[1]; v[8]  = Rwh[2];
        v[1] = -Rwh[3]; v[5] = -Rwh[4]; v[9] = -Rwh[5];
        v[2] = Rwh[6]; v[6] = Rwh[7]; v[10] = Rwh[8];
        v[12] = -(v[0]*t[0] + v[4]*t[1] + v[8]*t[2]);
        v[13] = -(v[1]*t[0] + v[5]*t[1] + v[9]*t[2]);
        v[14] = -(v[2]*t[0] + v[6]*t[1] + v[10]*t[2]);
        v[15] = 1f;
        return v;
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

    private static float[] norm3(float x, float y, float z) {
        float n = (float) Math.sqrt(x*x + y*y + z*z);
        return new float[]{x/n, y/n, z/n};
    }

    // ------------------------------------------------------------------ draw

    private void drawScene(float[] vp) {
        // cube grid + triad: baked world-space vertices, one uMVP = VP
        GLES20.glUseProgram(colorProg);
        GLES20.glUniformMatrix4fv(cUMvp, 1, false, vp, 0);
        GLES20.glEnableVertexAttribArray(cAPos);
        GLES20.glEnableVertexAttribArray(cACol);

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, gridVbo);
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, gridIbo);
        GLES20.glVertexAttribPointer(cAPos, 3, GLES20.GL_FLOAT, false, 24, 0);
        GLES20.glVertexAttribPointer(cACol, 3, GLES20.GL_FLOAT, false, 24, 12);
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, gridIdxCount, GLES20.GL_UNSIGNED_SHORT, 0);
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0);

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, triadVbo);
        GLES20.glVertexAttribPointer(cAPos, 3, GLES20.GL_FLOAT, false, 24, 0);
        GLES20.glVertexAttribPointer(cACol, 3, GLES20.GL_FLOAT, false, 24, 12);
        GLES20.glLineWidth(6f);
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, 6);
        GLES20.glDisableVertexAttribArray(cAPos);
        GLES20.glDisableVertexAttribArray(cACol);

        if (modelParts.isEmpty()) return;
        float[] mvp = new float[16];
        Matrix.multiplyMM(mvp, 0, vp, 0, modelM, 0);
        GLES20.glUseProgram(texProg);
        GLES20.glUniformMatrix4fv(tUMvp, 1, false, mvp, 0);
        GLES20.glUniform3fv(tULight, 1, LIGHT_DIR, 0);
        GLES20.glUniform1i(tUTex, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glEnableVertexAttribArray(tAPos);
        GLES20.glEnableVertexAttribArray(tANrm);
        GLES20.glEnableVertexAttribArray(tAUv);
        for (GlPart p : modelParts) {
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, p.vbo);
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, p.ibo);
            GLES20.glVertexAttribPointer(tAPos, 3, GLES20.GL_FLOAT, false, 32, 0);
            GLES20.glVertexAttribPointer(tANrm, 3, GLES20.GL_FLOAT, false, 32, 12);
            GLES20.glVertexAttribPointer(tAUv, 2, GLES20.GL_FLOAT, false, 32, 24);
            GLES20.glUniform4fv(tUColor, 1, p.baseColor, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, p.tex);
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, p.idxCount, GLES20.GL_UNSIGNED_SHORT, 0);
        }
        GLES20.glDisableVertexAttribArray(tAPos);
        GLES20.glDisableVertexAttribArray(tANrm);
        GLES20.glDisableVertexAttribArray(tAUv);
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
    }

    // ----------------------------------------------------------- geometry

    /** Whole grid baked world-space: 144 cubes x 24 verts (pos3+col3). */
    private void buildGrid() {
        float[] hsv = {0f, 0.85f, 1f};
        int nCubes = GRID * GRID;
        float[] verts = new float[nCubes * 24 * 6];
        short[] idx = new short[nCubes * 36];
        int vi = 0, ii = 0, vbase = 0;
        for (int i = 0; i < GRID; i++) {
            for (int j = 0; j < GRID; j++) {
                int id = i * GRID + j;
                float cx = SPACING * (i - GRID / 2);
                float czr = SPACING * (j - GRID / 2);
                hsv[0] = (id * 47) % 360;    // stable pseudo-random colour per cube
                int col = Color.HSVToColor(hsv);
                for (int f = 0; f < 6; f++) {
                    float sh = FACE_SHADE[f];
                    float r = Color.red(col) * sh / 255f;
                    float g = Color.green(col) * sh / 255f;
                    float b = Color.blue(col) * sh / 255f;
                    for (int k = 0; k < 4; k++) {
                        int c = FACES[f][k];
                        float lx = (c & 1) != 0 ? S : -S;
                        float ly = (c & 2) != 0 ? S : -S;
                        float lz = (c & 4) != 0 ? S : -S;
                        verts[vi++] = cx + CUBE_R[0]*lx + CUBE_R[1]*ly + CUBE_R[2]*lz;
                        verts[vi++] = CUBE_Y + CUBE_R[3]*lx + CUBE_R[4]*ly + CUBE_R[5]*lz;
                        verts[vi++] = czr + CUBE_R[6]*lx + CUBE_R[7]*ly + CUBE_R[8]*lz;
                        verts[vi++] = r; verts[vi++] = g; verts[vi++] = b;
                    }
                    idx[ii++] = (short) vbase;
                    idx[ii++] = (short) (vbase + 1);
                    idx[ii++] = (short) (vbase + 2);
                    idx[ii++] = (short) vbase;
                    idx[ii++] = (short) (vbase + 2);
                    idx[ii++] = (short) (vbase + 3);
                    vbase += 4;
                }
            }
        }
        gridVbo = uploadVerts(verts);
        gridIbo = uploadIdx(idx);
        gridIdxCount = idx.length;
    }

    /** World-axis triad at the origin: +X red, +Y green, +Z blue. */
    private void buildTriad() {
        float[] v = {
                0,0,0, 1,0,0,  AXIS,0,0, 1,0,0,
                0,0,0, 0,1,0,  0,AXIS,0, 0,1,0,
                0,0,0, 0,0,1,  0,0,AXIS, 0,0,1};
        triadVbo = uploadVerts(v);
    }

    // -------------------------------------------------------------- model

    private void loadModel() {
        modelParts.clear();
        byte[] data = null;
        String src = null;
        try {
            // 1) files dir (adb-pushable when the device allows it) for quick swaps
            File f = new File(ctx.getExternalFilesDir(null), "model.glb");
            if (f.isFile()) {
                data = readAll(new FileInputStream(f), (int) f.length());
                src = "files/";
            } else {  // 2) the APK asset
                InputStream is = ctx.getAssets().open("model.glb");
                data = readAll(is, 0);
                src = "apk:";
            }
            GlbModel m = GlbModel.load(data);

            // model matrix: centre the bbox on MODEL_POS, auto-scale to height
            float bh = m.bboxMax[1] - m.bboxMin[1];
            float sc = bh > 1e-6f ? MODEL_HEIGHT / bh : 1f;
            Matrix.setIdentityM(modelM, 0);
            Matrix.translateM(modelM, 0, MODEL_POS[0], MODEL_POS[1], MODEL_POS[2]);
            Matrix.scaleM(modelM, 0, sc, sc, sc);
            Matrix.translateM(modelM, 0,
                    -(m.bboxMin[0] + m.bboxMax[0]) / 2f,
                    -(m.bboxMin[1] + m.bboxMax[1]) / 2f,
                    -(m.bboxMin[2] + m.bboxMax[2]) / 2f);

            for (GlbModel.Part p : m.parts) modelParts.add(upload(p));
            modelStatus = String.format("model: %smodel.glb %d tris, %.2fm raw h, x%.3f",
                    src, m.totalTris, bh, sc);
            Log.i(TAG, modelStatus + String.format(
                    "  bbox [%.2f %.2f %.2f]..[%.2f %.2f %.2f]",
                    m.bboxMin[0], m.bboxMin[1], m.bboxMin[2],
                    m.bboxMax[0], m.bboxMax[1], m.bboxMax[2]));
        } catch (Throwable t) {
            modelStatus = "model: FAILED " + t.getMessage();
            Log.e(TAG, "model load failed", t);
        }
    }

    private GlPart upload(GlbModel.Part p) {
        int n = p.pos.length / 3;
        float[] inter = new float[n * 8];
        for (int i = 0; i < n; i++) {
            inter[i*8]   = p.pos[i*3];
            inter[i*8+1] = p.pos[i*3+1];
            inter[i*8+2] = p.pos[i*3+2];
            inter[i*8+3] = p.nrm[i*3];
            inter[i*8+4] = p.nrm[i*3+1];
            inter[i*8+5] = p.nrm[i*3+2];
            inter[i*8+6] = p.uv[i*2];
            inter[i*8+7] = p.uv[i*2+1];
        }
        GlPart g = new GlPart();
        g.vbo = uploadVerts(inter);
        g.ibo = uploadIdx(p.idx);
        g.idxCount = p.idx.length;
        g.baseColor = p.baseColor;
        g.tex = texture(p.bitmap, p.wrapS, p.wrapT);
        return g;
    }

    private static int texture(Bitmap bmp, int wrapS, int wrapT) {
        int[] id = new int[1];
        GLES20.glGenTextures(1, id, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id[0]);
        if (bmp == null) {   // untextured part: 1x1 white, baseColor does the rest
            ByteBuffer px = ByteBuffer.allocateDirect(4).put(new byte[]{-1, -1, -1, -1});
            px.position(0);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1, 1, 0,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, px);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
            return id[0];
        }
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0);
        boolean pot = (bmp.getWidth() & (bmp.getWidth() - 1)) == 0
                && (bmp.getHeight() & (bmp.getHeight() - 1)) == 0;
        if (pot) {   // ES2: mipmaps + repeat need power-of-two
            GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, wrapS);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, wrapT);
        } else {
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        }
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        return id[0];
    }

    // ---------------------------------------------------------------- util

    private static byte[] readAll(InputStream is, int sizeHint) throws Exception {
        try {
            java.io.ByteArrayOutputStream bos =
                    new java.io.ByteArrayOutputStream(sizeHint > 0 ? sizeHint : 1 << 17);
            byte[] buf = new byte[1 << 16];
            int r;
            while ((r = is.read(buf)) > 0) bos.write(buf, 0, r);
            return bos.toByteArray();
        } finally {
            is.close();
        }
    }

    private static int uploadVerts(float[] v) {
        FloatBuffer fb = ByteBuffer.allocateDirect(v.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        fb.put(v).position(0);
        int[] id = new int[1];
        GLES20.glGenBuffers(1, id, 0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, id[0]);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, v.length * 4, fb, GLES20.GL_STATIC_DRAW);
        return id[0];
    }

    private static int uploadIdx(short[] v) {
        ShortBuffer sb = ByteBuffer.allocateDirect(v.length * 2)
                .order(ByteOrder.nativeOrder()).asShortBuffer();
        sb.put(v).position(0);
        int[] id = new int[1];
        GLES20.glGenBuffers(1, id, 0);
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, id[0]);
        GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, v.length * 2, sb,
                GLES20.GL_STATIC_DRAW);
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0);
        return id[0];
    }

    private static int program(String vs, String fs) {
        int v = shader(GLES20.GL_VERTEX_SHADER, vs);
        int f = shader(GLES20.GL_FRAGMENT_SHADER, fs);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v);
        GLES20.glAttachShader(p, f);
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) Log.e(TAG, "link failed: " + GLES20.glGetProgramInfoLog(p));
        return p;
    }

    private static int shader(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) Log.e(TAG, "shader failed: " + GLES20.glGetShaderInfoLog(s));
        return s;
    }
}
