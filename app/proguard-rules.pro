# HyperGallery AI — ProGuard / R8 Optimization Rules

# Keep Kotlinx Serialization generated serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.hypergallery.**$$serializer { *; }
-keepclassmembers class com.hypergallery.** {
    *** Companion;
}
-keepclasseswithmembers class com.hypergallery.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Room Database & Entities
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**

# TensorFlow Lite / LiteRT + GPU / NNAPI Delegates (JNI reflection)
-keep class org.tensorflow.lite.** { *; }
-keep class org.tensorflow.lite.gpu.** { *; }
-keep class org.tensorflow.lite.nnapi.** { *; }
-dontwarn org.tensorflow.lite.**

# Media3 / ExoPlayer
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Coil 3
-dontwarn coil3.**

# ExifInterface & Biometric
-keep class androidx.exifinterface.** { *; }
-keep class androidx.biometric.** { *; }

# Hilt & WorkManager
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends androidx.work.CoroutineWorker {
    public <init>(...);
}
