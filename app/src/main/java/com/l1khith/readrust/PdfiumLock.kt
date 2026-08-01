package com.l1khith.readrust

import kotlinx.coroutines.sync.Mutex

/**
 * Global serialization lock for ALL native PDF engine calls.
 */
object PdfiumLock {
    val mutex = Mutex()
}
