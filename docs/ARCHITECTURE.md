# Camera / Aurora Architecture

## Product invariant

Where Camera2 exposes usable sensor RAW, the photographic source of truth is the sensor-domain RAW frames plus complete capture metadata. Rendered JPEG/HEIF/OEM-HDR output must never become the input to the canonical computational pipeline.

## Layering

```text
Jetpack Compose UI
        ↓ commands / state
Camera orchestration (Kotlin)
        ↓ Camera2 requests + metadata + native buffer handles
Aurora C++20 core
        ↓
Vulkan compute / CPU-NEON fallback
        ↓
Canonical RAW master + non-destructive render recipe
```

## Planned modules

```text
app/
camera-core/
aurora-core/
aurora-vulkan/        # later
aurora-format/        # later
aurora-ai/            # later
gallery/              # later
benchmarking/         # later
device-profiles/      # later
camera-lab/           # later
```

The initial repository only creates modules that are needed to compile the foundation. Future modules should be added when they gain real responsibilities; do not create empty architectural theatre.

## Camera discovery model

Never display `CameraManager.cameraIdList` directly. Build user-facing `LensCapability` objects from:

1. Directly openable Camera2 devices.
2. Logical multi-camera physical IDs.
3. Physical characteristics where the framework exposes them.
4. Preview stream availability.
5. RAW capability and RAW sizes.
6. Manual sensor / burst / ultra-high-resolution capability.
7. Later: explicit session-combination qualification.

A physical camera behind a logical parent may not be independently openable. The universal path therefore opens the logical device and routes an `OutputConfiguration` to a physical ID where the HAL supports it.

## Capture pipeline target

```text
RAW_SENSOR + CaptureResult + gyro
→ fast quality pre-pass
→ RAW calibration
→ coarse gyro alignment
→ multi-scale/tile registration
→ local motion + occlusion masks
→ radiometric exposure normalization
→ robust variance/confidence-weighted fusion
→ RAW-domain denoise
→ optional physical multi-frame super-resolution
→ canonical merged RAW
→ AURAW + optional DNG compatibility export
```

A separate rendering branch performs demosaic, color, HDR tone mapping, highlights, shadows, saturation, display sharpening and preview generation. Those controls are stored as a `RenderRecipe`; they do not destroy the canonical RAW master.

## Native compute rule

Use Kotlin for Android lifecycle, permissions, Camera2 orchestration, settings and MediaStore. Use C++20 for high-bandwidth image processing. Vulkan is the preferred portable GPU backend. CPU/NEON is the required fallback.

Do not keep unbounded full-resolution pixel arrays on the Kotlin/Java heap.

## Capability honesty

Never force unsupported native modes. If a hardware mode does not exist, the UI may later offer a clearly labelled synthetic/enhanced mode using application-side super-resolution or interpolation. Native source specifications must remain recorded in metadata.
