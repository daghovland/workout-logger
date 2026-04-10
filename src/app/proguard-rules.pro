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

# Gson referenced by Supabase/Ktor transitive deps but not used at runtime
-dontwarn com.google.gson.Gson
-dontwarn com.google.gson.GsonBuilder
-dontwarn com.google.gson.JsonIOException
-dontwarn com.google.gson.JsonParseException
-dontwarn com.google.gson.JsonSyntaxException
-dontwarn com.google.gson.TypeAdapter
-dontwarn com.google.gson.annotations.SerializedName
-dontwarn com.google.gson.stream.JsonReader
-dontwarn com.google.gson.stream.JsonToken
-dontwarn com.google.gson.stream.JsonWriter
