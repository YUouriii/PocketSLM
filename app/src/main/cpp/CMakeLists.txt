cmake_minimum_required(VERSION 3.22)

project(pocketllm LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Optional experimental Vulkan backend. Default OFF: the CPU-only build needs
# none of the Vulkan toolchain (glslc, host C++ compiler, Vulkan-Headers).
option(POCKETLLM_ENABLE_VULKAN "Build the optional Vulkan GPU backend" OFF)

# Override any stale cache value from a previous Vulkan-enabled configure.
if(NOT POCKETLLM_ENABLE_VULKAN)
    set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
endif()

# CPU inference on phones: NEON baseline, KleidiAI kernels only where the CPU supports them.
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_SYSTEM_ARCH "ARM" CACHE STRING "" FORCE)
    set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)
    # Build every armv8-x CPU backend and let ggml pick the best one at runtime.
    # Without this the library uses armv8-a baseline kernels and misses dotprod /
    # i8mm, which roughly halves token generation speed on modern phones.
    set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
    set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
    set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
else()
    set(GGML_SYSTEM_ARCH "x86" CACHE STRING "" FORCE)
    set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
endif()

if(POCKETLLM_ENABLE_VULKAN)
    include("${CMAKE_CURRENT_SOURCE_DIR}/cmake/vulkan.cmake")
endif()

# We only need the libraries. No tools, tests, server or curl.
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)

set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)

add_subdirectory(llama.cpp build-llama)

# Multimodal input (vision + audio) for the LFM3 Omni family.
add_subdirectory(llama.cpp/tools/mtmd build-mtmd)

add_library(pocketllm SHARED llama_jni.cpp)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    target_compile_definitions(pocketllm PRIVATE GGML_BACKEND_DL)
    if(POCKETLLM_ENABLE_VULKAN)
        target_compile_definitions(pocketllm PRIVATE POCKETLLM_VULKAN)
        target_link_libraries(pocketllm PRIVATE Vulkan::Vulkan)
    endif()
endif()

target_include_directories(pocketllm PRIVATE
        llama.cpp/include
        llama.cpp/ggml/include
        llama.cpp)

target_link_libraries(pocketllm PRIVATE
        llama
        mtmd
        android
        log)

target_compile_options(pocketllm PRIVATE -O3 -fno-finite-math-only)
