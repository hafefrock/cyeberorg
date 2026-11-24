# HackerStudio

A modern, bilingual **Workspace Studio** terminal shell UI for Android (`applicationId = "com.termux"`) built with **Kotlin**, **Jetpack Compose**, and **Material 3**, featuring holistic layered design tokens (`Hacker Pro` and `Cyberpunk Futuristik` themes with automatically paired terminal palettes, geometry, typography, motion, cursor styles, and `Reduce visual effects`), adaptive rail/split-pane layouts, a glassmorphic Command Palette, a PTY-ready cell-grid terminal backend, Gemini AI assistant, and fail-closed Android Keystore multi-key management.

---

## Project Architecture (Clean MVVM)

```text
app/src/main/
├── res/
│   ├── font/                        # Local JetBrains Mono & Inter .ttf fonts
│   ├── values/strings.xml           # English localization resources
│   ├── values-in/strings.xml        # Bahasa Indonesia localization resources
│   └── xml/locales_config.xml       # Per-app locale declaration (en, id)
└── java/com/example/
    ├── HackerStudioApp.kt           # Custom Application owning the single process-wide AppContainer instance
    ├── MainActivity.kt              # SplashScreen API + async DataStore hydration (no runBlocking), edge-to-edge host, LocaleManager
    ├── terminal/                    # PTY-ready terminal interface & VT cell-grid simulator
    │   ├── TerminalModels.kt        # TerminalCell, TerminalScreenBuffer, ExtraKeyCode byte sequences, TerminalInputEncoder
    │   ├── ShellIntegration.kt      # BASH (PROMPT_COMMAND) & ZSH (precmd) hooks, streaming OscStreamParser, atomic state file parser
    │   ├── TerminalBackend.kt       # TerminalSessionHandle & TerminalBackend interfaces (includes stateFilePath())
    │   └── FakeTerminalBackend.kt   # In-memory VT/ANSI cell-grid PTY simulator (SGR colors, scrollback, resize, state files)
    ├── ai/                          # Gemini SSE streaming client, safety classifier & multi-key failover engine
    │   ├── CommandRiskClassifier.kt # Pure Kotlin LOW / MEDIUM / HIGH safety classifier for suggested commands
    │   ├── KeyManager.kt            # Round-Robin / Failover, HTTP 429 & 403 delays, SSE pre-chunk failover, recheckKey()
    │   ├── GeminiApiService.kt      # Retrofit + Moshi REST & streamGenerateContent (alt=sse) client (x-goog-api-key, no logs)
    │   └── AiAssistantRepository.kt # Streaming & non-streaming prompts with toggleable context filtering & risk tagging
    ├── data/                        # Persistence, localization & fail-closed encrypted storage
    │   ├── EncryptedKeyVault.kt     # Android Keystore MasterKey + EncryptedSharedPreferences (no plaintext fallback)
    │   ├── LocaleHelper.kt          # AppLanguage (System / Bahasa Indonesia / English) + LocaleManager integration
    │   ├── SettingsModels.kt        # ExtraKeysLayout, KeyRoutingStrategy, GeminiModels, AppSettings
    │   ├── SettingsRepository.kt    # DataStore Preferences for workspace appearance, locale, and key metadata
    │   ├── SnippetModels.kt         # Moshi-backed CommandSnippet, SnippetExportEnvelope, PromptHistoryItem
    │   ├── SnippetRepository.kt     # DataStore JSON Snippets Library + SAF export/import codec
    │   ├── PromptHistoryRepository.kt # DataStore JSON Prompt History (last 30 prompts)
    │   └── AppContainer.kt          # Process-scoped manual DI graph instantiated once in HackerStudioApp
    ├── settings/                    # Two-level & wide two-pane adaptive Settings module
    │   ├── SettingsRoutes.kt        # Typed SettingsRoute, Saver, SettingsGroup, pure summary formatters
    │   ├── SettingsComponents.kt    # Uniform token-driven SettingsGroupRow/ToggleRow/ChoiceRow/ActionRow, DangerZoneSection & shared GeminiModelChoiceList
    │   ├── SettingsViewModel.kt     # AI model/fallback, keys, theme, layout, language & onboarding state
    │   └── SettingsScreen.kt + *Screen.kt # Compact home menu + per-group sub-pages (AiSettings, ApiKeys, Appearance, Terminal, Language, About)
    └── ui/
        ├── UiText.kt                # Locale-safe UI text abstraction (Resource / Dynamic)
        ├── theme/                   # DesignTokens.kt (StudioSpacing/Radius/Elevation/Motion/Surfaces), Color.kt, Type.kt, Theme.kt
        ├── onboarding/              # OnboardingScreen.kt (First-run Language -> Theme/Palette -> Skippable API Key wizard)
        ├── terminal/                # TerminalViewModel, TerminalScreen, SnippetsScreen, WorkspaceComponents, SessionTabsBar, TerminalConsoleView, ExtraKeysBar
        ├── ai/                      # AiPanel (SSE streaming, Stop/Retry, Context Chips, Prompt History) & CommandSuggestionCard (Risk Badge, Save, Insert, Copy)
        └── navigation/              # HackerStudioNavGraph (Onboarding <-> Terminal Workspace <-> Settings)
```

### Process-Scoped State & Startup Lifecycle
- **Single `AppContainer` (`HackerStudioApp`)**: Registered via `android:name=".HackerStudioApp"` in `AndroidManifest.xml`, `HackerStudioApp` creates the single `AppContainer` once per process. `MainActivity` reads `appContainer` from `application as HackerStudioApp` and never instantiates it, ensuring `TerminalBackend`, live PTY sessions, and `KeyManager` state survive screen rotation, language switching, theme changes, and Activity recreation.
- **Non-Blocking Startup (`SplashScreen` API)**: `MainActivity.onCreate` calls `installSplashScreen()` (`androidx.core:core-splashscreen`) and keeps the splash screen up only until the first DataStore `AppSettings` emission resolves asynchronously inside `lifecycleScope.launch` — eliminating `runBlocking` from the main thread.
- **Predictive Back Navigation**: `AndroidManifest.xml` enables `android:enableOnBackInvokedCallback="true"`, paired with layered Compose `BackHandler` hooks in `TerminalScreen` (Command Palette -> non-Terminal section -> AI panel).

---

## Stage 3 AI Power Features

- **SSE Streaming (`streamGenerateContent?alt=sse`)**:
  - Responses stream progressively into the AI panel as chunks arrive, with a **Stop** button to cancel generation cleanly.
  - **Failover Before First Chunk**: HTTP 429/403 errors prior to the first chunk mark the active key on cooldown/forbidden-delay and seamlessly retry the next enabled key.
  - **Mid-Stream Break Recovery**: If the stream drops after partial chunks arrive, generated text is preserved with an inline **Retry** action.
  - **Blocked / Empty Finish**: Streams ending with `finishReason` `SAFETY`/`BLOCKED` or empty text render `"Response blocked or empty"` without penalizing the active key's error count or cooldown.
- **Bracketed Paste Insert Safeguard (`DECSET 2004`)**:
  - `TerminalSessionHandle` exposes `bracketedPasteEnabled: StateFlow<Boolean>` (tracked from `ESC[?2004h` / `ESC[?2004l` in the emulator parser) and `pasteText(text: String)`.
  - Tapping **Insert** wraps text in `ESC[200~ ... ESC[201~` (with no trailing newline) when bracketed paste mode is enabled rather than rewriting newlines with `&&`. If DECSET 2004 is off and a command contains newlines, HackerStudio copies it to the clipboard and displays an explanatory banner instead of pasting raw newlines; single-line commands insert as-is.
- **In-Terminal "Fix with AI" & Selection Toolbar**:
  - When the active session reports a non-zero exit code, a lightweight dismissible **Explain / Fix with AI** chip appears above the terminal edge without blocking keyboard or extra-key input.
  - Long-pressing and dragging across the terminal cell grid highlights characters and opens a floating **Copy / Ask AI / Explain** toolbar.
- **Command Safety (`CommandRiskClassifier`)**:
  - Every suggested command card and snippet displays an advisory **LOW / MEDIUM / HIGH** risk badge (`"Heuristic check, not a guarantee"` shown in both the badge advisory dialog and the high-risk confirmation dialog). Read-only partition inspections (`fdisk -l`, `parted -l`, `gdisk -l`) classify as **LOW**.
  - **HIGH-risk** commands (e.g., destructive `rm -rf` targets, `dd of=/dev/...`, `mkfs`, `chmod -R 777`, `curl | sh`, fork bombs, block-device redirection) require explicit confirmation explaining why before **Insert** writes bytes to the terminal. Commands are **never auto-executed**.
- **Toggleable Context Chips**:
  - Removable chips above the AI composer (`Folder`, `Last error`, `Selection`, `Last 20 lines`) control precisely what terminal context is attached to each request; disabled context is never transmitted.
- **Snippets Library & Prompt History**:
  - **Save** any suggested command into a searchable, tag-filterable **Snippets** library (`DataStore` JSON) with create/edit/rename/delete and **Storage Access Framework (SAF)** JSON export/import (`CreateDocument` / `OpenDocument` — zero storage permissions needed).
  - **Prompt History** retains the last 30 prompts in the AI panel for one-tap reuse or clearing.

---

## Workspace Studio UI & Design System

- **Holistic Two-Theme System (`ui/theme/DesignTokens.kt`, `Color.kt`, `ChamferShape.kt`, `StudioModifiers.kt`)**:
  - **Hacker Pro** (`AppThemePreset.HACKER_PRO`, default): Dense utilitarian workspace (`density = 0.85f`), sharp `4.dp` corners, flat `1.dp` border strokes, `JetBrains Mono` throughout with uppercase tracked headings, `$` prompt glyph, solid `BLOCK` cursor, monospace `[LOW]`/`[MED]`/`[HIGH]` badges, top-line active nav indicator, and zero glow (`motionDurationMs = 120`).
  - **Cyberpunk Futuristik** (`AppThemePreset.CYBERPUNK`): Asymmetric chamfered polygon shapes (`ChamferShape`), cyan/magenta neon palette with subtle canvas gradient, `JetBrains Mono` headings + `Inter` body text, `▸` prompt glyph, `UNDERLINE_GLOW` cursor rendered on an isolated overlay Canvas, chamfered icon risk badges, bottom neon glow nav indicator, and selective `studioGlow` (`motionDurationMs = 220`).
  - **Paired Terminal Palette & Safe Migration**: `TerminalColorScheme` derives automatically from `AppThemePreset`, and `AppThemePreset.fromStoredName(...)` safely migrates legacy or unknown theme names to `HACKER_PRO`.
  - **Reduce Visual Effects**: Respects system `ANIMATOR_DURATION_SCALE == 0` by default or an explicit user toggle in Settings, disabling glow, pulse, and cursor blink and setting motion duration to `0ms`.
- **Adaptive Layouts**:
  - **Phones (`< 600.dp`)**: Compact bottom `NavigationBar` (`Terminal`, `Files`, `AI`, `Snippets`, `Settings`), swipe/drawer AI panel, haptic-feedback `ExtraKeysBar`.
  - **Tablets & Foldables (`>= 600.dp` / `>= 720.dp`)**: Left `NavigationRail` plus split-pane toggle (`Single`, `Term + AI`, `Dual Term`).
- **Command Palette**: Tap the search button in the top bar to filter and invoke workspace actions, switch studio themes, or switch app languages on the fly.
- **Session Tabs & Bottom Status Bar**: Session chips display active/OK/Error status dots; bottom status bar surfaces `cwd`, `EXIT <code>`, active API key label, and selected Gemini model.

---

## Theme Architecture (`AppThemePreset`)

1. Theme presets live in `AppThemePreset` (`app/src/main/java/com/example/ui/theme/Color.kt`), each supplying a complete `StudioSurfaceTokens` specification (`cardShape`, `buttonShape`, `smallShape`, `pillShape`, `sheetShape`, typography, spacing density, motion duration, `promptGlyph`, `cursorStyle`, `badgeStyle`, `navIndicatorStyle`, `iconStyle`, glow parameters) and its paired `TerminalColorScheme`.
2. Legacy or unknown stored theme names pass through `AppThemePreset.fromStoredName(String?)` and safely fall back to `HACKER_PRO`.
3. Settings, Onboarding, Command Palette, `TerminalConsoleView`, and `HackerStudioTheme` iterate `AppThemePreset.entries` and consume `StudioTheme.tokens` automatically.

---

## How to Add a New Language

1. Create a new Android resource directory `app/src/main/res/values-<bcp47>/strings.xml` (for example, `values-ja/strings.xml`) translating all keys from `values/strings.xml`.
2. Register the new locale tag in `app/src/main/res/xml/locales_config.xml`:
   ```xml
   <locale android:name="ja" />
   ```
3. Add a localized display label in `strings.xml` / `values-in/strings.xml`, then add the enum entry to `AppLanguage` in `app/src/main/java/com/example/data/LocaleHelper.kt`.
4. Update `AiAssistantRepository.buildSystemInstruction(language: AppLanguage)` if you want Gemini to include a specific language directive for the new `AppLanguage`.

---

## How to Add a New OFL Terminal Font

1. Place an Open Font License (OFL) `.ttf` monospace font file in `app/src/main/res/font/` using lowercase underscore naming (for example, `fira_code.ttf`).
2. Add a localized display label and short description in `app/src/main/res/values/strings.xml` and `app/src/main/res/values-in/strings.xml`.
3. Register a new `TerminalFont` entry in `TerminalFontRegistry.entries` (`app/src/main/java/com/example/terminal/TerminalFontRegistry.kt`) with a unique `id`, `@StringRes labelRes`, `@StringRes descriptionRes`, and `fontResId = R.font.fira_code`.
4. Optionally map the `id` in `terminalComposeFontFamily` (`app/src/main/java/com/example/ui/theme/Type.kt`) so live Settings previews use the exact `FontFamily`. Both `TerminalConsoleView` and PNG export (`TerminalOutputExporter`) resolve the typeface automatically via `TerminalFontRegistry`.

---

## PTY-Ready Terminal Contract & Plugging in Termux

`TerminalViewModel` and `TerminalConsoleView` depend **exclusively** on `TerminalBackend` and `TerminalSessionHandle`:

### Shell Integration (`PROMPT_COMMAND` / `precmd` + `OSC 7777` + Atomic State File)
`ShellIntegration` provides both **BASH** (`bashSnippet(stateFilePath)` via `PROMPT_COMMAND`) and **ZSH** (`zshSnippet(stateFilePath)` via `add-zsh-hook precmd`) hooks. Each hook reports `(exitCode, cwd)` over two redundant channels:
1. **In-band `OSC 7777` stream (`OscStreamParser`)**: emits `\033]7777;exit=<int>;cwd=<path>\007`. The stateful `OscStreamParser` handles sequences split across arbitrary byte chunks, supports both `BEL` (`0x07`) and `ST` (`ESC \`) terminators, and caps its buffer at **4 KB** before flushing malformed/oversized data as plain text.
2. **Out-of-band atomic state file (`stateFilePath()`)**: atomically writes `exit=<code>\ncwd=<path>\n` to `<stateFile>.tmp` and renames via `mv -f`. **Why this channel exists:** real terminal emulators (including Termux's `TerminalEmulator` and multiplexers like `tmux`/`screen`) may swallow, strip, or ignore unknown private `OSC` escape sequences like `7777`. Writing atomically to `stateFilePath()` guarantees reliable exit-code and `cwd` tracking even when `OSC` codes are filtered out.

### Next Step: Integrating the Real Termux Terminal
Follow this checklist to replace `FakeTerminalBackend` with a live native PTY shell powered by Termux:
- [ ] **1. Add Termux Modules**: Add `:terminal-emulator` (and optionally `:terminal-view`) from `termux-app` in `settings.gradle.kts` and `implementation(project(":terminal-emulator"))` in `app/build.gradle.kts`.
- [ ] **2. Bootstrap Rootfs / Shell Binary**: Ensure a valid POSIX shell binary + environment (`PREFIX`, `HOME`, `TMPDIR`, `TERM=xterm-256color`) is unpacked or pointed to inside `context.filesDir`.
- [ ] **3. Implement `TermuxSessionHandle : TerminalSessionHandle`**:
  - Forward `write(bytes)` -> `terminalSession.write(bytes, 0, bytes.size)`.
  - Forward `resize(cols, rows)` -> `terminalSession.updateSize(cols, rows, cellWidth, cellHeight)`.
  - Map `terminalSession.emulator.screen` rows/styles into `TerminalScreenBuffer` snapshots inside `readScreen()` and bump `_screenVersion` on `onTextChanged`.
  - Provide a unique per-session `stateFilePath()` inside `context.filesDir/session-state/<id>.state` and watch/read it after output changes alongside `ShellIntegration.OscStreamParser`.
- [ ] **4. Inject Shell Integration on Startup**: Pass `ShellIntegration.bashSnippet(stateFilePath)` (or `zshSnippet(...)`) via `PROMPT_COMMAND` env / startup rc file so `cwd`, `lastExitCode`, and the in-terminal **Fix with AI** chip populate automatically from live shell commands.
- [ ] **5. Implement `TermuxTerminalBackend : TerminalBackend`**: Manage session lifecycle (`createSession`, `closeSession`, `switchSession`) and clean up PTY file descriptors + `.state` files on close.
- [ ] **6. Swap DI Binding in `AppContainer.kt`**: Replace `FakeTerminalBackend()` with `TermuxTerminalBackend(context)` inside `HackerStudioApp`'s `AppContainer`. All ViewModels, AI context chips, selection toolbar, and Snippets keep working without UI changes.

---

## Multi-API-Key Security & Failover

- **Fail-Closed Encryption (`EncryptedKeyVault`)**: Raw Gemini API keys are stored strictly in `EncryptedSharedPreferences` (`AndroidKeyStore` `MasterKey` with `AES256_GCM` / `AES256_SIV`). There is **no** plaintext or in-memory fallback: if encrypted storage initialization or commit fails, `SettingsScreen` surfaces a security error banner and refuses to store keys.
- **No Logging**: `GeminiApiClient` omits HTTP body/header logging interceptors and sends keys via `x-goog-api-key` headers.
- **HTTP 429 & HTTP 403 Retry Delays + Recheck (`KeyManager`)**:
  - **HTTP 429** (`COOLDOWN`) and **HTTP 403** (`FORBIDDEN_DELAY`) both mark the key on a configurable retry delay (`15s–180s`) and automatically fail over to the next enabled key (both for standard requests and for SSE streams prior to the first chunk).
  - **HTTP 401** (`AUTH_ERROR`) flags the key as unauthenticated until repaired.
  - `NetworkOrServerError` during `recheckKey(id)` preserves the key's existing health status and shows a transient `"Network error, try again"` note.
- **Safety First**: AI command cards and Snippets show **LOW / MEDIUM / HIGH** safety badges, require explicit confirmation for **HIGH** risk inserts, and provide **Insert** (writes bytes without `\r`) and **Copy** actions — commands are **never auto-executed**.


---

## Environment Setup & Building APKs

### 1. Environment Check
```bash
chmod +x scripts/setup-env.sh scripts/build-apk.sh
./scripts/setup-env.sh
```

### 2. Build Debug APK (`debugConfig` signing)
```bash
./scripts/build-apk.sh debug
# or directly:
gradle :app:assembleDebug
```
Output: `app/build/outputs/apk/debug/app-debug.apk`

### 3. Build Release APK (`release` signing)
```bash
export KEYSTORE_PATH=/path/to/my-upload-key.jks
export STORE_PASSWORD="your_store_password"
export KEY_PASSWORD="your_key_password"

./scripts/build-apk.sh release
# or directly:
gradle :app:assembleRelease
```
Output: `app/build/outputs/apk/release/app-release.apk`

### 4. Run Unit & Robolectric Tests
```bash
gradle :app:testDebugUnitTest
```
