# Media3 ships its own consumer rules; keep effect/codec classes looked up reflectively.
-keep class androidx.media3.decoder.** { *; }
-keep class androidx.media3.effect.** { *; }
-dontwarn org.checkerframework.**
-dontwarn com.google.errorprone.**
-dontwarn javax.annotation.**
