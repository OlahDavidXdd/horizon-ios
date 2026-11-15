//! An opt-in graphics preset that keeps the player's chunk render distance and frame cap.
use crate::{
    graphics::{FrameGeneration, HdrMode, Preset, Upscaling},
    options_file::V,
    settings::Settings,
};

pub fn apply(s: &mut Settings) {
    // Do not assign render_distance: this preset must work at any chosen chunk range.
    s.biome_blend = 0;
    s.prioritize_chunk_updates = 0;
    s.simulation_distance = 5;
    s.smooth_lighting = false;
    s.clouds = "false".into();
    s.particles = 2;
    s.mipmap_levels = 0;
    s.entity_shadows = false;
    s.entity_distance = 0.5;
    s.menu_blur = 0;
    s.cloud_range = 2;
    s.cutout_leaves = false;
    s.improved_transparency = false;
    s.weather_radius = 3;
    s.chunk_fade = 0.0;
    s.vignette = false;
    s.vsync = false;
    s.view_bobbing = false;
    s.file.set("maxAnisotropyBit", V::Int(1));
    s.file.set("textureFiltering", V::Int(0));
    for key in [
        "fovEffectScale",
        "darknessEffectScale",
        "screenEffectScale",
        "damageTiltStrength",
        "glintSpeed",
        "glintStrength",
    ] {
        s.file.set(key, V::Double(0.0));
    }
    s.file.set("bobView", V::Bool(false));
    let g = &mut s.horizon;
    g.preset = Preset::Custom;
    g.lod_distance = 0;
    g.cave_culling = true;
    g.sky = false;
    g.fog = false;
    g.weather = false;
    g.animated_textures = false;
    g.dither_cutout_mips = false;
    g.fxaa = false;
    g.taa = false;
    g.upscaling = Upscaling::Native;
    g.upscaling_sharpness = 0;
    g.dynamic_resolution = false;
    g.resolution_scale = 100;
    g.frame_generation = FrameGeneration::Off;
    g.hdr = HdrMode::Off;
    g.hdr_lighting = false;
    g.hdr_smooth_gradients = false;
    g.shader_pack.clear();
    g.battery = false;
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn preserves_chunk_distance_frame_cap_audio_and_controls() {
        for distance in [2, 12, 128] {
            let mut s = Settings::default();
            s.render_distance = distance;
            s.max_fps = 120;
            s.sensitivity = 0.25;
            s.master_volume = 0.7;
            s.horizon.sky = true;
            s.horizon.fog = true;
            s.horizon.weather = true;
            s.horizon.animated_textures = true;
            s.horizon.fxaa = true;
            s.horizon.taa = true;
            s.horizon.hdr = HdrMode::On;
            s.horizon.shader_pack = "test.zip".into();
            apply(&mut s);
            assert_eq!(s.render_distance, distance);
            assert_eq!(s.max_fps, 120);
            assert_eq!(s.sensitivity, 0.25);
            assert_eq!(s.master_volume, 0.7);
            assert_eq!(s.simulation_distance, 5);
            assert_eq!(s.mipmap_levels, 0);
            assert!(!s.smooth_lighting && !s.entity_shadows && !s.view_bobbing && !s.vignette);
            assert!(
                !s.horizon.sky
                    && !s.horizon.fog
                    && !s.horizon.weather
                    && !s.horizon.animated_textures
            );
            assert!(!s.horizon.fxaa && !s.horizon.taa);
            assert_eq!(s.horizon.hdr, HdrMode::Off);
            assert!(s.horizon.shader_pack.is_empty());
            assert!(s.horizon.cave_culling);
            assert_eq!(s.file.double("screenEffectScale"), 0.0);
        }
    }
}
