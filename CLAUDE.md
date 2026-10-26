# RayNeo X3 Pro — native world-locked cube

## Goal

Render a **3D cube fixed in real-world space that you can physically walk around**, on
**RayNeo X3 Pro** AR glasses, from a **native Android app** (no Unity).

This project exists because the RayNeo *documented* native SDK exposes only 3DoF IMU
orientation — but reverse-engineering showed 6DoF SLAM **is** reachable from a plain
third-party app. That is now **proven on hardware** (see Status).

---

## STATUS — read this first

| Milestone | State |
|---|---|
| Self-signed 3rd-party app can bind the SLAM runtime | ✅ proven |
| Stereo side-by-side rendering into a normal Surface | ✅ works |
| Cube renders per-eye | ✅ renders |
| Projection / frame convention | ✅ **SOLVED = preset P3** |
| Live **6DoF** (translation) from pure Java | ❌ **impossible — needs a native call** |
| Cube is **world-locked** | ❌ blocked on 6DoF only |

### Projection convention — SOLVED, do not re-solve

`P3` = quaternion is **world→head**, **flip Y**, **no flip Z**. Settled empirically by
cycling 8 candidates and having the wearer describe a drawn world-axis triad; wearer
reports red=+X right, green=+Y up, blue=+Z away, and the cube holds still under yaw and
pitch. **Rendering needs no further work.**

### The open problem (current task): translation is missing

Rotation is live; **translation is not**. Measured: wearer walked 1–2 m, position span
was 2.5–3.7 cm — noise. `t` also swings with pitch and returns when the head returns, i.e.
a **head/neck lever-arm model synthesised from rotation**, not SLAM translation.

**Root cause (confirmed by disassembly, see "Enabling 6DoF" below): SLAM is never
enabled.** `SwitchTo3DMode()` does *not* enable it — an earlier version of this file
claimed it did. That claim was wrong. So was the claim that 6DoF works from pure Java.

⚠️ **`GetHeadTrackerStatus()` — corrected semantics.** An earlier version of this file
said `0 == OK`. **Wrong.** From `Algorithm.cs` (`SlamState`):

| value | meaning |
|---|---|
| 0 | `FFVINS_INITIALIZING` — **not tracking yet** |
| 1 | `FFVINS_TRACKING_SUCCESS` — tracking |
| 2 | `FFVINS_TRACKING_FAIL` |

A persistent `0` means SLAM never started. **Poll for `1`** — that is the real success
signal, far better than eyeballing translation.

---

## Hardware / environment

- Glasses: **RayNeo X3 Pro**, serial `A06B4A8D2D2A313`, model `ARGF20` / `MercuryLiteXR`.
- adb is a **Windows** exe used from WSL:
  `/mnt/c/Users/Zigzee/Android/Sdk/platform-tools/adb.exe -s A06B4A8D2D2A313`
- **WSL has no `unzip`, no `zip`, no JDK.** Use Python `zipfile`, and the Windows
  toolchain below. Windows `.exe`s cannot read `/tmp` — keep files under `/mnt/c` or
  `/mnt/d`, and convert paths with `wslpath -w`.

Toolchain (all offline, no Gradle, no network):
- Android SDK: `/mnt/c/Users/Zigzee/Android/Sdk` (build-tools 35.0.0, platforms android-35/36)
- JDK (javac/java/jar/keytool/**javap**): Unity's bundled OpenJDK
  `/mnt/c/Program Files/Unity/Hub/Editor/6000.3.10f1/Editor/Data/PlaybackEngines/AndroidPlayer/OpenJDK/bin`

---

## Build & deploy

```bash
cd /mnt/d/Work/RayNeo/rayneo-native-cube
bash build.sh            # javac -> jar -> d8 -> aapt2 link -> inject dex+libs -> zipalign -> apksigner
# => out/poseprobe.apk

ADB="/mnt/c/Users/Zigzee/Android/Sdk/platform-tools/adb.exe -s A06B4A8D2D2A313"
$ADB uninstall com.akular.poseprobe          # ALWAYS uninstall first (see gotchas)
$ADB install "$(wslpath -w out/poseprobe.apk)"
$ADB logcat -c
$ADB shell am start -n com.akular.poseprobe/.PoseProbeActivity
```

Watch it:
```bash
$ADB logcat -d -s PoseProbe:* | tail -30              # app logs
$ADB logcat -d | grep -i "slam running" | tail -3      # SLAM DSP alive?
```

### Layout
```
app/AndroidManifest.xml
app/src/com/akular/poseprobe/PoseProbeActivity.java   # everything (bring-up + CubeView renderer)
app/libs/client.jar                                   # com.rayneo.xr.* classes
app/jni/arm64-v8a/*.so                                # 5 RayNeo client native libs
build.sh
out/poseprobe.apk
```
`client.jar` + the `.so`s were extracted from the Unity ARDK aars at
`/mnt/d/Work/RayNeo/rayneo-test/Packages/com.unity.xr.rayneo.openxr/OXR/Runtime/Plugins/Android/`
(`RayNeoOpenXRLoader.aar`, `RayNeoSDK-client.aar`, `RayNeoUnityAdapter.aar`).

---

## How SLAM actually works here (reverse-engineered)

SLAM runs in a **system service**, not in your app: `com.rayneo.xr.runtime`
(`/system_ext/priv-app/RayNeoRuntime.apk`) containing `librayneo_slam_algorithm.so`,
`libslam.so`, and a Hexagon-DSP blob. Your app is a **client** over IPC.

**Critical:** its provider `com.rayneo.xr.provider` and `MonitorService` are
`exported=true` with **no permission** → any self-signed APK can bind them.

### Working bring-up sequence (pure Java)

```java
FFalconXRClient client = FFalconXRClient.Get();
client.initAndroid(this);      // MAIN THREAD + Activity ctx (starts Choreographer vsync pump)
client.registerLifecycleListener(...);   // onReady -> start
client.bindService();          // OFF main thread (blocks up to 10 s)
client.StartXR();
client.SwitchTo3DMode();       // <-- THE KEY: this is what spins up the SLAM DSP
client.OpenIMU();
// then poll:
FXRPoseInfo p = client.GetHeadTrackerPose();   // 6DoF
```

- `SwitchTo3DMode()` is required for 3D/stereo mode, but ⚠️ **it does NOT enable SLAM**
  (an earlier version of this file wrongly called it "THE KEY"). Without SLAM enabled the
  tracker returns rotation + a neck-model offset, and a canned constant translation
  `[0.262, 0.590, -0.273]`. `dsp slam running costs ~N ms` never appears — because SLAM
  never ran. See **Enabling 6DoF** below.
- `initAndroid` **must** run on the main thread with the *Activity* as context — that
  path starts the vsync pump. Passing the application context skips it and the pose
  never advances.
- `FXRPoseInfo.position` is a **private field with no getter** → read by reflection.
  `GetQuaternion()` is public and returns `[x,y,z,w]`.
- `GetHeadTrackerPose()` = *would be* 6DoF once SLAM is enabled; `GetHeadTrackerPose9X()`
  = 3DoF IMU (stays zero).
- `GetHeadTrackerStatus()`: **0 = INITIALIZING, 1 = TRACKING_SUCCESS, 2 = FAIL** (see the
  corrected table at the top — an earlier version of this file had this backwards).
- Before localization the pose reads `NaN` / all-zero quaternion. SLAM needs the user to
  **wear the glasses and move** (parallax) to bootstrap. It will never localize sitting
  on a desk.

### Per-eye projection data (from `getRenderInstriParameters`)

| eye | fx | fy | cx | cy |
|---|---|---|---|---|
| left  | 1494.03 | 1505.34 | 320 | 240 |
| right | 1494.51 | 1501.43 | 320 | 240 |

→ each eye renders **640×480** (maps 1:1 to a display half), ~24° horizontal FOV.
`getRenderFromCamera()` is **identity for both eyes**, so there is **no IPD baseline**
in the projection — the cube is currently rendered mono-per-eye (world-locked via head
translation, but fuses near optical infinity so depth reads far). Adding a hardcoded
IPD (~±31.5 mm along head-right) is a later refinement.

---

## Enabling 6DoF — the native path (verified by disassembly, 2026-07-21)

**There is no `EnableSlam*` anywhere in the Java API.** `client.jar` (56 classes) has none,
and `libRayNeoXRSDKClient.so` — the only lib exposing `Java_*` JNI entry points — does not
bind it. Unity gets 6DoF by calling it natively. Confirmed signatures:

```c
void RayNeoApi_EnableSlamHeadTracker(void);    // no args, no return
void RayNeoApi_DisableSlamHeadTracker(void);
int  RayNeoApi_GetHeadTrackerStatus(void);
int  RayNeoApi_GetHeadTrackerPose(float pos[3], float rot[4]);   // Cdecl
```
Source of truth: `XRInterfaces.cs` `[DllImport]` block in the Unity ARDK package
(`XRConstants.XRInterfaces == "RayNeoXRUnityInterfaces"`).

`RayNeoApi_EnableSlamHeadTracker` is a one-instruction tail-jump to
`XRWarp_EnableSlamHeadTracker` in `libRayNeoXRApiLayerClient.so`, which is a **vtable thunk**:

```
XRWarp_EnableSlamHeadTracker:
    bl   GetXRRuntimeLoader     ; returns &global_table (.data+0xf80)
    ldr  x0, [x0, #0x1d0]       ; slot 58  (0x1D0 / 8)
    br   x0                     ; tail-call
```

### The table must be populated first

`GetXRRuntimeLoader()` just returns the address of a **1248-byte (156-slot) global**.
It is zeroed until `GenerateXRRuntimeLoader(void* dlopenHandle)` fills every slot via 156
`dlsym` calls. **That bootstrap is Java-reachable and your existing bring-up already does it:**

```
FFalconXRClient.loadLibrary(ctx)
  -> LocalLoader.loadLibrary(ctx) -> loadLibs(ctx)
       createPackageContext("com.rayneo.xr.runtime", INCLUDE_CODE|IGNORE_SECURITY)
       reflect com.rayneo.xr.server.RemoteLoader.getLibs(ctx) -> String[] of .so paths
       dlopen each, then native Load(handle)
         -> XRApiLayer::LoadRuntime(handle) -> GenerateXRRuntimeLoader(handle)
```

So no `EstablishServiceConnection` / `InitInstance` call is needed from your shim — the
Java path covers it.

### ⚠️ The trap that will SIGSEGV you

This project **bundles its own copy** of `libRayNeoXRApiLayerClient.so` in
`app/jni/arm64-v8a/`. The populated table lives in the copy the *runtime's* classloader
loaded. If your shim `dlopen`s the bundled copy you get a **second, zeroed** mapping →
slot 58 is NULL → `br x0` on NULL → instant crash. **Always verify before calling:**

```c
void *h = dlopen("libRayNeoXRApiLayerClient.so", RTLD_NOW | RTLD_NOLOAD); // NOLOAD!
void **(*getLoader)(void) = dlsym(h, "GetXRRuntimeLoader");
void **vt = getLoader();
if (!vt || !vt[0] || !vt[58]) { /* NOT initialised — bail, do not call */ }
void (*enableSlam)(void) = dlsym(h, "XRWarp_EnableSlamHeadTracker");
enableSlam();
```
Then poll `GetHeadTrackerStatus()` until it returns **1**.

Ordering, per the official `SlamDemoCtrl.cs`: enable **once at startup** after the runtime
is up, before polling; no dependency on `OpenIMU`. Call `Disable...` on teardown.

### ⚠️ `XRWarp_GetHeadTrackerPose` — real signature (NOT what the C# says)

The C# `[DllImport]` declares two args; **the real function takes three**, and rotation
comes *before* position. Ground truth from `Java_com_rayneo_xr_sdk_FxrApi_GetHeadTrackerPose`:

```
mov x8, x2          ; jlong timestamp argument
add x1, sp, #0x18   ; 4 floats  -> rotation
add x2, sp, #0xc    ; 3 floats  -> position
mov x0, x8          ; timestamp goes in x0
bl  XRWarp_GetHeadTrackerPose
cbz w0, success     ; 0 == SUCCESS
```

```c
int XRWarp_GetHeadTrackerPose(int64_t timestampNs, float rot[4], float pos[3]); // 0 = OK
```
Try `timestampNs = 0` first (the Java no-arg wrapper works, so 0 likely means "latest").

Calling it as `f(pos, rot)` yields the exact garbage signature seen in testing: your `pos`
pointer is consumed as a timestamp so `pos` is never written (stays `[0,0,0]`), and `rot`
receives a rotation sampled at a nonsense time — **unit-norm but uncorrelated frame to
frame**. If you see that, the arg order is wrong.

### ⭐ `trackerAlgorithm` — the actual 3DoF/6DoF switch (magic values!)

Enabling SLAM is **not enough**. The runtime must also be told to *use* the SLAM
algorithm, via a prop with **non-obvious magic values**. From `RayNeoSupportFeature.cs`:

```csharp
public enum StartupCameraAttitudeType {
    DOF3 = 0x1001,      // 4097   <- the default!
    SLAM = 0x2001,      // 8193   <- what you want
}
settings.Add("trackerAlgorithm", (int)CameraAttitudeType);
```

**Small integers (0/1/2/3) are not valid values and are silently ignored.**

Set it through the **API-layer** path, not the SDK path:
```c
int XRWarp_SetProp(const char *key, void *value, int len);   // slot 0x230
int XRWarp_GetProp(const char *key, void *value, int *len);  // slot 0x238
int32_t v = 0x2001;
XRWarp_SetProp("trackerAlgorithm", &v, sizeof v);
```
`FxrApi.SetConfiguration("trackerAlgorithm", …)` (the SDK path) does **not** work —
`GetConfiguration` returns a `float[]`, i.e. the key is not recognised there.

**Order** (from `RayNeoSupportFeature.OnInstanceCreate`): `SetProp(trackerAlgorithm)`
**then** `EnableSlamHeadTracker()`. Note Unity's own defaults are `DOF3` and
`OpenSLAMOnStart = false` — a stock Unity build is 3DoF too until you flip both.

### Gating

No permission, manifest entry or signature check on this path was found in the bundled
client libs. The real gate is `createPackageContext("com.rayneo.xr.runtime", …)`
succeeding, which it already does. Caveat: only the bundled client libs were examined —
the on-device runtime APK was not, so a server-side check cannot be ruled out.

---

## Display / stereo model (important)

The X3 Pro hands a 2D app a single **1280×480 logical screen that is actually a
side-by-side stereo framebuffer**: left 640 px → one eye, right 640 px → the other —
and the waveguide optics route the halves **swapped** relative to naive expectation.
A normal full-width UI is therefore torn across the eye seam.

- **For a HUD: render a complete copy of the UI into *each* 640 px half.** Identical
  halves make the routing swap irrelevant, and zero disparity fuses the overlay at
  optical infinity (no vergence conflict). Do **not** try to detect and swap halves.
- Use a **fractional** safe-area inset (0.08 of each edge) — the ~30° FOV vignettes at
  the edges, and a fraction scales per eye-half instead of across the full 1280.
- Device density is **160 (mdpi)**, so 1 dp ≈ 1 px.
- For **3D content** the halves must differ (real per-eye disparity), so L/R assignment
  *does* matter there — expect to swap which half gets which eye.
- Hide the system title bar / go immersive, or system chrome spans the full 1280 and
  lands in only one eye.

**`adb shell screencap` reads back black on this device** (the world is never on the
display; only the overlay). You **cannot** screenshot to verify rendering — you must ask
the wearer what they actually see.

---

## Gotchas that cost time

1. **`adb install -r` silently fails to replace a running build.** Always
   `adb uninstall` first, or you will debug stale code (this happened).
2. `bindService()` blocks up to 10 s → never on the main thread (ANR).
3. Windows `.exe` tools can't read `/tmp`; stage under `/mnt/c` or `/mnt/d` and use
   `wslpath -w` for every path argument.
4. Use `javap -p -c -constants -classpath <jar> <class>` (Unity's JDK) to disassemble
   the RayNeo classes — there is no decompiler installed and the API is undocumented.

---

## Next steps after the cube is world-locked

1. Lock in the winning convention; delete the preset cycling.
2. Optional: hardcode IPD offset per eye for correct near-field depth.
3. Optional: solid/shaded cube instead of wireframe.
4. Optional: `RecenterHeadTracker()` binding to reset the anchor.

## Related

- Unity route (parallel, still viable): `/mnt/d/Work/RayNeo/rayneo-test` — a Unity
  2022.3.62f2 project with the RayNeo OpenXR ARDK 1.1.2. It was ~one build away, blocked
  on an "Android SDK is missing required platform: API level 30" dialog. It gives the
  same result via `Algorithm.EnableSlamHeadTracker()` + `HeadTrackedPoseDriver`.
- No native OpenXR renderer is needed: rendering goes through an ordinary Android
  Surface (the side-by-side framebuffer), **not** `xrEndFrame`.
