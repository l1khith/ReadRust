# Room rules
-keep class com.l1khith.readrust.BookEntity { *; }
-keep class com.l1khith.readrust.BookDao { *; }
-keep class com.l1khith.readrust.AppDatabase { *; }

# JNI & Native Bridge rules
-keep class com.l1khith.readrust.PdfiumBridge { *; }
-keepclassmembers class com.l1khith.readrust.PdfiumBridge {
    native <methods>;
}

# Gson models
-keep class com.l1khith.readrust.SentenceWithBounds { *; }
-keep class com.l1khith.readrust.PageRender { *; }
