# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.

# Keep MediaPipe classes (native libs + Java API)
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# MediaPipe's Graph logs through Flogger, which finds its caller by matching
# its own class names on the stack. Obfuscating it breaks Graph's static init
# ("no caller found on the stack") and pose detection fails in release only.
-keep class com.google.common.flogger.** { *; }
-dontwarn com.google.common.flogger.**

# MediaPipe task options/results are protobuf-lite messages accessed reflectively.
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }

# MediaPipe ships the AutoValue annotation processor as a runtime dependency.
# It references javac APIs that don't exist on Android and never runs in the app.
-dontwarn javax.annotation.processing.**
-dontwarn javax.lang.model.**

# Keep domain model data classes (serialization, Room converters)
-keep class com.liftr.domain.model.** { *; }
-keep class com.liftr.data.local.database.entities.** { *; }

# Room database — keep generated DAO implementations
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }

# Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# Hilt — keep generated components
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }

# CameraX
-keep class androidx.camera.** { *; }

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
