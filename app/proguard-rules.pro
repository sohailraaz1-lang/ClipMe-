# Keep NewPipeExtractor model classes (Gson reflection)
-keep class org.schabi.newpipe.extractor.** { *; }
-keep interface org.schabi.newpipe.extractor.** { *; }
-dontwarn org.schabi.newpipe.extractor.**
-dontwarn org.mozilla.javascript.**
-dontwarn org.mozilla.classfile.**
-dontwarn com.grack.nanojson.**

# Keep ML Kit
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# Media3 native / effect classes are referenced by name
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Vosk JNI
-keep class org.vosk.** { *; }
-dontwarn org.vosk.**
