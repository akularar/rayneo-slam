# Persistent, Markerless Spatial Rendering on Lightweight AR Glasses Without a Game Engine

**A research report from the Digital Twin Interaction research program**

Authors: [Consortium research team, KAUST / AKULAR / Makani Parking]
Correspondence: [placeholder]
Status: Preprint draft for internal review

---

## Abstract

Field operations in parking facilities, airports, ports, and data centers depend on workers who walk, look, and decide. Augmented reality promises to place operational data directly into their view. In practice, that promise has been gated by a hard dependency: spatially stable AR content has required a full game engine, usually Unity, and hardware designed to run one. Lightweight consumer-class AR glasses break both assumptions. They have narrow displays, constrained processors, restrictive vendor SDKs, and no engine-friendly rendering path.

This paper reports a result from our research program: markerless, persistent, six-degree-of-freedom (6DoF) world-locked rendering of 3D content on RayNeo X3 Pro glasses, achieved from a native application with no game engine in the loop. The rendering stack is ours end to end. A wearer can place virtual objects in a room, walk around them, leave, return, and find them where they were left. We measured positional drift of under 3 centimeters over a two-minute stationary interval and metric-accurate translation over multi-meter walks. The same device, driven through its documented third-party interfaces, reports orientation only.

We explain why this problem is harder than it appears, why removing the engine matters for the economics and physics of wearable computing, and how this capability slots into a larger system for recognition, validation, and digital twin writeback in live operational environments. We close with the deployment settings this unlocks, including high-density data center maintenance, where persistent spatial anchoring addresses a failure mode that language models and cameras alone cannot: telling identical-looking infrastructure apart, and remembering where things are.

**Keywords:** augmented reality, spatial computing, visual-inertial SLAM, wearable computing, digital twins, world-locked rendering, field operations, data center maintenance, parking enforcement

---

## 1. Introduction

Most operational work is spatial. An enforcement officer checks a specific car in a specific bay. A technician services a specific server in a specific rack. A ramp agent marshals a specific aircraft to a specific stand. The data these workers need exists, in payment systems, DCIM platforms, maintenance databases, and digital twins. The gap is between the data and the worker's eyes.

Handheld screens bridge that gap badly. They demand focus, occupy hands, and force the worker to translate between an abstract map and the physical scene in front of them. Head-worn displays close the gap in principle: the annotation appears on the object it describes. But this only works if the annotation stays on the object. An overlay that slides off a car when the wearer turns their head is worse than no overlay at all, because the wearer must now decide which car the label meant.

Keeping virtual content fixed in the world while a human moves freely through it is the core technical problem of augmented reality. The industry's standard answer bundles three heavy components: a game engine (typically Unity), an engine-integrated XR runtime (OpenXR or a vendor equivalent), and hardware provisioned to run both. This bundle works, and we have used it. It also imposes costs that make wide operational deployment difficult: engine licensing and build complexity, large memory and battery footprints, and a hardware floor that excludes exactly the class of devices workers will actually wear for a full shift.

This paper describes how our program removed the bundle. We demonstrate persistent, markerless, world-locked 3D rendering on RayNeo X3 Pro glasses, a lightweight consumer-class device, from a native application that uses no game engine, no engine XR plugin, and no engine asset pipeline. The application binds directly to the device's onboard visual-inertial tracking, solves the frame conventions and stereo geometry itself, and draws through the platform's ordinary display path. The result reproduces, on hardware not designed for this workload, the spatial behavior previously available only through the vendor's Unity toolchain.

We present the result at a systems level. We deliberately omit the specific interface and calibration details required to reproduce it, and we do not disclose the proprietary elements of the recognition and validation services it connects to. The contribution we want the reader to take away is architectural: the heaviest, most restrictive layer of the wearable AR stack is separable, and once separated, the remaining system fits hardware that people will actually wear.

## 2. Background

### 2.1 The research program

This work is part of a multi-year program studying how wearable computing, spatial AI, and digital twins change field operations. The program spans three validation domains: on-street parking operations in a historic urban district, bilingual cultural heritage guidance, and bounded airport and industrial maintenance with a robotic collaborator. All three share one substrate: a live digital twin holding authoritative spatial data, and field devices that read from it and write back to it in real time.

The wearable is the program's most demanding client. Phones have large batteries, active cooling margins, and mature graphics stacks. Robots carry their own compute. Glasses have none of these luxuries, and they sit on a human face, which caps their weight, heat, and power more strictly than any datasheet.

### 2.2 Why lightweight glasses

Enterprise AR headsets already deliver stable world-locked rendering. They also weigh half a kilogram, last one to three hours, and mark their wearer as a person operating unusual equipment. None of that survives contact with an eight-hour shift in a public street or a secure terminal.

Sunglasses-class devices such as the RayNeo X3 Pro invert the tradeoffs. They are socially acceptable, all-day wearable, and affordable at fleet scale. The price is capability: a narrow field of view of roughly 24 degrees per eye, a modest mobile SoC, a waveguide display driven as a side-by-side stereo framebuffer, and a vendor SDK that exposes a fraction of what the hardware can do. On this class of device, the documented third-party path yields head orientation only. Orientation without translation cannot world-lock anything: the moment the wearer takes a step, every anchored object smears.

### 2.3 The engine dependency

The vendor's supported route to full 6DoF tracking on this hardware runs through its Unity toolchain. This is a common pattern across the AR industry. The engine supplies the render loop, the pose-prediction machinery, the stereo camera rig, and the glue to the tracking runtime, and the vendor invests its integration effort there because that is where developers are.

For a research program targeting fleet deployment, the engine is a liability in four distinct ways.

First, footprint. An engine runtime occupies hundreds of megabytes of memory and keeps a scene graph, physics world, and managed runtime alive to draw what is, in operational AR, often a handful of labels and outlines. On a thermally limited wearable, that overhead is paid in battery and skin temperature.

Second, control. Latency between head motion and photon is the single most important quality metric in AR. Inside an engine, the critical path from pose sample to composited frame crosses subsystems the application does not own. Diagnosing or shaving milliseconds means fighting the framework.

Third, integration. Operational AR is a thin visual layer over deep enterprise systems. Twin queries, policy enforcement, audit logging, and on-prem service calls are ordinary systems programming. Embedding them inside a game engine's lifecycle and threading model complicates precisely the part of the system that must be most reliable and most auditable.

Fourth, portability of expertise and code. A native stack built on the platform's standard toolchain can be maintained by any competent systems team and audited line by line. This matters in privacy-regulated deployments where the operator must certify what the device does.

None of these arguments is new. What has kept the engine in place is that, on most consumer AR hardware, it held the only key to the tracker. The result re