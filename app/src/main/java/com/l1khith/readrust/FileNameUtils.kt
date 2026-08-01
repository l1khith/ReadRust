package com.l1khith.readrust

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log

object FileNameUtils {
    fun getFileName(context: Context, uri: Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) {
                        result = cursor.getString(index)
                    }
                }
            } catch (e: SecurityException) {
                Log.e("FileNameUtils", "SecurityException querying URI: ${e.message}")
            } catch (e: IllegalArgumentException) {
                Log.e("FileNameUtils", "Invalid URI: ${e.message}")
            } catch (e: Exception) {
                Log.e("FileNameUtils", "Error resolving file name: ${e.message}")
            } finally {
                cursor?.close()
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/')
            if (cut != null && cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result ?: "Unknown Document.pdf"
    }
}
