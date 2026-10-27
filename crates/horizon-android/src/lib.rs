//! Horizon on Android.
//!
//! NativeActivity loads this library and calls [`android_main`] on a thread of its own. Everything the
//! desktop `main` does before the game starts happens here: find a folder the app may write, work in it,
//! start the log, finish or pick up an import, and hand the activity to the game, which builds winit's
//! event loop on it (`horizon_client::game::run_android`). The rest of the port is the client itself.
//!
//! Empty off Android, so the workspace still builds on a desktop.
#![cfg(target_os = "android")]

use std::path::Path;
use winit::platform::android::activity::AndroidApp;

/// The folder the game runs in. An Android app has no usable working directory, and the game builds its
/// saves, `options.txt`, caches and `resourcepacks/` from `current_dir()`, so, as on iOS, the fix is one
/// call made before anything reads a path. `HORIZON_HOME` puts the log and crash reports beside them and
/// `HOME` keeps anything that asks the environment inside the app's folder too.
fn into_app_files(files: &Path) -> Result<(), String> {
    let describe = |error: std::io::Error| format!("{}: {error}", files.display());
    std::fs::create_dir_all(files).map_err(describe)?;
    std::env::set_current_dir(files).map_err(describe)?;
    // SAFETY: this is the first thing the game's thread does, before it starts any thread of its own,
    // and nothing else in the process reads Rust's copy of the environment.
    unsafe {
        std::env::set_var("HORIZON_HOME", files);
        std::env::set_var("HOME", files);
    }
    Ok(())
}

#[unsafe(no_mangle)]
fn android_main(app: AndroidApp) {
    // `Android/data/<package>/files`: reachable by `adb push` and needing no permission. The internal
    // folder is the fallback for a device that has no external storage mounted.
    let Some(files) = app
        .external_data_path()
        .or_else(|| app.internal_data_path())
    else {
        eprintln!("Horizon: Android gave this app no data folder");
        return;
    };
    if let Err(error) = into_app_files(&files) {
        eprintln!("Horizon: {error}");
        return;
    }
    horizon_client::telemetry::mark_start();
    let home = horizon_client::logging::game_home();
    horizon_client::logging::init(&home, "Render thread");
    horizon_client::crash::install(home.clone(), "client");
    // A swap that a flat battery or a crash left half done is finished, or the old tree put back, before
    // anything reads the folder it was working on.
    match horizon_client::import::recover(&files) {
        Ok(Some(path)) => log::info!("Horizon: finished the import at {}", path.display()),
        Ok(None) => {}
        Err(error) => log::error!("Horizon: could not finish the last import: {error}"),
    }
    // The cable's door: an archive `adb push`ed into the folder is installed here, so the launch it was
    // pushed for is the one that finds it.
    horizon_client::import::scan(&files);
    if let Err(error) = horizon_client::game::run_android(app) {
        log::error!("Horizon: {error}");
        horizon_client::crash::report_error(&home, "client", &*error);
    }
    horizon_client::logging::flush();
}
