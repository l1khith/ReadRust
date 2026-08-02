# R8 / ProGuard rules for ReadRust production release

# Keep JNI native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep PdfiumBridge and model classes accessed via JNI / Reflection / Gson
-keep class com.l1khith.readrust.PdfiumBridge { *; }
-keep class com.l1khith.readrust.PdfHelper { *; }
-keep class com.l1khith.readrust.SentenceWithBounds { *; }
-keepclassmembers class com.l1khith.readrust.SentenceWithBounds { *; }

# Keep Gson annotations and serialized attributes
-keepattributes Signature, *Annotation*, EnclosingMethod, InnerClasses
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Keep Compose annotations
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}
