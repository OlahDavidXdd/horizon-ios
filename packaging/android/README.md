# Horizon on Android

The `android-port` branch in `OlahDavidXdd/horizon-ios` merges the modified
`datlun2k11-pixel/a-bit-better-horizon-ios` game with an Android launcher.
The launcher and Rust NativeActivity game are packaged in the same ARM64 APK.
The package ID stays `dev.horizon.android`, as in this branch's original port.
Android 8 / API 26 or newer and Vulkan are required.

## Launcher

Play, create/import/manage worlds, add/remove/ping/join servers, import game files,
resource packs, shader packs and content mods, edit game options, browse screenshots,
choose an offline name, and manage Microsoft accounts. Commands use the same engine
queue and start/cancel ownership protocol as the iOS launcher. Refresh tokens and
Minecraft sessions are encrypted with a device-local Android Keystore AES-GCM key;
app backups are disabled. The launcher never logs authentication responses.

The bottom bar uses the actual `GlassNav` from
[zakisheriff/Liquid-Glass](https://github.com/zakisheriff/Liquid-Glass), pinned to
npm 4.0.0 (MIT). A local, network-blocked WebView hosts the React component and
connects tap/drag selection to the native pages. It samples only the native dock
background; no account data enters the web content. The JavaScript bundle and
licenses are embedded in the APK. See [glass-nav/README.md](glass-nav/README.md)
for build instructions. Reduce Transparency applies immediately. If the web
renderer fails, native navigation remains available. Interrupted gestures are canceled on activity pause/resume, launcher visibility changes, lost capture, and a fresh pointer-down. Long presses suppress Android context menus and retain the touch stream for lens dragging.

Game-file setup tries the same automatic source used by iOS. ZIP import and an owner-private browser download remain available when that source fails. No broad storage permission is needed: downloads/import staging use app-private storage, and the system picker grants access to the chosen file. Custom HTTPS direct links still download inside the app; Retry reuses the saved link.

This is a developing Android port, **not yet verified as a complete 1:1 replacement
for the SwiftUI launcher**. iOS-specific features (AirPlay, UIKit gesture recognizers,
advanced UIKit gesture parity) still need Android parity work. The Android touch
editor, in-game IME bridge, Android gamepad adapter, pack priorities and screenshot/log sharing need device
validation.
Physical-device input, rendering, authentication and background/resume must be
validated before calling the port stable. Microsoft sign-in also depends on the
configured application being approved by Microsoft/Mojang; no approval is implied
by a successful APK build. Native and executable WebAssembly mods are unsupported.

## Build and publish

`.github/workflows/android.yml` is manual. Choose the `android-port` branch in
Actions; enable `publish_release` to publish the successful APK as a prerelease.
The workflow cross-compiles the shared game with the NDK, verifies its NativeActivity
and JNI entry points, packages the launcher with Gradle, runs Android lint, verifies
the APK signature and launcher activity, and uploads the APK, SHA-256 and source SHA.
The publishing job alone gets `contents: write`, checks provenance and checksum,
uploads assets to a draft, then makes it public.

For update-compatible signatures configure `ANDROID_KEYSTORE` (base64 JKS with alias
`horizon`) and `ANDROID_KEYSTORE_PASSWORD` as repository Actions secrets. Without
these, the workflow makes a development key for that build. Such an APK cannot
update a differently signed installation: **back up worlds before uninstalling**.
The signing key is never committed or attached to a release.

Local prerequisites: Rust stable with `aarch64-linux-android`, Android SDK 35,
NDK, JDK 21 and Gradle 8.11.1. The workflow records the exact build commands.
`packaging/android/app/src/main/AndroidManifest.xml` is the launcher's manifest;
the older top-level manifest is retained only for the original game-only packager.

## Game files and logs

The APK carries no Mojang assets. Import a prepared resource ZIP through the launcher
or use the game's Wi-Fi import screen. Prepare resources from your own Minecraft
installation as described in [the iOS documentation](../ios/README.md).

For a cable transfer:

```sh
adb push Horizon-26.3.zip /sdcard/Android/data/dev.horizon.android/files/
```

Saves and imports live in that app-specific files directory. The game log is
`.local/share/horizon/logs/latest.log` below it. Panics also appear in
`adb logcat -s RustStdoutStderr`. Suspending Android destroys the Vulkan surface;
the port releases it and creates a new surface when the activity resumes.

Share Logs includes file fingerprints for the prepared fonts and representative block resources, selected rendering options, Android/WebView versions and the start and last 256 KiB of the engine log. It does not attach game assets, saves or account files. Base-file mismatches are diagnostic evidence; custom packs can override the checked resources.

Resource imports use the same native checked importer as iOS. Android preflights the ZIP, then verifies the publication without writing to files the engine may already be loading. Native import writes an external completion marker only after extraction and required Android font/model checks, and seals the published tree so the Android engine and launcher reject older unverified installations. Recovery discards uncommitted staging folders and restores the previous tree when available. No Java repair runs after publication.

First launch asks the shared `game-files-source` command for the automatic URL, as SwiftUI GameFilesDownload does. Download failure offers retry, a custom HTTPS source and ZIP import. The native source currently names `https://cdn.gayfeet.lol/horizon/26.3.zip`; its reachability is independent of Android versus iOS.

Android MotionEvents are forwarded directly to the bundled GlassNav using normalized coordinates. Android retains the touch stream, including release/cancel, rather than asking WebView to recognize a drag. Diagnostics include bounded native process-exit traces when Android provides them. Native protobuf tombstones are preserved as binary attachments in a diagnostic ZIP, alongside the readable report.

Install from Google Drive Parts opens Android’s system file picker with multiple selection. Choose all five `Horizon-download.7z.001` through `.005` files from Drive or Downloads. The selected provider handles its own account authentication; Horizon reads only the granted URIs, downloads them into private staging, and reconstructs the known prepared ZIP with SHA-256 verification before native import. ZArchiver is not required. Keep at least 3 GB free. Commons Compress/XZ licenses are bundled in `assets/archive-libraries/`.
