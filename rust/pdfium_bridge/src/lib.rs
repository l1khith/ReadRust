// UniFFI scaffolding
uniffi::include_scaffolding!("pdfium_bridge");

use std::ffi::{c_char, c_int, c_uint, c_void};
use std::sync::atomic::{AtomicBool, Ordering};

// ═══════════════════════════════════════════════════════════════════
// Pdfium C API FFI
// ═══════════════════════════════════════════════════════════════════

pub type FPDF_DOCUMENT = *mut c_void;
pub type FPDF_PAGE = *mut c_void;
pub type FPDF_TEXTPAGE = *mut c_void;
pub type FPDF_BITMAP = *mut c_void;

const FPDFBitmap_BGRA: c_int = 4;
const FPDF_ANNOT: c_int = 0x01;

#[link(name = "pdfium")]
extern "C" {
    fn FPDF_InitLibrary();
    fn FPDF_DestroyLibrary();
    fn FPDF_GetLastError() -> c_uint;
    fn FPDF_LoadMemDocument64(data: *const c_void, size: usize, password: *const c_char) -> FPDF_DOCUMENT;
    fn FPDF_GetPageCount(doc: FPDF_DOCUMENT) -> c_int;
    fn FPDF_CloseDocument(doc: FPDF_DOCUMENT);
    fn FPDF_LoadPage(doc: FPDF_DOCUMENT, page_index: c_int) -> FPDF_PAGE;
    fn FPDF_GetPageWidth(page: FPDF_PAGE) -> f64;
    fn FPDF_GetPageHeight(page: FPDF_PAGE) -> f64;
    fn FPDF_ClosePage(page: FPDF_PAGE);
    fn FPDFBitmap_CreateEx(width: c_int, height: c_int, format: c_int, first_scan: *mut c_void, stride: c_int) -> FPDF_BITMAP;
    fn FPDFBitmap_FillRect(bitmap: FPDF_BITMAP, left: c_int, top: c_int, width: c_int, height: c_int, color: c_uint);
    fn FPDFBitmap_Destroy(bitmap: FPDF_BITMAP);
    fn FPDF_RenderPageBitmap(bitmap: FPDF_BITMAP, page: FPDF_PAGE, start_x: c_int, start_y: c_int, size_x: c_int, size_y: c_int, rotate: c_int, flags: c_int);
    fn FPDFText_LoadPage(page: FPDF_PAGE) -> FPDF_TEXTPAGE;
    fn FPDFText_ClosePage(text_page: FPDF_TEXTPAGE);
    fn FPDFText_CountChars(text_page: FPDF_TEXTPAGE) -> c_int;
    fn FPDFText_GetUnicode(text_page: FPDF_TEXTPAGE, index: c_int) -> c_uint;
    fn FPDFText_GetCharBox(text_page: FPDF_TEXTPAGE, index: c_int, left: *mut f64, right: *mut f64, bottom: *mut f64, top: *mut f64) -> c_int;
}

// Android Bitmap JNI
#[link(name = "jnigraphics")]
extern "C" {
    fn AndroidBitmap_getInfo(env: *mut c_void, bitmap: *mut c_void, info: *mut AndroidBitmapInfo) -> c_int;
    fn AndroidBitmap_lockPixels(env: *mut c_void, bitmap: *mut c_void, pixels: *mut *mut c_void) -> c_int;
    fn AndroidBitmap_unlockPixels(env: *mut c_void, bitmap: *mut c_void) -> c_int;
}

#[repr(C)]
struct AndroidBitmapInfo {
    width: u32, height: u32, stride: u32, format: c_int, flags: u32,
}

const ANDROID_BITMAP_RESULT_SUCCESS: c_int = 0;
const ANDROID_BITMAP_FORMAT_RGBA_8888: c_int = 1;

// Android log
#[link(name = "log")]
extern "C" { fn __android_log_write(prio: c_int, tag: *const c_char, text: *const c_char); }

fn log_info(msg: &str) {
    let tag = b"PdfiumBridge\0";
    let cmsg = std::ffi::CString::new(msg).unwrap_or_default();
    unsafe { __android_log_write(4, tag.as_ptr() as *const c_char, cmsg.as_ptr()); }
}

fn log_error(msg: &str) {
    let tag = b"PdfiumBridge\0";
    let cmsg = std::ffi::CString::new(msg).unwrap_or_default();
    unsafe { __android_log_write(6, tag.as_ptr() as *const c_char, cmsg.as_ptr()); }
}

// ═══════════════════════════════════════════════════════════════════
// Error type
// ═══════════════════════════════════════════════════════════════════

#[derive(Debug, thiserror::Error)]
pub enum PdfiumError {
    #[error("Invalid file descriptor")] InvalidFd,
    #[error("File is empty or fstat failed")] FileEmpty,
    #[error("mmap failed")] MmapFailed,
    #[error("FPDF_LoadMemDocument64 failed")] LoadFailed,
    #[error("Out of memory")] Oom,
    #[error("Invalid or stale document handle")] InvalidHandle,
    #[error("Page index out of range")] PageOutOfRange,
    #[error("Failed to load page")] PageLoadFailed,
    #[error("Render failed")] RenderFailed,
    #[error("Text extraction failed")] TextExtractionFailed,
    #[error("Engine not initialized")] EngineNotInitialized,
    #[error("Bitmap error")] BitmapError,
}

// ═══════════════════════════════════════════════════════════════════
// Document handle
// ═══════════════════════════════════════════════════════════════════

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub struct DocumentHandle(pub u64);

impl UniffiCustomTypeConverter for DocumentHandle {
    type Builtin = u64;
    fn into_custom(val: Self::Builtin) -> uniffi::Result<Self> {
        Ok(DocumentHandle(val))
    }
    fn from_custom(obj: Self) -> Self::Builtin {
        obj.0
    }
}

impl DocumentHandle {
    fn as_ptr(&self) -> *mut PdfDocument { self.0 as *mut PdfDocument }
    fn from_ptr(ptr: *mut PdfDocument) -> Self { DocumentHandle(ptr as u64) }
}

pub struct RenderConfig {
    pub width: u32,
    pub height: u32,
    pub stride: u32,
    pub native_env: u64,
    pub native_bitmap: u64,
}

pub struct PageRender {
    pub success: bool,
    pub error: Option<String>,
}

// ═══════════════════════════════════════════════════════════════════
// Engine state
// ═══════════════════════════════════════════════════════════════════

static ENGINE_INITIALIZED: AtomicBool = AtomicBool::new(false);

fn ensure_initialized() -> Result<(), PdfiumError> {
    if !ENGINE_INITIALIZED.load(Ordering::Acquire) {
        return Err(PdfiumError::EngineNotInitialized);
    }
    Ok(())
}

// ═══════════════════════════════════════════════════════════════════
// PdfDocument struct
// ═══════════════════════════════════════════════════════════════════

pub struct PdfDocument {
    magic: std::sync::atomic::AtomicU32,
    doc: FPDF_DOCUMENT,
    mapped_data: *mut c_void,
    mapped_size: usize,
    page_count: i32,
}

const MAGIC_ALIVE: u32 = 0x50444631;
const MAGIC_DEAD: u32 = 0x00000000;

impl PdfDocument {
    fn is_alive(&self) -> bool { self.magic.load(Ordering::Acquire) == MAGIC_ALIVE }
    fn poison(&self) { self.magic.store(MAGIC_DEAD, Ordering::Release); }
}

unsafe impl Send for PdfDocument {}
unsafe impl Sync for PdfDocument {}

fn resolve_doc(handle: DocumentHandle, caller: &str) -> Result<&'static PdfDocument, PdfiumError> {
    if handle.0 == 0 {
        log_error(&format!("{}: null handle", caller));
        return Err(PdfiumError::InvalidHandle);
    }
    let doc = unsafe { &*handle.as_ptr() };
    if !doc.is_alive() {
        log_error(&format!("{}: stale handle", caller));
        return Err(PdfiumError::InvalidHandle);
    }
    Ok(doc)
}

// ═══════════════════════════════════════════════════════════════════
// UniFFI-exported functions
// ═══════════════════════════════════════════════════════════════════

pub fn init_engine() {
    if ENGINE_INITIALIZED.swap(true, Ordering::AcqRel) { return; }
    android_logger::init_once(android_logger::Config::default().with_max_level(log::LevelFilter::Debug));
    unsafe { FPDF_InitLibrary(); }
    log_info("Pdfium engine initialized (Rust/UniFFI)");
}

pub fn destroy_engine() {
    if !ENGINE_INITIALIZED.swap(false, Ordering::AcqRel) { return; }
    unsafe { FPDF_DestroyLibrary(); }
    log_info("Pdfium engine destroyed (Rust/UniFFI)");
}

pub fn load_document(fd: i32) -> Result<DocumentHandle, PdfiumError> {
    ensure_initialized()?;
    if fd < 0 { return Err(PdfiumError::InvalidFd); }
    
    let mut st = unsafe { std::mem::zeroed::<libc::stat>() };
    if unsafe { libc::fstat(fd, &mut st) } != 0 || st.st_size <= 0 {
        return Err(PdfiumError::FileEmpty);
    }
    let file_size = st.st_size as usize;
    
    let data = unsafe { libc::mmap(std::ptr::null_mut(), file_size, libc::PROT_READ, libc::MAP_PRIVATE, fd, 0) };
    if data == libc::MAP_FAILED { return Err(PdfiumError::MmapFailed); }
    unsafe { libc::madvise(data, file_size, libc::MADV_RANDOM); }
    
    let doc = unsafe { FPDF_LoadMemDocument64(data, file_size, std::ptr::null()) };
    if doc.is_null() {
        let _err = unsafe { FPDF_GetLastError() };
        unsafe { libc::munmap(data, file_size); }
        return Err(PdfiumError::LoadFailed);
    }
    
    let page_count = unsafe { FPDF_GetPageCount(doc) };
    let pdf_doc = Box::into_raw(Box::new(PdfDocument {
        magic: std::sync::atomic::AtomicU32::new(MAGIC_ALIVE),
        doc, mapped_data: data, mapped_size: file_size, page_count,
    }));
    
    log_info(&format!("Document loaded: {} pages", page_count));
    Ok(DocumentHandle::from_ptr(pdf_doc))
}

pub fn close_document(handle: DocumentHandle) -> Result<(), PdfiumError> {
    ensure_initialized()?;
    let doc = resolve_doc(handle, "close_document")?;
    doc.poison();
    unsafe {
        FPDF_CloseDocument(doc.doc);
        libc::madvise(doc.mapped_data, doc.mapped_size, libc::MADV_DONTNEED);
        libc::munmap(doc.mapped_data, doc.mapped_size);
        drop(Box::from_raw(handle.as_ptr()));
    }
    log_info("Document closed");
    Ok(())
}

pub fn get_page_count(handle: DocumentHandle) -> Result<i32, PdfiumError> {
    ensure_initialized()?;
    let doc = resolve_doc(handle, "get_page_count")?;
    Ok(doc.page_count)
}

pub fn render_page(handle: DocumentHandle, page_index: i32, config: RenderConfig) -> Result<PageRender, PdfiumError> {
    ensure_initialized()?;
    let doc = resolve_doc(handle, "render_page")?;
    
    if page_index < 0 || page_index >= doc.page_count {
        return Err(PdfiumError::PageOutOfRange);
    }
    
    if config.width == 0 || config.height == 0 || config.stride < config.width * 4 {
        return Err(PdfiumError::BitmapError);
    }
    
    let page = unsafe { FPDF_LoadPage(doc.doc, page_index) };
    if page.is_null() { return Err(PdfiumError::PageLoadFailed); }
    
    let page_w = unsafe { FPDF_GetPageWidth(page) };
    let page_h = unsafe { FPDF_GetPageHeight(page) };
    
    if page_w <= 0.0 || page_h <= 0.0 {
        unsafe { FPDF_ClosePage(page); }
        return Err(PdfiumError::RenderFailed);
    }
    
    let scale_x = config.width as f64 / page_w;
    let scale_y = config.height as f64 / page_h;
    let scale = scale_x.min(scale_y);
    let render_w = (page_w * scale) as i32;
    let render_h = (page_h * scale) as i32;
    let offset_x = (config.width as i32 - render_w) / 2;
    let offset_y = (config.height as i32 - render_h) / 2;
    
    let mut pixels: *mut c_void = std::ptr::null_mut();
    let env = config.native_env as *mut c_void;
    let bitmap = config.native_bitmap as *mut c_void;
    
    if unsafe { AndroidBitmap_lockPixels(env, bitmap, &mut pixels) } != ANDROID_BITMAP_RESULT_SUCCESS {
        unsafe { FPDF_ClosePage(page); }
        return Err(PdfiumError::BitmapError);
    }
    
    struct Guard { env: *mut c_void, bitmap: *mut c_void }
    impl Drop for Guard {
        fn drop(&mut self) { unsafe { AndroidBitmap_unlockPixels(self.env, self.bitmap); } }
    }
    let _guard = Guard { env, bitmap };
    
    let fpdf_bitmap = unsafe { FPDFBitmap_CreateEx(config.width as i32, config.height as i32, FPDFBitmap_BGRA, pixels, config.stride as i32) };
    if fpdf_bitmap.is_null() {
        unsafe { FPDF_ClosePage(page); }
        return Err(PdfiumError::RenderFailed);
    }
    
    unsafe {
        FPDFBitmap_FillRect(fpdf_bitmap, 0, 0, config.width as i32, config.height as i32, 0xFFFFFFFF);
        FPDF_RenderPageBitmap(fpdf_bitmap, page, offset_x, offset_y, render_w, render_h, 0, FPDF_ANNOT);
        FPDFBitmap_Destroy(fpdf_bitmap);
        FPDF_ClosePage(page);
    }
    
    Ok(PageRender { success: true, error: None })
}

pub fn extract_text(handle: DocumentHandle, page_index: i32) -> Result<String, PdfiumError> {
    ensure_initialized()?;
    let doc = resolve_doc(handle, "extract_text")?;
    
    if page_index < 0 || page_index >= doc.page_count {
        return Err(PdfiumError::PageOutOfRange);
    }
    
    let page = unsafe { FPDF_LoadPage(doc.doc, page_index) };
    if page.is_null() { return Err(PdfiumError::PageLoadFailed); }
    
    let text_page = unsafe { FPDFText_LoadPage(page) };
    if text_page.is_null() {
        unsafe { FPDF_ClosePage(page); }
        return Err(PdfiumError::TextExtractionFailed);
    }
    
    let page_height = unsafe { FPDF_GetPageHeight(page) };
    let bottom_exclusion = page_height * 0.08;
    let top_exclusion = page_height * 0.92;
    let char_count = unsafe { FPDFText_CountChars(text_page) };
    let mut text = String::with_capacity(char_count as usize);
    
    for i in 0..char_count {
        let mut left = 0.0f64; let mut right = 0.0f64; let mut bottom = 0.0f64; let mut top = 0.0f64;
        let has_box = unsafe { FPDFText_GetCharBox(text_page, i, &mut left, &mut right, &mut bottom, &mut top) != 0 };
        if has_box && (top >= top_exclusion || bottom <= bottom_exclusion) { continue; }
        
        let code = unsafe { FPDFText_GetUnicode(text_page, i) };
        if code == 0 || code > 0x10FFFF { continue; }
        if let Some(c) = std::char::from_u32(code) { text.push(c); }
    }
    
    unsafe { FPDFText_ClosePage(text_page); FPDF_ClosePage(page); }
    Ok(text)
}
