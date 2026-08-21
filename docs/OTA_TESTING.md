# Phase 01 OTA testing

Camera's test package identity is fixed at `com.sahid.camera`.

## Development update channel

Phase-01 CI builds use a dedicated stable development signing certificate and a monotonically increasing `versionCode` derived from the GitHub Actions workflow run number. Successful pushes to `phase/01-foundation-discovery` publish a moving `phase01-latest` GitHub prerelease containing:

- `Camera-Phase01-latest.apk`
- `update.json`

The app checks the manifest, requires the package name to remain `com.sahid.camera`, downloads the APK, verifies its SHA-256 digest, and then opens Android's package installer.

A normal Android application cannot silently replace itself. The user must allow installs from Camera once and confirm Android's update prompt.

## Bootstrap transition

APKs built before the stable Phase-01 signer was introduced were signed by ephemeral CI debug keys. Android cannot update those builds to the new signer. Testers must uninstall an older pre-OTA Camera build exactly once, then install the OTA bootstrap APK. All later Phase-01 OTA builds use the same signer and can update in place.

## Native aux-camera validation builds

The Phase-01 OTA channel now also carries the native aux-camera qualification work. `NDK_DIRECT` cameras are independently tested through ACameraManager/ACameraDevice/ACameraCaptureSession and must produce an actual SurfaceTexture, YUV, or RAW frame before qualification evidence is recorded. An NDK camera is no longer preflighted and then handed back to Java Camera2 for the real session.

Visible YUV-only lenses use Camera's CPU YUV fallback renderer. RAW-only qualification remains diagnostic until Aurora has a dedicated RAW preview renderer; Camera does not pretend a RAW-only endpoint already has a usable live view.

When testing an OTA build, share the schema-v3 Camera diagnostics after qualification. The report records Java IDs, NDK IDs, logical topology, independent access paths, FOV data, renderer mode, and qualification details.

## Security boundary

`keystore/phase01-dev.jks` is intentionally a development-only key committed for reproducible sideload testing. It must never be used for a production release. Before production, replace it with a private signing key stored outside the repository and establish a production update channel.
