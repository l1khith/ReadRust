// =============================================================================
// ReadRust — jni_adapter/annotation_bridge.rs
// JNI export for PDF annotations & audio sidecars adhering to pro_rust_patterns_guide.md
// =============================================================================

use crate::annotations::audio_meta::generate_sidecar_json;
use crate::annotations::pdf_writer::export_annotated_pdf;
use crate::annotations::types::{Bookmark, Highlight};
use jni::objects::JString;
use jni::sys::{jdouble, jint, jstring};
use jni::JNIEnv as JNIEnvStruct;
use std::fs::File;
use std::io::Write;
use std::path::Path;
use std::ptr;

#[no_mangle]
pub unsafe extern "system" fn Java_com_l1khith_readrust_annotations_AnnotationNativeBridge_nativeExportAnnotatedPdf(
    mut env: JNIEnvStruct,
    _class: jni::sys::jobject,
    pdf_fd: jint,
    output_path: JString,
    highlights_json: JString,
    bookmarks_json: JString,
) -> jint {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let out_str: String = match env.get_string(&output_path) {
            Ok(s) => s.into(),
            Err(_) => return -1,
        };
        let hl_str: String = match env.get_string(&highlights_json) {
            Ok(s) => s.into(),
            Err(_) => return -1,
        };
        let bm_str: String = match env.get_string(&bookmarks_json) {
            Ok(s) => s.into(),
            Err(_) => return -1,
        };

        let highlights: Vec<Highlight> = match serde_json::from_str(&hl_str) {
            Ok(h) => h,
            Err(e) => {
                log::error!("Failed to parse highlights JSON: {}", e);
                Vec::new()
            }
        };

        let bookmarks: Vec<Bookmark> = match serde_json::from_str(&bm_str) {
            Ok(b) => b,
            Err(e) => {
                log::error!("Failed to parse bookmarks JSON: {}", e);
                Vec::new()
            }
        };

        let out_path = Path::new(&out_str);

        match export_annotated_pdf(pdf_fd, out_path, &highlights, &bookmarks) {
            Ok(_) => 0,
            Err(e) => {
                log::error!("export_annotated_pdf failed: {}", e);
                -2
            }
        }
    }));

    result.unwrap_or(-99)
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_l1khith_readrust_annotations_AnnotationNativeBridge_nativeGenerateAudioSidecar(
    mut env: JNIEnvStruct,
    _class: jni::sys::jobject,
    output_sidecar_path: JString,
    highlights_json: JString,
    bookmarks_json: JString,
    total_pages: jint,
    total_audio_seconds: jdouble,
) -> jint {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let sidecar_path: String = match env.get_string(&output_sidecar_path) {
            Ok(s) => s.into(),
            Err(_) => return -1,
        };
        let hl_str: String = match env.get_string(&highlights_json) {
            Ok(s) => s.into(),
            Err(_) => return -1,
        };
        let bm_str: String = match env.get_string(&bookmarks_json) {
            Ok(s) => s.into(),
            Err(_) => return -1,
        };

        let highlights: Vec<Highlight> = match serde_json::from_str(&hl_str) {
            Ok(h) => h,
            Err(_) => Vec::new(),
        };
        let bookmarks: Vec<Bookmark> = match serde_json::from_str(&bm_str) {
            Ok(b) => b,
            Err(_) => Vec::new(),
        };

        let sidecar_content = generate_sidecar_json(
            &bookmarks,
            &highlights,
            total_pages as u32,
            total_audio_seconds as f64,
        );

        match File::create(&sidecar_path).and_then(|mut f| f.write_all(sidecar_content.as_bytes())) {
            Ok(_) => 0,
            Err(e) => {
                log::error!("Failed to write audio sidecar file: {}", e);
                -2
            }
        }
    }));

    result.unwrap_or(-99)
}
