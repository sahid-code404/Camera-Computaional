# Camera — Aurora Computational RAW Camera

`Camera` is a RAW-first Android computational-photography project. The internal native image engine is **Aurora**.

Development is currently on **Phase 01 — Foundation and lens qualification**. The normal camera UI no longer trusts Camera2 IDs or static RAW metadata alone: candidate lenses are runtime-qualified with real Camera2 sessions before they are shown.

## Current Phase 01 behavior

- Discovers public Camera2 logical cameras and framework-exposed physical members.
- Keeps logical-camera fallbacks for devices where explicit physical streams are rejected.
- Creates a real preview session before a lens is considered user-visible.
- When `RAW_SENSOR` is advertised, separately tests a preview + RAW session combination.
- Shows a RAW badge only after that combination is runtime-qualified.
- Filters failed physical/logical candidates out of the normal lens selector.
- Persists the latest qualification evidence by `Build.FINGERPRINT` for diagnostics; cached results never bypass a fresh runtime check.
- Exports a JSON diagnostic report containing accepted and rejected candidates, topology, capabilities and qualification results.
- Routes qualified physical preview streams through the logical parent using public Camera2 APIs.
- Loads the C++20 Aurora NDK core and runs its JNI self-test.

The shutter remains intentionally disabled in Phase 01. Single-frame RAW acquisition and RAW file output start in Phase 02 after real-device lens qualification is proven.

## Build stack

- Android `compileSdk` / `targetSdk`: **36**
- Minimum Android: **API 28**
- Android Gradle Plugin: **9.3.0**
- Gradle: **9.5.0**
- Kotlin: **2.3.21**
- Compose BOM: **2025.12.00**
- Activity Compose: **1.11.0**
- Lifecycle: **2.9.4**
- AndroidX Core: **1.17.0**
- JDK: **17**
- NDK: **28.2.13676358**
- CMake: **3.22.1**
- Native core: **C++20**

The repository intentionally stays on the stable Android 16/API 36 SDK for Phase 01. Android 17/API 37-specific work such as RAW14 belongs in a later, separately tested toolchain upgrade. Compose 1.12+ requires compileSdk 37, so Phase 01 pins the Compose 1.10-era BOM instead of forcing preview SDK infrastructure.

## Required Android SDK packages

```bash
sdkmanager \
  "platform-tools" \
  "platforms;android-36" \
  "build-tools;36.0.0" \
  "ndk;28.2.13676358" \
  "cmake;3.22.1"
```

Set `ANDROID_HOME` or create `local.properties`:

```properties
sdk.dir=/path/to/Android/Sdk
```

## Build

```bash
chmod +x gradlew
./gradlew :app:assembleDebug
```

Debug APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

`gradlew` enforces Gradle 9.5.0. If another system Gradle is installed, the launcher ignores it and downloads/verifies the pinned distribution instead.

Quick source checks:

```bash
./scripts/validate-source.sh
```

## Real-device Phase 01 test

Install the debug APK:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Then:

1. Grant camera permission.
2. Wait for lens qualification to finish.
3. Switch through every displayed rear/front lens and confirm live preview.
4. Check that only genuinely useful lenses are shown.
5. Check which lenses say **RAW verified**.
6. Tap **Share diagnostics** and send the JSON report back to the project for analysis.

The diagnostic report contains camera topology/build information but does not request network, storage, contacts, location, microphone, serial-number, IMEI or account permissions.

## Repository structure

```text
Camera-Computaional/
├── app/                 # Compose UI and Android lifecycle/orchestration
├── camera-core/         # Camera2 discovery, qualification, diagnostics, preview
├── aurora-core/         # C++20 NDK computational core foundation
├── docs/                # Architecture, roadmap, research input
├── .github/workflows/   # CI and debug APK artifact
├── PROJECT_STATE.md     # Current engineering checkpoint
└── README.md
```

## Architecture invariant

Where Camera2 exposes usable sensor RAW, **sensor RAW plus complete capture metadata is the source of truth**. JPEG/HEIF/OEM-rendered images must not become inputs to the canonical computational pipeline. Expensive image processing belongs in C++20/Vulkan; display tone, highlights, shadows, saturation and sharpening remain non-destructive render-recipe operations.

See `docs/ARCHITECTURE.md`, `docs/ROADMAP.md`, and `docs/RESEARCH_ARCHITECTURE_SOURCE.md`.
