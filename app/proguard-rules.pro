# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.

# Keep MediaPipe classes
-keep class com.google.mediapipe.** { *; }

# Keep data classes for serialization
-keep class com.lifthelper.domain.model.** { *; }
-keep class com.lifthelper.data.local.database.entities.** { *; }
