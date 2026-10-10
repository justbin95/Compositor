# Notes for AI agents

Compositor is a macOS image editor for compositing and photo work, written in Swift (SwiftUI and AppKit, with some C for pixel work).

## Designing or editing a Compositor project

If you've been asked to make or change an image in a `.comp` project, you don't need the app's source code. Read [docs/writing-comp-files.md](docs/writing-comp-files.md): it covers the file format, the rules that make a project load, and how to write it safely while it's open, so the person can watch the canvas update as you work.

## Working on the app itself

### Android development

- For Android work, read `docs/android-prd.zh-CN.md` and `docs/android-development-plan.zh-CN.md`, especially the execution rules and current progress in sections 13–15 of the plan.
- The final authorized goal is functional and UI parity with the pinned macOS version, with responsive scaling and panel adaptation for Android tablets and foldable inner screens. Read PRD section 21 and plan section 16. P0 is an intermediate milestone; continue through the macOS parity queue rather than stopping at the basic editor.
- Codex owns implementation, routine technical decisions, checks, and progress records. Explain results to the user in plain Chinese. Preserve the repository's English conventions in source and app UI.
- Keep Android work under `android/` and preserve the existing macOS project. Follow more specific instructions if present in that directory.
- At the end of development work, update the plan's progress snapshot, actual validation results, blockers, and next step. Distinguish code implemented, checks passed, and device validation pending; never claim unrun checks passed.
- Continue independent work when device or environment validation is blocked. Paid resources, public publishing, and features outside the authorized macOS parity scope require applicable user authorization. Do not silently remove parity items or declare untested platform exceptions complete.

### macOS development

- Build: open `Compositor.xcodeproj` and run the **Compositor** scheme, or `xcodebuild -project Compositor.xcodeproj -scheme Compositor -destination 'platform=macOS' build`.
- Tests: the `CompositorTests` target (`xcodebuild ... test -only-testing:CompositorTests`). CI runs these on every push.
- Match the surrounding code: its naming, its comment style and density.
- American spelling in code, comments and UI ("color", not "colour").
- The project file format is described in [docs/project-format.md](docs/project-format.md). A change to what's saved means a format version bump there and in `ProjectManifest.current`.
