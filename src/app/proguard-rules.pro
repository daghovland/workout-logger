# Keep kotlinx.serialization classes
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep all @Serializable data classes
-keep @kotlinx.serialization.Serializable class ** { *; }

# Supabase / Ktor
-dontwarn io.ktor.**
-dontwarn kotlinx.coroutines.**

# Samsung Health SDK
-keep class com.samsung.android.sdk.healthdata.** { *; }
