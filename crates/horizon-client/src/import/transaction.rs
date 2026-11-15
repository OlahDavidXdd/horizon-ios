//! An unpacking folder is disposable until its external completion record is durable.
use std::{
    fs, io,
    io::Write,
    path::{Path, PathBuf},
};

const READY: &str = "resources.unpacking.complete";
const CONTENTS: &[u8] = b"horizon-resource-import-v1\n";
const PUBLISHED_SEAL: &str = ".horizon-import-verified";
const REQUIRED: &[&str] = &[
    "horizon-resources.json",
    "assets/minecraft/atlases/blocks.json",
    "assets/minecraft/blockstates/stone.json",
    "assets/minecraft/models/block/stone.json",
    "assets/minecraft/font/default.json",
    "assets/minecraft/font/include/default.json",
    "assets/minecraft/font/include/space.json",
    "assets/minecraft/font/include/unifont.json",
    "assets/minecraft/font/unifont.zip",
    "assets/minecraft/textures/font/ascii.png",
    "assets/minecraft/textures/block/stone.png",
];

fn remove_ready(home: &Path) -> io::Result<()> {
    match fs::remove_file(home.join(READY)) {
        Err(error) if error.kind() != io::ErrorKind::NotFound => Err(error),
        _ => Ok(()),
    }
}

pub(super) fn verify_core(root: &Path) -> io::Result<()> {
    for name in REQUIRED {
        if !fs::metadata(root.join(name)).is_ok_and(|m| m.is_file() && m.len() > 0) {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                format!("Incomplete resource ZIP: missing {name}"),
            ));
        }
    }
    Ok(())
}

pub(super) fn published_ready(root: &Path) -> bool {
    verify_core(root).is_ok()
        && fs::read(root.join(PUBLISHED_SEAL)).is_ok_and(|bytes| bytes == CONTENTS)
}

pub(super) fn begin(home: &Path) -> io::Result<PathBuf> {
    // Clear the old commit before removing its tree: a crash cannot bless a new partial tree.
    remove_ready(home)?;
    let scratch = home.join("resources.unpacking");
    match fs::remove_dir_all(&scratch) {
        Err(error) if error.kind() != io::ErrorKind::NotFound => return Err(error),
        _ => {}
    }
    fs::create_dir_all(&scratch)?;
    Ok(scratch)
}

pub(super) fn mark_ready(home: &Path, require_core: bool) -> io::Result<()> {
    if require_core {
        verify_core(&home.join("resources.unpacking"))?;
        let mut seal = fs::File::create(home.join("resources.unpacking").join(PUBLISHED_SEAL))?;
        seal.write_all(CONTENTS)?;
        seal.sync_all()?;
    }
    // Outside the tree: a ZIP member cannot forge this record while extraction is incomplete.
    let mut file = fs::File::create(home.join(READY))?;
    file.write_all(CONTENTS)?;
    file.sync_all()
}

pub(super) fn clear_ready(home: &Path) {
    let _ = remove_ready(home);
}

pub(super) fn recover(home: &Path, require_core: bool) -> io::Result<Option<PathBuf>> {
    let scratch = home.join("resources.unpacking");
    let dest = home.join("resources");
    let previous = home.join("resources.previous");
    let committed = fs::read(home.join(READY)).is_ok_and(|bytes| bytes == CONTENTS);
    let valid = committed && scratch.is_dir() && (!require_core || verify_core(&scratch).is_ok());
    if !dest.exists() && valid {
        fs::rename(&scratch, &dest)?;
        clear_ready(home);
        let _ = fs::remove_dir_all(&previous);
        return Ok(Some(dest));
    }
    // Uncommitted extraction is not a usable resource root, even if its manifest arrived first.
    let _ = fs::remove_dir_all(&scratch);
    remove_ready(home)?;
    if !dest.exists() && previous.is_dir() {
        fs::rename(&previous, &dest)?;
        return Ok(Some(dest));
    }
    if dest.exists() {
        let _ = fs::remove_dir_all(&previous);
    }
    Ok(None)
}

#[cfg(test)]
mod tests {
    use super::*;
    fn home() -> PathBuf {
        static NEXT: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(0);
        let root = std::env::temp_dir().join(format!(
            "horizon-import-transaction-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, std::sync::atomic::Ordering::Relaxed)
        ));
        fs::create_dir_all(&root).unwrap();
        root
    }
    fn complete(root: &Path) {
        for name in REQUIRED {
            let file = root.join(name);
            fs::create_dir_all(file.parent().unwrap()).unwrap();
            fs::write(file, b"fixture").unwrap();
        }
    }
    #[test]
    fn manifest_and_pngs_do_not_commit_an_interrupted_import() {
        let home = home();
        let scratch = begin(&home).unwrap();
        fs::write(scratch.join("horizon-resources.json"), b"{}").unwrap();
        fs::write(scratch.join(READY), CONTENTS).unwrap(); // An archive cannot forge the external marker.
        assert!(mark_ready(&home, true).is_err());
        assert!(recover(&home, true).unwrap().is_none());
        assert!(!home.join("resources").exists());
        assert!(!scratch.exists());
        fs::remove_dir_all(home).unwrap();
    }
    #[test]
    fn interrupted_replacement_restores_previous_tree() {
        let home = home();
        let scratch = begin(&home).unwrap();
        fs::write(scratch.join("horizon-resources.json"), b"incomplete").unwrap();
        let previous = home.join("resources.previous");
        fs::create_dir_all(&previous).unwrap();
        fs::write(previous.join("sentinel"), b"old").unwrap();
        assert_eq!(recover(&home, true).unwrap(), Some(home.join("resources")));
        assert_eq!(fs::read(home.join("resources/sentinel")).unwrap(), b"old");
        fs::remove_dir_all(home).unwrap();
    }
    #[test]
    fn committed_tree_recovers_after_crash_between_renames() {
        let home = home();
        let scratch = begin(&home).unwrap();
        complete(&scratch);
        mark_ready(&home, true).unwrap();
        assert_eq!(recover(&home, true).unwrap(), Some(home.join("resources")));
        verify_core(&home.join("resources")).unwrap();
        assert!(!home.join(READY).exists());
        fs::remove_dir_all(home).unwrap();
    }
    #[test]
    fn new_attempt_cannot_inherit_previous_completion_record() {
        let home = home();
        let scratch = begin(&home).unwrap();
        complete(&scratch);
        mark_ready(&home, true).unwrap();
        begin(&home).unwrap();
        assert!(!home.join(READY).exists());
        assert!(recover(&home, true).unwrap().is_none());
        fs::remove_dir_all(home).unwrap();
    }
    #[test]
    fn completed_publish_keeps_current_tree() {
        let home = home();
        let scratch = begin(&home).unwrap();
        complete(&scratch);
        mark_ready(&home, true).unwrap();
        fs::rename(scratch, home.join("resources")).unwrap();
        assert!(recover(&home, true).unwrap().is_none());
        verify_core(&home.join("resources")).unwrap();
        assert!(published_ready(&home.join("resources")));
        assert!(!home.join(READY).exists());
        fs::remove_dir_all(home).unwrap();
    }
}
