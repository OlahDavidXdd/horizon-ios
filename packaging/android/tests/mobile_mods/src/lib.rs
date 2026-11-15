//! Exercise the actual mobile mod API without building a renderer or starting a game.
extern crate self as horizon_client;
pub type Result<T> = std::result::Result<T, Box<dyn std::error::Error + Send + Sync>>;
#[path = "../../../../../crates/horizon-client-render/src/mod_list.rs"]
pub mod mod_list;
pub mod settings {
    #[derive(Default)]
    pub struct Settings { pub mods: crate::mod_list::ModList }
}
#[path = "../../../../../crates/horizon-client/src/game/mods_control.rs"]
mod mods_control;
