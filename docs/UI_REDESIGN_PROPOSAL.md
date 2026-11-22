# PocketLLM UI/UX redesign proposal (read-only, v2)

Production source was not changed. Visual previews live outside the app module at
`design-previews/src/main/java/com/pocketllm/preview/UiPreviews.kt`. To inspect
them in Android Studio, temporarily add that directory as a preview-only source
root; no app code or behavior needs to change.

---

## 1. Expanded screen, flow and state inventory

### Navigation shell
| Item | Current location | Redesigned placement | Class |
|---|---|---|---|
| Tab state (Chat/Models/Settings) | `PocketLlmRoot.kt`, `rememberSaveable` enum | same, with status badges on tabs | A |
| Back behavior | system Back exits app | unchanged unless separately approved | – |
| Status/nav bar colors | `MainActivity` `WindowCompat` | unchanged | A |
| Starfield | Chat background + Models/Settings content | kept on content screens only; reduced opacity behind forms | A |

### Chat (`ChatScreen.kt`, `ChatViewModel.kt`)
| Flow / state | Current | Redesign | Class |
|---|---|---|---|
| Empty chat | `EmptyChat` with 3 fixed suggestions | same plus contextual "no model" row | A |
| No model loaded | strings exist; generic empty shown | explicit inline state with "Choose a model" action | A |
| Model loading | small status dot + text in top bar | prominent but quiet status line + shimmer placeholder | A |
| Active conversation | `LazyColumn` + `MessageRow` | same structure, regrouped: user bubble, thinking block, answer, actions | A |
| Thinking stream | raw `<think>` block with `▍` | collapsible "Thinking · N tokens" block; answer separated | A/B (collapse state is new behavior) |
| Generating / Stop | send button turns into Stop | same, with clearer progress affordance | A |
| Error | Snackbar from `ui.error` | same snackbar + inline retry on failed assistant reply | A/B (inline retry is new) |
| Session list | History icon → `ModalBottomSheet` | same; richer rows with preview and date | A |
| New chat / delete session | top-bar + sheet + `AlertDialog` | same | A |
| Attach photo / gallery / mic | "+" `DropdownMenu` gated by capabilities | same; chips gain thumbnails/duration | A/B (thumbnails need image loading) |
| Copy / regenerate | tiny icon buttons on last assistant | same actions, 48 dp targets, copy-all optional | A (+ new copy-all) |
| Stats line | centered grey text | moved into message meta row | A |

### Models (`ModelsScreen.kt`, `ModelsViewModel.kt`)
| Flow / state | Current | Redesign | Class |
|---|---|---|---|
| Device info | `DeviceCard` at top of list | compact collapsible card; also feeds Device compatibility view | A |
| Recommended / All / Installed | `FilterTabs` in list | sticky segmented control | A |
| Hugging Face search | manual `SearchBox` + `vm.search` | same; results count + retry state | A |
| Installed models | `InstalledCard` | `ModelSummaryCard` with Use / Details / Delete | A |
| Catalog | `CatalogCard` with name, meta, description, capability row, fit badge, size, download | same info, clearer hierarchy and "Why this model?" affordance | A/B (details sheet is new) |
| Download progress | 4 dp bar + text + cancel | same with retry action on failure | A/B (retry may already exist in VM; verify) |
| Model detail | none | new bottom sheet/screen: capabilities, requirements, license, benchmark history | C |
| Fit / compatibility | `FitBadge` (GOOD/TIGHT/TOO_BIG) | same, plus explanation text | A |
| Delete confirmation | `AlertDialog` | shared `ConfirmDialog` | A |
| Repo file picker | `RepoDialog` `AlertDialog` | same, restyled | A |

### Settings (`SettingsScreen.kt`, `SettingsViewModel.kt`)
| Flow / state | Current | Redesign | Class |
|---|---|---|---|
| Generation group | system prompt, thinking, threads, KV, GPU, context, temperature, top-p, max tokens | grouped, with Advanced disclosure | A |
| CPU / GPU / KV config | sliders + choice rows at same weight as theme | under Advanced, with richer explanations | A |
| GPU compatibility | grey Surface with missing features | `CompatibilityCard` shared with Device view | A |
| Appearance | theme choices | same | A |
| Interface | keep-screen-on switch | same | A |
| Hugging Face token | password field | same | A |
| About | device text + body copy | same, plus diagnostics entry | A/B |
| Settings-not-applied notice | small line at bottom | inline next to affected controls | A |
| Restore defaults | none | proposed new action | C |

### Benchmark interface
| Item | Current | Redesign | Class |
|---|---|---|---|
| Benchmark run | adb intent extra only, JSONL on device | optional in-app Benchmark screen/card (read-only results + run) | C |
| Metrics | tok/s, TTFT, prompt ms, RSS/HWM in JSONL | same, presented as cards and history table | C |
| Incomplete runs | `status`, `limitReached` in JSONL | shown as excluded/greyed | C |

### Dialogs, sheets, permissions, recovery
| Item | Current | Redesign | Class |
|---|---|---|---|
| Session delete | `AlertDialog` | shared `ConfirmDialog` | A |
| Model delete | `AlertDialog` | shared `ConfirmDialog` | A |
| Repo details | `AlertDialog` | same or sheet | A |
| Mic permission | `RequestPermission` launcher, no rationale | add rationale sheet before request | B |
| Camera/photo failure | silent `runCatching` | inline error with retry | B |
| Model load failure | Status.Failed + snackbar | inline recovery card (smaller ctx / switch model) | B/C |
| Download failure | text + cancel | retry action | A/B |
| No network | search error text | `AsyncStateView` with retry | A |

**Classification legend:** A = visual only; B = UX change to existing behavior; C = new functionality.

---

## 2. Revised design system

The current monochrome palette remains the strongest part of the identity; it is consistent with the privacy/offline story and avoids theme drift. **Keep it**, but refine usage:

- **Palette**: ink/paper with five greys; hairline borders instead of shadows; error expressed with weight + outline, not color. Dark theme keeps `#C7C7CC` secondary text (already fixed earlier).
- **Starfield**: keep as a single decorative layer on Chat/Models/Settings, not on nav; reduce density/opacity behind dense forms and disable it behind sheets/dialogs. It should never compete with text.
- **Typography**: existing serif display for empty states and large titles; sans for data, labels, buttons. Add explicit sizes: display 34, headline 24, title 18/16, body 16/14, label 12/11 with 1.2 sp tracking.
- **Spacing**: 4/8/12/16/24/32 scale; 16 dp screen gutters, 16 dp card padding, 24 dp between sections.
- **Shapes**: 12 dp controls, 20 dp cards, full-circle buttons/pills; one consistent hairline.
- **Elevation**: flat cards; elevation only for sheets and dialogs.
- **Icons**: filled-rounded, 20/24 dp, always paired with text for actions except well-known glyphs (send, stop, add).
- **Component states**: default / pressed / disabled / focused / error defined once; disabled uses outline + 40% content alpha.
- **Accessibility**: 48 dp minimum touch targets, content descriptions on actionable icons, semantic grouping for decorative starfield, sp units everywhere, RTL-safe start/end.

---

## 3. Visual previews

`design-previews/.../UiPreviews.kt` contains concrete `@Preview` composables with mock data for:

- Chat empty (light), active conversation (light), generating (dark)
- Models installed + catalog (light), catalog (dark)
- Model details (light)
- Device compatibility (light)
- Settings normal (light), Settings advanced (dark)
- Benchmark (light)
- Session sheet (dark)
- Dialogs (light) — delete, permission, load-failure
- Empty/error states (light)

They use the proposed tokens and mock data only; they do not call production ViewModels or modify production UI. Both themes are represented.

---

## 4. Reusable component plan

`AppTopBar`, `ScreenScaffold`, `SectionHeader`, `SectionCard`, `SegmentedControl`, `LabeledSlider`, `SettingRow`, `StatusIndicator`, `ModelSummaryCard`, `CapabilityBadge`, `EmptyState`, `AsyncStateView`, `ConfirmDialog`, `CompatibilityCard`, `BenchmarkMetricCard`.

Each component is stateless where possible; state stays in existing ViewModels.

---

## 5. Implementation boundaries

Changes requiring ViewModel/state/navigation modifications (must be separately approved):

- Thinking collapse state (B)
- Inline retry for failed replies / downloads (B)
- Permission rationale sheet (B)
- Model details sheet with aggregated data (C)
- Restore defaults (C)
- In-app benchmark screen reading JSONL (C)
- Back-stack tab navigation (C, proposed but not adopted in this redesign)

All class-A work stays in composables and theme tokens. No inference, JNI, Vulkan, KV policy, model execution, session persistence, settings keys, or download logic changes.

---

## 6. Phased plan and verification

| Phase | Scope | Verification |
|---|---|---|
| P0 | Tokens + shared components in a preview-only module; approve visuals | Android Studio previews in both themes |
| P1 | `ScreenScaffold`, `AppTopBar`, tab badges; no behavior change | tab switching, insets, keyboard gap, system bar colors |
| P2 | Chat visual redesign (A only) | send/stop/regenerate/copy, media chips, session sheet, scroll |
| P3 | Models visual redesign (A only) | download/cancel/use/delete/search, fit badges |
| P4 | Settings visual redesign (A only) | every persisted setting before/after; GPU status text |
| P5 | Accessibility, text scaling, RTL, tablet | TalkBack, 200% font, RTL, landscape |
| P6 | Optional B/C items, each separately approved | targeted functional tests |

---

## 7. Risks

- Insets/keyboard regression (previously fixed) — re-verify with keyboard open.
- Starfield contrast behind forms — verify both themes and large font.
- ViewModel state preserved — no ViewModel signature changes in A phases.
- Download polling and recorder lifetime untouched — re-run those checks after P3/P2.
- Benchmark dev hook and settings keys untouched.
- Previews are mock-only; production wiring happens after approval.
