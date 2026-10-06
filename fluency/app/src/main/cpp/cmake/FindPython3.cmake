# Fluency: stands in for Python 3, which ggml's OpenCL backend needs for one thing only: wrapping
# every line of its kernels in a raw string literal (kernels/embed_kernel.py). The "interpreter"
# is CMake itself running embed_opencl_kernel.cmake, which writes the same header. This way the
# GPU backend is always built, also where no Python is installed (Android Studio on Windows).
set(Python3_FOUND TRUE)
set(Python3_Interpreter_FOUND TRUE)
set(Python3_EXECUTABLE "${CMAKE_COMMAND}" -P "${CMAKE_CURRENT_LIST_DIR}/embed_opencl_kernel.cmake" --)
