# rayneo-slam

World-locked 6DoF head tracking for **RayNeo X3 Pro** AR glasses, from a plain Android
app — no Unity, no OpenXR renderer.

RayNeo's documented native SDK only exposes 3DoF (rotation). The glasses' system runtime
*does* run full SLAM (rotation + position); this library switches it on and hands you the
pose, plus ready-made per-eye camera matrices so content you place stays fixed in the room
while the wearer walks around it.

```java
RayNeoSlam slam = RayNeoSlam.create(activity);
slam.start(listener);
// every frame:
if (slam.getPose(pose)) camera.update(pose);
```

- **Package:** `com.akular.rayneo.slam`
- **Version:** 0.1.0 — see [CHANGELOG.md](CHANGELOG.md)
- **Tested on:** RayNeo X3 Pro (`ARGF20`), Android 11+ (minSdk 30), `arm64-v8a`

---

## Requirements

| | |
|---|---|
| Device | RayNeo X3 Pro with the system runtime `com.rayneo.xr.runtime` installed (stock firmware) |
| minSdk | 30 |
| ABI | `arm64-v8a` only |
| Rendering | OpenGL ES 2.0+ (only `StereoCamera.applyViewport` touches GL; the matrices work with any renderer) |
| RayNeo client SDK | **Supplied by your app**, not bundled — see below |

### RayNeo client libraries (you provide these)

The library talks to the RayNeo runtime through RayNeo's own client SDK, which is **not**
included in the AAR (so it can't clash with a copy your app already ships). Your app must
contain:

| File | Where it goes |
|---|---|
| `client.jar` (`com.rayneo.xr.*` classes) | `app/libs/` |
| `libRayNeoXRSDKClient.so` | `app/src/main/jniLibs/arm64-v8a/` |
| `libRayNeoXRLocalLoader.so` | `app/src/main/jniLibs/arm64-v8a/` |
| `libRayNeoXRApiLayerClient.so` | `app/src/main/jniLibs/arm64-v8a/` |

They come from RayNeo's Unity ARDK (`com.unity.xr.rayneo.openxr`, under
`OXR/Runtime/Plugins/Android/`: `RayNeoSDK-client.aar`, `RayNeoOpenXRLoader.aar`,
`RayNeoUnityAdapter.aar`). If your project already uses the RayNeo SDK, you probably have
them already.

---

## Installation (Gradle)

1. Build the AAR (see [Building](#building-from-source)) and copy
   `rayneo-slam-0.1.0.aar` to `app/libs/`.
2. Add the RayNeo client files listed above.
3. In `app/build.gradle`:

   ```groovy
   android {
       defaultConfig {
           minSdk 30
           ndk { abiFilters 'arm64-v8a' }
       }
       packagingOptions {
           jniLibs { useLegacyPackaging true }   // = android:extractNativeLibs="true"
       }
   }
   dependencies {
       implementation files('libs/rayneo-slam-0.1.0.aar')
       implementation files('libs/client.jar')
   }
   ```

4. In your `AndroidManifest.xml`, mark the app as a RayNeo AR app:

   ```xml
   <application android:extractNativeLibs="true" ...>
       <meta-data android:name="com.rayneo.mercury.app" android:value="true" />
       <activity android:screenOrientation="landscape" ... />
   </application>
   ```

   The `<queries>` entry for `com.rayneo.xr.runtime` (needed to bind the runtime on
   Android 11+) comes from the library's manifest and is merged automatically.

R8/ProGuard keep rules ship inside the AAR — nothing to add.

---

## Usage

```java
public class MainActivity extends Activity {
    private RayNeoSlam slam;
    private GLSurfaceView glView;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        slam = RayNeoSlam.create(this);           // main thread, pass the Activity
        glView = new GLSurfaceView(this);
        glView.setEGLContextClientVersion(2);
        glView.setRenderer(new MyRenderer(slam));
        setContentView(glView);

        slam.start(new RayNeoSlam.Listener() {
            public void onStarted()            { /* SLAM on; poses valid once it localizes */ }
            public void onError(String reason) { Log.e("App", "SLAM unavailable: " + reason); }
        });
    }

    @Override protected void onPause()   { super.onPause();  glView.onPause(); }
    @Override protected void onResume()  { super.onResume(); glView.onResume(); }
    @Override protected void onDestroy() { super.onDestroy(); slam.stop(); }
}

class MyRenderer implements GLSurfaceView.Renderer {
    private final RayNeoSlam slam;
    private final StereoCamera camera = new StereoCamera();
    private final Pose pose = new Pose();
    private final float[] vp = new float[16];

    MyRenderer(RayNeoSlam slam) { this.slam = slam; }

    public void onSurfaceCreated(GL10 gl, EGLConfig c) {
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glFrontFace(GLES20.GL_CW);         // see "Coordinate frame" below
    }

    public void onSurfaceChanged(GL10 gl, int w, int h) {
        camera.setSurfaceSize(w, h);              // full 1280x480 surface
    }

    public void onDrawFrame(GL10 gl) {
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        if (!slam.getPose(pose)) return;          // not localized yet
        camera.update(pose);

        GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
        for (int eye = 0; eye < 2; eye++) {
            camera.applyViewport(eye);
            camera.getViewProjection(eye, vp);
            drawMyScene(vp);                      // uMVP = vp * model
        }
    }
}
```

The sample app in [`app/`](app/) is a complete working example (cube grid + GLB model,
render-on-demand for battery).

---

## API

### `RayNeoSlam`

| Method | |
|---|---|
| `static RayNeoSlam create(Activity)` | Initialise the RayNeo client. **Main thread, Activity context** (not the app context — that skips the runtime's vsync pump and the pose never advances). |
| `void start(Listener)` | Bind the runtime and enable SLAM, in the background. Call once. |
| `boolean getPose(Pose out)` | Latest head pose into `out`. Returns `true` only for a valid, localized pose. Allocation-free; any thread; fine to call every frame. |
| `void stop()` | Disable SLAM and release the runtime. Call from `onDestroy`. |

`Listener` callbacks run on the main thread:
- `onStarted()` — SLAM is enabled. `getPose` turns `true` once it localizes.
- `onError(String reason)` — bring-up failed; `reason` names the step.

### `StereoCamera`

| Method | |
|---|---|
| `void setSurfaceSize(int w, int h)` | Call from `onSurfaceChanged` with the full surface size. |
| `void update(Pose)` | Set the camera from a head pose. |
| `void getViewProjection(int eye, float[16] out)` | World → clip for `LEFT` / `RIGHT`, column-major (`android.opengl.Matrix` layout). |
| `void applyViewport(int eye)` | `glViewport` + `glScissor` to that eye's half. Enable `GL_SCISSOR_TEST` yourself. |

Use it from the GL thread.

### `Pose`

`float[3] position` (metres) and `float[4] rotation` (quaternion `x, y, z, w`), in the
runtime's **raw** frame. Pass it to `StereoCamera` rather than interpreting it directly.
In world coordinates the head is at `(position[0], position[1], -position[2])`.

---

## Coordinate frame

- **World:** +X right, +Y up, +Z forward (away from where tracking started), metres.
  The origin is where the head was when SLAM localized.
- Place content in world coordinates; `StereoCamera` keeps it fixed in the room.
- The view matrix flips Y internally, so **visible winding is reversed**: with
  counter-clockwise-outward meshes, use `glFrontFace(GL_CW)` if you enable face culling.

---

## Things to know

- **SLAM needs movement to start.** Until the wearer puts the glasses on and moves (for
  parallax), `getPose` returns `false`. It will never localize sitting on a desk.
- **No tracking-status API.** The runtime's tracker status reads `0` ("initializing")
  even while tracking works, so the library judges validity from the pose itself.
- **The display is side-by-side stereo.** A 2D app gets one 1280×480 surface; the left
  640 px go to one eye and the right 640 px to the other. Render each eye into its own
  half (that's what `applyViewport` does). Go fullscreen/immersive, or system chrome
  lands in one eye only.
- **No IPD yet.** Both eyes use the same viewpoint, so content fuses at optical infinity
  and depth reads as far. Fine for room-scale content; near-field depth is a planned
  refinement.
- **Power.** SLAM itself is the dominant power cost. Render on demand (only when the pose
  changes) rather than every vsync — see the sample's `PoseProbeActivity`.
- **Screenshots come back black** (`adb shell screencap`): the waveguide image isn't in
  the framebuffer you can read back. Verify by wearing the glasses.
- **Logs disappear quickly.** The SLAM runtime floods logcat; run
  `adb logcat -G 16M` before launching. Library logs use the tag `RayNeoSlam`.
- **Unofficial.** This uses RayNeo runtime entry points that are not part of the public
  SDK. A firmware update can break it; `onError` will tell you which step failed.

---

## Building from source

The build is a single offline script — no Gradle, no network — driving the Android SDK
build tools from WSL.

**First, add the RayNeo client SDK** (not in git — see
[RayNeo client libraries](#rayneo-client-libraries-you-provide-these)). The library needs
`client.jar` to compile; the sample app also ships the `.so` files:

```
app/libs/client.jar
app/jni/arm64-v8a/libRayNeoXRSDKClient.so
app/jni/arm64-v8a/libRayNeoXRLocalLoader.so
app/jni/arm64-v8a/libRayNeoXRApiLayerClient.so
```

Then:

```bash
bash build.sh
# => out/rayneo-slam-0.1.0.aar   (library)
#    out/poseprobe.apk           (sample app)
```

Paths to the SDK, NDK (27.2) and JDK are set at the top of `build.sh`; adjust them for your
machine.

Run the sample:

```bash
adb uninstall com.akular.poseprobe            # always uninstall first; -r can keep stale code
adb install out/poseprobe.apk
adb logcat -G 16M && adb logcat -c
adb shell am start -n com.akular.poseprobe/.PoseProbeActivity
adb logcat -s RayNeoSlam:* PoseProbe:*
```

### Repository layout

```
lib/                          the library
  src/com/akular/rayneo/slam/   RayNeoSlam, StereoCamera, Pose, SlamShim (internal)
  jni/slamshim.c                native bridge to the runtime's SLAM entry points
  AndroidManifest.xml           merged into the host app
  consumer-rules.pro            R8 rules, shipped in the AAR
app/                          sample app (uses lib/, ships the RayNeo client libs you add)
docs/                         design notes and white paper
build.sh                      builds both
```

---

## How it works (short version)

SLAM runs inside RayNeo's system service, not in your app; your app is a client over IPC.
The public Java SDK can start the runtime but has no call to enable SLAM, so the library
adds a small JNI shim that calls the runtime's own native entry points to:

1. select the SLAM tracking algorithm (by default the runtime uses 3DoF), and
2. enable the SLAM head tracker,

then reads the 6DoF pose directly. `StereoCamera` holds the coordinate convention and
per-eye lens parameters, which were worked out on hardware. More detail is in
[`docs/`](docs/).

---

## License

Proprietary — © 2026 Akular, all rights reserved. See [LICENSE](LICENSE). The RayNeo
client libraries belong to RayNeo, are not covered by this license, and are not stored in
this repository.
