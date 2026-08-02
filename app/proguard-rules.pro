# R8 / ProGuard rules for ReadRust production release

# Keep all ReadRust package classes, fields, methods, and JNI entrypoints
-keep class com.l1khith.readrust.** { *; }
-keepclassmembers class com.l1khith.readrust.** { *; }

# Keep native JNI methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep Gson library and reflection classes
-keep class com.google.gson.** { *; }
-keepclassmembers class com.google.gson.** { *; }

# Keep Gson annotations and serialized attributes
-keepattributes Signature, *Annotation*, EnclosingMethod, InnerClasses
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Keep Compose runtime annotations
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}
