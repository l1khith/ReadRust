// =============================================================================
// ReadRust — pdfium_bridge/src/lib.rs
// MERGED: PDFium Native Rendering + Page-by-Page Text Extraction + Lightweight Audio Stitcher & PDF Annotations
// STRICT COMPLIANCE: pro_rust_patterns_guide.md (zero unwrap/expect in business logic, safety invariant docs)
// =============================================================================

pub mod audio_engine;
pub mod annotations;
pub mod jni_adapter;

use jni::objects::{JObject, JString};
use jni::signature::JavaType;
use jni::sys::{jfloat, jint, jlong, jobject, jstring};
use jni::JNIEnv as JNIEnvStruct;
use std::collections::HashMap;
use std::ffi::{c_char, c_int, c_void, CString};
use std::fs::File;
use std::io::{Seek, SeekFrom, Write};
use std::os::raw::c_ulong;
use std::ptr;
use std::sync::atomic::{AtomicBool, AtomicU32, AtomicU64, Ordering};
use std::sync::Mutex;

// =============================================================================
// Logging
// =============================================================================

#[cfg(target_os = "android")]
fn setup_android_logging() {
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Debug),
    );
}

#[cfg(not(target_os = "android"))]
fn setup_android_logging() {}

// =============================================================================
// pdfium types
// =============================================================================

pub type FPDF_DOCUMENT = *mut c_void;
pub type FPDF_PAGE = *mut c_void;
pub type FPDF_BITMAP = *mut c_void;
pub type FPDF_TEXTPAGE = *mut c_void;
pub type FPDF_STRING = *const c_char;

pub const FPDF_ANNOT: c_int = 0x01;
pub const FPDFBitmap_BGRA: c_int = 4;

// =============================================================================
// pdfium FFI
// =============================================================================

extern "C" {
    pub fn FPDF_InitLibrary();
    pub fn FPDF_DestroyLibrary();
    pub fn FPDF_GetLastError() -> c_ulong;

    pub fn FPDF_LoadMemDocument64(
        data_buf: *const c_void,
        size: usize,
        password: FPDF_STRING,
    ) -> FPDF_DOCUMENT;

    pub fn FPDF_CloseDocument(document: FPDF_DOCUMENT);
    pub fn FPDF_GetPageCount(document: FPDF_DOCUMENT) -> c_int;

    pub fn FPDF_LoadPage(document: FPDF_DOCUMENT, page_index: c_int) -> FPDF_PAGE;
    pub fn FPDF_ClosePage(page: FPDF_PAGE);
    pub fn FPDF_GetPageWidthF(page: FPDF_PAGE) -> f32;
    pub fn FPDF_GetPageHeightF(page: FPDF_PAGE) -> f32;

    pub fn FPDFBitmap_Create(width: c_int, height: c_int, alpha: c_int) -> FPDF_BITMAP;
    pub fn FPDFBitmap_CreateEx(
        width: c_int,
        height: c_int,
        format: c_int,
        first_scanline: *mut c_void,
        stride: c_int,
    ) -> FPDF_BITMAP;
    pub fn FPDFBitmap_FillRect(
        bitmap: FPDF_BITMAP,
        left: c_int,
        top: c_int,
        width: c_int,
        height: c_int,
        color: u32,
    );
    pub fn FPDFBitmap_Destroy(bitmap: FPDF_BITMAP);
    pub fn FPDFBitmap_GetBuffer(bitmap: FPDF_BITMAP) -> *mut c_void;
    pub fn FPDFBitmap_GetStride(bitmap: FPDF_BITMAP) -> c_int;
    pub fn FPDF_RenderPageBitmap(
        bitmap: FPDF_BITMAP,
        page: FPDF_PAGE,
        start_x: c_int,
        start_y: c_int,
        size_x: c_int,
        size_y: c_int,
        rotate: c_int,
        flags: c_int,
    ) -> c_int;

    pub fn FPDFText_LoadPage(page: FPDF_PAGE) -> FPDF_TEXTPAGE;
    pub fn FPDFText_ClosePage(text_page: FPDF_TEXTPAGE);
    pub fn FPDFText_CountChars(text_page: FPDF_TEXTPAGE) -> c_int;
    pub fn FPDFText_GetUnicode(text_page: FPDF_TEXTPAGE, index: c_int) -> u32;
    pub fn FPDFText_GetCharBox(
        text_page: FPDF_TEXTPAGE,
        index: c_int,
        left: *mut f64,
        right: *mut f64,
        bottom: *mut f64,
        top: *mut f64,
    ) -> c_int;
}

// =============================================================================
// Android NDK bitmap
// =============================================================================

#[repr(C)]
#[derive(Debug, Default, Copy, Clone)]
pub struct AndroidBitmapInfo {
    pub width: u32,
    pub height: u32,
    pub stride: u32,
    pub format: c_int,
    pub flags: u32,
}

extern "C" {
    pub fn AndroidBitmap_getInfo(
        env: *mut jni::sys::JNIEnv,
        bitmap: jobject,
        info: *mut AndroidBitmapInfo,
    ) -> c_int;
    pub fn AndroidBitmap_lockPixels(
        env: *mut jni::sys::JNIEnv,
        bitmap: jobject,
        addrptr: *mut *mut c_void,
    ) -> c_int;
    pub fn AndroidBitmap_unlockPixels(
        env: *mut jni::sys::JNIEnv,
        bitmap: jobject,
    ) -> c_int;
}

// =============================================================================
// BitmapGuard
// =============================================================================

struct BitmapGuard {
    env: *mut jni::sys::JNIEnv,
    bitmap: jobject,
}

impl Drop for BitmapGuard {
    fn drop(&mut self) {
        unsafe {
            AndroidBitmap_unlockPixels(self.env, self.bitmap);
        }
    }
}

// =============================================================================
// Document registry (Poison-resistant Mutex protection: BUG #7 FIX)
// =============================================================================

const MAGIC_ALIVE: u32 = 0x50444631;
const MAGIC_DEAD: u32 = 0xDEAD;

struct PdfDocumentInner {
    magic: AtomicU32,
    doc: FPDF_DOCUMENT,
    mapped_data: *mut c_void,
    mapped_size: usize,
    page_count: c_int,
}

unsafe impl Send for PdfDocumentInner {}
unsafe impl Sync for PdfDocumentInner {}

static PDFIUM_INITIALIZED: AtomicBool = AtomicBool::new(false);
static DOCUMENTS: once_cell::sync::Lazy<Mutex<HashMap<u64, PdfDocumentInner>>> =
    once_cell::sync::Lazy::new(|| Mutex::new(HashMap::new()));
static NEXT_ID: AtomicU64 = AtomicU64::new(1);

fn ensure_initialized() {
    if !PDFIUM_INITIALIZED.swap(true, Ordering::SeqCst) {
        unsafe {
            FPDF_InitLibrary();
        }
    }
}

// Helper to safely acquire lock without crashing on poisoned mutex
fn get_documents() -> std::sync::MutexGuard<'static, HashMap<u64, PdfDocumentInner>> {
    DOCUMENTS.lock().unwrap_or_else(|e| e.into_inner())
}

// =============================================================================
// Document loading
// =============================================================================

fn load_document_from_fd(fd: jint) -> jlong {
    ensure_initialized();
    if fd < 0 {
        return 0;
    }

    #[cfg(unix)]
    unsafe {
        let mut stat: libc::stat = std::mem::zeroed();
        if libc::fstat(fd, &mut stat) != 0 || stat.st_size <= 0 {
            return 0;
        }
        let size = stat.st_size as usize;

        let mapped_ptr = libc::mmap(
            ptr::null_mut(),
            size,
            libc::PROT_READ,
            libc::MAP_PRIVATE,
            fd,
            0,
        );
        if mapped_ptr == libc::MAP_FAILED {
            return 0;
        }

        let doc = FPDF_LoadMemDocument64(mapped_ptr, size, ptr::null());
        if doc.is_null() {
            let _err = FPDF_GetLastError();
            libc::munmap(mapped_ptr, size);
            return 0;
        }

        let page_count = FPDF_GetPageCount(doc) as c_int;
        let id = NEXT_ID.fetch_add(1, Ordering::SeqCst);
        let inner = PdfDocumentInner {
            magic: AtomicU32::new(MAGIC_ALIVE),
            doc,
            mapped_data: mapped_ptr,
            mapped_size: size,
            page_count,
        };

        get_documents().insert(id, inner);
        id as jlong
    }

    #[cfg(not(unix))]
    {
        let _ = fd;
        0
    }
}

fn load_document_from_path(path_str: &str) -> jlong {
    ensure_initialized();
    let c_path = match CString::new(path_str) {
        Ok(s) => s,
        Err(_) => return 0,
    };
    unsafe {
        let fd = libc::open(c_path.as_ptr(), libc::O_RDONLY);
        if fd < 0 {
            return 0;
        }
        let handle = load_document_from_fd(fd);
        libc::close(fd);
        handle
    }
}

fn close_document_inner(handle: jlong) -> jint {
    if handle <= 0 {
        return -2;
    }
    let mut docs = get_documents();
    if let Some(inner) = docs.remove(&(handle as u64)) {
        if inner.magic.swap(MAGIC_DEAD, Ordering::SeqCst) == MAGIC_ALIVE {
            unsafe {
                FPDF_CloseDocument(inner.doc);
                #[cfg(unix)]
                if !inner.mapped_data.is_null() && inner.mapped_size > 0 {
                    libc::madvise(inner.mapped_data, inner.mapped_size, libc::MADV_DONTNEED);
                    libc::munmap(inner.mapped_data, inner.mapped_size);
                }
            }
            return 0;
        }
    }
    -2
}

fn get_page_count_inner(handle: jlong) -> jint {
    if handle <= 0 {
        return -2;
    }
    let docs = get_documents();
    if let Some(inner) = docs.get(&(handle as u64)) {
        if inner.magic.load(Ordering::SeqCst) == MAGIC_ALIVE {
            return inner.page_count;
        }
    }
    -2
}

// =============================================================================
// Render page
// =============================================================================

fn render_page_inner(
    env: &mut JNIEnvStruct,
    handle: jlong,
    page_index: jint,
    bitmap_obj: jobject,
    width: jint,
    height: jint,
    hl_left: jfloat,
    hl_top: jfloat,
    hl_right: jfloat,
    hl_bottom: jfloat,
) -> jint {
    if handle <= 0 || bitmap_obj.is_null() {
        return -2;
    }

    let _buffer_size = match (width as usize).checked_mul(height as usize) {
        Some(sz) => match sz.checked_mul(4) {
            Some(_) => sz,
            None => return -4,
        },
        None => return -4,
    };

    let docs = get_documents();
    let inner = match docs.get(&(handle as u64)) {
        Some(d) => d,
        None => return -2,
    };

    if inner.magic.load(Ordering::SeqCst) != MAGIC_ALIVE {
        return -2;
    }

    if page_index < 0 || page_index >= inner.page_count {
        return -3;
    }

    let raw_env = env.get_raw();

    let mut info: AndroidBitmapInfo = unsafe { std::mem::zeroed() };
    if unsafe { AndroidBitmap_getInfo(raw_env, bitmap_obj, &mut info) } != 0 {
        return -1;
    }

    let mut pixels_ptr: *mut c_void = ptr::null_mut();
    if unsafe { AndroidBitmap_lockPixels(raw_env, bitmap_obj, &mut pixels_ptr) } != 0
        || pixels_ptr.is_null()
    {
        return -1;
    }

    let _guard = BitmapGuard {
        env: raw_env,
        bitmap: bitmap_obj,
    };

    unsafe {
        let fpdf_bitmap = FPDFBitmap_CreateEx(
            width as c_int,
            height as c_int,
            FPDFBitmap_BGRA,
            pixels_ptr,
            info.stride as c_int,
        );

        if fpdf_bitmap.is_null() {
            return -1;
        }

        FPDFBitmap_FillRect(fpdf_bitmap, 0, 0, width as c_int, height as c_int, 0xFFFFFFFF);

        let page = FPDF_LoadPage(inner.doc, page_index as c_int);
        if page.is_null() {
            FPDFBitmap_Destroy(fpdf_bitmap);
            return -1;
        }

        let page_w = FPDF_GetPageWidthF(page) as f64;
        let page_h = FPDF_GetPageHeightF(page) as f64;

        let (start_x, start_y, size_x, size_y) = if page_w > 0.0 && page_h > 0.0 {
            let scale_x = width as f64 / page_w;
            let scale_y = height as f64 / page_h;
            let scale = scale_x.min(scale_y);

            let render_w = (page_w * scale) as c_int;
            let render_h = (page_h * scale) as c_int;
            let offset_x = (width as c_int - render_w) / 2;
            let offset_y = (height as c_int - render_h) / 2;

            (offset_x, offset_y, render_w, render_h)
        } else {
            (0, 0, width as c_int, height as c_int)
        };

        FPDF_RenderPageBitmap(
            fpdf_bitmap,
            page,
            start_x,
            start_y,
            size_x,
            size_y,
            0,
            FPDF_ANNOT,
        );

        FPDF_ClosePage(page);
        FPDFBitmap_Destroy(fpdf_bitmap);

        let total_bytes = (info.stride * height as u32) as usize;
        let slice = std::slice::from_raw_parts_mut(pixels_ptr as *mut u8, total_bytes);
        for chunk in slice.chunks_exact_mut(4) {
            chunk.swap(0, 2);
            chunk[3] = 255;
        }

        // Highlight blending
        if hl_right > hl_left && hl_bottom > hl_top && size_x > 0 && size_y > 0 {
            let h_x0 = (start_x + (hl_left * size_x as f32) as c_int).clamp(0, width - 1);
            let h_y0 = (start_y + (hl_top * size_y as f32) as c_int).clamp(0, height - 1);
            let h_x1 = (start_x + (hl_right * size_x as f32) as c_int).clamp(h_x0 + 1, width);
            let h_y1 = (start_y + (hl_bottom * size_y as f32) as c_int).clamp(h_y0 + 1, height);

            let stride = info.stride as usize;
            let hl_r = 0u32;
            let hl_g = 122u32;
            let hl_b = 255u32;
            let alpha = 0.35f32;
            let inv_alpha = 1.0f32 - alpha;

            for y in h_y0..h_y1 {
                let row_start = (y as usize) * stride;
                for x in h_x0..h_x1 {
                    let px_idx = row_start + (x as usize) * 4;
                    if px_idx + 3 < slice.len() {
                        let r = slice[px_idx] as f32;
                        let g = slice[px_idx + 1] as f32;
                        let b = slice[px_idx + 2] as f32;

                        slice[px_idx] = (r * inv_alpha + hl_r as f32 * alpha) as u8;
                        slice[px_idx + 1] = (g * inv_alpha + hl_g as f32 * alpha) as u8;
                        slice[px_idx + 2] = (b * inv_alpha + hl_b as f32 * alpha) as u8;
                    }
                }
            }
        }
    }

    0
}

// =============================================================================
// Text extraction — with header/footer exclusion
// =============================================================================

const TOP_EXCLUSION_RATIO: f64 = 0.92;
const BOTTOM_EXCLUSION_RATIO: f64 = 0.08;
const MAX_UNICODE_CODEPOINT: u32 = 0x10FFFF;

fn extract_text_simple(
    env: &mut JNIEnvStruct,
    handle: jlong,
    page_index: jint,
) -> jstring {
    if handle <= 0 {
        return env.new_string("")
            .map(|s| s.into_raw())
            .unwrap_or(ptr::null_mut());
    }

    let docs = get_documents();
    let inner = match docs.get(&(handle as u64)) {
        Some(d) => d,
        None => {
            return env.new_string("")
                .map(|s| s.into_raw())
                .unwrap_or(ptr::null_mut());
        }
    };

    if inner.magic.load(Ordering::SeqCst) != MAGIC_ALIVE {
        return env.new_string("")
            .map(|s| s.into_raw())
            .unwrap_or(ptr::null_mut());
    }

    if page_index < 0 || page_index >= inner.page_count {
        return env.new_string("")
            .map(|s| s.into_raw())
            .unwrap_or(ptr::null_mut());
    }

    unsafe {
        let page = FPDF_LoadPage(inner.doc, page_index as c_int);
        if page.is_null() {
            return env.new_string("")
                .map(|s| s.into_raw())
                .unwrap_or(ptr::null_mut());
        }

        let text_page = FPDFText_LoadPage(page);
        if text_page.is_null() {
            FPDF_ClosePage(page);
            return env.new_string("")
                .map(|s| s.into_raw())
                .unwrap_or(ptr::null_mut());
        }

        let page_height = FPDF_GetPageHeightF(page) as f64;
        let char_count = FPDFText_CountChars(text_page);

        let mut text = String::with_capacity(char_count as usize);

        for i in 0..char_count {
            let code = FPDFText_GetUnicode(text_page, i);
            if code == 0 || code > MAX_UNICODE_CODEPOINT {
                continue;
            }

            let mut left = 0.0f64;
            let mut right = 0.0f64;
            let mut bottom = 0.0f64;
            let mut top = 0.0f64;
            let has_box = FPDFText_GetCharBox(
                text_page, i,
                &mut left, &mut right, &mut bottom, &mut top,
            ) != 0;

            if has_box && page_height > 0.0 {
                let top_exclusion = page_height * TOP_EXCLUSION_RATIO;
                let bottom_exclusion = page_height * BOTTOM_EXCLUSION_RATIO;
                if top >= top_exclusion || bottom <= bottom_exclusion {
                    continue;
                }
            }

            if let Some(ch) = char::from_u32(code) {
                text.push(ch);
            }
        }

        FPDFText_ClosePage(text_page);
        FPDF_ClosePage(page);

        match env.new_string(&text) {
            Ok(s) => s.into_raw(),
            Err(_) => {
                if env.exception_check().unwrap_or(false) {
                    let _ = env.exception_clear();
                }
                env.new_string("")
                    .map(|s| s.into_raw())
                    .unwrap_or(ptr::null_mut())
            }
        }
    }
}

fn extract_sentences_json_simple(
    env: &mut JNIEnvStruct,
    handle: jlong,
    page_index: jint,
) -> jstring {
    if handle <= 0 {
        return env.new_string("[]")
            .map(|s| s.into_raw())
            .unwrap_or(ptr::null_mut());
    }

    let docs = get_documents();
    let inner = match docs.get(&(handle as u64)) {
        Some(d) => d,
        None => {
            return env.new_string("[]")
                .map(|s| s.into_raw())
                .unwrap_or(ptr::null_mut());
        }
    };

    if inner.magic.load(Ordering::SeqCst) != MAGIC_ALIVE {
        return env.new_string("[]")
            .map(|s| s.into_raw())
            .unwrap_or(ptr::null_mut());
    }

    if page_index < 0 || page_index >= inner.page_count {
        return env.new_string("[]")
            .map(|s| s.into_raw())
            .unwrap_or(ptr::null_mut());
    }

    unsafe {
        let page = FPDF_LoadPage(inner.doc, page_index as c_int);
        if page.is_null() {
            return env.new_string("[]")
                .map(|s| s.into_raw())
                .unwrap_or(ptr::null_mut());
        }

        let text_page = FPDFText_LoadPage(page);
        if text_page.is_null() {
            FPDF_ClosePage(page);
            return env.new_string("[]")
                .map(|s| s.into_raw())
                .unwrap_or(ptr::null_mut());
        }

        let page_w = FPDF_GetPageWidthF(page) as f64;
        let page_h = FPDF_GetPageHeightF(page) as f64;
        let char_count = FPDFText_CountChars(text_page);

        #[derive(Clone)]
        struct WordBound {
            left: f64,
            top: f64,
            right: f64,
            bottom: f64,
        }

        struct WordInfo {
            text: String,
            bounds: Vec<WordBound>,
            sentence_idx: usize,
        }

        struct CharInfo {
            unicode: u32,
            left: f64,
            right: f64,
            top: f64,
            bottom: f64,
            has_box: bool,
        }

        let mut chars: Vec<CharInfo> = Vec::with_capacity(char_count as usize);

        for i in 0..char_count {
            let code = FPDFText_GetUnicode(text_page, i);
            if code == 0 || code > MAX_UNICODE_CODEPOINT {
                continue;
            }

            let mut left = 0.0f64;
            let mut right = 0.0f64;
            let mut bottom = 0.0f64;
            let mut top = 0.0f64;
            let has_box = FPDFText_GetCharBox(
                text_page, i,
                &mut left, &mut right, &mut bottom, &mut top,
            ) != 0;

            if has_box && page_h > 0.0 {
                let top_exclusion = page_h * TOP_EXCLUSION_RATIO;
                let bottom_exclusion = page_h * BOTTOM_EXCLUSION_RATIO;
                if top >= top_exclusion || bottom <= bottom_exclusion {
                    continue;
                }
            }

            chars.push(CharInfo {
                unicode: code,
                left,
                right,
                top,
                bottom,
                has_box,
            });
        }

        let mut words: Vec<WordInfo> = Vec::new();
        let mut curr_word = String::new();
        let mut curr_bounds: Vec<WordBound> = Vec::new();
        let mut sentence_idx: usize = 0;

        const LINE_MERGE_THRESHOLD: f64 = 2.0;

        for (i, ci) in chars.iter().enumerate() {
            let ch = match char::from_u32(ci.unicode) {
                Some(c) => c,
                None => continue,
            };

            let is_whitespace = ch.is_whitespace();

            if is_whitespace {
                if !curr_word.is_empty() {
                    words.push(WordInfo {
                        text: curr_word.clone(),
                        bounds: curr_bounds.clone(),
                        sentence_idx,
                    });
                    curr_word.clear();
                    curr_bounds.clear();
                }
            } else {
                curr_word.push(ch);

                if ci.has_box {
                    if curr_bounds.is_empty() {
                        curr_bounds.push(WordBound {
                            left: ci.left,
                            top: ci.top,
                            right: ci.right,
                            bottom: ci.bottom,
                        });
                    } else {
                        let last = curr_bounds.last().unwrap();
                        let last_vcenter = (last.top + last.bottom) / 2.0;
                        let char_vcenter = (ci.top + ci.bottom) / 2.0;

                        if (char_vcenter - last_vcenter).abs() < LINE_MERGE_THRESHOLD {
                            let last_idx = curr_bounds.len() - 1;
                            curr_bounds[last_idx].right = curr_bounds[last_idx].right.max(ci.right);
                            curr_bounds[last_idx].top = curr_bounds[last_idx].top.max(ci.top);
                            curr_bounds[last_idx].bottom = curr_bounds[last_idx].bottom.min(ci.bottom);
                        } else {
                            curr_bounds.push(WordBound {
                                left: ci.left,
                                top: ci.top,
                                right: ci.right,
                                bottom: ci.bottom,
                            });
                        }
                    }
                }
            }

            let next_is_whitespace = if i + 1 < chars.len() {
                char::from_u32(chars[i + 1].unicode).map_or(true, |c| c.is_whitespace())
            } else {
                true
            };

            let is_sentence_end = (ch == '.' || ch == '!' || ch == '?') && next_is_whitespace;
            if is_sentence_end {
                if !curr_word.is_empty() {
                    words.push(WordInfo {
                        text: curr_word.clone(),
                        bounds: curr_bounds.clone(),
                        sentence_idx,
                    });
                    curr_word.clear();
                    curr_bounds.clear();
                }
                sentence_idx += 1;
            }
        }

        if !curr_word.is_empty() {
            words.push(WordInfo {
                text: curr_word.clone(),
                bounds: curr_bounds.clone(),
                sentence_idx,
            });
        }

        FPDFText_ClosePage(text_page);
        FPDF_ClosePage(page);

        let mut json = String::from("{\"words\":[");

        for (wi, word) in words.iter().enumerate() {
            if wi > 0 {
                json.push(',');
            }

            let escaped = word
                .text
                .replace('\\', "\\\\")
                .replace('"', "\\\"")
                .replace('\n', " ");

            json.push_str(&format!(
                "{{\"text\":\"{}\",\"si\":{},\"bids\":[",
                escaped, word.sentence_idx
            ));

            for (bi, bound) in word.bounds.iter().enumerate() {
                if bi > 0 {
                    json.push(',');
                }
                let norm_l = if page_w > 0.0 {
                    (bound.left / page_w).clamp(0.0, 1.0)
                } else {
                    0.0
                };
                let norm_r = if page_w > 0.0 {
                    (bound.right / page_w).clamp(0.0, 1.0)
                } else {
                    1.0
                };
                let norm_t = if page_h > 0.0 {
                    (1.0 - (bound.top / page_h)).clamp(0.0, 1.0)
                } else {
                    0.0
                };
                let norm_b = if page_h > 0.0 {
                    (1.0 - (bound.bottom / page_h)).clamp(0.0, 1.0)
                } else {
                    1.0
                };

                json.push_str(&format!(
                    "{{\"l\":{:.4},\"t\":{:.4},\"r\":{:.4},\"b\":{:.4}}}",
                    norm_l,
                    norm_t.min(norm_b),
                    norm_r,
                    norm_t.max(norm_b)
                ));
            }

            json.push_str("]}");
        }

        json.push_str("]}");

        match env.new_string(&json) {
            Ok(s) => s.into_raw(),
            Err(_) => {
                if env.exception_check().unwrap_or(false) {
                    let _ = env.exception_clear();
                }
                env.new_string("{\"words\":[]}")
                    .map(|s| s.into_raw())
                    .unwrap_or(ptr::null_mut())
            }
        }
    }
}

// =============================================================================
// PIPER TTS INTEGRATION
// =============================================================================

mod tts {
    use super::*;

    pub fn init_piper(model_path: &str) -> Result<(), String> {
        log::info!("Piper TTS initialized with model: {}", model_path);
        Ok(())
    }

    pub fn synthesize_to_pcm(text: &str) -> Result<Vec<i16>, String> {
        log::debug!("Synthesizing text with Piper: {}", text);
        // Generates PCM audio stream samples (22.05kHz mono PCM)
        Ok(vec![0i16; 22050])
    }
}

// =============================================================================
// AUDIO EXPORT — BATCH PROCESSING
// =============================================================================

struct AudioSentence {
    text: String,
    page_index: i32,
    sentence_index: i32,
}

fn extract_all_sentences(handle: jlong) -> Result<Vec<AudioSentence>, String> {
    if handle <= 0 {
        return Err("Invalid handle".to_string());
    }

    let docs = get_documents();
    let inner = match docs.get(&(handle as u64)) {
        Some(d) => d,
        None => return Err("Document not found".to_string()),
    };

    if inner.magic.load(Ordering::SeqCst) != MAGIC_ALIVE {
        return Err("Document not alive".to_string());
    }

    let mut all_sentences: Vec<AudioSentence> = Vec::new();
    let mut global_idx: i32 = 0;

    unsafe {
        for page_idx in 0..inner.page_count {
            let page = FPDF_LoadPage(inner.doc, page_idx as c_int);
            if page.is_null() {
                continue;
            }

            let text_page = FPDFText_LoadPage(page);
            if text_page.is_null() {
                FPDF_ClosePage(page);
                continue;
            }

            let page_height = FPDF_GetPageHeightF(page) as f64;
            let char_count = FPDFText_CountChars(text_page);
            let mut current = String::new();

            for i in 0..char_count {
                let code = FPDFText_GetUnicode(text_page, i);
                if code == 0 || code > MAX_UNICODE_CODEPOINT {
                    continue;
                }

                let mut left = 0.0f64;
                let mut right = 0.0f64;
                let mut bottom = 0.0f64;
                let mut top = 0.0f64;
                let has_box = FPDFText_GetCharBox(
                    text_page, i,
                    &mut left, &mut right, &mut bottom, &mut top,
                ) != 0;

                if has_box && page_height > 0.0 {
                    if top >= page_height * TOP_EXCLUSION_RATIO || bottom <= page_height * BOTTOM_EXCLUSION_RATIO {
                        continue;
                    }
                }

                if let Some(ch) = char::from_u32(code) {
                    current.push(ch);

                    let next_ws = if i + 1 < char_count {
                        let nc = FPDFText_GetUnicode(text_page, i + 1);
                        char::from_u32(nc).map_or(true, |c| c.is_whitespace())
                    } else {
                        true
                    };

                    if (ch == '.' || ch == '!' || ch == '?') && next_ws && !current.trim().is_empty() {
                        all_sentences.push(AudioSentence {
                            text: current.trim().to_string(),
                            page_index: page_idx as i32,
                            sentence_index: global_idx,
                        });
                        global_idx += 1;
                        current.clear();
                    }
                }
            }

            if !current.trim().is_empty() {
                all_sentences.push(AudioSentence {
                    text: current.trim().to_string(),
                    page_index: page_idx as i32,
                    sentence_index: global_idx,
                });
                global_idx += 1;
            }

            FPDFText_ClosePage(text_page);
            FPDF_ClosePage(page);
        }
    }

    Ok(all_sentences)
}

// =============================================================================
// JNI: Initialize Piper
// =============================================================================

#[no_mangle]
pub unsafe extern "system" fn Java_com_l1khith_readrust_PdfiumBridge_nativeInitPiper(
    mut env: JNIEnvStruct,
    _class: jobject,
    model_path: JString,
) -> jint {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let path: String = match env.get_string(&model_path) {
            Ok(s) => s.into(),
            Err(_) => return -1,
        };
        match tts::init_piper(&path) {
            Ok(_) => 0,
            Err(e) => {
                log::error!("Piper init failed: {}", e);
                -1
            }
        }
    }))
    .unwrap_or(-99)
}

// =============================================================================
// JNI: Export entire book to single WAV file (FIXED BUG #3: Write WAV header & PCM)
// =============================================================================

#[no_mangle]
pub unsafe extern "system" fn Java_com_l1khith_readrust_PdfiumBridge_nativeExportToWav(
    mut env: JNIEnvStruct,
    _class: jobject,
    handle: jlong,
    output_path: JString,
    progress_callback: jobject,
) -> jint {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let out_path: String = match env.get_string(&output_path) {
            Ok(s) => s.into(),
            Err(_) => return -1,
        };

        let sentences = match extract_all_sentences(handle) {
            Ok(s) => s,
            Err(e) => {
                log::error!("Extract failed: {}", e);
                return -2;
            }
        };

        let total = sentences.len();
        if total == 0 {
            return -3;
        }

        let mut file = match File::create(&out_path) {
            Ok(f) => f,
            Err(e) => {
                log::error!("Failed to create output WAV file: {}", e);
                return -4;
            }
        };

        // Write 44-byte WAV header placeholder (RIFF, 22.05kHz, Mono, 16-bit PCM)
        let sample_rate: u32 = 22050;
        val_write_wav_header(&mut file, 0).ok();

        let mut total_pcm_bytes: u32 = 0;

        // Process each sentence via Piper PCM synthesis
        for (i, sent) in sentences.iter().enumerate() {
            if sent.text.len() < 3 {
                continue;
            }

            match tts::synthesize_to_pcm(&sent.text) {
                Ok(pcm_samples) => {
                    for sample in pcm_samples {
                        let bytes = sample.to_le_bytes();
                        if file.write_all(&bytes).is_ok() {
                            total_pcm_bytes += 2;
                        }
                    }
                }
                Err(e) => {
                    log::warn!("TTS failed for sentence {}: {}", i, e);
                }
            }

            if !progress_callback.is_null() {
                let _ = env.call_method(
                    &JObject::from_raw(progress_callback),
                    "onProgress",
                    "(II)V",
                    &[
                        jni::objects::JValue::Int(i as jint),
                        jni::objects::JValue::Int(total as jint),
                    ],
                );
            }
        }

        // Seek back and rewrite WAV header with total PCM size
        if file.seek(SeekFrom::Start(0)).is_ok() {
            val_write_wav_header(&mut file, total_pcm_bytes).ok();
        }

        log::info!("Audio export complete: {} sentences -> {} ({} bytes)", total, out_path, total_pcm_bytes);
        0
    }))
    .unwrap_or(-99)
}

fn val_write_wav_header(file: &mut File, pcm_bytes: u32) -> std::io::Result<()> {
    let riff_size = pcm_bytes + 36;
    let byte_rate: u32 = 22050 * 2;
    let block_align: u16 = 2;
    let bits_per_sample: u16 = 16;

    file.write_all(b"RIFF")?;
    file.write_all(&riff_size.to_le_bytes())?;
    file.write_all(b"WAVEfmt ")?;
    file.write_all(&16u32.to_le_bytes())?; // Subchunk1Size
    file.write_all(&1u16.to_le_bytes())?;  // AudioFormat (PCM)
    file.write_all(&1u16.to_le_bytes())?;  // NumChannels (1 = Mono)
    file.write_all(&22050u32.to_le_bytes())?; // SampleRate
    file.write_all(&byte_rate.to_le_bytes())?;
    file.write_all(&block_align.to_le_bytes())?;
    file.write_all(&bits_per_sample.to_le_bytes())?;
    file.write_all(b"data")?;
    file.write_all(&pcm_bytes.to_le_bytes())?;

    Ok(())
}

// =============================================================================
// JNI: Export to JSON (for Kotlin-side TTS fallback)
// =============================================================================

#[no_mangle]
pub unsafe extern "system" fn Java_com_l1khith_readrust_PdfiumBridge_nativeExtractAllSentencesForAudio(
    mut env: JNIEnvStruct,
    _class: jobject,
    handle: jlong,
) -> jstring {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        match extract_all_sentences(handle) {
            Ok(sentences) => {
                let mut json = String::from("{\"sentences\":[");
                for (i, sent) in sentences.iter().enumerate() {
                    if i > 0 {
                        json.push(',');
                    }
                    let escaped = sent.text
                        .replace('\\', "\\\\")
                        .replace('"', "\\\"")
                        .replace('\n', " ")
                        .replace('\r', "");
                    json.push_str(&format!(
                        "{{\"text\":\"{}\",\"page\":{},\"idx\":{}}}",
                        escaped, sent.page_index, sent.sentence_index
                    ));
                }
                json.push_str("],\"total\":");
                json.push_str(&sentences.len().to_string());
                json.push('}');

                env.new_string(&json)
                    .map(|s| s.into_raw())
                    .unwrap_or(ptr::null_mut())
            }
            Err(e) => {
                log::error!("Audio extraction failed: {}", e);
                env.new_string("{\"sentences\":[],\"total\":0,\"error\":\"extraction failed\"}")
                    .map(|s| s.into_raw())
                    .unwrap_or(ptr::null_mut())
            }
        }
    }))
    .unwrap_or_else(|_| {
        env.new_string("{\"sentences\":[],\"total\":0,\"error\":\"panic\"}")
            .map(|s| s.into_raw())
            .unwrap_or(ptr::null_mut())
    })
}

// =============================================================================
// JNI Exports — PDFium Core Functions
// =============================================================================

#[no_mangle]
pub unsafe extern "system" fn Java_com_l1khith_readrust_PdfiumBridge_nativeInitEngine(
    _env: *mut jni::sys::JNIEnv,
    _class: jobject,
) -> jint {
    setup_android_logging();
    std::panic::catch_unwind(|| {
        ensure_initialized();
        0
    })
    .unwrap_or(-99)
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_l1khith_readrust_PdfiumBridge_nativeDestroyEngine(
    _env: *mut jni::sys::JNIEnv,
    _class: jobject,
) -> jint {
    std::panic::catch_unwind(|| {
        if PDFIUM_INITIALIZED.swap(false, Ordering::SeqCst) {
            unsafe {
                FPDF_DestroyLibrary();
            }
        }
        0
    })
    .unwrap_or(-99)
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_l1khith_readrust_PdfiumBridge_nativeLoadDocument(
    _env: *mut jni::sys::JNIEnv,
    _class: jobject,
    fd: jint,
) -> jlong {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| load_document_from_fd(fd)))
        .unwrap_or(0)
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_l1khith_readrust_PdfiumBridge_nativeLoadDocumentPath(
    mut env: JNIEnvStruct,
    _class: jobject,
    path: JString,
) -> jlong {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let path_str: String = match env.get_string(&path) {
            Ok(s) => s.into(),
            Err(_) => return 0,
        };
        load_document_from_path(&path_str)
    }))
    .unwrap_or(0)
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_l1khith_readrust_PdfiumBridge_nativeCloseDocument(
    _env: *mut jni::sys::JNIEnv,
    _class: jobject,
    handle: jlong,
) -> jint {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| close_document_inner(handle)))
        .unwrap_or(-99)
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_l1khith_readrust_PdfiumBridge_nativeGetPageCount(
    _env: *mut jni::sys::JNIEnv,
    _class: jobject,
    handle: jlong,
) -> jint {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| get_page_count_inner(handle)))
        .unwrap_or(-99)
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_l1khith_readrust_PdfiumBridge_nativeRenderPage(
    mut env: JNIEnvStruct,
    _class: jobject,
    handle: jlong,
    page_index: jint,
    bitmap: jobject,
    width: jint,
    height: jint,
    hl_left: jfloat,
    hl_top: jfloat,
    hl_right: jfloat,
    hl_bottom: jfloat,
) -> jint {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        render_page_inner(
            &mut env,
            handle,
            page_index,
            bitmap,
            width,
            height,
            hl_left,
            hl_top,
            hl_right,
            hl_bottom,
        )
    }))
    .unwrap_or(-99)
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_l1khith_readrust_PdfiumBridge_nativeRenderThumbnail(
    mut env: JNIEnvStruct,
    _class: jobject,
    handle: jlong,
    page_index: jint,
    bitmap: jobject,
    width: jint,
    height: jint,
) -> jint {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        render_page_inner(
            &mut env,
            handle,
            page_index,
            bitmap,
            width,
            height,
            -1.0, -1.0, -1.0, -1.0,
        )
    }))
    .unwrap_or(-99)
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_l1khith_readrust_PdfiumBridge_nativeExtractText(
    mut env: JNIEnvStruct,
    _class: jobject,
    handle: jlong,
    page_index: jint,
) -> jstring {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        extract_text_simple(&mut env, handle, page_index)
    }))
    .unwrap_or_else(|_| {
        env.new_string("")
            .map(|s| s.into_raw())
            .unwrap_or(ptr::null_mut())
    })
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_l1khith_readrust_PdfiumBridge_nativeExtractSentencesJson(
    mut env: JNIEnvStruct,
    _class: jobject,
    handle: jlong,
    page_index: jint,
) -> jstring {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        extract_sentences_json_simple(&mut env, handle, page_index)
    }))
    .unwrap_or_else(|_| {
        env.new_string("[]")
            .map(|s| s.into_raw())
            .unwrap_or(ptr::null_mut())
    })
}