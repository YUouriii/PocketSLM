# Building PocketLLM (CPU and optional Vulkan)

## Default: CPU-only build (recommended)

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File <opencode>\pocketllm-gradle.ps1 :app:assembleDebug
```

No Vulkan dependencies are needed. The default build has
`POCKETLLM_ENABLE_VULKAN=OFF`, so no shader compilation, glslc, WinLibs, or
Vulkan-Headers lookup takes place. The APK contains only the CPU backends.

## Optional: Vulkan-enabled build

Vulkan is experimental and only affects `arm64-v8a`. Build it explicitly:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File <opencode>\pocketllm-gradle.ps1 :app:assembleDebug '-Ppocketllm.vulkan=true'
```

Quote the `-P` argument when calling through a PowerShell wrapper script so the
dotted property name is passed as a single argument. Other ABIs (x86_64) remain
CPU-only even in a Vulkan-enabled build.

### Dependencies

| Dependency | Purpose | Discovery / override |
|---|---|---|
| Android NDK 29 (`ndkVersion`) with `shader-tools/glslc` | compiles Vulkan shaders | found under `<NDK>/shader-tools/<host-tag>`; override `POCKETLLM_VULKAN_GLSLC` |
| Vulkan-Headers (`vulkan/vulkan.hpp`) | C++ Vulkan bindings | vendored in `app/src/main/cpp/vulkan-headers`; override `POCKETLLM_VULKAN_HEADERS_DIR` |
| `libvulkan.so` from NDK sysroot | Vulkan ICD loader shipped in the APK | newest `aarch64-linux-android/<api>/libvulkan.so`; override `POCKETLLM_VULKAN_LIBRARY` |
| SPIRV-Headers (`spirv/unified1/spirv.hpp`) | shader generated C++ headers | from NDK `sources/third_party`; override `POCKETLLM_SPIRV_HEADERS_INCLUDE_DIR` |
| Windows host C++17 compiler | builds `vulkan-shaders-gen` during the build | `gcc`/`clang`/`cl` on PATH, winget WinLibs install, or `POCKETLLM_HOST_COMPILER_DIR` / `POCKETLLM_HOST_CC` / `POCKETLLM_HOST_CXX` |

To install the recommended host compiler:

```powershell
winget install --id BrechtSanders.WinLibs.POSIX.UCRT --source winget
```

MSVC `cl`/`clang-cl` with a Windows SDK is also supported (no DLL copy needed).

### CMake cache variables

All paths are optional; CMake discovers them where practical and reports a
clear error when a required one is missing. Pass overrides as Gradle properties
with the same name, for example:

```powershell
... -Ppocketllm.vulkan=true -DPOCKETLLM_HOST_CC=C:/msys64/mingw64/bin/gcc.exe
```

| CMake variable | Meaning |
|---|---|
| `POCKETLLM_ENABLE_VULKAN` | master switch (`OFF` by default) |
| `POCKETLLM_VULKAN_HEADERS_DIR` | directory containing `vulkan/vulkan.hpp` |
| `POCKETLLM_VULKAN_GLSLC` | glslc executable |
| `POCKETLLM_VULKAN_LIBRARY` | arm64 `libvulkan.so` |
| `POCKETLLM_SPIRV_HEADERS_INCLUDE_DIR` | SPIRV-Headers include root |
| `POCKETLLM_HOST_COMPILER_DIR` | host compiler bin directory (and runtime DLLs) |
| `POCKETLLM_HOST_CC`, `POCKETLLM_HOST_CXX` | explicit host compiler paths |

The host toolchain used by the shader generator is generated from
`app/src/main/cpp/cmake/host-toolchain-vulkan.cmake.in` into the build directory;
no developer-specific absolute path is stored in the repository.

## Runtime

Vulkan is never enabled automatically. It appears as a "GPU layers" setting only
when the device reports Vulkan, and exact device compatibility is checked before
offloading (see the Vulkan compatibility probe). CPU inference remains the
default and the fallback on every device.
