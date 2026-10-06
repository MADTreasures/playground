package ch.madtreasures.fluency

/** Versions of the bundled native engines (see third_party/ and native/build-sherpa-onnx.sh). */
object NativeVersions {
    const val LLAMA_CPP = "llama.cpp abeada3 (2026-10-06)"
    const val WHISPER_CPP = "whisper.cpp 1.9.5"
    const val SHERPA_ONNX = "sherpa-onnx 1.13.8"
    const val ONNX_RUNTIME = "ONNX Runtime 1.28.0"
    const val SUMMARY = "$LLAMA_CPP · $WHISPER_CPP · $SHERPA_ONNX · $ONNX_RUNTIME"
}
