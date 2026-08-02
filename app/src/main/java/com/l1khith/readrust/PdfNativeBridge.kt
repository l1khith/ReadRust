package com.l1khith.readrust

object PdfNativeBridge {
    init {
        System.loadLibrary("pdfium_bridge")
    }

    external fun loadDocument(filePath: String): Long
    external fun closeDocument(handle: Long)
    external fun getPageCount(handle: Long): Int
    external fun renderPage(handle: Long, pageIndex: Int, width: Int, height: Int): ByteArray?
    external fun extractText(handle: Long, pageIndex: Int): String?
}
