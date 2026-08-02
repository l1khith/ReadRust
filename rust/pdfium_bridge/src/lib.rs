use jni::objects::JString;
use jni::sys::{jfloat, jint, jlong, jobject, jstring, JNIEnv};
use jni::JNIEnv as JNIEnvStruct;
use std::collections::HashMap;
use std::ffi::{c_char, c_int, c_void, CString};
use std::sync::atomic::{AtomicBool, AtomicU32, AtomicU64, Ordering};
use std::sync::Mutex;

const MAGIC_ALIVE: u32 = 0x50444631;
const MAGIC_DEAD: u32 = 0xDEAD;

pub type FPDF_DOCUMENT = *mut c_void;
pub type FPDF_PAGE = *mut c_void;
pub type FPDF_BITMAP = *mut c_void;
pub type FPDF_TEXTPAGE = *mut c_void;
pub type FPDF_STRING = *const c_char;

pub const FPDFBitmap_BGRA: i32 = 4;

extern "C" {
    pub fn FPDF_InitLibrary();
    pub fn FPDF_DestroyLibrary();
    pub fn FPDF_LoadMemDocument64(
        data_buf: *const c_void,
        size: usize,
        password: FPDF_STRING,
    ) -> FPDF_DOCUMENT;
    pub fn FPDF_CloseDocument(document: FPDF_DOCUMENT);
    pub fn FPDF_GetPageCount(document: FPDF_DOCUMENT) -> c_int;
    pub fn FPDF_LoadPage(document: FPDF_DOCUMENT, page_index: c_int) -> FPDF_PAGE;
    pub fn FPDF_ClosePage(page: FPDF_PAGE);

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
    pub fn FPDF_RenderPageBitmap(
        bitmap: FPDF_BITMAP,
        page: FPDF_PAGE,
        start_x: c_int,
        start_y: c_int,
        size_x: c_int,
        size_y: c_int,
        rotate: c_int,
        flags: c_int,
    );

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
    pub fn FPDF_GetPageWidthF(page: FPDF_PAGE) -> f32;
    pub fn FPDF_GetPageHeightF(page: FPDF_PAGE) -> f32;
}

#[repr(C)]
#[derive(Debug, Default, Copy, Clone)]
pub struct AndroidBitmapInfo {
    pub width: u32,
    pub height: u32,
    pub stride: u32,
    pub format: i32,
    pub flags: u32,
}

extern "C" {
    pub fn AndroidBitmap_getInfo(
        env: *mut JNIEnv,
        bitmap: jobject,
        info: *mut AndroidBitmapInfo,
    ) -> c_int;
    pub fn AndroidBitmap_lockPixels(
        env: *mut JNIEnv,
        bitmap: jobject,
        addrptr: *mut *mut c_void,
    ) -> c_int;
    pub fn AndroidBitmap_unlockPixels(env: *mut JNIEnv, bitmap: jobject) -> c_int;
}

struct BitmapGuard {
    env: *mut JNIEnv,
    bitmap: jobject,
}

impl Drop for BitmapGuard {
    fn drop(&mut self) {
        unsafe {
            AndroidBitmap_unlockPixels(self.env, self.bitmap);
        }
    }
}

struct PdfDocumentInner {
    magic: AtomicU32,
    doc: FPDF_DOCUMENT,
    mapped_data: *mut c_void,
    mapped_size: usize,
    page_count: i32,
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

fn load_document_from_fd(fd: jint) -> jlong {
    ensure_initialized();
    if fd < 0 {
        return 0;
    }
    unsafe {
        let mut stat: libc::stat = std::mem::zeroed();
        if libc::fstat(fd, &mut stat) != 0 || stat.st_size <= 0 {
            return 0;
        }
        let size = stat.st_size as usize;
        let mapped_ptr = libc::mmap(
            std::ptr::null_mut(),
            size,
            libc::PROT_READ,
            libc::MAP_PRIVATE,
            fd,
            0,
        );
        if mapped_ptr == libc::MAP_FAILED {
            return 0;
        }

        let doc = FPDF_LoadMemDocument64(mapped_ptr, size, std::ptr::null());
        if doc.is_null() {
            libc::munmap(mapped_ptr, size);
            return 0;
        }

        let page_count = FPDF_GetPageCount(doc) as i32;
        let id = NEXT_ID.fetch_add(1, Ordering::SeqCst);
        let inner = PdfDocumentInner {
            magic: AtomicU32::new(MAGIC_ALIVE),
            doc,
            mapped_data: mapped_ptr,
            mapped_size: size,
            page_count,
        };

        DOCUMENTS.lock().unwrap().insert(id, inner);
        id as jlong
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
    let mut docs = DOCUMENTS.lock().unwrap();
    if let Some(inner) = docs.remove(&(handle as u64)) {
        if inner.magic.swap(MAGIC_DEAD, Ordering::SeqCst) == MAGIC_ALIVE {
            unsafe {
                FPDF_CloseDocument(inner.doc);
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
    let docs = DOCUMENTS.lock().unwrap();
    if let Some(inner) = docs.get(&(handle as u64)) {
        if inner.magic.load(Ordering::SeqCst) == MAGIC_ALIVE {
            return inner.page_count;
        }
    }
    -2
}

fn render_page_inner(
    env: &mut JNIEnvStruct,
    handle: jlong,
    page_index: jint,
    bitmap_obj: jobject,
    width: jint,
    height: jint,
    hl_left: f32,
    hl_top: f32,
    hl_right: f32,
    hl_bottom: f32,
) -> jint {
    if handle <= 0 || bitmap_obj.is_null() {
        return -2;
    }

    let docs = DOCUMENTS.lock().unwrap();
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

    let raw_env = env.get_native_interface();
    let mut info: AndroidBitmapInfo = unsafe { std::mem::zeroed() };
    if unsafe { AndroidBitmap_getInfo(raw_env, bitmap_obj, &mut info) } != 0 {
        return -1;
    }

    let mut pixels_ptr: *mut c_void = std::ptr::null_mut();
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

        // Fill background with solid white (0xFFFFFFFF)
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

        // Render page with annotations & letterboxing offset
        FPDF_RenderPageBitmap(
            fpdf_bitmap,
            page,
            start_x,
            start_y,
            size_x,
            size_y,
            0,
            0x01, // FPDF_ANNOT
        );

        FPDF_ClosePage(page);
        FPDFBitmap_Destroy(fpdf_bitmap);

        // Swap B and R channels for Android ARGB_8888 [R, G, B, A] & force Alpha to 255
        let total_bytes = (info.stride * height as u32) as usize;
        let slice = std::slice::from_raw_parts_mut(pixels_ptr as *mut u8, total_bytes);
        for chunk in slice.chunks_exact_mut(4) {
            chunk.swap(0, 2);
            chunk[3] = 255;
        }

        // ── Native Rust Translucent Blue Highlight Blending ──
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

// ---------------------------------------------------------------------------
// Text extraction -- mirrors C++ bridge + produces valid JSON bounds for Kotlin
// ---------------------------------------------------------------------------

const TOP_EXCLUSION_RATIO: f64 = 0.92;      // exclude top 8% (header)
const BOTTOM_EXCLUSION_RATIO: f64 = 0.08;   // exclude bottom 8% (footer)
const MAX_UNICODE_CODEPOINT: u32 = 0x10FFFF;

fn extract_text_simple(
    env: &mut JNIEnvStruct,
    handle: jlong,
    page_index: jint,
) -> jstring {
    if handle <= 0 {
        return env.new_string("")
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut());
    }

    let docs = DOCUMENTS.lock().unwrap();
    let inner = match docs.get(&(handle as u64)) {
        Some(d) => d,
        None => {
            return env.new_string("")
                .map(|s| s.into_raw())
                .unwrap_or(std::ptr::null_mut());
        }
    };

    if inner.magic.load(Ordering::SeqCst) != MAGIC_ALIVE {
        return env.new_string("")
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut());
    }

    if page_index < 0 || page_index >= inner.page_count {
        return env.new_string("")
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut());
    }

    unsafe {
        let page = FPDF_LoadPage(inner.doc, page_index as c_int);
        if page.is_null() {
            return env.new_string("")
                .map(|s| s.into_raw())
                .unwrap_or(std::ptr::null_mut());
        }

        let text_page = FPDFText_LoadPage(page);
        if text_page.is_null() {
            FPDF_ClosePage(page);
            return env.new_string("")
                .map(|s| s.into_raw())
                .unwrap_or(std::ptr::null_mut());
        }

        let page_height = FPDF_GetPageHeightF(page) as f64;
        let char_count = FPDFText_CountChars(text_page);

        let mut text = String::with_capacity(char_count as usize);

        for i in 0..char_count {
            let code = FPDFText_GetUnicode(text_page, i);
            if code == 0 || code > MAX_UNICODE_CODEPOINT {
                continue;
            }

            let mut left = 0.0;
            let mut right = 0.0;
            let mut bottom = 0.0;
            let mut top = 0.0;
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
                    .unwrap_or(std::ptr::null_mut())
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
            .unwrap_or(std::ptr::null_mut());
    }

    let docs = DOCUMENTS.lock().unwrap();
    let inner = match docs.get(&(handle as u64)) {
        Some(d) => d,
        None => {
            return env.new_string("[]")
                .map(|s| s.into_raw())
                .unwrap_or(std::ptr::null_mut());
        }
    };

    if inner.magic.load(Ordering::SeqCst) != MAGIC_ALIVE {
        return env.new_string("[]")
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut());
    }

    if page_index < 0 || page_index >= inner.page_count {
        return env.new_string("[]")
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut());
    }

    unsafe {
        let page = FPDF_LoadPage(inner.doc, page_index as c_int);
        if page.is_null() {
            return env.new_string("[]")
                .map(|s| s.into_raw())
                .unwrap_or(std::ptr::null_mut());
        }

        let text_page = FPDFText_LoadPage(page);
        if text_page.is_null() {
            FPDF_ClosePage(page);
            return env.new_string("[]")
                .map(|s| s.into_raw())
                .unwrap_or(std::ptr::null_mut());
        }

        let page_w = FPDF_GetPageWidthF(page) as f64;
        let page_h = FPDF_GetPageHeightF(page) as f64;
        let char_count = FPDFText_CountChars(text_page);

        struct SentenceItem {
            text: String,
            min_l: f64,
            max_t: f64,
            max_r: f64,
            min_b: f64,
        }

        let mut sentences: Vec<SentenceItem> = Vec::new();
        let mut curr_text = String::new();
        let mut min_l: f64 = page_w;
        let mut max_t: f64 = 0.0;
        let mut max_r: f64 = 0.0;
        let mut min_b: f64 = page_h;
        let mut has_box_chars = false;

        for i in 0..char_count {
            let code = FPDFText_GetUnicode(text_page, i);
            if code == 0 || code > MAX_UNICODE_CODEPOINT {
                continue;
            }

            let mut left = 0.0;
            let mut right = 0.0;
            let mut bottom = 0.0;
            let mut top = 0.0;
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

            let Some(ch) = char::from_u32(code) else { continue };

            if has_box && (top - bottom).abs() > 0.0 {
                min_l = min_l.min(left);
                max_t = max_t.max(top);
                max_r = max_r.max(right);
                min_b = min_b.min(bottom);
                has_box_chars = true;
            }

            curr_text.push(ch);

            let next_code = if i + 1 < char_count {
                FPDFText_GetUnicode(text_page, i + 1)
            } else {
                0
            };
            let next_is_ws = next_code == 0
                || char::from_u32(next_code).map_or(true, |c| c.is_whitespace());

            let is_sentence_end = (ch == '.' || ch == '!' || ch == '?') && next_is_ws;
            if is_sentence_end {
                let trimmed = curr_text.trim();
                if !trimmed.is_empty() {
                    sentences.push(SentenceItem {
                        text: trimmed.to_string(),
                        min_l: if has_box_chars { min_l } else { 0.0 },
                        max_t: if has_box_chars { max_t } else { page_h },
                        max_r: if has_box_chars { max_r } else { page_w },
                        min_b: if has_box_chars { min_b } else { 0.0 },
                    });
                }
                curr_text.clear();
                min_l = page_w; max_t = 0.0; max_r = 0.0; min_b = page_h;
                has_box_chars = false;
            }
        }

        let trimmed = curr_text.trim();
        if !trimmed.is_empty() {
            sentences.push(SentenceItem {
                text: trimmed.to_string(),
                min_l: if has_box_chars { min_l } else { 0.0 },
                max_t: if has_box_chars { max_t } else { page_h },
                max_r: if has_box_chars { max_r } else { page_w },
                min_b: if has_box_chars { min_b } else { 0.0 },
            });
        }

        FPDFText_ClosePage(text_page);
        FPDF_ClosePage(page);

        let mut json = String::from("[");
        for (i, item) in sentences.iter().enumerate() {
            if i > 0 { json.push(','); }
            let norm_l = if page_w > 0.0 { (item.min_l / page_w).clamp(0.0, 1.0) as f32 } else { 0.0 };
            let norm_r = if page_w > 0.0 { (item.max_r / page_w).clamp(0.0, 1.0) as f32 } else { 1.0 };
            let norm_t = if page_h > 0.0 { (1.0 - (item.max_t / page_h)).clamp(0.0, 1.0) as f32 } else { 0.0 };
            let norm_b = if page_h > 0.0 { (1.0 - (item.min_b / page_h)).clamp(0.0, 1.0) as f32 } else { 1.0 };

            let escaped = item.text.replace('\\', "\\\\").replace('"', "\\\"").replace('\n', " ");
            json.push_str(&format!(
                "{{\"text\":\"{}\",\"left\":{:.4},\"top\":{:.4},\"right\":{:.4},\"bottom\":{:.4}}}",
                escaped, norm_l, norm_t.min(norm_b), norm_r, norm_t.max(norm_b)
            ));
        }
        json.push(']');

        match env.new_string(&json) {
            Ok(s) => s.into_raw(),
            Err(_) => {
                if env.exception_check().unwrap_or(false) {
                    let _ = env.exception_clear();
                }
                env.new_string("[]")
                    .map(|s| s.into_raw())
                    .unwrap_or(std::ptr::null_mut())
            }
        }
    }
}

// -----------------------------------------------------------------------------
// JNI Exports for com.l1khith.readrust.PdfiumBridge
// -----------------------------------------------------------------------------

#[no_mangle]
pub unsafe extern "system" fn Java_com_l1khith_readrust_PdfiumBridge_nativeInitEngine(
    _env: *mut JNIEnv,
    _class: jobject,
) -> jint {
    std::panic::catch_unwind(|| {
        ensure_initialized();
        0
    })
    .unwrap_or(-99)
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_l1khith_readrust_PdfiumBridge_nativeDestroyEngine(
    _env: *mut JNIEnv,
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
    _env: *mut JNIEnv,
    _class: jobject,
    fd: jint,
) -> jlong {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| load_document_from_fd(fd))).unwrap_or(0)
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
    _env: *mut JNIEnv,
    _class: jobject,
    handle: jlong,
) -> jint {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| close_document_inner(handle))).unwrap_or(-99)
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_l1khith_readrust_PdfiumBridge_nativeGetPageCount(
    _env: *mut JNIEnv,
    _class: jobject,
    handle: jlong,
) -> jint {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| get_page_count_inner(handle))).unwrap_or(-99)
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
            &mut env, handle, page_index, bitmap, width, height,
            hl_left, hl_top, hl_right, hl_bottom,
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
            &mut env, handle, page_index, bitmap, width, height,
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
            .unwrap_or(std::ptr::null_mut())
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
            .unwrap_or(std::ptr::null_mut())
    })
}