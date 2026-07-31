# Whitepaper V4 — section map

A one-line-per-section guide to `whitepaper-v4.html` / `.pdf` (21 pages, ~7,670 words).
Callout boxes and figures are listed where they fall, so the map matches the document as you
scroll it.

**If you read only two things:** Section 5 (the engineering) and Section 10 (what we
learned). Those carry the material that is new in V4.

---

### Front matter

**Abstract.** States the result (registered 3D content on RayNeo X3 Pro from a signed
third-party app with no game engine) and lists what the report covers: the engineering, the
class of faults that return well-formed wrong answers, the acceptance procedures that
separate them, and the load figures for the resulting client.

---

### 1. Introduction

Field work loses time carrying a record back and forth between a handset and a physical
object, and a head-worn display removes that carry if the mark stays on the object.
Introduces registration as the one behaviour everything else depends on, and places the work
inside the consortium's Saudi deployments.

### 2. Engineering Contribution

Front-loads the result and marks its edges, so a reader knows within two pages what was done
and what was not.

- **2.1 The problem.** The public interface returns orientation only; the device runs a full
  6DoF tracker in a system service that third parties were expected to reach through Unity.
- **2.2 Why it was hard.** Four obstacle classes with different characters: abrupt failures
  in the runtime layer, quiet failures in the pose convention, optics that defeat a first
  guess, and a power budget too small for an engine.
- **2.3 What the work demonstrates.** Signed app, no engine, 6DoF pose, stereo projection
  from device optics, content that holds position while the wearer circles it.
- **2.4 Why it is worth reporting.** Removing the engine shortens the path from pose to
  photon and frees a power-limited device; every planned application reuses this one layer.
- *Figure 1.* Enforcement officer seeing payment status pinned to individual cars.

### 3. Background and Related Work

Separates video pass-through from optical see-through hardware, then places the device's
tracker in the visual-inertial literature and the projection work in the see-through calibration literature. States plainly
that we touch none of the estimation mathematics.

- *Callout: why satellite positioning is not enough.* A satellite fix is two orders of
  magnitude too coarse for registration and carries no heading.

### 4. The Combined Tracking Problem

Why the field environment is harder than a bench: blur, shadow, reflective car bodies and a
scene that will not settle, with failures that couple through the environment and feed each
other. Closes on persistence being harder than recognition, and on the hardware removing the
usual answer of more computation.

- *Callout: why parking areas are close to a worst case.* Flat repeating ground, mirrored
  objects, constant change, look-alike vehicles, and no chance to map the site at leisure.

### 5. Implementation

The core technical chapter. Opens by stating that vendor library names, symbols, dispatch
offsets and configuration encodings are withheld by intent and are not needed to follow the
argument.

- **5.1 Reaching the position stream.** How an ordinary signed app connects to the tracking
  service, plus the two disciplines that made it safe: binding to the runtime's own
  initialised library copy (a private copy gives an empty dispatch table and an instant
  crash), and a configuration value whose valid encoding is not discoverable and whose
  invalid values are accepted in silence. Also records that the vendor's published pose
  binding has the wrong argument list and fails in a way that looks like a tracker still
  localising.
- *Callout: the rule that came out of this layer.* Absence of an error is no evidence of an
  effect, so every state-changing call is written and read back, and the tracker's own status
  code had to have its meaning corrected first.
- **5.2 Recovering the pose convention.** The tracker states no convention, so we enumerated
  candidates and had a wearer describe drawn world axes. The convention that passed was
  wrong, because the natural test motions cannot separate it from the correct one; roll and
  facing-away pitch exposed it, and one reflection applied to both the rotation and the
  translation fixed both symptoms.
- *Figure 2.* Head rolled sideways: content rolling with the head versus content holding
  level.
- **5.3 Per-eye projection from the device optics.** Calibration arrives as focal lengths and
  a principal point per eye with no projection matrix and no interpupillary baseline; the
  renderer builds the projection itself and draws one frame in two viewport-and-scissor
  passes. Notes that overlay content goes into both halves identically, and that near-field
  depth is a known open simplification.
- **5.4 The render path.** Static geometry is baked to world space once and drawn with one
  call per eye. Records the trap it creates: the view transform contains a reflection,
  so enabling back-face culling required rewinding the geometry and flipping the front-face
  rule together.
- **5.5 Holding the power budget.** Frames are tied to the arrival of a new pose in place of
  the panel refresh, which took the app from 60 fps to 28.6 fps with no visible change.
  Carries the load numbers (GPU 2.7 %, app CPU 3 %, tracking service 12 %).
- *Callout: why the motion-to-photon interval governs stability.* Each stage between pose and
  drawn frame adds delay, and delay is what makes a mark slide.
- *Figure 3.* The software stack with the engine and vendor plugin lifted out.
- **5.6 Content and build path.** A ~300-line glTF reader replaces the engine asset pipeline,
  and two hardware limits it has to handle in the open. The app builds with platform tools
  alone, with no engine and no network.

### 6. System Architecture

Where the parts run: pose and rendering on the glasses, light detection and speech on a
phone, heavy vision and the digital twin and audit log on an on-site server, cloud optional
and off by default. One shared coordinate frame is what lets a person and a robot name the
same physical point.

- *Figure 4.* The tiers with the on-premises boundary drawn.

### 7. Operational Example: Parking Enforcement

Walks one enforcement task step by step, tagging each step as demonstrated or as an
operational target, so the reader can see exactly which link in the chain this report
delivers.

- *Callout: demonstrated today versus operational target.* The explicit two-column honesty
  statement for the whole report.

### 8. Applications

The same loop with a different recogniser and a different record: airport aprons, container
yards, and other dense repeating-asset sites.

- *Figures 5 and 6.* Ramp technician with ground support equipment; a container carrying its
  manifest among identical boxes.
- **8.1 High-density data centres.** The strongest case: indoors with no satellite fix,
  corridors of identical racks, and a technician who needs the fault drawn on the right unit.
- *Callout + Figure 7.* Why a registered mark beats a floating panel in a hall of racks, and
  a cold aisle with one rack highlighted.

### 9. Operational and Economic Significance

Where the value comes from: identification cost collapses when the record arrives already on
the right object, the memory carry that produces mismatched entries disappears, and the gain
scales with how dense and repetitive the site is.

### 10. Discussion

Four short paragraphs. Faults in an undocumented interface divide into those that halt the
process and those that return well-formed wrong answers, and the second class drives the
instrumentation design; an acceptance procedure built from instinctive test motions certifies
a class of frame errors; the stationary-versus-walking boundary belongs to the inherited
estimator and sets the limit of the claim; and removing the engine is stated as a trade with
both sides given.

### 11. Future Work

Separated by maturity so nothing reads as a promise: demonstrated, prototype (instrument the
open quantities, run the controlled comparison against the engine build, add the per-eye offset,
evaluate pose prediction and reprojection), operational target, planned, research direction,
and clearly-labelled speculation.

- *Figure 8.* A person and a robot resolving the same marked point from opposite sides.

### 12. Conclusion

Restates the result and the reason a narrow result deserves a careful report: the capability
carries across domains unchanged, and everything else in the programme depends on it.

### References

27 entries, all cited in the text: the visual-inertial lineage, see-through calibration,
latency and registration perception, the platform and graphics specifications, digital twin
practice, and the perception models the later stages will use.
