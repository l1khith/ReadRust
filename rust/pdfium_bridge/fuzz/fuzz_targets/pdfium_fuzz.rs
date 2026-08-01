#![no_main]

use libfuzzer_sys::fuzz_target;
use pdfium_bridge::*;

fuzz_target!(|data: &[u8]| {
    if data.len() < 10 {
        return;
    }

    pdfium_bridge::init_engine();

    unsafe {
        // Create an anonymous file in memory (Linux only)
        let fd = libc::memfd_create(b"fuzz_pdf\0".as_ptr() as *const i8, 0);
        if fd < 0 {
            return;
        }
        
        libc::write(fd, data.as_ptr() as *const libc::c_void, data.len());
        libc::lseek(fd, 0, libc::SEEK_SET);

        if let Ok(handle) = load_document(fd) {
            if let Ok(count) = get_page_count(handle) {
                if count > 0 {
                    let page_idx = (data[0] as i32) % count;
                    let config = RenderConfig {
                        width: 800,
                        height: 1200,
                        stride: 800 * 4,
                        native_env: 0,
                        native_bitmap: 0, // Mock for harness
                    };
                    let _ = render_page(handle, page_idx, config);
                    let _ = extract_text(handle, page_idx);
                }
            }
            let _ = close_document(handle);
        }
        libc::close(fd);
    }
});
