# --- JNI ------------------------------------------------------------------------------------
# sherpa-onnx's native code reads the Kotlin config objects field by field (by name) and creates
# result objects (SpeechSegment, OfflineRecognizerResult, GeneratedAudio, ...).
-keep class com.k2fsa.sherpa.onnx.** { *; }

# Our own JNI bridges (llama.cpp / whisper.cpp in libfluency_jni.so)
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }
-keep class ch.madtreasures.fluency.engine.llm.LlamaNative { *; }
-keep class ch.madtreasures.fluency.engine.asr.WhisperCppNative { *; }
# streamed tokens are delivered by calling onText(byte[]) on the sink object from C++
-keep interface ch.madtreasures.fluency.engine.llm.TokenSink { *; }
-keep class * implements ch.madtreasures.fluency.engine.llm.TokenSink { public boolean onText(byte[]); }

# ONNX Runtime's Java API is not used (sherpa-onnx talks to libonnxruntime.so directly)
-dontwarn ai.onnxruntime.**
