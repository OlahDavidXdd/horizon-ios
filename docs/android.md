# Horizon on Android

An unverified first port, written without an Android toolchain to compile it: expect the first build to
need a fix or two. It follows the iOS port: the same touch overlay, the same game-file import, a native
activity in place of UIKit.

## What changed

- `crates/horizon-android`: a `cdylib` whose `android_main` finds the app's folder, works in it, starts
  the log, installs a pushed archive and hands the activity to the game.
- `horizon-client` carries `game.rs` as a library module on Android (a `.so` has no `main`), builds winit's
  loop on the activity (`run_android`), drops its surface on `suspended` and makes a new one on `resumed`.
- The touch overlay and the "waiting for game files" screen are on, as on iOS. The clipboard is the
  game's own buffer (arboard has no Android backend). No controllers or mods.

## Build (on your computer)

You need Android Studio's SDK and an NDK (r26 or newer), a JDK, and Rust:

```sh
rustup target add aarch64-linux-android
cargo install cargo-apk
export ANDROID_HOME=~/Android/Sdk
export ANDROID_NDK_ROOT=$ANDROID_HOME/ndk/<version>
cargo apk build --release -p horizon-android      # no --locked: Cargo.lock gains the new crate
adb install -r target/release/apk/Horizon.apk
```

The first debug build of any cargo-apk project makes `~/.android/debug.keystore`, which the release APK is
signed with (`crates/horizon-android/Cargo.toml`). If cargo-apk rejects a metadata key, remove that key.

## Game files

Prepare them on a computer as for iOS, then pack and push:

```sh
horizon-client --pack-resources .local/horizon-client/26.3 --out Horizon-26.3.zip
adb push Horizon-26.3.zip /sdcard/Android/data/dev.horizon.android/files/
```

Launch the app: the archive is unpacked into `.../files/resources` and deleted. Without it the game shows
the Wi-Fi import screen instead (an address and six digits to upload the same ZIP to).

## Known gaps

- **Text entry:** NativeActivity gives the game key events, not text input, so the soft keyboard is
  unreliable for chat and sign editing. The fix is GameActivity (a Java/Gradle shell).
- Server address SRV lookups read `resolv.conf`, which Android lacks; use `host:port`.
- No Microsoft sign-in (as everywhere), no controllers, no mods, no hardware-mouse capture.
- Performance is untested: lower the render distance in Video Settings first.
- Logs and crash reports are in `.../files/logs` and `.../files/crash-reports`; `adb logcat` shows panics.
