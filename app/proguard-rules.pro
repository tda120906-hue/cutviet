# Media3 ships its own consumer rules; keep effect/codec classes looked up reflectively.
-keep class androidx.media3.decoder.** { *; }
-keep class androidx.media3.effect.** { *; }
-dontwarn org.checkerframework.**
-dontwarn com.google.errorprone.**
-dontwarn javax.annotation.**
# Called from whisper_jni.cpp by name.
-keep class com.cutviet.editor.media.dubbing.WhisperNative$Listener { *; }
-keep class com.cutviet.editor.media.dubbing.WhisperNative { native <methods>; }
# ONNX Runtime and MediaPipe GenAI call into Java from native code and load protos reflectively.
-keep class ai.onnxruntime.** { *; }
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.protobuf.**
