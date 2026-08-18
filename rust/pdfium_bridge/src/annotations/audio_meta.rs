// =============================================================================
// ReadRust — annotations/audio_meta.rs
// Sidecar JSON metadata generator for audio bookmarks and chapter marks
// =============================================================================

use super::types::{Bookmark, Highlight};
use serde_json::json;

pub fn generate_sidecar_json(
    bookmarks: &[Bookmark],
    highlights: &[Highlight],
    total_pages: u32,
    total_audio_seconds: f64,
) -> String {
    let seconds_per_page = if total_pages > 0 {
        total_audio_seconds / (total_pages as f64)
    } else {
        0.0
    };

    let bookmark_marks: Vec<_> = bookmarks
        .iter()
        .map(|bm| {
            let est_seconds = (bm.page as f64) * seconds_per_page;
            json!({
                "page": bm.page,
                "label": bm.label,
                "timestamp_seconds": est_seconds,
            })
        })
        .collect();

    let highlight_marks: Vec<_> = highlights
        .iter()
        .map(|hl| {
            let est_seconds = (hl.page as f64) * seconds_per_page;
            json!({
                "page": hl.page,
                "text_snippet": hl.text_snippet,
                "color_argb": hl.color_argb,
                "timestamp_seconds": est_seconds,
                "note": hl.note,
            })
        })
        .collect();

    json!({
        "version": 1,
        "total_audio_seconds": total_audio_seconds,
        "total_pages": total_pages,
        "bookmarks": bookmark_marks,
        "highlights": highlight_marks,
    })
    .to_string()
}
