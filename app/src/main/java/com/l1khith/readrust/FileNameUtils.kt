package com.l1khith.readrust

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log

object FileNameUtils {
    fun getFileName(context: Context, uri: Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0) {
                            val name = cursor.getString(index)
                            if (!name.isNullOrBlank()) {
                                result = name
                            }
                        }
                    }
                }
            } catch (e: SecurityException) {
                Log.e("FileNameUtils", "SecurityException querying URI: ${e.message}")
            } catch (e: IllegalArgumentException) {
                Log.e("FileNameUtils", "Invalid URI: ${e.message}")
            } catch (e: Exception) {
                Log.e("FileNameUtils", "Error resolving file name: ${e.message}")
            }
        }
        
        if (result.isNullOrBlank()) {
            val path = uri.path
            if (!path.isNullOrBlank()) {
                val cut = path.lastIndexOf('/')
                result = if (cut != -1) path.substring(cut + 1) else path
            }
        }
        
        return if (!result.isNullOrBlank()) result!! else "Unknown Document.pdf"
    }
}
