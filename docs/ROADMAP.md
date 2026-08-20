# Camera / Aurora Development Roadmap

## Phase 01 — Foundation and lens qualification

- Build system, Compose shell, Camera2, NDK bridge, CI.
- Logical/physical topology discovery.
- User-facing lens filtering.
- Verify preview sessions for every displayed lens.
- Verify preview + RAW session combinations where RAW is advertised.
- Cache capability results by device/OS/camera fingerprint.
- Add diagnostic capability export.

**Gate:** no broken or duplicate camera IDs are shown to normal users.

## Phase 02 — Single RAW

- `ImageReader` RAW_SENSOR acquisition.
- Per-frame `TotalCaptureResult` synchronization by sensor timestamp.
- Correct CFA, black level, white level, ISO/exposure/focus/lens metadata.
- Immutable source RAW record.
- DNG compatibility export.
- First AURAW container version.
- MediaStore output and processing state.

**Gate:** RAW round-trip and metadata correctness tests pass on real devices.

## Phase 03 — Burst engine

- `BurstCaptureSequencer`.
- Bounded native `RawFramePool`.
- Gyroscope timestamp synchronization.
- Constant-exposure burst capture.
- Deterministic capture state machine.
- Memory/back-pressure safeguards.

## Phase 04 — Fusion baseline

- Global + tile alignment.
- Frame and tile quality confidence.
- Motion/occlusion rejection.
- Noise model.
- Robust temporal fusion.
- RAW-domain denoise.

## Phase 05 — Adaptive HDR and Night

- Scene histogram and clipping analysis.
- Motion-aware exposure planner.
- Hybrid short/long RAW brackets.
- Deghosting confidence masks.
- Adaptive frame count controlled by SNR benefit, motion, memory, latency and thermal headroom.

## Phase 06 — Vulkan

- Vulkan context and pipeline cache.
- Buffer pool.
- AHardwareBuffer import where supported.
- GPU pyramid/alignment/warp/fusion.
- CPU fallback.
- Profiling and thermal adaptation.

## Phase 07 — Physical multi-frame super-resolution

- Subpixel alignment.
- CFA-aware reconstruction.
- Measurable resolution gain on chart targets.
- Keep AI texture generation separately labelled as AI Detail.

## Phase 08 — Product modes

- Photo / HDR / HDR+ Auto.
- Night.
- Pro controls.
- Portrait/depth/mattes.
- Panorama source bundles.
- RAW video / AURV.
- Slow motion and clearly labelled enhanced FPS paths.

## Phase 09 — Device qualification

Continuous:

- Per-lens calibration.
- Cross-lens color/exposure/FOV consistency.
- Thermal soak.
- Storage stress.
- Device farm regressions.
- SNR, dynamic range, MTF/detail, ghosting, motion, color and latency laboratory measurements.
