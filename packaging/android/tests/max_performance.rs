//! Run the production preset without linking a GPU renderer. The full Android build checks real types.
#![allow(dead_code)]
mod graphics {
    #[derive(Clone,Copy,Debug,Default,PartialEq)] pub enum FrameGeneration { #[default] Off }
    #[derive(Clone,Copy,Debug,Default,PartialEq)] pub enum HdrMode { #[default] Off, On }
    #[derive(Clone,Copy,Debug,Default,PartialEq)] pub enum Upscaling { #[default] Native }
    #[derive(Clone,Copy,Debug,Default,PartialEq)] pub enum Preset { #[default] Custom }
    #[derive(Default)] pub struct Graphics {
        pub preset:Preset,
        pub lod_distance:u32, pub cave_culling:bool, pub sky:bool, pub fog:bool, pub weather:bool,
        pub animated_textures:bool, pub dither_cutout_mips:bool, pub fxaa:bool, pub taa:bool,
        pub upscaling:Upscaling, pub upscaling_sharpness:u32, pub dynamic_resolution:bool,
        pub resolution_scale:u32, pub frame_generation:FrameGeneration, pub hdr:HdrMode,
        pub hdr_lighting:bool, pub hdr_smooth_gradients:bool, pub shader_pack:String, pub battery:bool,
    }
}
mod options_file {
    #[derive(Clone)] pub enum V { Int(i32), Double(f64), Bool(bool) }
    #[derive(Default)] pub struct File(std::collections::HashMap<String,V>);
    impl File {
        pub fn set(&mut self,key:&str,value:V) { self.0.insert(key.into(),value); }
        pub fn double(&self,key:&str)->f64 { match self.0.get(key) { Some(V::Double(x))=>*x,_=>f64::NAN } }
    }
}
mod settings {
    #[derive(Default)] pub struct Settings {
        pub render_distance:i32,pub max_fps:u32,pub sensitivity:f32,pub master_volume:f32,
        pub biome_blend:i32,pub prioritize_chunk_updates:u8,pub simulation_distance:i32,
        pub smooth_lighting:bool,pub clouds:String,pub particles:u8,pub mipmap_levels:i32,
        pub entity_shadows:bool,pub entity_distance:f32,pub menu_blur:i32,pub cloud_range:i32,
        pub cutout_leaves:bool,pub improved_transparency:bool,pub weather_radius:i32,pub chunk_fade:f32,
        pub vignette:bool,pub vsync:bool,pub view_bobbing:bool,pub file:crate::options_file::File,
        pub horizon:crate::graphics::Graphics,
    }
}
#[path="../../../crates/horizon-client-render/src/max_performance.rs"] mod max_performance;
