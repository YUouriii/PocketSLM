# PocketSLM 1.0.0 Beta

This is a **beta / pre-release** build for testing and feedback — not a final stable release.

Small language models, fully offline on an Android phone's CPU.

## Highlights

- **Beta 1.0.0** — published as a pre-release for testing.
- **Renamed from PocketLLM to PocketSLM** — it runs small (0.3B–3B) GGUF models.
- **Arabic UI** — every string translated; RTL chat with mirrored lists, quotes and
  headings; a warning when you type Arabic with an English-focused model.
- **Honest model information** — measured tok/s, TTFT and RAM with confidence
  levels; multilingual / English-focused / unknown language labels.
- **Reliable downloads** — staged `.part` files validated before being registered,
  no phantom "installed" models mid-download, no deletion of your existing copy
  until the replacement is complete, cancel and failure cleanup.
- **Memory modes** that never force-stop other apps, 4-thread default on big.LITTLE,
  thinking-budget controls, minimum reply length, truncated-reply notice.
- **69 unit tests**, minified release build smoke-tested on a Xiaomi 2201117TG.

## Notes

- Vulkan is experimental and disabled by default; this release is CPU-only.
- Small models can hallucinate facts. Arabic answer quality depends on the model:
  Qwen / Gemma / LFM are multilingual; SmolLM2 and Llama 3.2 are English-focused.
- Developer benchmark intent hooks are disabled in release builds.

## 🤲 العربية

- تغيّر الاسم إلى **PocketSLM** لأن التطبيق مخصص للنماذج الصغيرة.
- الواجهة مترجمة بالكامل للعربية، مع دعم كامل للكتابة من اليمين إلى اليسار.
- تنزيل النماذج أصبح موثوقًا: لا يظهر نموذج كمثبّت قبل اكتماله وتحقّق منه.
- المعلومات المعروضة عن الأداء مبنية على قياسات فعلية على الجهاز.

## Assets

| File | SHA-256 |
|---|---|
| `app-release.apk` | `59a4d1cff88b30eb3b322005f4efdd1d10a972a54e3494f49dec343e29171f68` |
| `app-release.aab` | `859d13b1b5a3744a11f28071869298daa69be61ff8c613c88c37591c581d5252` |

Built from versionCode 3 / versionName `1.0.0-beta`.

Signed with the PocketSLM release key (self-signed). Keep your keystore backed up:
an update signed with a different key cannot replace this installation.
