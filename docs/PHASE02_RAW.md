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

The payload is not repacked. `width`, `height`, `rowStride`, `pixelStride`, byte count, byte order, and SHA-256 are stored in the JSON header so the original buffer layout is unambiguous.

The JSON envelope records route identity, ROM fingerprint, sensor/static calibration metadata when exposed, and per-frame capture metadata such as exposure time, ISO, frame duration, focus distance, aperture, focal length, rolling-shutter skew, white/black level information, neutral point, noise profile, and color correction state when the platform provides them.

## Current qualification behavior

Phase 02A uses a dedicated RAW-only session. The UI releases live preview, performs the one-shot capture, validates and writes the AURAW record, then restores preview. This keeps the first RAW truth path auditable.

A lightweight JPEG rendition is derived only after canonical AURAW persistence. It is generated from the same RAW packet for gallery qualification and is never used as a computational input. Later Aurora Render Engine output will replace this temporary renderer without changing the canonical source contract.

Phase 02B will replace the temporary preview hand-off with simultaneous preview + RAW multi-output sessions and route-family fallback without changing the canonical AURAW source contract.

## Storage policy

Storage follows the product plan's album/gallery model rather than exposing implementation folders as separate user albums.

### User-facing album

On Android 10+, the current Phase-02 rendition is published through `MediaStore.Images` to Android's conventional camera album:

`DCIM/Camera/`

This is the item that normal Gallery/Google Photos applications are expected to index. The app should present this as the saved capture and later use it for the camera thumbnail/gallery entry.

### Canonical source master

The immutable custom AURAW source record is a generic binary document, not an image MIME type, so it cannot be portably published as an ordinary image inside `DCIM/Camera`. It is retained separately through `MediaStore.Files` at:

`Documents/Camera/RAW/`

Aurora's own gallery/storage database will pair the AURAW master and its user-facing rendition into one logical capture. Users should not need to browse the RAW-master directory during normal camera use; RAW metadata/view/export will be exposed from the capture item itself.

Android 9 keeps a conservative app-specific external-files fallback so Phase 02 does not request legacy broad storage permission.

A later storage-settings milestone will make the visible save album persistent and user-selectable (`DCIM/Camera`, another Camera album, or a Storage Access Framework tree) without asking on every shutter press.
