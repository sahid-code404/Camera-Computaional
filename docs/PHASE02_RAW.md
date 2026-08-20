# Phase 02 — Canonical Single RAW DNG

Phase 02 establishes the sensor-source contract that every later Aurora computational mode depends on.

## Phase 02A invariant

A successful shutter press must produce exactly one `ImageFormat.RAW_SENSOR` image and the capture metadata for that same sensor exposure. Aurora accepts the pair only when the RAW image timestamp exactly equals `SENSOR_TIMESTAMP` from the final capture result.

The one and only persisted photo output is a single `.dng` file. No `.auraw`, JPEG, HEIF, PNG, rendered preview sidecar, or duplicate public file is created.

No demosaic, tone mapping, HDR, denoise, sharpening, saturation adjustment, upscaling, or fusion is applied before DNG persistence.

## DNG path

Phase 02A uses Android `DngCreator` with the original `RAW_SENSOR` `Image`, the matching `CaptureResult`, and the relevant `CameraCharacteristics`. The Bayer mosaic is not rotated or resampled. Display orientation is represented by the DNG/TIFF orientation tag.

Output location on Android 10+:

`DCIM/Camera/IMG_<timestamp>_AURORA.dng`

This is the only saved capture item.

## Access paths

- `JAVA_DIRECT`: Camera2 Java still capture to a RAW `ImageReader`, timestamp-match the final result, and write one DNG.
- `PHYSICAL_VIA_LOGICAL`: open the logical Java camera, route the RAW `OutputConfiguration` to the target physical camera, use that physical camera's result/characteristics when exposed, and write one DNG.
- `NDK_DIRECT`: remains valid for discovery/preview, but Phase 02A does not fabricate DNG metadata from an NDK-only profile. `RawRouteResolver` first searches the same lens family for a Java or physical-via-logical RAW profile. If none exists, capture is reported unsupported instead of writing another format.

A later native DNG writer may extend standards-correct DNG output to truly NDK-only RAW routes, but the product output contract remains one DNG file.

## Current qualification behavior

Phase 02A uses a dedicated RAW-only session. The UI releases live preview, performs the one-shot capture, validates the image/result timestamp pair, writes the DNG, then restores preview.

Phase 02B will replace the temporary preview hand-off with simultaneous preview + RAW multi-output sessions without changing the one-DNG output contract.

## Orientation

The application activity is portrait-locked and no longer follows full-device auto-rotation. Live preview transforms use the inverse sensor-to-display rotation so Camera2 Surface, NDK Surface, Java YUV, and NDK YUV paths are shown upright instead of 90 degrees sideways.

The DNG itself preserves the sensor mosaic and stores display orientation as metadata.
