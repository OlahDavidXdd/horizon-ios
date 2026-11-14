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
renderer fails, native navigation remains available. Long presses suppress Android context menus and retain the touch stream for lens dragging.

Game-file setup opens an owner-private download page in the system browser. Sign in, save the prepared ZIP, and select it with Import Game Files ZIP. No broad storage permission is needed: downloads/import staging use app-private storage, and the system picker grants access to the chosen file. Custom HTTPS direct links still download inside the app; Retry reuses the saved link.

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
