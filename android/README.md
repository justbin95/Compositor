# Compositor for Android

This is a separate Android project. The original macOS Xcode project stays at the repository root.

The current app can open a native `.comp` project folder through Android's system file picker. It checks the manifest, layer hierarchy, and referenced PNG headers, then renders a bounded-resolution read-only preview from visible pixel layers in native bottom-to-top order. It applies layer transforms, opacity, group visibility and opacity, raster masks, group masks, and clipping masks. Preview Bitmap limits and the mask cache are derived from current process and system memory headroom. Selecting a row still previews that layer's source image by itself, without masks.

This is a partial renderer. Adjustments and non-Normal blend layers are skipped, and layer effects are not applied; the screen reports these limits. The project remains read-only. The canvas can be dragged to pan, pinched to zoom, and double-tapped to reset its viewport; this is preview navigation, not brush input. Drawing, undo, layer editing, and saving are not implemented yet. `editor-core` now holds platform-independent layer metadata, pixel layout, coordinate mapping, and a working-set estimate; it does not store editable pixels or drive the preview UI. The NDK ARM64 library currently includes the portable `BrushPixels.c` algorithms and a direct-ByteBuffer alpha-bounds JNI entry. JNI output and runtime memory limits still need fixed-data and device checks. The initial layout adapts between a portrait stack and a landscape split workspace; project selection and orientation state recovery have been manually checked on a Xiaomi 18 Fold inner display.

## Build a debug APK

The current toolchain uses JDK 21, Gradle 9.6.0, Android Gradle Plugin 9.4.0, Android SDK Platform 36 with Build Tools 36.0.0, NDK 27.2.12479018, and CMake 3.22.1. Install those components and use the checked-in Gradle Wrapper. The Debug package includes only `arm64-v8a`. Set `ANDROID_HOME` to the SDK directory and run:

```sh
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

Choose the folder that directly contains `manifest.json` and `images/`. The app does not modify the selected project.

## Sample project

`samples/Compositor-Android-Demo.zip` contains a small six-record `.comp` folder with five pixel layers and one group. `samples/Compositor-Android-Mask-Demo.zip` exercises raster masks, a group mask, and a clipping mask whose source layer is hidden. `samples/Compositor-Android-Blend-Demo.comp.zip` contains an eight-layer project that displays the seven supported blend modes for visual comparison. These were created for Android preview testing from the format described in the [official project-writing guide](https://github.com/robbietilton/Compositor/blob/main/docs/writing-comp-files.md); none is a project or artwork published by the Compositor author. The mask sample's expected composite and the blend sample's seven mode previews have been visually confirmed by the user on a Xiaomi 18 Fold. On Android, download and extract a ZIP, then choose the extracted `.comp` folder in the app.

To install it on a connected Android device, install Android Platform Tools and run:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Development status and continuation

The authoritative requirements are [Android PRD](../docs/android-prd.zh-CN.md) v0.4 and the [development plan](../docs/android-development-plan.zh-CN.md), especially sections 14.3–16. The preview still uses Activity/Views and bounded Bitmap/Canvas rendering; Compose and the GPU editing workspace remain planned. The core model and initial ARM64 pixel library build, but neither pixel output nor per-device memory behavior has been checked on a device.

The user has confirmed on a Xiaomi 18 Fold that pinch zoom and pan remain continuous, and that releasing at the fit scale animates the canvas back to center. Development is currently paused at the user's request. When work resumes, the next planned task is to map touch input into the editor coordinate system and begin a single brush transaction, with model and native pixel contract checks. The eventual P0 workflow remains editable import → edit → save → reopen → export. Drawing and storage checks cannot be replaced by preview screenshots.

Run `./gradlew --no-daemon :app:lintDebug :app:assembleDebug` from `android/` for the current build. When editor-core tests are added, run `:editor-core:testDebugUnitTest` as well as `:app:testDebugUnitTest`. Reports and APKs are generated under `app/build/` and are excluded from Git. Recorded validation is in [validation/README.zh-CN.md](validation/README.zh-CN.md). SDK/JDK paths, proxy trust configuration and signing keys must be supplied by the environment and are not committed.
