# Native part of Fluency: llama.cpp (translation) + whisper.cpp (Swiss German ASR) behind one JNI
# library. Built by AGP for arm64-v8a and by native/build-host-jni.sh for the x86-64 JVM tests.
cmake_minimum_required(VERSION 3.22)
project(fluency_native C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if (NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()

set(TP ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party)
option(FLUENCY_OPENCL "Build the Adreno OpenCL (GPU) backend" ON)

# ---------------------------------------------------------------- llama.cpp / ggml
set(BUILD_SHARED_LIBS        ON  CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON       OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS        OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS        OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER       OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP          OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL            OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL               OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP              OFF CACHE BOOL "" FORCE)   # ggml's own threadpool, no libomp.so to ship

if (ANDROID)
    # One libggml-cpu-<variant>.so per ARM feature level; the best one is picked at runtime
    # (Snapdragon 8 Elite Gen 5: dotprod + i8mm, SVE/SME variants if the kernel exposes them).
    set(GGML_NATIVE            OFF CACHE BOOL "" FORCE)
    set(GGML_BACKEND_DL        ON  CACHE BOOL "" FORCE)
    set(GGML_CPU_ALL_VARIANTS  ON  CACHE BOOL "" FORCE)
    set(GGML_CPU_KLEIDIAI      ON  CACHE BOOL "" FORCE)
    set(FETCHCONTENT_SOURCE_DIR_KLEIDIAI ${TP}/kleidiai CACHE PATH "" FORCE)
    if (FLUENCY_OPENCL)
        # ggml embeds its OpenCL kernels with a Python script; cmake/FindPython3.cmake does the
        # same with CMake, so no Python installation is needed.
        list(PREPEND CMAKE_MODULE_PATH ${CMAKE_CURRENT_LIST_DIR}/cmake)
        # Headers + Khronos ICD loader are only needed to *link* libggml-opencl.so. The loader is
        # excluded from the APK; at runtime the vendor's /vendor/lib64/libOpenCL.so is used.
        set(OPENCL_ICD_LOADER_HEADERS_DIR ${TP}/OpenCL-Headers CACHE PATH "" FORCE)
        set(OPENCL_ICD_LOADER_BUILD_TESTING OFF CACHE BOOL "" FORCE)
        set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
        add_subdirectory(${TP}/OpenCL-ICD-Loader ocl EXCLUDE_FROM_ALL)
        set(OpenCL_INCLUDE_DIR ${TP}/OpenCL-Headers CACHE PATH "" FORCE)
        set(OpenCL_LIBRARY OpenCL CACHE STRING "" FORCE)
        set(OpenCL_FOUND TRUE)
        set(GGML_OPENCL                  ON CACHE BOOL "" FORCE)
        set(GGML_OPENCL_EMBED_KERNELS    ON CACHE BOOL "" FORCE)
        set(GGML_OPENCL_USE_ADRENO_KERNELS ON CACHE BOOL "" FORCE)
    endif()
else()
    set(GGML_NATIVE            ON  CACHE BOOL "" FORCE)
    set(GGML_BACKEND_DL        OFF CACHE BOOL "" FORCE)
endif()

add_subdirectory(${TP}/llama.cpp ll)   # short binary dir: Windows path length

# ---------------------------------------------------------------- whisper.cpp (uses llama.cpp's ggml)
add_library(whisper STATIC
    ${TP}/whisper.cpp/src/whisper.cpp
    ${TP}/whisper.cpp/src/aneforge/whisper-aneforge.cpp)   # no-op stub outside Apple Silicon
target_include_directories(whisper PUBLIC ${TP}/whisper.cpp/include PRIVATE ${TP}/whisper.cpp/src)
target_compile_definitions(whisper PRIVATE WHISPER_VERSION="fluency")
target_link_libraries(whisper PUBLIC ggml)
set_target_properties(whisper PROPERTIES POSITION_INDEPENDENT_CODE ON)

# ---------------------------------------------------------------- JNI bridge
add_library(fluency_jni SHARED fluency_jni.cpp)
target_link_libraries(fluency_jni PRIVATE llama whisper ggml)
if (ANDROID)
    target_link_libraries(fluency_jni PRIVATE log)
    target_compile_definitions(fluency_jni PRIVATE FLUENCY_BACKEND_DL)
else()
    find_package(JNI REQUIRED)
    target_include_directories(fluency_jni PRIVATE ${JNI_INCLUDE_DIRS})
    set_target_properties(fluency_jni llama ggml ggml-base ggml-cpu PROPERTIES
        BUILD_RPATH "\$ORIGIN" INSTALL_RPATH "\$ORIGIN")
    # same strictness as the NDK: unresolved symbols must fail at link time, not at System.load()
    target_link_options(fluency_jni PRIVATE -Wl,--no-undefined)
endif()
