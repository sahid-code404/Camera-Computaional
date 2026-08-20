# Camera / Aurora — Project State

## Repository

Target GitHub repository: `sahid-code404/Camera-Computaional`

> The repository name intentionally matches the existing GitHub repository spelling (`Computaional`).

## Current milestone

**Foundation / Phase 01**

This starter repository establishes the build, module boundaries, Camera2 discovery path, physical-camera preview routing, Jetpack Compose UI, C++20 NDK bridge, and CI skeleton.

### Implemented

- Android app named **Camera**.
- Kotlin + Jetpack Compose application layer.
- Camera2-only public API baseline.
- Capability-driven logical/physical lens discovery.
- Filters out candidates without preview-compatible streams.
- UI shows user-facing lenses instead of raw Camera2 numeric IDs.
- Physical stream routing through a logical parent when Camera2 exposes it.
- Correctly keeps RAW support as a per-lens capability rather than an assumption.
- C++20 **Aurora** NDK module with JNI self-test.
- No network permission.
- No JPEG/HEIF capture or processing path in the foundation.
- GitHub Actions build workflow.

### Not implemented yet

The shutter button is intentionally non-capturing in this foundation package. The following must be added in subsequent phases rather than faked:

1. Verified session-combination qualification per lens and mode.
2. Single RAW capture with metadata synchronization.
3. DNG compatibility writer and canonical AURAW format.
4. Bounded native RAW frame pool.
5. Burst capture sequencer and adaptive exposure planner.
6. Frame/tile quality scoring.
7. RAW calibration, alignment, fusion, HDR, denoise, and physical SR.
8. Vulkan compute backend.
9. Night, Pro, Portrait, Panorama, RAW video/AURV, slow motion.
10. Per-lens render recipes and persistent device profiles.
11. Gallery/MediaStore output and background processing.
12. Device laboratory and numerical image-quality regression gates.

## Non-negotiable engineering rules

- Sensor RAW + complete metadata is the photographic source of truth where RAW is available.
- Do not merge JPEG/HEIF or pretend vendor-rendered images are RAW.
- Do not label synthetic resolution/FPS as hardware native.
- Do not expose every Camera2 ID directly to the user.
- Do not use hidden APIs, root, private CamX/CHI calls, copied proprietary camera code, or leaked vendor source in the universal build.
- Keep expensive pixel processing in C++20/Vulkan, not Compose or Java/Kotlin heaps.
- Canonical RAW remains non-destructive; tone, saturation, shadows, highlights and display sharpening live in a render recipe.

## Next branch after upload

Create: `phase/01-foundation-discovery`

Recommended immediate work:

1. Make the GitHub Action green.
2. Install the debug APK on at least one physical phone.
3. Record the discovered logical/physical lens topology.
4. Validate every displayed lens by creating an actual preview+RAW session where RAW is advertised.
5. Persist qualified capabilities keyed by build/camera fingerprint.
6. Only then begin `phase/02-single-raw`.
