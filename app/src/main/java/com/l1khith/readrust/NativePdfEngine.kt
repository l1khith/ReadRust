package com.l1khith.readrust

import android.graphics.Bitmap

/**
 * NativePdfEngine
 *
 * Kotlin singleton interface for native PDF engine calls.
 */
object NativePdfEngine {

    @Volatile
    private var isInitialized = false

    init {
        try {
            System.loadLibrary("pdfium_bridge")
        } catch (e: UnsatisfiedLinkError) {
            // Fallback for when Rust engine replaces C++ engine
        }
    }

    fun initEngineOnce() {
        if (!isInitialized) {
            synchronized(this) {
                if (!isInitialized) {
                    try {
                        initEngine()
                    } catch (e: UnsatisfiedLinkError) {
                        // Native library replaced by Rust engine
                    }
                    isInitialized = true
                }
            }
        }
    }

    external fun initEngine()
    external fun destroyEngine()
    external fun loadDocument(fd: Int): Long
    external fun getPageCount(docPtr: Long): Int
    external fun renderPage(docPtr: Long, pageIndex: Int, bitmap: Bitmap): Boolean
    external fun extractText(docPtr: Long, pageIndex: Int): String?
    external fun closeDocument(docPtr: Long)
}
