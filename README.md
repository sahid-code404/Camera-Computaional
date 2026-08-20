# Camera — Aurora Computational RAW Camera

`Camera` is a research-grade Android camera project built around a **RAW-first** computational-photography architecture. The internal computational engine is called **Aurora**.

This repository is currently the **Foundation milestone**. It is intentionally capability-driven: it discovers Camera2 logical/physical topology, filters preview-usable lens candidates, routes physical preview streams where Android allows it, and proves the Kotlin/Compose ↔ C++20 NDK architecture before adding the expensive RAW pipeline.

## Build stack

- Android `compileSdk/targetSdk`: **37**
- Minimum Android: **API 28**
- Android Gradle Plugin: **9.3.0**
- Gradle: **9.5.0**
- Kotlin: **2.3.21**
- Jetpack Compose BOM: **2026.08.00**
- JDK: **17**
- NDK: **28.2.13676358**
- CMake: **3.22.1**
- Native core: **C++20**

> Build note: AGP 9 normally enables built-in Kotlin. This foundation temporarily opts out (`android.builtInKotlin=false` and `android.newDsl=false`) so Kotlin **2.3.21** and the Compose compiler plugin stay explicitly pinned together. This is a temporary compatibility pin, not a long-term architecture decision.

## Required Android SDK packages

Install:

```bash
sdkmanager \
  "platform-tools" \
  "platforms;android-37" \
  "build-tools;36.0.0" \
  "ndk;28.2.13676358" \
  "cmake;3.22.1"
```

Set `ANDROID_HOME` or create `local.properties`:

```properties
sdk.dir=/path/to/Android/Sdk
```

## Build

Linux/macOS:

```bash
chmod +x gradlew
./gradlew :app:assembleDebug
```

Windows:

```bat
gradlew.bat :app:assembleDebug
```

Debug APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Quick source sanity check before a full Android build:

```bash
./scripts/validate-source.sh
```

The included `gradlew`/`gradlew.bat` are transparent bootstrap launchers. If Gradle is not installed, they download the pinned Gradle 9.5.0 binary distribution and verify its official SHA-256 checksum before executing it.

## What the current APK does

- Requests only camera permission.
- Enumerates public Camera2 devices.
- Expands logical multi-camera devices into framework-exposed physical members.
- Filters out candidates with no preview-compatible stream.
- Displays lens-like choices instead of exposing raw numeric Camera2 IDs.
- Shows per-lens RAW availability and maximum RAW size where advertised.
- Opens a live Camera2 preview.
- Routes a preview output to a selected physical camera through its logical parent when supported.
- Loads the C++20 Aurora native library and runs a JNI self-test.

The shutter is **not wired yet**. That is deliberate: capture will only be enabled once preview+RAW session combinations are explicitly qualified per lens so unsupported modes are never presented as working.

## Repository structure

```text
Camera-Computaional/
├── app/                 # Compose UI and Android lifecycle/orchestration
├── camera-core/         # Camera2 discovery and preview session control
├── aurora-core/         # C++20 NDK computational core foundation
├── docs/                # Architecture, roadmap, research input
├── .github/workflows/   # CI
├── PROJECT_STATE.md     # Handoff checkpoint for continued development
└── README.md
```

The longer-term architecture expands this into dedicated capture, format, Vulkan, AI, gallery, benchmark and device-profile modules. See `docs/ARCHITECTURE.md` and `docs/ROADMAP.md`.

## Upload to the existing GitHub repository

The existing repository is:

```text
git@github.com:sahid-code404/Camera-Computaional.git
```

After extracting this project:

```bash
cd Camera-Computaional
git init
git add .
git commit -m "feat: initialize Camera Aurora foundation"
git branch -M main
git remote add origin git@github.com:sahid-code404/Camera-Computaional.git
git push -u origin main
```

If `origin` already exists:

```bash
git remote set-url origin git@github.com:sahid-code404/Camera-Computaional.git
git push -u origin main
```

After the first push, development should continue on feature branches. The first recommended branch is `phase/01-foundation-discovery`.
