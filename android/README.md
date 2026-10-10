# Compositor for Android

This is a separate Android project. The original macOS Xcode project stays at the repository root.

The current app can open a native `.comp` project folder through Android's system file picker. It checks the manifest, layer hierarchy, and referenced PNG headers, then renders a bounded-resolution read-only preview from visible pixel layers in native bottom-to-top order. It applies layer transforms, opacity, group visibility and opacity, raster masks, group masks, and clipping masks. Selecting a row still previews that layer's source image by itself, without masks.

This is a partial renderer. Adjustments and non-Normal blend layers are skipped, and layer effects are not applied; the screen reports these limits. The project remains read-only. Drawing, undo, layer editing, and saving are not implemented yet. The initial layout adapts between a portrait stack and a landscape split workspace; project selection and orientation state recovery have been manually checked on a Xiaomi 18 Fold inner display.

## Build a debug APK

The verified toolchain uses JDK 21, Gradle 9.6.0, Android Gradle Plugin 9.4.0, and Android SDK Platform 36 with Build Tools 36.0.0. Install those components and use the checked-in Gradle Wrapper. Then set `ANDROID_HOME` to the SDK directory and run:

```sh
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

Choose the folder that directly contains `manifest.json` and `images/`. The app does not modify the selected project.

## Sample project

`samples/Compositor-Android-Demo.zip` contains a small six-record `.comp` folder with five pixel layers and one group. `samples/Compositor-Android-Mask-Demo.zip` exercises raster masks, a group mask, and a clipping mask whose source layer is hidden. Both were created for Android preview testing from the format described in the [official project-writing guide](https://github.com/robbietilton/Compositor/blob/main/docs/writing-comp-files.md); neither is a project or artwork published by the Compositor author. The mask sample's expected composite was visually confirmed by the user on a Xiaomi 18 Fold. On Android, download and extract a ZIP, then choose the extracted `.comp` folder in the app.

To install it on a connected Android device, install Android Platform Tools and run:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Development status and continuation

The authoritative requirements are [Android PRD](../docs/android-prd.zh-CN.md) v0.4 and the [development plan](../docs/android-development-plan.zh-CN.md), especially sections 14.3–16. The current preview uses Activity/Views and bounded Bitmap/Canvas rendering; Compose, the native ARM64 pixel core and the GPU editor remain planned. The current API 26 minimum and Chinese UI differ from the API 31 and English UI defaults and are scheduled for correction.

Start the next session from the latest `work` branch and task FIX-01 (keep the active project when a new project fails to open). The next goal is the editable import → edit → save → reopen → export workflow. Drawing and storage checks cannot be replaced by preview screenshots.

Run `./gradlew --no-daemon :app:lintDebug :app:testDebugUnitTest :app:assembleDebug` from `android/`. There is currently only an `app` module; `:editor-core:test` is not available yet. Reports and APKs are generated under `app/build/` and are excluded from Git. Recorded validation is in [validation/README.zh-CN.md](validation/README.zh-CN.md). SDK/JDK paths, proxy trust configuration and signing keys must be supplied by the environment and are not committed.
