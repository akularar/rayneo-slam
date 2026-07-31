# Engineering contributions of the native RayNeo AR renderer

**Internal working document.** Source material for the technical sections of the paper.
Derived from a read of the full source tree and the git history (commits `e681f38`,
`d06f8f8`, `93d5f34`, `5d586c1`) as of 2026-07-31.

Read this as an inventory: for each area it states the problem, what was actually done,
why the area was hard, where the evidence sits in the repository, and how much of it can
be said in public without handing a reader a reproduction recipe.

---

## 0. The framing to use in the paper

The application-level capability (3D content that stays fixed in the room while the wearer
walks around it) is standard augmented reality and claims nothing new. The contribution is
that the capability was reconstructed **outside the vendor's supported path**, on a device
whose public third-party API exposes head orientation only, in an ordinary Android
application with no game engine, no engine XR plugin and no engine asset pipeline, and that
the resulting client is small enough and quiet enough to run on a face-worn battery.

Everything worth writing about therefore lives in four categories:

1. **Runtime archaeology.** Locating a 6DoF service that exists on the device but is not
   offered to third-party applications, and reaching it safely from a normal application
   process.
2. **Convention recovery.** An undocumented tracker returns a pose in an unstated
   coordinate convention. Recovering that convention without ground-truth instrumentation,
   using a human wearer as the only measurement device.
3. **Display and projection geometry.** Driving a waveguide stereo panel that presents
   itself to the operating system as one ordinary 2D surface, with per-eye intrinsics given
   in computer-vision form and no vendor-supplied projection matrix.
4. **Power as a first-class design constraint.** Turning "no engine" from a claim into a
   measurable result by decoupling the render loop from the display refresh and removing
   per-frame work.

A fifth, methodological, category is unusual enough to deserve its own subsection in the
paper: **validation without screen capture** (section G).

---

## 1. Summary table

| # | Area | Novelty | Difficulty | Time cost | Public? |
|---|------|---------|-----------|-----------|---------|
| A | Reaching the 6DoF tracker from a third-party process | High | High | High | Describe the class of problem, withhold specifics |
| B | Algorithm-selection property with non-obvious values | High | Medium | High (search cost) | Concept only |
| C | Recovering the true native ABI of the pose call | Medium | Medium | Medium | Concept only, it is a good cautionary example |
| D | Pose convention recovery (the frame problem) | Medium | **Highest** | **Highest** | Fully publishable, this is the best engineering story |
| E | Stereo geometry and CV-to-GL projection | Medium | Medium | Medium | Fully publishable |
| F | Winding, culling and the reflected view matrix | Low | Medium | Low | Fully publishable, a nice concrete trap |
| G | Validation without screen capture | Medium | Medium | High | Fully publishable, methodological contribution |
| H | Power and thermal engineering of the render loop | Medium | Medium | Medium | Fully publishable, carries the paper's thesis |
| I | Engine-free asset path (GLB loader in ~300 lines) | Low | Low | Low | Fully publishable |
| J | Engine-free, network-free build and deployment | Low | Low | Medium | Publishable as a reproducibility note |

"Public?" refers to the constraint carried over from the white paper: the reader should
understand what was hard and why, without acquiring enough detail to reproduce the vendor
bypass.

---

## A. Reaching the tracker that the public API does not expose

### The situation

Visual-inertial tracking on this device does not run inside the application. It runs in a
system service packaged with the platform image, and applications are clients of it over
IPC. The documented third-party Java surface (56 classes) exposes session control,
orientation and a pose accessor, and contains no call that switches the tracker into its
six-degree-of-freedom mode. The only native library in the client bundle that publishes
JNI entry points does not bind that call either. The vendor's own engine integration
reaches it natively.

### What was done

A small JNI shim (`app/jni/src/slamshim.c`, 140 lines) resolves the enable call at runtime
from the already-loaded client library and invokes it. The Java side (`SlamShim.java`)
exposes it as five native methods and nothing else.

The interesting part is not the call, it is the **binding discipline** around it. The
target function is a thunk through a dispatch table that a loader populates at
initialisation time. The application ships its own copy of the same library for link
purposes. Resolving the symbol through a fresh load would bind a **second, uninitialised
mapping** of that library, whose dispatch table is entirely zero, and the thunk would
branch to a null pointer and take the process down instantly. The shim therefore:

- resolves only against the mapping that is **already loaded** in the process
  (`slamshim.c:23-25`, the `RTLD_NOLOAD` policy),
- verifies that the dispatch table exists and that both the first slot and the target slot
  are non-null before any call (`table_ready`, `slamshim.c:27-32`),
- logs which file the resolved symbol actually came from and how many table slots are
  populated, so a wrong binding is visible in a log line rather than as a crash
  (`diag`, `slamshim.c:35-46`),
- returns distinct negative error codes for each failure mode so the Java layer can report
  the exact reason (`SlamShim.err`).

The table is populated as a side effect of the ordinary Java bring-up, which loads the
runtime's libraries through a package context. No extra initialisation call is needed, and
none is made.

### Why it was hard

The failure mode is silent, immediate and total. A wrong resolution is not a wrong value or
a degraded result, it is a segmentation fault with no diagnostic. There is also no
authoritative description of the dispatch table anywhere. It was mapped by disassembling
the shipped libraries with the JDK and platform tools, because no decompiler was available
in the environment.

### Paper phrasing

Safe to say: the 6DoF tracker is present on the device and reachable from a normal
application, that reaching it requires binding to the runtime's own already-initialised
library mapping instead of a private copy, that a naive binding produces an immediate
crash, and that the shim validates the dispatch table before every call. Withhold: library
names, symbol names, slot indices, and the loader bootstrap chain.

Good sentence for the paper: the practical difficulty was not calling the function but
proving, at runtime and before the call, that the function's dispatch table belonged to the
initialised runtime instance rather than to a look-alike copy in the same process.

---

## B. The algorithm-selection property

### The situation

Enabling the tracker is necessary and not sufficient. The runtime also has to be told which
attitude algorithm to use. That selection is a property (a key-value setting) whose valid
values are **large magic constants**, not the small enumerated integers a reader would
guess. Writing an out-of-range value produces no error and no effect: the runtime silently
keeps its 3DoF default, the pose keeps arriving, the quaternion keeps updating, and the
translation channel keeps returning a neck-model approximation that looks plausible until
it is measured.

The property is also not reachable through the SDK's own configuration accessor. It has to
go through the API layer's property interface (`slamshim.c:61-98`), and it has to be set
**before** the session starts, because the runtime samples it at startup
(`PoseProbeActivity.java:124`).

### Why it was hard

This is the highest search-cost item in the project. Every symptom points at the tracker
failing to localise: the pose is live, the status code is stable, translation is small.
Nothing indicates a rejected setting. The measured evidence that finally isolated it was
that a wearer walking one to two metres produced a position span of 2.5 to 3.7 cm, and that
the translation swung with head pitch and returned when the head returned, which is the
signature of a lever-arm model synthesised from orientation.

Two secondary traps sit next to it:

- **Status semantics are inverted from the obvious reading.** The tracker status is an
  initialisation state, where zero means "still initialising" and one means "tracking".
  Zero was read as success for some time, which made a non-starting tracker look healthy.
  Polling for the tracking value is the correct health signal and is far more reliable than
  eyeballing translation.
- The setting is written and then **read back** to confirm it took
  (`selectSlamAlgorithm`, `PoseProbeActivity.java:138-143`), because a silent rejection is
  otherwise indistinguishable from success.

### Paper phrasing

Safe to say: mode selection required a property whose accepted values are not discoverable
by inspection, that invalid values are accepted silently, that the resulting behaviour is a
convincing 3DoF approximation with a small residual translation, and that the project's
response was to always write-then-read-back and to treat the tracker's own state code as
the health signal. Withhold: the property name and the constants.

This is the single most useful lesson to publish for practitioners: on an undocumented
runtime, **the absence of an error is not evidence of an effect**, and a plausible-looking
signal is the most expensive kind of failure.

---

## C. Recovering the real ABI of the pose call

The vendor's own engine bindings declare the native pose function with two parameters, with
position first. The shipped implementation takes **three**, the timestamp comes first and
rotation precedes position (`slamshim.c:118-140`). The correct signature was recovered by
disassembling the vendor's own JNI wrapper and reading how it lays out its stack arguments.

The reason this is worth a paragraph in the paper is the **failure signature**. Calling the
function as declared feeds the position pointer in as a timestamp. Position is never
written and stays exactly zero, and rotation comes back correctly normalised but sampled at
a nonsense time, so it is unit-norm and uncorrelated from frame to frame. Read without
suspicion, that looks exactly like a tracker that has not localised yet. A published
binding was wrong in a way that produced output which passed every cheap sanity check
(finite, normalised, changing).

The shipped application currently reads the pose through the Java accessor
(`PoseProbeActivity.java:173-207`) and keeps the native read behind a debug flag as an A/B
instrument, so a single walk can decide whether the two paths differ in drift.

Note one more small item in the same family: the pose object's position is a **private
field with no accessor**, so it is read by reflection, resolved once at startup
(`PoseProbeActivity.java:82-87`).

---

## D. The frame problem (the hardest part of the project)

### The situation

The tracker returns a quaternion and a translation with no statement of what either means.
Head-to-world or world-to-head, which handedness, which axis points where, whether the
translation is expressed in the same frame as the rotation. Get any of it wrong and the
scene still renders, still responds to the head, and still looks superficially correct.

### What was done

Eight candidate conventions were enumerated (transpose or not, flip Y or not, flip Z or
not) and cycled on-device while a wearer described what a rendered world-axis triad did.
That produced a working convention. It then failed two later tests, and the failure was the
informative part.

The final result, in the code as one line plus a sign
(`GlRenderer.java:206-210` and `GlRenderer.java:77`):

```
q is head->world expressed in a Z-flipped frame, so
world->head  =  Fz · R(q)ᵀ · Fz  =  R([x, y, -z, w])
and the translation carries the same flip:  t = [tx, ty, -tz_raw]
```

### Why it was hard, and the lesson

The first convention passed its tests because **the tests were degenerate**. Under pure yaw
and under pitch while facing the anchor, `R([x, y, -z, w])` and the raw `R(q)` give the same
visible result. The two motions that separate them are head **roll** and pitch **while
facing away** from the anchor, and neither appears in the natural "place an object, walk to
it, walk back" test. The symptoms when they finally appeared were: roll rotated the scene
about the view axis at twice the true rate (an object that should stay parallel to the
ground did not), and pitch reversed sign once the wearer had walked through the scene and
turned around.

One frame conjugation explained both symptoms at once, preserved every earlier validated
observation, and unified the rotation convention with the independently determined
translation sign. That is the paper-worthy shape of the story: an error of the form
`R` versus `F·Rᵀ·F` is **invisible under the symmetric motions people naturally test with**,
and only asymmetric motion exposes it.

The translation sign itself was settled by a separate hardware observation: with the
opposite sign, the anchored object fled the wearer on approach instead of growing
(`GlRenderer.java:74-77`).

### Paper phrasing

Publish this one in full. It costs no secrets (the convention is specific to one device's
tracker and reveals nothing about how the tracker was reached), it is honest about a wrong
turn, and it generalises: any team integrating an undocumented pose source will hit the same
class of error, and the actionable advice is to include roll and reversed-facing motion in
the acceptance test from the first day.

---

## E. Stereo geometry and the projection matrix

### The situation

The glasses present themselves to Android as a single ordinary landscape surface. That one
surface is physically a side-by-side stereo framebuffer: the left half drives one eye, the
right half drives the other, and the waveguide routes the halves in a way that does not
match the naive assumption. A conventional full-width user interface is therefore torn
across the eye seam.

Per-eye calibration is supplied in computer-vision form (focal lengths and principal point
in pixels, at 640x480 per eye, about 24 degrees horizontal field of view). No projection
matrix is supplied, and the vendor's eye-to-render transform is identity for both eyes, so
there is no interpupillary baseline in the supplied calibration.

### What was done

- **Rendering:** one GL surface, two passes, per-eye viewport and scissor
  (`GlRenderer.java:213-220`). Scissor as well as viewport, because the clear has to be
  confined to the half being drawn.
- **Projection built directly from the intrinsics** (`GlRenderer.java:230-238`), keeping the
  computer-vision camera convention (Z forward, image V axis pointing down) and folding the
  Y flip into the matrix, with `w_clip = +camZ`. Because the principal point sits exactly at
  the centre of each half, the matrix carries no off-axis terms, which is the reason the
  code does not use a general off-centre frustum helper.
- **View matrix as `cam = S · R_wh · (p - t)` with `S = diag(1,-1,1)`**
  (`GlRenderer.java:240-249`).
- Both projection matrices are constant for the life of the surface and are computed once
  in `onSurfaceChanged`, never per frame.
- For two-dimensional overlay content the opposite rule applies: draw **the same content
  into both halves** (`HudView.drawHalf`). Identical halves make the routing question moot
  and fuse at optical infinity, so there is no vergence conflict for text. Safe-area insets
  are expressed as a fraction of the half, not of the full surface.

### Worth saying in the paper

The engine normally hides all of this. Removing the engine means the application owns the
stereo rig, and on this device class the rig is unusual enough that the ownership is real
work rather than boilerplate: a single surface that is secretly two eyes, calibration in
pixels rather than in a matrix, and no baseline. The current build renders the same eye
transform to both halves, which fuses correctly at distance and is a known simplification
for near-field depth. State it as a known limitation with a clear remedy (a fixed
interpupillary offset per eye).

---

## F. The reflected view matrix and triangle winding

Short, concrete, and a good example of a trap that only appears once the renderer stops
being naive.

The view matrix above contains a reflection (`S = diag(1,-1,1)`, determinant -1). A
reflection reverses the apparent winding of every triangle. As long as the renderer sorted
faces by depth and drew all of them (the first implementation used a painter's algorithm on
a software canvas), winding never mattered and the cube geometry was allowed to carry a
mixed set of face orderings.

Enabling back-face culling for power reasons made winding load-bearing overnight. Two
changes were needed together (commit `5d586c1`): rewind the cube's face list into a single
consistent outward orientation (`GlRenderer.java:56-60`), and tell GL that front faces are
clockwise (`GlRenderer.java:150-153`), which compensates for the reflection in the view
matrix. Either change alone leaves the scene inside-out.

Payoff: roughly half of the grid's triangles never reach the rasteriser.

---

## G. Validation with no screen capture (methodological)

This constraint shaped the entire test methodology and deserves a subsection in the paper.

**`adb shell screencap` returns black on this device.** The augmented content is never
composited into a readback-able buffer, only into the waveguide. There is no way to
screenshot what the wearer sees, no way to diff frames, and no way to automate a visual
regression test. The only measurement instrument available is a human being wearing the
glasses and describing what they observe.

The project's responses:

1. **A rendered world-axis triad as a convention probe.** Ask the wearer which coloured arm
   points where. That converts an abstract matrix question into a one-sentence answer.
2. **An in-view metrology overlay** (`HudView`, `PoseProbeActivity.java:233-303`): a tap
   zeroes the origin, and the display then shows the head translation since that tap per
   axis plus the scalar distance. This turns the wearer into a repeatable ruler. It is how
   the multi-metre walk test was scored (walk to a marked point, read the displayed
   distance, walk back, read zero) and how the stationary drift figures were obtained.
3. **Like-for-like scene replication.** The vendor's own engine sample scene (a regular grid
   of small rotated cubes) was reproduced exactly in the native renderer, down to the grid
   spacing, cube size and the shared prefab rotation (`GlRenderer.java:49-66`). The same
   physical walk can then be performed under both applications and compared by the same
   observer. This is the only viable A/B when neither system can be captured.
4. **Explicitly scripted acceptance motions.** After the frame problem (section D), the test
   script must contain roll and reversed-facing pitch, because the natural test motions
   cannot distinguish a whole class of convention errors.

There is also a hard-won procedural rule: **replacing an installed build in place can
silently leave the previous build running**, which produces test results for code that is
not under test. Every deployment uninstalls first and force-stops the tracking runtime
before reinstalling. Time was lost to this before the rule existed.

---

## H. Power and thermals as a design constraint

This is the section that carries the paper's central claim, because it converts "we removed
the engine" into numbers.

The device has a small battery, a passively cooled mobile SoC, and it sits on a face. The
tracking runtime itself is an unavoidable and dominant cost. Everything the application
does on top of it is discretionary and was treated as such (commit `5d586c1`).

**Decouple the render rate from the display refresh.** A conventional renderer draws every
vsync. Here there is no reason to draw a frame that would be identical to the previous one,
because the scene is static and the only input is the head pose. The GL surface therefore
runs in on-demand mode (`PoseProbeActivity.java:62-63`), and a frame is requested only when
a newly sampled pose actually differs from the last one by more than a small epsilon
(`GlRenderer.setPose`, `GlRenderer.java:116-137`). Pose sampling runs at about 30 Hz on its
own background thread (`PoseProbeActivity.java:206`). The result is that the render rate
tracks the pose rate rather than the panel rate, and idles lower when the wearer holds
still.

**Measured on hardware (X3 Pro), from the commit that introduced these changes:**

| Quantity | Before | After |
|---|---|---|
| Application frame rate | 60 fps (vsync-locked) | 28.6 fps (pose-driven), idling to ~15 Hz |
| GPU busy | (not recorded) | ~2.7% |
| Application CPU | (not recorded) | ~3% |
| Tracking runtime CPU | 12% | 12% (unchanged, dominant term) |

Supporting measures, all in the same commit:

- **Zero allocation in the draw path.** Every rotation, view, view-projection and
  model-view-projection buffer is a preallocated field reused each frame
  (`GlRenderer.java:94-102`). Nothing on the render thread produces garbage, so the
  collector never runs there.
- **Constant work hoisted out of the frame.** Both eye projection matrices depend only on
  the surface size and the fixed intrinsics, so they are built once per surface.
- **Static geometry baked once into world space.** The whole cube grid is a single vertex
  buffer and a single index buffer, drawn with one call per eye and one uniform matrix
  (`buildGrid`, `GlRenderer.java:325-366`). There is no per-object matrix upload and no
  scene graph traversal.
- **Back-face culling** (section F).
- **Scene size reduced** from 144 cubes to 16 for the showcase configuration.
- **The overlay layer is removed entirely when the overlay is off**
  (`PoseProbeActivity.java:65-74`), so the compositor has one layer instead of two. Turning
  a view invisible is not the same as not creating it.
- **Debug instrumentation is compile-time gated** (`DEBUG` flag), including the per-tick log
  formatting and the background probe thread.

**Footprint, for the engine comparison.** The signed application is **497 KB**, of which the
vendor's own client libraries account for about 340 KB and a demonstration 3D asset for
98 KB. The entire application-specific code base is **1,415 lines** across four Java files
and one C file: renderer 540, glTF loader 301, activity 304, native shim 140, Java bridge
38. That number is the honest form of the "no engine" claim, and it is checkable.

---

## I. The engine-free asset path

Removing the engine removes the asset pipeline with it. To show that this is not a fatal
loss, the project reads standard binary glTF (`.glb`) directly: `GlbModel.java`, 301 lines,
using only the platform's JSON parser and image decoder, no third-party dependency.

The loader covers what operational content actually needs and refuses the rest loudly:
scene node tree traversal with transforms **baked into the vertices at load time** so the
draw path needs no per-node matrices, the inverse-transpose treatment for normals, honoured
buffer strides, generated face normals when the asset carries none, base colour factor and
embedded base colour texture. Draco compression, sparse accessors, skinning, morph targets
and animation are rejected with a clear message.

Two constraints in the loader come directly from the target class of hardware and are worth
a sentence in the paper, because they are exactly the kind of detail an engine hides:

- **GLES2 has no 32-bit index support** without an extension, so 32-bit index buffers are
  accepted only when every value happens to fit in 16 bits, and anything larger is rejected
  rather than silently truncated (`GlbModel.java:250-256`).
- **Mipmapping and texture repeat require power-of-two textures** on this profile, so the
  loader inspects each decoded texture and downgrades filtering and wrap mode for
  non-power-of-two images instead of producing an incomplete texture that renders black
  (`GlRenderer.java:442-477`).

Content is loaded from the application package, with an override path on external storage
so a model can be swapped on a device without rebuilding.

---

## J. Build and deployment without an engine or a network

`build.sh` is 92 lines and produces a signed APK with **no Gradle, no Android Studio, no
Unity and no network access**: compile the native shim with the NDK's clang, `javac`, `jar`,
`d8` to Dalvik bytecode, `aapt2 link` for the manifest and assets, inject the dex and the
native libraries into the package as zip entries, `zipalign`, `apksigner`.

It also runs cross-platform in an awkward way that is worth one honest line in a
reproducibility note: the shell runs under WSL while every tool is a Windows executable,
so every path argument is translated, and the toolchain is assembled from an Android SDK
plus a JDK borrowed from a Unity installation, because no standalone JDK is present.

The relevance to the paper is not the script, it is the claim it supports: the artefact is
an ordinary Android application, buildable with platform tools alone, auditable line by
line, with no licensed runtime in the loop. That property is what makes the approach
attractive for privacy-regulated deployments, and it is a direct consequence of dropping
the engine.

**One defect noticed while writing this document** (not fixed, flagged only): the packaging
step iterates every directory under `app/jni/`, which includes the source directory, so the
shim's C source is packaged into the APK as `lib/src/slamshim.c`. It is inert, but it ships
source that should not ship. One-line fix in the packaging loop when someone touches that
file next.

---

## 2. Algorithm schema

### 2.1 Cold start (once per session)

```
  main thread                        background thread
  -----------                        -----------------
  create GL surface (on-demand mode)
  hide system chrome, go immersive
  resolve pose accessor by reflection
  client.initAndroid(activity) ─────┐   [must be main thread + Activity context:
        (starts the vsync pump)     │    this is what starts the pump; the
  register lifecycle listener       │    application context silently does not]
                                    └─> bindService()        [blocks up to 10 s]
                                        load native shim
                                        SELECT 6DoF ALGORITHM  ── write, then read back
                                        StartXR()              ── session up
                                        SwitchTo3DMode()       ── stereo mode
                                        OpenIMU()
                                        ENABLE 6DoF TRACKER    ── via the shim, after
                                                                  validating the runtime's
                                                                  dispatch table
                                        enter pose loop
```

Ordering is not cosmetic. The algorithm selection precedes session start because the
runtime samples it at startup. The tracker enable follows session start. The bind call
blocks and must never touch the main thread.

The tracker needs the wearer to **wear the glasses and move** before it localises; parallax
is required to bootstrap, and it will never localise sitting on a desk. Until it does, the
pose reads as NaN or an all-zero quaternion, which the renderer treats as "no pose" and
declines to draw (`setPose` validity gate).

### 2.2 Steady state (per pose sample, about 30 Hz)

```
   ┌──────────────────────────────────────────────────────────────┐
   │ pose thread (33 ms period)                                   │
   │                                                              │
   │   sample tracker  ──►  (t_raw, q)                            │
   │        │                                                     │
   │        ├─ reject if NaN or zero quaternion  ──► draw nothing │
   │        │                                                     │
   │        ├─ compare with previous sample                       │
   │        │     unchanged (within 1e-5) ──► do not request a    │
   │        │                                  frame at all       │
   │        └─ changed ──► requestRender()                        │
   └──────────────────────────────────────────────────────────────┘
                                   │
                                   ▼
   ┌──────────────────────────────────────────────────────────────┐
   │ GL thread (only when requested)                              │
   │                                                              │
   │   t  = [tx, ty, -tz_raw]              (frame convention)     │
   │   R  = R([qx, qy, -qz, qw])           (world -> head)        │
   │   V  = S · [R | -R t],  S = diag(1,-1,1)                     │
   │                                                              │
   │   clear whole surface once                                   │
   │   for eye in {left, right}:                                  │
   │       viewport + scissor  = that half of the surface         │
   │       VP = P_eye · V           (P_eye precomputed once)      │
   │       draw baked grid   (1 draw call, culled, depth-tested)  │
   │       draw world triad  (1 draw call)                        │
   │       draw model parts  (1 draw call per material)           │
   └──────────────────────────────────────────────────────────────┘
```

### 2.3 The projection, written out

Per-eye calibration arrives as `(fx, fy, cx, cy)` in pixels for a 640x480 eye image, with
the principal point at the exact centre of the half. The camera convention is
computer-vision style: `+Z` forward along the view axis, image `v` increasing downward.

```
        ⎡ 2 fx / w        0            0                0        ⎤
  P  =  ⎢    0        -2 fy / h        0                0        ⎥
        ⎢    0            0      (F+N)/(F-N)   -2 F N /(F-N)     ⎥
        ⎣    0            0            1                0        ⎦          (row form)
```

with `w`, `h` the pixel size of one half of the surface and `N = 0.10 m`, `F = 100 m`. The
negative second row converts the downward image axis to the upward normalised device axis.
The unit in the third column of the last row makes the clip `w` equal to `+camZ`, which is
what a Z-forward camera requires. No off-axis terms appear because the principal point is
centred; a device whose principal point were offset would need them.

The view matrix carries the reflection `S = diag(1,-1,1)`, which is why the renderer
declares clockwise triangles to be front-facing (section F).

### 2.4 The full transform of one vertex, end to end

```
  p_world  ──(baked at load: node transform, prefab rotation, grid offset)──►  p_world
     │
     │  V = S · R_wh · (p - t)          head pose, frame-corrected
     ▼
  p_cam    (Z forward, Y down)
     │
     │  P_eye                            intrinsics-derived, per eye, constant
     ▼
  clip ──► NDC ──► viewport of one half of the side-by-side surface
```

There is no model matrix in the hot path for static scene content, because the world
transform is already baked into the vertex buffer. The only per-draw matrix is for the
loaded model.

---

## 3. Difficulty ranking, for the Discussion section

Ranked by real cost to the project rather than by how impressive each sounds.

1. **The frame problem (D).** Highest total cost. It was solved twice: once wrongly with a
   convention that survived because the test motions were symmetric, then correctly. No tool
   helps here, since the only instrument is a wearer's description of what a rendered triad
   does.
2. **The algorithm-selection property (B).** Highest search cost. A silently ignored setting
   that leaves a plausible signal in place is the worst possible failure mode, and it was
   only isolated by measuring translation against a physical walk.
3. **Safe binding to the runtime (A).** Highest risk per attempt. The failure is a crash
   with no diagnostic, so the work went into validating before calling rather than into the
   call itself.
4. **ABI recovery (C).** Moderate cost, high instructional value. A published binding was
   wrong in a way that produced output passing every cheap sanity check.
5. **Stereo geometry and projection (E).** Moderate and mostly mechanical, but nothing about
   it is standard on this device class.
6. **Power tuning (H).** Moderate, and the most quantifiable. Straightforward once framed as
   "the pose is the only input, so draw only when the pose changes".
7. **Winding and culling (F).** Small in hours, worth publishing because it is a precise,
   reproducible trap created by a reflected view matrix.
8. **Asset loading and build (I, J).** Lowest difficulty. They matter as evidence that the
   engine's remaining services are replaceable at small cost, not as contributions in
   themselves.

---

## 4. Numbers that exist, and numbers that do not

Use only the first table in the paper. Everything in the second must stay a marked
placeholder until it is measured.

**Measured, defensible:**

| Quantity | Value | Source |
|---|---|---|
| Pose sampling rate | ~30 Hz (33 ms period) | `PoseProbeActivity.java:206` |
| Runtime-reported tracking cycle | ~21 ms | runtime log line |
| Render resolution | 640x480 per eye | vendor intrinsics |
| Horizontal field of view | ~24 degrees per eye | derived from fx ~1494 px |
| Application frame rate, before and after | 60 fps -> 28.6 fps | commit `5d586c1`, on-device |
| GPU busy | ~2.7% | commit `5d586c1`, on-device |
| Application CPU | ~3% | commit `5d586c1`, on-device |
| Tracking runtime CPU | ~12% | commit `5d586c1`, on-device |
| Stationary drift, 133 s | 0.029 m net, 0.001-0.007 m per 10 s window | wearer test |
| Largest self-recovered stationary excursion | 0.21 m | wearer test |
| Relocalisation jump under aggressive motion | ~2.4 m | wearer test |
| Signed application size | 497 KB | `out/poseprobe.apk` |
| Application source size | 1,415 lines | source tree |

**Not measured. Do not state, do not estimate:** motion-to-photon latency, GPU frame time in
milliseconds, resident memory, battery life in hours, skin temperature, and any direct
side-by-side power comparison against an engine build. Several of these are the most
persuasive numbers the paper could carry, and a reviewer will ask for them. Measuring
motion-to-photon and battery life against an equivalent engine build is the highest-value
remaining experimental work.

---

## 5. Open problems, for Future Work

State these plainly; they strengthen the paper rather than weaken it.

- **Motion-induced drift.** The native client drifts under walking motion more than the
  vendor's engine application does on the same device in the same room. The scene
  replication described in section G exists precisely to make that comparison fair, and the
  A/B instrumentation for the two pose-read paths is already in the code behind the debug
  flag. The leading hypotheses are a difference in pose-read path, in sampling timing, or in
  pose prediction that the engine performs and the native client does not. This is the
  project's main open technical question and should be named as such.
- **No interpupillary baseline.** The supplied eye-to-render transform is identity for both
  eyes, so both halves are currently drawn from the same viewpoint. Content fuses correctly
  at distance and lacks proper near-field depth. The remedy is a fixed per-eye offset along
  the head-right axis, and it is a small change.
- **No pose prediction and no late-stage reprojection.** The renderer draws from the most
  recent sampled pose with no forward prediction and no post-draw warp. Both are standard in
  engine and runtime compositors, and both are candidates for reducing perceived swim
  without raising the frame rate.
- **Single supported device.** Everything above was established on one hardware model. The
  method generalises; the specific conventions and constants do not.

---

## 6. Secrecy guard

Keep out of any public version:

- names of vendor libraries, symbols, dispatch slots and property keys,
- the magic constants for algorithm selection,
- the loader bootstrap chain and the package-context step that populates the dispatch table,
- the exact per-eye intrinsic values (the field of view derived from them is fine),
- anything that would let a reader reproduce the vendor bypass without repeating the
  investigation.

Safe to publish in full: the frame problem and its resolution, the stereo and projection
geometry, the winding and culling trap, the validation methodology, every power measurement
and every design decision behind it, the asset path, the build approach, the size and line
counts, and the open problems.

The rule of thumb that keeps both goals satisfied: describe **the class of obstacle and the
discipline that overcame it**, and omit the coordinates. A reader should finish the paper
able to recognise the same class of problem on their own hardware, and unable to shortcut
the work on this one.
