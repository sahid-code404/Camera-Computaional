# Camera / Aurora — Project State

## Repository

GitHub: `sahid-code404/Camera-Computaional`

Active branch: `phase/01-foundation-discovery`

Draft PR: `#1 — Phase 01: runtime lens/session qualification`

## Current milestone

**Phase 01 — Foundation and lens qualification**

### Implemented in Phase 01

- Camera2 logical/physical candidate discovery.
- Logical fallback candidate retained for logical multi-camera devices.
- Dedicated `CameraSessionQualifier` on its own handler thread.
- Real preview `CameraCaptureSession` configuration required before a lens is visible.
- Preview + `RAW_SENSOR` session configuration is tested separately when RAW is advertised.
- RAW badge is shown only when the runtime combination succeeds.
- Physical outputs are routed through their logical parent with `OutputConfiguration.setPhysicalCameraId`.
- API 35+ `CameraDeviceSetup.isSessionConfigurationSupported` is used as a fast negative gate, followed by an actual session configuration for evidence.
- RAW probing is bounded to prevent pathological startup times.
- Qualified physical members are preferred; a qualified logical stream is used when all physical members fail.
- Accepted and rejected candidates are retained in `CameraQualificationReport`.
- Qualification evidence is persisted by `Build.FINGERPRINT` for diagnostics; cache never bypasses fresh runtime qualification.
- JSON capability/session diagnostic export added to the app.
- UI exposes **Share diagnostics** for real-device testing.
- Stable Phase 01 build stack moved to Android 16/API 36.
- Compose/AndroidX versions pinned to API-36-compatible releases.
- Gradle launcher now enforces the pinned Gradle 9.5.0 instead of silently using an arbitrary system Gradle.
- GitHub Actions builds the debug APK, runs unit-test tasks, and uploads the APK artifact successfully.

## CI checkpoint

A complete GitHub Actions run on the Phase 01 branch passed:

- Android SDK/NDK installation: pass
- Debug APK build: pass
- Unit-test tasks: pass
- APK artifact upload: pass

The branch remains unmerged because Phase 01 requires real-device evidence before the qualification gate is considered complete.

## Current hardware gate

The next required evidence must come from physical Android phones:

1. Install the Phase 01 debug APK.
2. Allow the startup qualification pass to finish.
3. Verify live preview for every displayed rear/front lens.
4. Check that useless/depth/duplicate/non-functional candidates are absent from normal UI.
5. Record which lenses show `RAW verified`.
6. Export **Share diagnostics** JSON and attach it to the project/test conversation.
7. Use the report to fix OEM-specific session/topology edge cases.

A Camera2 session that configures successfully is stronger evidence than static metadata, but Phase 01 may still be tightened further if device testing reveals cameras that configure yet fail to produce a stable live stream.

## Phase 02 remains blocked

Do **not** start normal photo output, HDR, burst fusion, Night, SR or AI until Phase 01 lens qualification is demonstrated on real hardware.

Phase 02 begins with:

- `ImageReader` `RAW_SENSOR` acquisition.
- sensor-timestamp ↔ `TotalCaptureResult` synchronization.
- immutable source RAW + full metadata record.
- DNG compatibility export.
- first AURAW container version.
- MediaStore/output state.

## Non-negotiable engineering rules

- Sensor RAW + complete metadata is the photographic source of truth where RAW is available.
- Never merge JPEG/HEIF or label vendor-rendered output as RAW.
- Never label synthetic resolution/FPS as native sensor output.
- Never display raw Camera2 IDs directly to normal users.
- Universal builds use documented Android SDK/NDK APIs only; no hidden APIs, root, private CamX/CHI calls, copied proprietary code or leaked vendor source.
- Expensive pixel processing belongs in C++20/Vulkan, with bounded memory and CPU fallback.
- Canonical RAW remains non-destructive; tone, saturation, shadows, highlights and display sharpening belong to a render recipe.
