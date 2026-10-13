# Compositor for Android

## Current development status (2026-10-13)

This is a separate Android project; the macOS project remains at the repository root. The final authorized scope is functional and UI parity with the pinned macOS version, including tablet and foldable inner-screen adaptation. P0 is an intermediate milestone. The [development plan, sections 15–16](../docs/android-development-plan.zh-CN.md) is the current status and continuation source.

GROUP13 (`892eebfef4e0c787956f877cc09155f5eb944b1d`) adds numeric transforms for one selected folder. Visible pixel descendants, including nested folders, form an upright box around their rotated corners. Hidden/ancestor-hidden/outside layers and folder masks stay in place. Position, dimensions, rotation, flips, ratio coupling and percentage scaling update all members in one validated manifest candidate and one metadata history step; each member retains its sampling and source pixels. Child masks follow the existing linked/independent placement rules. Group percentages use the initial box of that dialog as 100%.

Existing features include configurable brushes/eraser, unified history, layer properties/sibling ordering, single-layer numeric/canvas transforms, internal recovery, independent ZIP/folder copies and budgeted tiled PNG export. JPEG still requires whole-image admission. Numeric edits use confirm/cancel dialogs. Group canvas handles, multiple-layer selection/transforms, independent masks, shape-specific redraw, full GPU editing and complete macOS parity remain unfinished. Invalid member results reject the entire Android group; macOS skips invalid members. That exception-path difference remains unaccepted and awaits comparison.

The full check set passed 104 JVM tests (app 83/core 21), lint with zero errors and six existing suggestions, and ARM64 app/instrumentation APK builds. See [GROUP13 evidence and package identity](validation/group13-20261013.zh-CN.md). Device testing remains deferred; no emulator installation, ADB, device or Mac run occurred. Instrumentation compilation does not establish runtime success. Old device results cover only their recorded packages/scenarios; rotation remains NOT_RUN.

Next development: group canvas controls, then multiple-layer selection/transforms, independent mask editing and the remaining parity queue. Track [six GROUP13 deferred cases](validation/group13-device-checklist.zh-CN.md) and earlier checklists in the [validation index](validation/README.zh-CN.md).

On Windows, `./scripts/build-local.ps1` configures the local JDK/SDK and reads `%USERPROFILE%/.android/compositor-signing/signing.json` outside Git. The four explicit `COMPOSITOR_DEBUG_*` environment variables take priority. Supply custom `-JavaHome`, `-SdkRoot`, or `-SigningConfig` paths when needed. No signing secrets are stored in this repository.

Prefer the original certificate `342d1ddebd6a0824589e9031328223c2887687f3f362321c54c748a079ec211a`. The user's 2026-10-12 authorization permits a persistent external test key when the original is unavailable. GROUP13 uses the same test certificate as SCALE12, recorded in its evidence. A different signature normally cannot update an old installation; preserve artwork and never automatically uninstall or clear app data. Default INSTALL01 compatibility packaging stays enabled.

## Build a debug APK

The current toolchain uses JDK 21, Gradle 9.6.0, Android Gradle Plugin 9.4.0, Android SDK Platform 36 with Build Tools 36.0.0, NDK 27.2.12479018, and CMake 3.22.1. Install those components and use the checked-in Gradle Wrapper. The Debug package includes only `arm64-v8a`. Set `ANDROID_HOME` to the SDK directory and run:

```sh
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

To keep debug APK signatures identical across build environments, provide the same keystore outside the repository through `COMPOSITOR_DEBUG_KEYSTORE`, `COMPOSITOR_DEBUG_KEYSTORE_PASSWORD`, `COMPOSITOR_DEBUG_KEY_ALIAS`, and `COMPOSITOR_DEBUG_KEY_PASSWORD`. Set all four variables together. If none are set, Gradle uses its per-user default debug keystore, which can differ between machines. Never commit the keystore or passwords.

Choose the folder that directly contains `manifest.json` and `images/`, or choose a `.comp.zip` archive. ZIP extraction uses app-private cache; the source project is not modified.

## Sample project

`samples/Compositor-Android-Demo.zip` contains a small six-record `.comp` folder with five pixel layers and one group. `samples/Compositor-Android-Mask-Demo.zip` exercises raster masks, a group mask, and a clipping mask whose source layer is hidden. `samples/Compositor-Android-Blend-Demo.comp.zip` contains an eight-layer project that displays the seven supported blend modes for visual comparison. These were created for Android preview testing from the format described in the [official project-writing guide](https://github.com/robbietilton/Compositor/blob/main/docs/writing-comp-files.md); none is a project or artwork published by the Compositor author. The mask sample's expected composite and the blend sample's seven mode previews have been visually confirmed by the user on a Xiaomi 18 Fold. On Android, choose an extracted `.comp` folder or a supported `.comp.zip` archive.

To install it on a connected Android device, install Android Platform Tools and run:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Development status and continuation

Read the [Android PRD](../docs/android-prd.zh-CN.md), [development plan](../docs/android-development-plan.zh-CN.md) and [validation index](validation/README.zh-CN.md). The preview uses Activity/Views and bounded Bitmap/Canvas rendering; Compose and the GPU editing workspace remain planned. JNI fixed-data output and native runtime memory/performance still need separate validation.

Run `./scripts/build-local.ps1 -Tasks ':app:testDebugUnitTest',':editor-core:testDebugUnitTest',':app:lintDebug',':app:assembleDebug',':app:assembleDebugAndroidTest'` from `android/` for the local check set. Build reports, APKs, SDK/JDK paths and signing material are excluded from Git. Deferred device cases are indexed by stage; the old IO-02 checklist alone does not cover current development.

### Installation compatibility candidate

NDK builds opt into `ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON` for 16 KB ELF LOAD alignment. Debug APKs use the same external certificate with v1/v2/v3 signatures. Native libraries are compressed and extracted by the installer by default, matching INSTALL01, which the user confirmed installs on the Fold. Ordinary `./gradlew :app:assembleDebug` uses this configuration. `-Pcompositor.installerCompatibility=false` remains an unvalidated diagnostic override for uncompressed direct loading. The successful package combined several compatibility changes; their individual causal contribution is unknown. See [installation diagnostics](validation/installation-diagnostics.zh-CN.md). The accepted artifact is `app/build/outputs/apk/debug/Compositor-Android-INSTALL01.apk`; previous failed artifacts remain preserved. Editor functionality and other devices still need separate validation.

## Continue development locally

Clone or update the GitHub `work` branch to get the current Android source and progress records. Read [local Codex / ADB handoff](validation/local-codex-adb-handoff.zh-CN.md). The old offline worktree patch is a historical snapshot; do not apply it over the committed source. APKs, SDKs and signing material remain outside Git. Transfer the existing signing keystore separately to preserve the accepted certificate before distributing new local builds.

## Historical stage records

The following entries retain results, limitations and next steps from their recorded stages. For current state, use the summary above and development plan sections 15–16. An old package PASS does not validate a new package.

## Recorded ADB results and repair priority (2026-10-11)

The local report contains 34 subcases: 21 PASS, 3 FAIL, 5 BLOCKED and 5 NOT_RUN. The four failure/investigation packages and their listed file hashes were independently checked. PNG/JPEG import currently fails because the generated transform manifest uses arrays where the reader requires objects. The original blend sample ZIP is rejected because it includes a project-root README. A layer PNG export is incomplete; logs show an Activity replacement during an orientation change, and interrupted export/cleanup handling requires investigation. That planning snapshot did not implement repairs; the implementation status is now recorded below. See the [evidence review](validation/adb-results-20261011.zh-CN.md) and development plan section 14.4 before adding features. Earlier statements that all file/device behavior is untested describe the pre-report state; selected composite exports and ZIP roundtrips are now reported PASS, while Mac comparisons and remaining device cases are pending. This documentation update changes no application code and delivers no APK.

## Implemented ADB repairs and transparent new projects (2026-10-11)

Image import now writes object-shaped transform fields. The archive importer accepts only regular project-root README.md / README.zh-CN.md files up to 64 KiB, retaining archive safety and integrity checks. Layer, canvas and ZIP exports share an Activity-independent task: configuration changes continue the task and retain its result; encoding completes in a bounded private file before SAF publication, and failed target deletion is reported. Provider publication is not atomic, and process-death recovery remains unfinished. Brush errors now expose the actual reason.

Open → New Project creates a transparent raster canvas with pixel dimensions and PPI, with budget admission and candidate validation before replacing the current project. Use Save Copy to keep it. Background options, presets, document history, safe folder saving and automatic recovery remain pending.

See the [changelog](CHANGELOG.zh-CN.md) and [implementation checks / local APK handoff](validation/adb-fixes-20261011.zh-CN.md). Unit tests cover the generated manifest through the production reader and archive importer, PNG completeness, injected export failures, Activity recreation, budget/history rules and new-project ZIP roundtrips. PNG/JPEG decoder and EXIF 1/3/6/8 pipeline checks are Android instrumentation tests; they remain unrun without a device. These checks do not replace device validation. The old 34-case report remains tied to INSTALL01. The user chose local builds with the original keystore; this cloud session delivers no APK. Preserve the accepted certificate and default installer compatibility packaging, and record the new source commit and APK hash before retesting. Device rotation remains skipped until the user arranges it again. Existing samples and the three old packaging ZIPs are unchanged.

## Recorded document history stage (2026-10-12)

HISTORY05 connects committed brush/eraser patches and image-layer appends to one chronological document undo/redo branch, bounded to 20 entries and a shared payload budget. Undo can affect a different layer from the selected one. Undoing an append restores the previous manifest and selection; its private asset remains available for redo and is cleaned when the document closes. Configuration recreation retains the same history. ZIP copies contain current pixels and metadata, not undo history. Completed export messages are discarded after append or history edits.

See [implementation checks and artifact identity](validation/document-history05-20261012.zh-CN.md). New device installation and history acceptance are NOT_RUN; previous LOCAL03/INPUT04 results do not validate this APK. Layer deletion/reordering/transforms, safe folder saving, process-death recovery, tiled full-image composition and full macOS parity remain pending. Older README descriptions of per-layer history refer to earlier builds.

## Recorded internal recovery stage (2026-10-12)

RECOVERY06 keeps internal recovery separate from Save Copy. Every 30 seconds, after supported long operations and when leaving the foreground, the editor attempts to capture completed edits within a measured snapshot budget. An active stroke is not captured. Frozen chunk payloads allow later editing while a background task writes a ZIP, syncs its file descriptor, reopens it through the production importer, atomically publishes a new generation and syncs its directory. Earlier valid generations are retained. Revision and session identity prevent an older save from acknowledging newer edits or a reopened document.

Recovery Copies is available in Open and the tool row. It lists timestamps, project names and sizes, offers restoration with an unsaved-edits warning, and confirms deletion of an individual internal copy. The latest two generations of the three most recently recovered projects are retained. Undo history is not restored. Uninstalling or clearing app data may remove these internal copies; use Save Copy for a project file you want to keep.

See [checks and artifact identity](validation/recovery06-20261012.zh-CN.md) and [deferred device checklist](validation/recovery06-device-checklist.zh-CN.md). The user has deferred device testing: this stage makes no ADB calls or installation. Windows fault injection does not validate Android directory durability, process death, real low storage or the device UI. External folder saving, incremental recovery asset reuse, full-image tiled composition and final macOS parity remain pending.

## Recorded folder generation stage (2026-10-13)

FOLDER07 adds Save Project Folder: select a parent directory to create a new independent .comp generation. The complete archive is validated privately first, every new external file is read back for SHA-256 and length, images precede the manifest, and the saved package is checked through the production reader and bounded image preflight. Earlier versions remain untouched. The result shows the actual folder name. Failures clean only this newly created directory; a refused cleanup is reported. This is a version copy; original-location replacement and provider durability remain unfinished.

Document history now exposes references retained by both undo and redo. Hidden layer sessions and private appended assets are released after they lose all current/history references; running recovery tasks continue to pin their sources. See [checks and artifact identity](validation/folder07-20261013.zh-CN.md) and [deferred device checklist](validation/folder07-device-checklist.zh-CN.md). The 65 JVM checks, lint and ARM64/instrumentation builds passed locally. No device or Mac validation was run. Next independent development is tiled full-image composition; full macOS parity remains the authorized final scope.

## Recorded tiled PNG stage (2026-10-13)

TILE08 streams budgeted strips of exact-size PNG composite tiles and decodes inverse-mapped source/mask regions. Oversized regions split recursively. Global pixel-center sampling with premultiplied bilinear interpolation avoids viewport-dependent rotated edge coverage. Nearest remains distinct; Smooth and High quality currently share bilinear sampling. JPEG retains whole-image admission. All 71 JVM checks, lint and ARM64/instrumentation builds passed; seven-mode native Canvas comparisons covered 1,856,547 pixels with zero channel error. Tests inject an explicit source-crop adapter because Robolectric's region decoder returns blank pixels; the production region decoder instrumented test is compiled, NOT_RUN. See [evidence and limits](validation/tile08-20261013.zh-CN.md) and [deferred checks](validation/tile08-device-checklist.zh-CN.md). Device native memory/performance and Mac output remain pending. Development continues with layer metadata operations and unified history.

## Recorded layer operations stage (2026-10-13)

LAYER09 adds Layer Properties (name, visibility, opacity) and adjacent sibling Move Up/Move Down. Candidates preserve unknown fields and asset ownership, pass the production reader, and join the same undo/redo history as strokes and appended images. Existing v3/v8 appearance upgrades apply to older project copies. Recovery and project copies retain the edited manifest. All 78 JVM tests, lint (zero errors, six existing suggestions), ARM64 and instrumentation builds passed. Device and Mac validation remain pending; no installation or ADB was performed. See [evidence and APK identity](validation/layer09-20261013.zh-CN.md) and [seven deferred cases](validation/layer09-device-checklist.zh-CN.md). Next independent development is layer transforms and unified history; complete macOS parity remains the final scope.

## Recorded numeric layer transform stage (2026-10-13)

TRANSFORM10 adds position, size, rotation, flips and sampling for a selected pixel layer. Linked and independently placed masks follow the pinned macOS placement rules, including shear projection. Candidates preserve asset pixels and unknown fields and use the same metadata history, recovery and saved manifest. All 81 JVM tests, lint (zero errors, six existing suggestions), ARM64/instrumentation builds passed. See [evidence and APK identity](validation/transform10-20261013.zh-CN.md) and [six deferred cases](validation/transform10-device-checklist.zh-CN.md). No device or Mac run was performed. Canvas handles, ratio lock, group/multiple-layer transforms, independent mask editing and the complete parity queue remain pending.

## Recorded canvas transform stage (2026-10-13)

HANDLES11 adds body movement, eight resize handles, an outer rotation handle and locked/free aspect ratio for a selected pixel layer. A drag previews transient geometry and records one validated metadata edit on release; cancellation, a second pointer, focus/size changes and preview replacement discard the draft. Tool and ratio preferences survive activity recreation. All 89 JVM tests, lint (zero errors, six existing suggestions) and ARM64/instrumentation builds passed. See [evidence and APK identity](validation/handles11-20261013.zh-CN.md) and [six deferred cases](validation/handles11-device-checklist.zh-CN.md). No emulator installation, ADB, device or Mac run occurred. Numeric ratio/scale fields, snapping, group/multiple-layer transforms, independent masks and the full parity queue remain pending.

## Recorded numeric ratio and percentage stage (2026-10-13)

SCALE12 adds shared locked/free ratio coupling in the numeric transform dialog and source-relative percentage scaling about the center, preserving fractional typed values and the original pixels. Validated changes use one metadata history entry and the existing recovery/save paths. All 96 JVM tests, lint and ARM64/instrumentation builds passed; [evidence](validation/scale12-20261013.zh-CN.md) records exact scope and APK identity. [Six device/Mac cases](validation/scale12-device-checklist.zh-CN.md) remain NOT_RUN. The confirm/cancel dialog still differs from macOS live per-field editing; group/multiple-layer transforms, independent masks and full parity remain pending.

## Recorded numeric group-transform stage (2026-10-13)

GROUP13 adds nested visible-member bounds and numeric group transforms using one validated manifest candidate/history transaction, preserving source pixels and unknown fields and carrying child masks. All 104 JVM tests, lint and ARM64/instrumentation builds passed; [evidence](validation/group13-20261013.zh-CN.md) records exact checks and limits. [Six deferred cases](validation/group13-device-checklist.zh-CN.md) remain NOT_RUN. Group canvas handles, multiple-layer selection, complex-layer redraw and full parity remain pending.
