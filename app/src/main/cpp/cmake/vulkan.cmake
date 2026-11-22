# Optional Vulkan backend configuration. Included only when
# POCKETLLM_ENABLE_VULKAN=ON. Every dependency is discovered or supplied via a
# cache variable; no developer-specific absolute paths live here.
#
# Required:
#   - Android NDK with shader-tools/glslc for the host platform
#   - Windows host C++17 compiler for vulkan-shaders-gen (MinGW via winget, MSVC,
#     or clang-cl with MSVC headers)
#   - Vulkan-Headers (vendored in ../vulkan-headers) and libvulkan.so from the NDK
#
# See docs/BUILD_VULKAN.md for setup and example commands.

if(NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(STATUS "Vulkan backend supports arm64-v8a only; ${ANDROID_ABI} stays CPU-only")
    return()
endif()

set(GGML_VULKAN ON CACHE BOOL "" FORCE)

# --- NDK root ---------------------------------------------------------------
if(CMAKE_ANDROID_NDK)
    set(_pm_ndk "${CMAKE_ANDROID_NDK}")
elseif(DEFINED ENV{ANDROID_NDK_ROOT} AND NOT "$ENV{ANDROID_NDK_ROOT}" STREQUAL "")
    set(_pm_ndk "$ENV{ANDROID_NDK_ROOT}")
elseif(DEFINED ENV{ANDROID_NDK} AND NOT "$ENV{ANDROID_NDK}" STREQUAL "")
    set(_pm_ndk "$ENV{ANDROID_NDK}")
else()
    get_filename_component(_pm_cmake_dir "${CMAKE_TOOLCHAIN_FILE}" DIRECTORY)
    get_filename_component(_pm_ndk "${_pm_cmake_dir}/../.." ABSOLUTE)
endif()
if(NOT EXISTS "${_pm_ndk}/build/cmake/android.toolchain.cmake")
    message(FATAL_ERROR "Cannot locate the Android NDK (looked in '${_pm_ndk}'). Set CMAKE_ANDROID_NDK or ANDROID_NDK_ROOT.")
endif()

# Host triple used by NDK prebuilt tools (windows-x86_64, linux-x86_64, ...).
string(TOLOWER "${CMAKE_HOST_SYSTEM_NAME}" _pm_host_os)
if(CMAKE_HOST_SYSTEM_PROCESSOR MATCHES "^(x86_64|AMD64)$")
    set(_pm_host_arch "x86_64")
elseif(CMAKE_HOST_SYSTEM_PROCESSOR MATCHES "^(aarch64|arm64|ARM64)$")
    set(_pm_host_arch "aarch64")
else()
    set(_pm_host_arch "${CMAKE_HOST_SYSTEM_PROCESSOR}")
endif()
set(_pm_host_tag "${_pm_host_os}-${_pm_host_arch}")

# --- Vulkan headers (C++ bindings) -----------------------------------------
set(POCKETLLM_VULKAN_HEADERS_DIR "${CMAKE_CURRENT_LIST_DIR}/../vulkan-headers/include"
    CACHE PATH "Directory containing vulkan/vulkan.hpp (vendored by default)")
if(NOT EXISTS "${POCKETLLM_VULKAN_HEADERS_DIR}/vulkan/vulkan.hpp")
    message(FATAL_ERROR "Vulkan-Headers not found in '${POCKETLLM_VULKAN_HEADERS_DIR}'. Set POCKETLLM_VULKAN_HEADERS_DIR.")
endif()
set(Vulkan_INCLUDE_DIR "${POCKETLLM_VULKAN_HEADERS_DIR}")

# --- glslc (shader compiler) ------------------------------------------------
find_program(POCKETLLM_VULKAN_GLSLC NAMES glslc glslc.exe
    HINTS "${_pm_ndk}/shader-tools/${_pm_host_tag}"
    NO_CMAKE_FIND_ROOT_PATH)
if(NOT POCKETLLM_VULKAN_GLSLC)
    message(FATAL_ERROR "glslc not found. Install an NDK with shader-tools or set POCKETLLM_VULKAN_GLSLC.")
endif()
set(Vulkan_GLSLC_EXECUTABLE "${POCKETLLM_VULKAN_GLSLC}")
message(STATUS "Vulkan glslc: ${POCKETLLM_VULKAN_GLSLC}")

# --- libvulkan.so (NDK stub/ICD loader) ------------------------------------
set(_pm_vulkan_lib "")
set(_pm_best_level 0)
file(GLOB _pm_libdirs "${_pm_ndk}/toolchains/llvm/prebuilt/${_pm_host_tag}/sysroot/usr/lib/aarch64-linux-android/*")
foreach(_pm_dir IN LISTS _pm_libdirs)
    get_filename_component(_pm_level "${_pm_dir}" NAME)
    if(_pm_level MATCHES "^[0-9]+$" AND _pm_level GREATER _pm_best_level AND EXISTS "${_pm_dir}/libvulkan.so")
        set(_pm_best_level "${_pm_level}")
        set(_pm_vulkan_lib "${_pm_dir}/libvulkan.so")
    endif()
endforeach()
set(POCKETLLM_VULKAN_LIBRARY "${_pm_vulkan_lib}" CACHE FILEPATH "Path to libvulkan.so for arm64-v8a")
if(NOT EXISTS "${POCKETLLM_VULKAN_LIBRARY}")
    message(FATAL_ERROR "libvulkan.so not found. Set POCKETLLM_VULKAN_LIBRARY.")
endif()
set(Vulkan_LIBRARY "${POCKETLLM_VULKAN_LIBRARY}")
message(STATUS "Vulkan loader: ${POCKETLLM_VULKAN_LIBRARY}")

# Create Vulkan::Vulkan in this (root) scope so both ggml-vulkan and the JNI
# probe can link the loader; ggml-vulkan calls find_package again harmlessly.
find_package(Vulkan COMPONENTS glslc REQUIRED)
if(NOT TARGET Vulkan::Vulkan)
    message(FATAL_ERROR "Vulkan::Vulkan target was not created")
endif()

# --- SPIRV-Headers (compile-time C++ headers) ------------------------------
find_path(POCKETLLM_SPIRV_HEADERS_INCLUDE_DIR NAMES spirv/unified1/spirv.hpp
    HINTS "${_pm_ndk}/sources/third_party/shaderc/third_party/spirv-tools/external/spirv-headers/include"
    NO_CMAKE_FIND_ROOT_PATH)
if(NOT POCKETLLM_SPIRV_HEADERS_INCLUDE_DIR)
    message(FATAL_ERROR "SPIRV-Headers not found. Set POCKETLLM_SPIRV_HEADERS_INCLUDE_DIR.")
endif()
list(APPEND CMAKE_CXX_STANDARD_INCLUDE_DIRECTORIES "${POCKETLLM_SPIRV_HEADERS_INCLUDE_DIR}")
set(SPIRV-Headers_DIR "${CMAKE_CURRENT_LIST_DIR}")

# --- Host C++ compiler for vulkan-shaders-gen ------------------------------
set(POCKETLLM_HOST_COMPILER_DIR "" CACHE PATH
    "Directory containing a Windows host C++ compiler (and its runtime DLLs), e.g. WinLibs mingw64/bin")

# Drop a bad cached value from an earlier discovery attempt.
if(POCKETLLM_HOST_CC AND (POCKETLLM_HOST_CC MATCHES "(^|/)toolchains/llvm/prebuilt/" OR POCKETLLM_HOST_CC MATCHES "/ndk/"))
    unset(POCKETLLM_HOST_CC CACHE)
    unset(POCKETLLM_HOST_CXX CACHE)
endif()

# Collect explicit candidates first. find_program() on PATH can otherwise pick
# the NDK's Android clang, which cannot produce a Windows host executable.
set(_pm_host_bins "")
if(POCKETLLM_HOST_COMPILER_DIR)
    list(APPEND _pm_host_bins "${POCKETLLM_HOST_COMPILER_DIR}")
elseif(CMAKE_HOST_WIN32)
    # WIN32 reflects the target (Android); use CMAKE_HOST_WIN32 for host discovery.
    file(GLOB _pm_winlibs "$ENV{LOCALAPPDATA}/Microsoft/WinGet/Packages/BrechtSanders.WinLibs.POSIX.UCRT_*/mingw64/bin")
    file(GLOB _pm_winlibs2 "$ENV{USERPROFILE}/AppData/Local/Microsoft/WinGet/Packages/BrechtSanders.WinLibs.POSIX.UCRT_*/mingw64/bin")
    list(APPEND _pm_host_bins ${_pm_winlibs} ${_pm_winlibs2})
endif()

message(STATUS "Vulkan host compiler directories: ${_pm_host_bins}")
set(_pm_cc "")
set(_pm_cxx "")
foreach(_pm_bin IN LISTS _pm_host_bins)
    foreach(_pm_name gcc.exe g++.exe clang++.exe clang.exe cl.exe)
        if(NOT _pm_cc AND EXISTS "${_pm_bin}/${_pm_name}")
            set(_pm_cc "${_pm_bin}/${_pm_name}")
        endif()
    endforeach()
    foreach(_pm_name g++.exe clang++.exe clang-cl.exe cl.exe)
        if(NOT _pm_cxx AND EXISTS "${_pm_bin}/${_pm_name}")
            set(_pm_cxx "${_pm_bin}/${_pm_name}")
        endif()
    endforeach()
endforeach()

if(NOT POCKETLLM_HOST_CC AND _pm_cc)
    set(POCKETLLM_HOST_CC "${_pm_cc}" CACHE FILEPATH "Host C compiler for shader-gen" FORCE)
endif()
if(NOT POCKETLLM_HOST_CC)
    find_program(POCKETLLM_HOST_CC NAMES gcc g++ clang clang++ cl NO_CMAKE_FIND_ROOT_PATH)
endif()
if(POCKETLLM_HOST_CC MATCHES "(^|/)toolchains/llvm/prebuilt/" OR POCKETLLM_HOST_CC MATCHES "/ndk/")
    message(FATAL_ERROR
        "POCKETLLM_HOST_CC points into the Android NDK ('${POCKETLLM_HOST_CC}'). "
        "A Windows host compiler is required. Install WinLibs via "
        "winget install BrechtSanders.WinLibs.POSIX.UCRT or set POCKETLLM_HOST_CC.")
endif()
if(NOT POCKETLLM_HOST_CC)
    message(FATAL_ERROR
        "No host C++ compiler found. Install WinLibs "
        "(winget install BrechtSanders.WinLibs.POSIX.UCRT) or set POCKETLLM_HOST_CC.")
endif()

get_filename_component(_pm_host_bin "${POCKETLLM_HOST_CC}" DIRECTORY)
if(NOT POCKETLLM_HOST_CXX AND _pm_cxx)
    set(POCKETLLM_HOST_CXX "${_pm_cxx}" CACHE FILEPATH "Host C++ compiler for shader-gen" FORCE)
endif()
if(NOT POCKETLLM_HOST_CXX)
    find_program(POCKETLLM_HOST_CXX NAMES g++ clang++ clang-cl cl
        HINTS "${_pm_host_bin}" NO_CMAKE_FIND_ROOT_PATH)
endif()
if(NOT POCKETLLM_HOST_CXX)
    message(FATAL_ERROR "No host C++ compiler found. Set POCKETLLM_HOST_CXX.")
endif()
message(STATUS "Vulkan host compiler: ${POCKETLLM_HOST_CC} / ${POCKETLLM_HOST_CXX}")

# Runtime DLLs must sit next to the installed host tool. Derive them from the
# compiler directory; MSVC/clang-cl builds need none and get an empty list.
set(_pm_host_dlls "")
foreach(_pm_dll libwinpthread-1.dll libgcc_s_seh-1.dll libstdc++-6.dll)
    if(EXISTS "${_pm_host_bin}/${_pm_dll}")
        list(APPEND _pm_host_dlls "${_pm_host_bin}/${_pm_dll}")
    endif()
endforeach()
set(_pm_host_dll_str "")
foreach(_pm_dll IN LISTS _pm_host_dlls)
    string(APPEND _pm_host_dll_str " \"${_pm_dll}\"")
endforeach()

# Normalize Windows paths (CMake accepts forward slashes and avoids escape issues).
file(TO_CMAKE_PATH "${CMAKE_MAKE_PROGRAM}" _pm_make_program)
file(TO_CMAKE_PATH "${POCKETLLM_HOST_CC}" _pm_host_cc)
file(TO_CMAKE_PATH "${POCKETLLM_HOST_CXX}" _pm_host_cxx)

# Generate the host toolchain from a template so no machine path is stored.
configure_file(
    "${CMAKE_CURRENT_LIST_DIR}/host-toolchain-vulkan.cmake.in"
    "${CMAKE_BINARY_DIR}/pocketllm-host-toolchain.cmake"
    @ONLY)
set(GGML_VULKAN_SHADERS_GEN_TOOLCHAIN "${CMAKE_BINARY_DIR}/pocketllm-host-toolchain.cmake")
message(STATUS "Vulkan host toolchain: ${GGML_VULKAN_SHADERS_GEN_TOOLCHAIN}")
