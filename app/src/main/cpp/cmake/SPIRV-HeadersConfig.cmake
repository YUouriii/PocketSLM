# Minimal SPIRV-Headers config for llama.cpp's ggml-vulkan target
# (find_package(SPIRV-Headers CONFIG REQUIRED)). The include directory is
# discovered by cmake/vulkan.cmake; the fallback uses the NDK's bundled headers.
if(POCKETLLM_SPIRV_HEADERS_INCLUDE_DIR)
    set(SPIRV-Headers_INCLUDE_DIRS "${POCKETLLM_SPIRV_HEADERS_INCLUDE_DIR}")
else()
    set(SPIRV-Headers_INCLUDE_DIRS
        "${CMAKE_ANDROID_NDK}/sources/third_party/shaderc/third_party/spirv-tools/external/spirv-headers/include")
endif()
set(SPIRV-Headers_INCLUDE_DIR "${SPIRV-Headers_INCLUDE_DIRS}")
if(NOT TARGET SPIRV-Headers::SPIRV-Headers)
    add_library(SPIRV-Headers::SPIRV-Headers INTERFACE IMPORTED)
    set_target_properties(SPIRV-Headers::SPIRV-Headers PROPERTIES
        INTERFACE_INCLUDE_DIRECTORIES "${SPIRV-Headers_INCLUDE_DIRS}")
endif()
