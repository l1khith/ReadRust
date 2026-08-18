package com.l1khith.readrust

import android.os.ParcelFileDescriptor

/**
 * Legacy JNI Bridge wrapper — delegates directly to [PdfiumBridge]
 * to maintain backwards compatibility without duplicate library loading.
 */
object PdfNativeBridge {
    fun loadDocument(filePath: String): Long {
        return PdfiumBridge.nativeLoadDocumentPath(filePath)
    }

    fun openDocument(pfd: ParcelFileDescriptor?): Long {
        if (pfd == null || pfd.fd < 0) return 0L
        return PdfiumBridge.nativeLoadDocument(pfd.fd)
    }

    fun closeDocument(handle: Long) {
        if (handle > 0L) {
            PdfiumBridge.nativeCloseDocument(handle)
        }
    }

    fun getPageCount(handle: Long): Int {
        if (handle <= 0L) return 0
        return PdfiumBridge.nativeGetPageCount(handle)
    }

    fun extractText(handle: Long, pageIndex: Int): String? {
        if (handle <= 0L || pageIndex < 0) return null
        return PdfiumBridge.nativeExtractText(handle, pageIndex)
    }
}
