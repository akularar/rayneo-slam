# Changelog

All notable changes to this project are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the
project uses [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.0] — 2026-10-26

First release as a library.

### Added
- `RayNeoSlam`: starts the RayNeo runtime, enables SLAM 6DoF tracking, and gives you
  `getPose(Pose)` (allocation-free, safe from any thread) and `stop()`.
- `StereoCamera`: per-eye view-projection matrices and viewports for the X3 Pro's
  side-by-side display, using the coordinate convention worked out on hardware.
- `Pose`: raw head position and rotation.
- The AAR ships its R8 keep rules and the `<queries>` entry for the RayNeo runtime.
- Sample app (`app/`), rewritten on top of the library.

### Changed
- Poses are read with the native runtime call instead of the Java SDK plus reflection.
- The sample reads the pose when each frame is drawn, not on a 33 ms timer. This lowers
  the worst-case pose age; on hardware it felt as smooth as before, with no clear
  difference.

### Removed
- `CAMERA` and `HIGH_SAMPLING_RATE_SENSORS` permissions (SLAM runs in the system service).
- Unused bundled libs `libRayNeoXRUnityInterfaces.so` and `libopenxr_loader.so`.
