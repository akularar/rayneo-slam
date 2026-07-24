package com.akular.poseprobe;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.Matrix;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal GLB (binary glTF 2.0) loader — static meshes only, no dependencies
 * beyond the platform's org.json and BitmapFactory.
 *
 * Supported: default scene node tree ("matrix" or TRS), TRIANGLES primitives,
 * float POSITION / NORMAL / TEXCOORD_0, ubyte/ushort indices (uint only when
 * every value fits 16 bits — GLES2 has no uint index without an extension),
 * baseColorFactor + baseColorTexture (PNG/JPEG). Node transforms are baked
 * into the vertices at load; normals get the inverse-transpose treatment.
 *
 * Not supported (throws or skips with a log): Draco, sparse accessors,
 * skinning, morph targets, animation, non-triangle primitives, extensions.
 */
class GlbModel {

    private static final String TAG = "PoseProbe";

    /** One drawable chunk = one glTF primitive. Arrays are tightly packed. */
    static class Part {
        float[] pos, nrm, uv;              // baked model space; uv zeros if absent
        short[] idx;                        // raw bits, draw as GL_UNSIGNED_SHORT
        float[] baseColor = {1f, 1f, 1f, 1f};
        Bitmap bitmap;                      // null -> untextured, baseColor only
        int wrapS = 10497, wrapT = 10497;   // glTF wrap enums == GL enums (REPEAT)
    }

    final List<Part> parts = new ArrayList<>();
    final float[] bboxMin = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
    final float[] bboxMax = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
    int totalTris = 0;

    static GlbModel load(byte[] d) throws Exception {
        ByteBuffer bb = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        if (d.length < 20 || bb.getInt(0) != 0x46546C67) throw new IOException("not a GLB (magic)");
        if (bb.getInt(4) != 2) throw new IOException("glTF version != 2");
        int jsonLen = bb.getInt(12);
        if (bb.getInt(16) != 0x4E4F534A) throw new IOException("first chunk not JSON");
        JSONObject gltf = new JSONObject(new String(d, 20, jsonLen, "UTF-8"));
        int binBase = -1;
        int c = 20 + jsonLen;
        if (c + 8 <= d.length && bb.getInt(c + 4) == 0x004E4942) binBase = c + 8;

        GlbModel m = new GlbModel();
        new Parser(gltf, d, binBase).run(m);
        if (m.parts.isEmpty()) throw new IOException("no triangle primitives found");
        return m;
    }

    // ------------------------------------------------------------------

    private static class Parser {
        final JSONObject gltf;
        final byte[] data;
        final int binBase;              // absolute offset of BIN chunk payload
        final ByteBuffer bb;
        GlbModel out;

        Parser(JSONObject gltf, byte[] data, int binBase) {
            this.gltf = gltf;
            this.data = data;
            this.binBase = binBase;
            this.bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        }

        void run(GlbModel out) throws Exception {
            this.out = out;
            JSONArray scenes = gltf.getJSONArray("scenes");
            JSONObject scene = scenes.getJSONObject(gltf.optInt("scene", 0));
            JSONArray roots = scene.getJSONArray("nodes");
            float[] id = new float[16];
            Matrix.setIdentityM(id, 0);
            for (int i = 0; i < roots.length(); i++) walk(roots.getInt(i), id);
        }

        void walk(int nodeIdx, float[] parent) throws Exception {
            JSONObject node = gltf.getJSONArray("nodes").getJSONObject(nodeIdx);
            float[] world = new float[16];
            Matrix.multiplyMM(world, 0, parent, 0, localMatrix(node), 0);
            if (node.has("mesh")) bakeMesh(node.getInt("mesh"), world);
            JSONArray kids = node.optJSONArray("children");
            if (kids != null)
                for (int i = 0; i < kids.length(); i++) walk(kids.getInt(i), world);
        }

        /** Column-major local transform: "matrix" (glTF is col-major too) or T*R*S. */
        float[] localMatrix(JSONObject node) throws Exception {
            float[] m = new float[16];
            JSONArray a = node.optJSONArray("matrix");
            if (a != null) {
                for (int i = 0; i < 16; i++) m[i] = (float) a.getDouble(i);
                return m;
            }
            Matrix.setIdentityM(m, 0);
            JSONArray t = node.optJSONArray("translation");
            if (t != null) Matrix.translateM(m, 0,
                    (float) t.getDouble(0), (float) t.getDouble(1), (float) t.getDouble(2));
            JSONArray r = node.optJSONArray("rotation");   // [x,y,z,w]
            if (r != null) {
                float[] rm = quatM((float) r.getDouble(0), (float) r.getDouble(1),
                        (float) r.getDouble(2), (float) r.getDouble(3));
                float[] tmp = new float[16];
                Matrix.multiplyMM(tmp, 0, m, 0, rm, 0);
                m = tmp;
            }
            JSONArray s = node.optJSONArray("scale");
            if (s != null) Matrix.scaleM(m, 0,
                    (float) s.getDouble(0), (float) s.getDouble(1), (float) s.getDouble(2));
            return m;
        }

        void bakeMesh(int meshIdx, float[] M) throws Exception {
            JSONObject mesh = gltf.getJSONArray("meshes").getJSONObject(meshIdx);
            JSONArray prims = mesh.getJSONArray("primitives");

            // normal matrix = transpose(inverse(M)) upper 3x3
            float[] inv = new float[16], nm = new float[16];
            if (!Matrix.invertM(inv, 0, M, 0)) Matrix.setIdentityM(inv, 0);
            Matrix.transposeM(nm, 0, inv, 0);

            for (int p = 0; p < prims.length(); p++) {
                JSONObject prim = prims.getJSONObject(p);
                if (prim.optInt("mode", 4) != 4) {
                    Log.w(TAG, "glb: skipping non-TRIANGLES primitive (mode="
                            + prim.optInt("mode", 4) + ")");
                    continue;
                }
                JSONObject attrs = prim.getJSONObject("attributes");
                float[] pos = floats(attrs.getInt("POSITION"), 3);
                float[] nrm = attrs.has("NORMAL") ? floats(attrs.getInt("NORMAL"), 3) : null;
                float[] uv  = attrs.has("TEXCOORD_0") ? floats(attrs.getInt("TEXCOORD_0"), 2) : null;
                int nVerts = pos.length / 3;
                short[] idx = prim.has("indices")
                        ? indices(prim.getInt("indices"))
                        : sequential(nVerts);

                // bake node transform into positions, normal matrix into normals
                for (int i = 0; i < nVerts; i++) {
                    float x = pos[i*3], y = pos[i*3+1], z = pos[i*3+2];
                    float wx = M[0]*x + M[4]*y + M[8]*z  + M[12];
                    float wy = M[1]*x + M[5]*y + M[9]*z  + M[13];
                    float wz = M[2]*x + M[6]*y + M[10]*z + M[14];
                    pos[i*3] = wx; pos[i*3+1] = wy; pos[i*3+2] = wz;
                    out.bboxMin[0] = Math.min(out.bboxMin[0], wx);
                    out.bboxMin[1] = Math.min(out.bboxMin[1], wy);
                    out.bboxMin[2] = Math.min(out.bboxMin[2], wz);
                    out.bboxMax[0] = Math.max(out.bboxMax[0], wx);
                    out.bboxMax[1] = Math.max(out.bboxMax[1], wy);
                    out.bboxMax[2] = Math.max(out.bboxMax[2], wz);
                }
                if (nrm != null) {
                    for (int i = 0; i < nVerts; i++) {
                        float x = nrm[i*3], y = nrm[i*3+1], z = nrm[i*3+2];
                        float nx = nm[0]*x + nm[4]*y + nm[8]*z;
                        float ny = nm[1]*x + nm[5]*y + nm[9]*z;
                        float nz = nm[2]*x + nm[6]*y + nm[10]*z;
                        float n = (float) Math.sqrt(nx*nx + ny*ny + nz*nz);
                        if (n < 1e-8f) n = 1f;
                        nrm[i*3] = nx/n; nrm[i*3+1] = ny/n; nrm[i*3+2] = nz/n;
                    }
                } else {
                    nrm = faceNormals(pos, idx);
                }

                Part part = new Part();
                part.pos = pos;
                part.nrm = nrm;
                part.uv = uv != null ? uv : new float[nVerts * 2];
                part.idx = idx;
                material(prim.optInt("material", -1), part);
                out.parts.add(part);
                out.totalTris += idx.length / 3;
            }
        }

        void material(int matIdx, Part part) throws Exception {
            if (matIdx < 0) return;
            JSONObject mat = gltf.getJSONArray("materials").getJSONObject(matIdx);
            JSONObject pbr = mat.optJSONObject("pbrMetallicRoughness");
            if (pbr == null) return;
            JSONArray f = pbr.optJSONArray("baseColorFactor");
            if (f != null)
                for (int i = 0; i < 4; i++) part.baseColor[i] = (float) f.getDouble(i);
            JSONObject bct = pbr.optJSONObject("baseColorTexture");
            if (bct == null) return;
            JSONObject tex = gltf.getJSONArray("textures").getJSONObject(bct.getInt("index"));
            JSONObject img = gltf.getJSONArray("images").getJSONObject(tex.getInt("source"));
            if (!img.has("bufferView")) throw new IOException("external image URIs unsupported");
            JSONObject bv = gltf.getJSONArray("bufferViews").getJSONObject(img.getInt("bufferView"));
            int off = binBase + bv.optInt("byteOffset", 0), len = bv.getInt("byteLength");
            part.bitmap = BitmapFactory.decodeByteArray(data, off, len);
            if (part.bitmap == null) throw new IOException("texture decode failed ("
                    + img.optString("mimeType", "?") + ")");
            if (tex.has("sampler")) {
                JSONObject smp = gltf.getJSONArray("samplers").getJSONObject(tex.getInt("sampler"));
                part.wrapS = smp.optInt("wrapS", 10497);
                part.wrapT = smp.optInt("wrapT", 10497);
            }
        }

        /** Read a float accessor into a tightly packed array (honours byteStride). */
        float[] floats(int accIdx, int comps) throws Exception {
            JSONObject acc = gltf.getJSONArray("accessors").getJSONObject(accIdx);
            if (acc.has("sparse")) throw new IOException("sparse accessors unsupported");
            if (acc.getInt("componentType") != 5126)
                throw new IOException("accessor " + accIdx + ": expected float components");
            int count = acc.getInt("count");
            JSONObject bv = gltf.getJSONArray("bufferViews")
                    .getJSONObject(acc.getInt("bufferView"));
            int base = binBase + bv.optInt("byteOffset", 0) + acc.optInt("byteOffset", 0);
            int stride = bv.optInt("byteStride", comps * 4);
            float[] outArr = new float[count * comps];
            for (int i = 0; i < count; i++)
                for (int c = 0; c < comps; c++)
                    outArr[i * comps + c] = bb.getFloat(base + i * stride + c * 4);
            return outArr;
        }

        /** Read an index accessor as raw ushort bits (uint allowed if values fit). */
        short[] indices(int accIdx) throws Exception {
            JSONObject acc = gltf.getJSONArray("accessors").getJSONObject(accIdx);
            int comp = acc.getInt("componentType");
            int count = acc.getInt("count");
            JSONObject bv = gltf.getJSONArray("bufferViews")
                    .getJSONObject(acc.getInt("bufferView"));
            int base = binBase + bv.optInt("byteOffset", 0) + acc.optInt("byteOffset", 0);
            short[] idx = new short[count];
            switch (comp) {
                case 5121:  // ubyte
                    for (int i = 0; i < count; i++) idx[i] = (short) (data[base + i] & 0xFF);
                    break;
                case 5123:  // ushort
                    for (int i = 0; i < count; i++) idx[i] = bb.getShort(base + i * 2);
                    break;
                case 5125:  // uint -- ok only if everything fits 16 bits
                    for (int i = 0; i < count; i++) {
                        long v = bb.getInt(base + i * 4) & 0xFFFFFFFFL;
                        if (v > 0xFFFF) throw new IOException("mesh too large for ushort indices");
                        idx[i] = (short) v;
                    }
                    break;
                default: throw new IOException("index componentType " + comp + " unsupported");
            }
            return idx;
        }

        static short[] sequential(int n) throws IOException {
            if (n > 0x10000) throw new IOException("unindexed mesh too large");
            short[] idx = new short[n];
            for (int i = 0; i < n; i++) idx[i] = (short) i;
            return idx;
        }

        /** No NORMAL attribute: accumulate triangle face normals per vertex. */
        static float[] faceNormals(float[] pos, short[] idx) {
            float[] nrm = new float[pos.length];
            for (int t = 0; t < idx.length; t += 3) {
                int a = idx[t] & 0xFFFF, b = idx[t+1] & 0xFFFF, c = idx[t+2] & 0xFFFF;
                float ux = pos[b*3]-pos[a*3], uy = pos[b*3+1]-pos[a*3+1], uz = pos[b*3+2]-pos[a*3+2];
                float vx = pos[c*3]-pos[a*3], vy = pos[c*3+1]-pos[a*3+1], vz = pos[c*3+2]-pos[a*3+2];
                float nx = uy*vz - uz*vy, ny = uz*vx - ux*vz, nz = ux*vy - uy*vx;
                for (int v : new int[]{a, b, c}) {
                    nrm[v*3] += nx; nrm[v*3+1] += ny; nrm[v*3+2] += nz;
                }
            }
            for (int i = 0; i < nrm.length; i += 3) {
                float n = (float) Math.sqrt(nrm[i]*nrm[i] + nrm[i+1]*nrm[i+1] + nrm[i+2]*nrm[i+2]);
                if (n < 1e-8f) { nrm[i+1] = 1f; continue; }
                nrm[i] /= n; nrm[i+1] /= n; nrm[i+2] /= n;
            }
            return nrm;
        }

        /** Quaternion [x,y,z,w] -> column-major 4x4 rotation. */
        static float[] quatM(float x, float y, float z, float w) {
            float n = (float) Math.sqrt(x*x + y*y + z*z + w*w);
            if (n > 1e-6f) { x/=n; y/=n; z/=n; w/=n; }
            float xx=x*x, yy=y*y, zz=z*z, xy=x*y, xz=x*z, yz=y*z, wx=w*x, wy=w*y, wz=w*z;
            return new float[]{
                    1-2*(yy+zz), 2*(xy+wz),   2*(xz-wy),   0,
                    2*(xy-wz),   1-2*(xx+zz), 2*(yz+wx),   0,
                    2*(xz+wy),   2*(yz-wx),   1-2*(xx+yy), 0,
                    0,           0,           0,           1};
        }
    }
}
