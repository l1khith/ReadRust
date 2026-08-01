#![allow(non_camel_case_types)]

use std::ffi::{c_char, c_int, c_uint, c_void};
use std::sync::atomic::Ordering;

// ═══════════════════════════════════════════════════════════════════════════
// PDFium C API FFI Declarations
// ═══════════════════════════════════════════════════════════════════════════

pub type FPDF_DOCUMENT = *mut c_void;
pub type FPDF_PAGE = *mut c_void;
pub type FPDF_TEXTPAGE = *mut c_void;
pub type FPDF_BITMAP = *mut c_void;

pub const FPDFBitmap_BGRA: c_int = 4;
pub const FPDF_ANNOT: c_int = 0x01;

#[link(name = "pdfium")]
extern "C" {
    fn FPDF_InitLibrary();
    fn FPDF_DestroyLibrary();
    fn FPDF_GetLastError() -> c_uint;

    fn FPDF_LoadMemDocument64(
        data_buf: *const c_void,
        size: usize,
        password: *const c_char,
    ) -> FPDF_DOCUMENT;
    fn FPDF_GetPageCount(document: FPDF_DOCUMENT) -> c_int;
    fn FPDF_CloseDocument(document: FPDF_DOCUMENT);

    fn FPDF_LoadPage(document: FPDF_DOCUMENT, page_index: c_int) -> FPDF_PAGE;
    fn FPDF_GetPageWidth(page: FPDF_PAGE) -> f64;
    fn FPDF_GetPageHeight(page: FPDF_PAGE) -> f64;
    fn FPDF_ClosePage(page: FPDF_PAGE);

    fn FPDFBitmap_CreateEx(
        width: c_int,
        height: c_int,
        format: c_int,
        first_scan: *mut c_void,
        stride: c_int,
    ) -> FPDF_BITMAP;
    fn FPDFBitmap_FillRect(
        bitmap: FPDF_BITMAP,
        left: c_int,
        top: c_int,
        width: c_int,
        height: c_int,
        color: c_uint,
    );
    fn FPDFBitmap_Destroy(bitmap: FPDF_BITMAP);

    fn FPDF_RenderPageBitmap(
        bitmap: FPDF_BITMAP,
        page: FPDF_PAGE,
        start_x: c_int,
        start_y: c_int,
        size_x: c_int,
        size_y: c_int,
        rotate: c_int,
        flags: c_int,
    );

    fn FPDFText_LoadPage(page: FPDF_PAGE) -> FPDF_TEXTPAGE;
    fn FPDFText_ClosePage(text_page: FPDF_TEXTPAGE);
    fn FPDFText_CountChars(text_page: FPDF_TEXTPAGE) -> c_int;
    fn FPDFText_GetUnicode(text_page: FPDF_TEXTPAGE, index: c_int) -> c_uint;
    fn FPDFText_GetCharBox(
        text_page: FPDF_TEXTPAGE,
        index: c_int,
        left: *mut f64,
        right: *mut f64,
        bottom: *mut f64,
        top: *mut f64,
    ) -> c_int;
}

// ═══════════════════════════════════════════════════════════════════════════
// Android Bitmap & Log FFI
// ═══════════════════════════════════════════════════════════════════════════

#[repr(C)]
pub struct AndroidBitmapInfo {
    pub width: u32,
    pub height: u32,
    pub stride: u32,
    pub format: c_int,
    pub flags: u32,
}

pub const ANDROID_BITMAP_RESULT_SUCCESS: c_int = 0;

#[allow(dead_code)]
pub const ANDROID_BITMAP_FORMAT_RGBA_8888: c_int = 1;

#[link(name = "jnigraphics")]
extern "C" {
    fn AndroidBitmap_getInfo(
        env: *mut c_void,
        bitmap: *mut c_void,
        info: *mut AndroidBitmapInfo,
    ) -> c_int;
    fn AndroidBitmap_lockPixels(
        env: *mut c_void,
        bitmap: *mut c_void,
        pixels: *mut *mut c_void,
    ) -> c_int;
    fn AndroidBitmap_unlockPixels(env: *mut c_void, bitmap: *mut c_void) -> c_int;
}

#[link(name = "log")]
extern "C" {
    fn __android_log_write(prio: c_int, tag: *const c_char, text: *const c_char);
}

const ANDROID_LOG_INFO: c_int = 4;
const ANDROID_LOG_ERROR: c_int = 6;

fn log_info(msg: &str) {
    let tag = b"PdfiumBridge\0";
    let sanitized = msg.replace('\0', "?");
    if let Ok(cmsg) = std::ffi::CString::new(sanitized) {
        unsafe {
            __android_log_write(
                ANDROID_LOG_INFO,
                tag.as_ptr() as *const c_char,
                cmsg.as_ptr(),
            );
        }
    }
}

fn log_error(msg: &str) {
    let tag = b"PdfiumBridge\0";
    let sanitized = msg.replace('\0', "?");
    if let Ok(cmsg) = std::ffi::CString::new(sanitized) {
        unsafe {
            __android_log_write(
                ANDROID_LOG_ERROR,
                tag.as_ptr() as *const c_char,
                cmsg.as_ptr(),
            );
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Native State Management
// ═══════════════════════════════════════════════════════════════════════════

pub struct PdfDocument {
    magic: std::sync::atomic::AtomicU32,
    doc: FPDF_DOCUMENT,
    mapped_data: *mut c_void,
    mapped_size: usize,
    page_count: i32,
    _fd: i32,
}

// SAFETY: PdfDocument is only accessed behind PDFIUM_ENGINE_LOCK or via atomic magic field.
unsafe impl Send for PdfDocument {}
unsafe impl Sync for PdfDocument {}

const MAGIC_ALIVE: u32 = 0x50444631; // "PDF1"
const MAGIC_DEAD: u32 = 0x00000000;

static ENGINE_REF_COUNT: std::sync::atomic::AtomicUsize =
    std::sync::atomic::AtomicUsize::new(0);
static PDFIUM_ENGINE_LOCK: std::sync::Mutex<()> = std::sync::Mutex::new(());

fn resolve_doc(doc_ptr: i64, caller: &str) -> *const PdfDocument {
    if doc_ptr == 0 {
        log_error(&format!("{}: null doc_ptr", caller));
        return std::ptr::null();
    }
    let pdf_doc = doc_ptr as *const PdfDocument;
    let is_alive =
        unsafe { (*pdf_doc).magic.load(Ordering::Acquire) == MAGIC_ALIVE };
    if !is_alive {
        log_error(&format!("{}: stale or double-freed doc_ptr", caller));
        return std::ptr::null();
    }
    pdf_doc
}

// ═══════════════════════════════════════════════════════════════════════════
// JNI Entry Points — package: com.l1khith.readrust
// ═══════════════════════════════════════════════════════════════════════════

#[no_mangle]
pub extern "C" fn Java_com_l1khith_readrust_NativePdfEngine_initEngine(
    _env: *mut c_void,
    _obj: *mut c_void,
) {
    let _guard = PDFIUM_ENGINE_LOCK
        .lock()
        .unwrap_or_else(|e| e.into_inner());
    let prev = ENGINE_REF_COUNT.fetch_add(1, Ordering::AcqRel);
    if prev > 0 {
        return;
    }
    unsafe {
        FPDF_InitLibrary();
    }
    log_info("Pdfium library initialized (Rust)");
}

#[no_mangle]
pub extern "C" fn Java_com_l1khith_readrust_NativePdfEngine_destroyEngine(
    _env: *mut c_void,
    _obj: *mut c_void,
) {
    let _guard = PDFIUM_ENGINE_LOCK
        .lock()
        .unwrap_or_else(|e| e.into_inner());
    let prev = ENGINE_REF_COUNT.fetch_sub(1, Ordering::AcqRel);
    if prev != 1 {
        if prev == 0 {
            ENGINE_REF_COUNT.store(0, Ordering::Release);
            log_error("destroyEngine: engine was not initialized");
        }
        return;
    }
    unsafe {
        FPDF_DestroyLibrary();
    }
    log_info("Pdfium library destroyed (Rust)");
}

#[no_mangle]
pub extern "C" fn Java_com_l1khith_readrust_NativePdfEngine_loadDocument(
    _env: *mut c_void,
    _obj: *mut c_void,
    fd: i32,
) -> i64 {
    let _guard = PDFIUM_ENGINE_LOCK
        .lock()
        .unwrap_or_else(|e| e.into_inner());
    if fd < 0 {
        log_error(&format!("loadDocument: invalid fd ({})", fd));
        return 0;
    }

    // dup() the fd so we own our copy — the original is owned by
    // Kotlin's ParcelFileDescriptor and will be closed on the Java side.
    // Closing the original fd triggers Android's fdsan abort.
    let owned_fd = unsafe { libc::dup(fd) };
    if owned_fd < 0 {
        log_error("loadDocument: dup(fd) failed");
        return 0;
    }

    let mut st = unsafe { std::mem::zeroed::<libc::stat>() };
    if unsafe { libc::fstat(owned_fd, &mut st) } != 0 || st.st_size <= 0 {
        log_error("loadDocument: fstat failed or file is empty");
        unsafe {
            libc::close(owned_fd);
        }
        return 0;
    }
    let file_size = st.st_size as usize;

    let data = unsafe {
        libc::mmap(
            std::ptr::null_mut(),
            file_size,
            libc::PROT_READ,
            libc::MAP_PRIVATE,
            owned_fd,
            0,
        )
    };
    if data == libc::MAP_FAILED {
        log_error("loadDocument: mmap failed");
        unsafe {
            libc::close(owned_fd);
        }
        return 0;
    }

    unsafe {
        libc::madvise(data, file_size, libc::MADV_RANDOM);
        libc::close(owned_fd); // safe — we own this dup'd fd
    }

    let doc =
        unsafe { FPDF_LoadMemDocument64(data, file_size, std::ptr::null()) };
    if doc.is_null() {
        let err = unsafe { FPDF_GetLastError() };
        log_error(&format!(
            "loadDocument: FPDF_LoadMemDocument64 failed, error={}",
            err
        ));
        unsafe {
            libc::munmap(data, file_size);
        }
        return 0;
    }

    let pages = unsafe { FPDF_GetPageCount(doc) };

    let pdf_doc = Box::into_raw(Box::new(PdfDocument {
        magic: std::sync::atomic::AtomicU32::new(MAGIC_ALIVE),
        doc,
        mapped_data: data,
        mapped_size: file_size,
        page_count: pages,
        _fd: -1,
    }));

    log_info(&format!("loadDocument: success — {} pages", pages));
    pdf_doc as i64
}

#[no_mangle]
pub extern "C" fn Java_com_l1khith_readrust_NativePdfEngine_getPageCount(
    _env: *mut c_void,
    _obj: *mut c_void,
    doc_ptr: i64,
) -> i32 {
    let pdf_doc = resolve_doc(doc_ptr, "getPageCount");
    if pdf_doc.is_null() {
        return 0;
    }
    unsafe { (*pdf_doc).page_count }
}

#[no_mangle]
pub extern "C" fn Java_com_l1khith_readrust_NativePdfEngine_renderPage(
    env: *mut c_void,
    _obj: *mut c_void,
    doc_ptr: i64,
    page_index: i32,
    bitmap: *mut c_void,
) -> u8 {
    let _guard = PDFIUM_ENGINE_LOCK
        .lock()
        .unwrap_or_else(|e| e.into_inner());
    let pdf_doc = resolve_doc(doc_ptr, "renderPage");
    if pdf_doc.is_null() || bitmap.is_null() {
        return 0;
    }

    let page_count = unsafe { (*pdf_doc).page_count };
    if page_index < 0 || page_index >= page_count {
        return 0;
    }

    let page = unsafe { FPDF_LoadPage((*pdf_doc).doc, page_index) };
    if page.is_null() {
        return 0;
    }

    let page_w = unsafe { FPDF_GetPageWidth(page) };
    let page_h = unsafe { FPDF_GetPageHeight(page) };
    if page_w <= 0.0 || page_h <= 0.0 {
        unsafe {
            FPDF_ClosePage(page);
        }
        return 0;
    }

    let mut info = unsafe { std::mem::zeroed::<AndroidBitmapInfo>() };
    if unsafe { AndroidBitmap_getInfo(env, bitmap, &mut info) }
        != ANDROID_BITMAP_RESULT_SUCCESS
    {
        unsafe {
            FPDF_ClosePage(page);
        }
        return 0;
    }

    let mut pixels: *mut c_void = std::ptr::null_mut();
    if unsafe { AndroidBitmap_lockPixels(env, bitmap, &mut pixels) }
        != ANDROID_BITMAP_RESULT_SUCCESS
    {
        unsafe {
            FPDF_ClosePage(page);
        }
        return 0;
    }

    let scale_x = info.width as f64 / page_w;
    let scale_y = info.height as f64 / page_h;
    let scale = scale_x.min(scale_y);

    let render_w = (page_w * scale) as i32;
    let render_h = (page_h * scale) as i32;
    let offset_x = (info.width as i32 - render_w) / 2;
    let offset_y = (info.height as i32 - render_h) / 2;

    let fpdf_bitmap = unsafe {
        FPDFBitmap_CreateEx(
            info.width as i32,
            info.height as i32,
            FPDFBitmap_BGRA,
            pixels,
            info.stride as i32,
        )
    };

    if !fpdf_bitmap.is_null() {
        unsafe {
            FPDFBitmap_FillRect(
                fpdf_bitmap,
                0,
                0,
                info.width as i32,
                info.height as i32,
                0xFFFFFFFF,
            );
            FPDF_RenderPageBitmap(
                fpdf_bitmap,
                page,
                offset_x,
                offset_y,
                render_w,
                render_h,
                0,
                FPDF_ANNOT,
            );
            FPDFBitmap_Destroy(fpdf_bitmap);
        }
    }

    unsafe {
        AndroidBitmap_unlockPixels(env, bitmap);
        FPDF_ClosePage(page);
    }

    1
}

#[no_mangle]
pub extern "C" fn Java_com_l1khith_readrust_NativePdfEngine_extractText(
    env: *mut c_void,
    _obj: *mut c_void,
    doc_ptr: i64,
    page_index: i32,
) -> *mut c_void {
    let _guard = PDFIUM_ENGINE_LOCK
        .lock()
        .unwrap_or_else(|e| e.into_inner());
    let pdf_doc = resolve_doc(doc_ptr, "extractText");
    if pdf_doc.is_null() {
        return new_jstring(env, "");
    }

    let page_count = unsafe { (*pdf_doc).page_count };
    if page_index < 0 || page_index >= page_count {
        return new_jstring(env, "");
    }

    let page = unsafe { FPDF_LoadPage((*pdf_doc).doc, page_index) };
    if page.is_null() {
        return new_jstring(env, "");
    }

    let text_page = unsafe { FPDFText_LoadPage(page) };
    if text_page.is_null() {
        unsafe {
            FPDF_ClosePage(page);
        }
        return new_jstring(env, "");
    }

    let page_height = unsafe { FPDF_GetPageHeight(page) };
    let bottom_exclusion = page_height * 0.08;
    let top_exclusion = page_height * 0.92;

    let char_count = unsafe { FPDFText_CountChars(text_page) };
    let mut utf16_units: Vec<u16> = Vec::with_capacity(char_count as usize);

    for i in 0..char_count {
        let mut left = 0.0f64;
        let mut right = 0.0f64;
        let mut bottom = 0.0f64;
        let mut top = 0.0f64;

        let has_box = unsafe {
            FPDFText_GetCharBox(
                text_page,
                i,
                &mut left,
                &mut right,
                &mut bottom,
                &mut top,
            ) != 0
        };

        // Skip characters in header/footer exclusion zones
        if has_box && (top >= top_exclusion || bottom <= bottom_exclusion) {
            continue;
        }

        let code = unsafe { FPDFText_GetUnicode(text_page, i) };
        if code != 0 {
            if let Some(ch) = char::from_u32(code) {
                let mut buf = [0u16; 2];
                let encoded = ch.encode_utf16(&mut buf);
                for unit in encoded.iter() {
                    utf16_units.push(*unit);
                }
            }
        }
    }

    unsafe {
        FPDFText_ClosePage(text_page);
        FPDF_ClosePage(page);
    }

    let text = String::from_utf16_lossy(&utf16_units);
    new_jstring(env, &text)
}

#[no_mangle]
pub extern "C" fn Java_com_l1khith_readrust_NativePdfEngine_closeDocument(
    _env: *mut c_void,
    _obj: *mut c_void,
    doc_ptr: i64,
) {
    let _guard = PDFIUM_ENGINE_LOCK
        .lock()
        .unwrap_or_else(|e| e.into_inner());
    let pdf_doc = resolve_doc(doc_ptr, "closeDocument");
    if pdf_doc.is_null() {
        return;
    }

    let doc_handle = unsafe { (*pdf_doc).doc };
    let mapped_data = unsafe { (*pdf_doc).mapped_data };
    let mapped_size = unsafe { (*pdf_doc).mapped_size };

    unsafe { (*pdf_doc).magic.store(MAGIC_DEAD, Ordering::Release) };

    unsafe {
        FPDF_CloseDocument(doc_handle);
        libc::madvise(mapped_data, mapped_size, libc::MADV_DONTNEED);
        libc::munmap(mapped_data, mapped_size);
        drop(Box::from_raw(doc_ptr as *mut PdfDocument));
    }

    log_info("closeDocument: released");
}

// ═══════════════════════════════════════════════════════════════════════════
// JNI String Helper
// ═══════════════════════════════════════════════════════════════════════════

/// Creates a Java String (jstring) from a Rust &str via the JNI NewString function.
/// The JNIEnv vtable index for NewString is 163.
fn new_jstring(env: *mut c_void, text: &str) -> *mut c_void {
    unsafe {
        let env_ptr = env as *mut *mut *const c_void;
        if env_ptr.is_null() || (*env_ptr).is_null() {
            return std::ptr::null_mut();
        }
        let vtable = **env_ptr;
        let fn_ptr = *(vtable as *const *const c_void).add(163);
        if fn_ptr.is_null() {
            return std::ptr::null_mut();
        }
        let new_string_fn: extern "C" fn(
            *mut c_void,
            *const u16,
            i32,
        ) -> *mut c_void = std::mem::transmute(fn_ptr);

        let utf16: Vec<u16> = text.encode_utf16().collect();
        new_string_fn(env, utf16.as_ptr(), utf16.len() as i32)
    }
}
