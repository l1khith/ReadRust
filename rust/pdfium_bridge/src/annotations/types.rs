// =============================================================================
// ReadRust — annotations/types.rs
// Highlight, Bookmark, and RgbColor data structures
// =============================================================================

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Highlight {
    pub page: u32,
    #[serde(default)]
    pub char_start: usize,
    #[serde(default)]
    pub char_end: usize,
    #[serde(default)]
    pub color_argb: i64,
    #[serde(default)]
    pub bounds_left: f32,
    #[serde(default)]
    pub bounds_top: f32,
    #[serde(default)]
    pub bounds_right: f32,
    #[serde(default)]
    pub bounds_bottom: f32,
    #[serde(default)]
    pub text_snippet: String,
    pub note: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Bookmark {
    pub page: u32,
    pub label: String,
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct RgbColor {
    pub r: f32,
    pub g: f32,
    pub b: f32,
}

impl RgbColor {
    pub fn from_argb(argb: i64) -> Self {
        let u_val = argb as u32;
        let r = ((u_val >> 16) & 0xFF) as f32 / 255.0;
        let g = ((u_val >> 8) & 0xFF) as f32 / 255.0;
        let b = (u_val & 0xFF) as f32 / 255.0;
        Self { r, g, b }
    }
}
