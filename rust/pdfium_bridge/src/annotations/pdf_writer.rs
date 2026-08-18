// =============================================================================
// ReadRust — annotations/pdf_writer.rs
// Pure Rust PDF annotation & bookmark injection using `lopdf`
// Strictly adheres to pro_rust_patterns_guide.md (zero unwrap/expect)
// =============================================================================

use super::types::{Bookmark, Highlight, RgbColor};
use lopdf::{dictionary, Dictionary, Document, Object, ObjectId, StringFormat};
use std::fs::File;
use std::path::Path;
use thiserror::Error;

#[derive(Error, Debug)]
pub enum PdfWriterError {
    #[error("Failed to load source PDF: {0}")]
    Lopdf(#[from] lopdf::Error),

    #[error("I/O error during PDF export: {0}")]
    Io(#[from] std::io::Error),

    #[error("Source PDF descriptor invalid or empty")]
    InvalidSource,
}

pub type PdfWriterResult<T> = Result<T, PdfWriterError>;

/// Injects highlights (/Annots) and bookmarks (/Outlines) into a PDF and saves to output_path.
pub fn export_annotated_pdf(
    source_fd: i32,
    output_path: &Path,
    highlights: &[Highlight],
    bookmarks: &[Bookmark],
) -> PdfWriterResult<()> {
    if source_fd < 0 {
        return Err(PdfWriterError::InvalidSource);
    }

    // Load PDF document from Linux file descriptor path /proc/self/fd/{fd}
    let fd_path = format!("/proc/self/fd/{}", source_fd);
    let mut doc = Document::load_from(File::open(&fd_path)?)?;

    let page_ids: Vec<(u32, ObjectId)> = doc
        .get_pages()
        .into_iter()
        .map(|(num, id)| (num, id))
        .collect();

    // 1. Inject Highlight Annotations per page
    for &(page_num, page_id) in &page_ids {
        let page_index = page_num.saturating_sub(1);
        let page_highlights: Vec<&Highlight> = highlights
            .iter()
            .filter(|h| h.page == page_index)
            .collect();

        if page_highlights.is_empty() {
            continue;
        }

        let mut annot_refs = Vec::new();

        for hl in page_highlights {
            let color = RgbColor::from_argb(hl.color_argb);
            let left = hl.bounds_left * 612.0;
            let top = (1.0 - hl.bounds_top) * 792.0;
            let right = hl.bounds_right * 612.0;
            let bottom = (1.0 - hl.bounds_bottom) * 792.0;

            let rect = vec![
                Object::Real(left.min(right)),
                Object::Real(bottom.min(top)),
                Object::Real(left.max(right)),
                Object::Real(bottom.max(top)),
            ];

            let quad_points = vec![
                Object::Real(left),
                Object::Real(top),
                Object::Real(right),
                Object::Real(top),
                Object::Real(left),
                Object::Real(bottom),
                Object::Real(right),
                Object::Real(bottom),
            ];

            let mut annot_dict = Dictionary::new();
            annot_dict.set(b"Type".to_vec(), Object::Name(b"Annot".to_vec()));
            annot_dict.set(b"Subtype".to_vec(), Object::Name(b"Highlight".to_vec()));
            annot_dict.set(b"Rect".to_vec(), Object::Array(rect));
            annot_dict.set(b"QuadPoints".to_vec(), Object::Array(quad_points));
            annot_dict.set(
                b"C".to_vec(),
                Object::Array(vec![
                    Object::Real(color.r),
                    Object::Real(color.g),
                    Object::Real(color.b),
                ]),
            );
            annot_dict.set(b"P".to_vec(), Object::Reference(page_id));
            annot_dict.set(
                b"T".to_vec(),
                Object::String(b"ReadRust".to_vec(), StringFormat::Literal),
            );

            if let Some(note) = &hl.note {
                annot_dict.set(
                    b"Contents".to_vec(),
                    Object::String(note.as_bytes().to_vec(), StringFormat::Literal),
                );
            }

            let annot_id = doc.add_object(Object::Dictionary(annot_dict));
            annot_refs.push(Object::Reference(annot_id));
        }

        if let Ok(page_obj) = doc.get_object_mut(page_id) {
            if let Ok(dict) = page_obj.as_dict_mut() {
                if let Ok(existing_annots) = dict.get_mut(b"Annots") {
                    if let Ok(arr) = existing_annots.as_array_mut() {
                        arr.extend(annot_refs);
                    }
                } else {
                    dict.set(b"Annots".to_vec(), Object::Array(annot_refs));
                }
            }
        }
    }

    // 2. Inject Bookmarks (/Outlines)
    if !bookmarks.is_empty() {
        let mut outline_items = Vec::new();

        for bm in bookmarks {
            let target_page_id = page_ids
                .iter()
                .find(|(num, _)| *num == bm.page + 1)
                .map(|(_, id)| *id);

            if let Some(page_id) = target_page_id {
                let dest = vec![
                    Object::Reference(page_id),
                    Object::Name(b"Fit".to_vec()),
                ];

                let item_dict = dictionary! {
                    "Title" => Object::String(bm.label.as_bytes().to_vec(), StringFormat::Literal),
                    "Dest" => Object::Array(dest),
                };

                let item_id = doc.add_object(Object::Dictionary(item_dict));
                outline_items.push(item_id);
            }
        }

        if !outline_items.is_empty() {
            let root_outlines = dictionary! {
                "Type" => Object::Name(b"Outlines".to_vec()),
                "Count" => Object::Integer(outline_items.len() as i64),
                "First" => Object::Reference(outline_items[0]),
                "Last" => Object::Reference(*outline_items.last().unwrap_or(&outline_items[0])),
            };

            let outlines_id = doc.add_object(Object::Dictionary(root_outlines));

            if let Ok(catalog_id) = doc.trailer.get(b"Root").and_then(|o| o.as_reference()) {
                if let Ok(catalog_obj) = doc.get_object_mut(catalog_id) {
                    if let Ok(dict) = catalog_obj.as_dict_mut() {
                        dict.set(b"Outlines".to_vec(), Object::Reference(outlines_id));
                    }
                }
            }
        }
    }

    // 3. Save modified PDF to output path
    doc.save(output_path)?;
    log::info!(
        "Annotated PDF exported successfully: {:?} ({} highlights, {} bookmarks)",
        output_path,
        highlights.len(),
        bookmarks.len()
    );

    Ok(())
}
