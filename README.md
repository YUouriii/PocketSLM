# PocketSLM

**Small language models, in your pocket.** PocketSLM runs GGUF models (0.3B–3B) fully
offline on an Android phone's CPU. No account, no cloud, no telemetry — the model and
your conversations stay on the device.

<p>
  <b>English</b> · <a href="#العربية">العربية</a>
</p>

## What it does

- **Chat** — streaming replies, stop/regenerate, saved sessions, Markdown/code/table
  rendering. Full RTL support: Arabic, Persian, Hebrew and Urdu text renders
  right-to-left, with mirrored bullets, quotes and headings.
- **Models** — curated catalog with memory-fit checks based on detected RAM, Hugging
  Face search for any public GGUF repo, staged downloads with validation, cancel and
  delete. A language label (multilingual / English-focused / unknown) is shown for
  every model so you know what to expect.
- **Performance** — measured tokens/s, TTFT and RAM per model on this device, with
  honest confidence levels and no fabricated estimates. Recommendations favour
  models that clear 6 tok/s. Threads default to the big-core cluster.
- **Settings** — threads, context, KV precision, temperature, top-p, sampling limits,
  thinking level, memory mode, theme, and Hugging Face token for gated repos.
  Memory modes never force-stop other apps and never claim `System.gc()` is free RAM.

Inference is llama.cpp compiled through the NDK, CPU only. Models are memory-mapped,
so a 1 GB file does not become a 1 GB copy in RAM. Vulkan is experimental and off by
default.

## Arabic / العربية

التطبيق يدعم العربية بالكامل: الواجهة مترجمة، والمحادثات تُعرض من اليمين إلى اليسار،
مع إظهار تحذير عند كتابة العربية أثناء استخدام نموذج غير متعدد اللغات. النماذج
متعددة اللغات مثل Qwen و Gemma و LFM تعمل بالعربية، بينما SmolLM2 و Llama 3.2
موجّهان للإنجليزية. جودة الإجابة تعتمد على حجم النموذج نفسه — النماذج الصغيرة قد
تخطئ في المعلومات.

## Requirements

| | |
|---|---|
| JDK | 17+ (Android Studio's bundled JBR works) |
| Gradle | 9.9.1 (wrapper included) |
| AGP / Kotlin | 9.5.1 / 2.4.21 |
| SDK | compileSdk 37, minSdk 26 |
| NDK | 29.0.14206865 |
| CMake | 3.31.6 |

```powershell
sdkmanager "ndk;29.0.14206865" "cmake;3.31.6"
```

Only `arm64-v8a` and `x86_64` are built. There is no 32-bit build on purpose.

## Build

```powershell
# Debug APK for a connected phone
.\gradlew.bat :app:assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk

# Tests + signed release (see Signing below)
.\gradlew.bat :app:testDebugUnitTest :app:assembleRelease
```

## Signing

Release signing is optional for local builds. Point `local.properties` (git-ignored)
at your own keystore:

```properties
pocketslm.keystore=C:/path/to/your-release.jks
pocketslm.storePassword=...
pocketslm.keyAlias=...
pocketslm.keyPassword=...
```

Without those properties the release task still builds an unsigned APK. **Back up
your keystore** — an update signed with a different key cannot replace the installed
app.

## Privacy

INTERNET is used only for Hugging Face model search and downloads. RECORD_AUDIO is
used only for voice attachments you explicitly start. Sessions, settings and
downloaded models live in the app's own storage; `allowBackup` is disabled.

No license file is included yet — add one if you want to open-source the code. Model
weights keep their own licenses, shown in the Models tab.
