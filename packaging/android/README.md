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

AndroidLiquidGlassView **1.0.0** is imported from Maven Central, under the MIT
license, with attribution in the APK's Licenses screen. Refraction requires Android
13 / API 33; older versions use an opaque material. Reduce Transparency selects the
opaque material on the next start. Version 1.0.0 is pinned because newer published
AARs require compile SDK 37, unavailable in the current build environment.

This is a developing Android port, **not yet verified as a complete 1:1 replacement
for the SwiftUI launcher**. iOS-specific features (AirPlay, UIKit gesture recognizers,
GameController, advanced UIKit gesture parity) still need Android parity work. The Android touch
editor, in-game IME bridge, pack priorities and screenshot/log sharing need device
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
