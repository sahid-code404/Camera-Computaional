# Phase 02 — Canonical Single RAW

Phase 02 establishes the sensor-source contract that every later Aurora computational mode depends on.

## Phase 02A invariant

A successful shutter press must produce exactly one `ImageFormat.RAW_SENSOR` image and the capture metadata for that same sensor exposure. Aurora accepts the pair only when the RAW image timestamp exactly equals `SENSOR_TIMESTAMP` from the final capture result.

No JPEG, HEIF, YUV conversion, demosaic, tone mapping, HDR, denoise, sharpening, saturation adjustment, upscaling, or fusion is allowed before canonical persistence.

## Access paths

- `JAVA_DIRECT`: Camera2 Java still capture to a RAW `ImageReader`.
- `PHYSICAL_VIA_LOGICAL`: open the logical Java camera, route the RAW `OutputConfiguration` to the target physical camera, and prefer that physical camera's `CaptureResult` metadata.
- `NDK_DIRECT`: `ACameraManager` → `ACameraDevice` → native RAW capture session → `ACameraCaptureSession_capture`; the NDK completed-result callback retains sensor metadata until it is paired with the RAW ImageReader buffer.

## AURAW v1

The Phase-02 canonical file uses extension `.auraw` and is intentionally simple and lossless.

Binary layout, with big-endian scalar fields:

1. 8-byte magic: `AURAW\0\1\0`
2. signed 32-bit UTF-8 JSON metadata length
3. signed 64-bit RAW payload length
4. JSON metadata bytes
5. exact bytes copied from the single Android RAW image plane

The payload is not repacked. `width`, `height`, `rowStride`, `pixelStride`, byte count, and SHA-256 are stored in the JSON header so the original buffer layout is unambiguous.

The JSON envelope records route identity, ROM fingerprint, sensor/static calibration metadata when exposed, and per-frame capture metadata such as exposure time, ISO, frame duration, focus distance, aperture, focal length, rolling-shutter skew, white/black level information, neutral point, noise profile, and color correction state when the platform provides them.

## Current qualification behavior

Phase 02A uses a dedicated RAW-only session. The UI releases live preview, performs the one-shot capture, validates and writes the AURAW record, then restores preview. This keeps the first RAW truth path auditable.

Phase 02B will replace that temporary hand-off with simultaneous preview + RAW multi-output sessions and route-family fallback without changing the canonical AURAW source contract.

## Storage during development

On Android 10+, completed AURAW source records are published through `MediaStore.Files` under the user-visible path:

`Documents/Aurora/RAW/`

AURAW is a generic canonical source record (`application/octet-stream`), not a rendered image, so scoped storage does not permit it under the `Pictures` primary directory through `MediaStore.Files`. Rendered/export formats such as JPEG or DNG can use image-oriented public collections later without changing the canonical AURAW contract.

Android 9 keeps the conservative app-specific external-files fallback so Phase 02 does not request legacy broad storage permission.
