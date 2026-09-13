# DevicePilot AI

> **An autonomous on-device agent for Android.** A small local LLM (Qwen2.5 0.5B / 1.5B via llama.cpp) sees your screen, taps, types, opens apps and changes system settings — all through Shizuku's ADB-level privileges. No cloud, no root.

---

## What it does

Tell it a goal in plain language and it works through it step by step, showing every action it takes:

- *"Turn on dark mode and set brightness to 40%"*
- *"Open Telegram, search for Ali and open the chat"*
- *"Which apps have camera permission? Revoke it for TikTok."*
- *"Read my notifications and dismiss them"*
- *"Connect to the Wi-Fi called Office"*

The agent runs a **ReAct loop**: it reads the device/screen, picks one tool, observes the result, and repeats until it calls `done`. Destructive actions (uninstall, clear data, shell commands, reboot…) pause for an in-chat **Allow / Deny** confirmation. It can ask you a question mid-task when it needs information.

## Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│  Compose UI                                                      │
│  chat · per-step cards (thought → action → result) · confirm     │
│  cards · model picker + downloader · Stop button                 │
└───────────────┬──────────────────────────────────────────────────┘
                │ AgentSession (Application scope, survives Activity)
                ▼
┌──────────────────────────────────────────────────────────────────┐
│  AgentRunner — ReAct loop                                        │
│  build ChatML prompt → LlamaEngine → ToolCallParser              │
│  → risk gate (SAFE / LOW / CONFIRM) → Tool.execute → feed result │
│  loop detection · step cap · ask_user / reply / done             │
└──────┬─────────────────────────────────────┬─────────────────────┘
       │                                     │
       ▼                                     ▼
┌──────────────────────┐        ┌───────────────────────────────────┐
│ LlamaEngine (JNI)    │        │ ToolRegistry  (~60 tools)         │
│ llama.cpp, KV-cache  │        │  screen: get_screen, tap_element, │
│ prefix reuse across  │        │   type_text, swipe, press_key…    │
│ steps, cancel, UTF-8 │        │  apps: open_app, list_apps,       │
│ safe streaming       │        │   uninstall, permissions…         │
└──────────────────────┘        │  system: wifi, bt, brightness,    │
                                │   DND, dark mode, alarms, intents…│
┌──────────────────────┐        │  files / info / shell (guarded)   │
│ ModelManager         │        └───────────────┬───────────────────┘
│ HF download, resume, │                        ▼
│ 0.5B ⇄ 1.5B switch   │        ┌───────────────────────────────────┐
└──────────────────────┘        │ ShizukuShell  → Shizuku.newProcess│
                                │ (adb shell privileges)            │
┌──────────────────────┐        └───────────────────────────────────┘
│ AgentForegroundSvc   │  keeps the loop alive while the agent drives
│ + floating STOP pill │  other apps; notification shows current step
└──────────────────────┘
```

### Key packages

| Package | Purpose |
|---|---|
| `agent/` | `AgentRunner` (loop), `AgentPrompt` (ChatML + compact tool catalog), `ToolCallParser` (lenient JSON), `AgentSession` (app-scoped state) |
| `tools/` | `Tool`/`ToolRegistry`, `UiTools` (perception + input), `AppTools`, `SystemTools`, `ScreenReader` (uiautomator → indexed elements), `ShellGuard` (blocklist) |
| `llm/` | `LlamaEngine` Kotlin wrapper over `cpp/llama-jni.cpp` |
| `model/` | `ModelCatalog`, `ModelManager` (download / resume / select) |
| `service/` | Foreground service + overlay Stop button |
| `shizuku/` | `ShizukuManager` (binder/permission), `ShizukuShell` (exec) |

### Screen perception

`get_screen` runs `uiautomator dump`, parses the hierarchy and gives the model a compact indexed list:

```
app=org.telegram.messenger activity=LaunchActivity screen=1080x2400
[0] button desc="Open navigation menu" @66,150
[1] input "Search" id=search_field @540,150
[2] text "Ali Karimov" @400,420
...
```

The model then calls `tap_element(index=1)` — it never guesses coordinates.

### Safety model

| Risk | Examples | Behaviour |
|---|---|---|
| `SAFE` | get_screen, list_apps, get_device_state | runs immediately |
| `LOW` | tap, type, open_app, set_wifi, set_brightness | runs immediately, shown in step card |
| `CONFIRM` | uninstall_app, clear_app_data, set_permission, install_apk, shell, reboot, write/delete_file | pauses for Allow/Deny (can be disabled in settings) |

`ShellGuard` additionally hard-blocks commands that could brick the device or lock out Shizuku/DevicePilot (`rm -rf /`, factory reset, disabling SystemUI, `su`, etc.), regardless of confirmation.

## Models

| Model | Size | RAM | Notes |
|---|---|---|---|
| Qwen2.5-0.5B-Instruct Q4_K_M (default) | ~490 MB | 3 GB+ | fast; good for 1–3 step tasks |
| Qwen2.5-1.5B-Instruct Q4_K_M | ~1.1 GB | 6 GB+ | recommended for multi-step UI automation |

Switch in **⚙ → Model**. The selected model is downloaded from Hugging Face on first use (resumable) into `Android/data/com.devicepilot.ai/files/models/`. If you already have the GGUF in `Download/`, it is imported automatically.

## Build

```bash
git clone --recursive https://github.com/Nurboev1/Device-Pilot-AI
# or, if already cloned:
git submodule update --init --recursive     # pulls llama.cpp

cp local.properties.example local.properties  # set sdk.dir
./gradlew assembleDebug
```

Requirements: Android Studio Koala+, NDK 26.1, CMake 3.22, arm64-v8a device on Android 8+ (Android 11+ recommended for `cmd wifi connect-network`, `cmd uimode`, etc.).

## Setup on device

1. Install [Shizuku](https://shizuku.rikka.app/), start it (Wireless debugging or root).
2. Open DevicePilot → tap the Shizuku chip → grant permission.
3. Grant **Display over other apps** (floating Stop button) and notifications when prompted.
4. Wait for the model to download and load, then give it a task.

## Limitations

- `type_text` uses `input text`, which is ASCII-only on most ROMs. Non-Latin text may not be typed correctly.
- Secure screens (banking apps, password fields flagged `FLAG_SECURE`) return no UI hierarchy.
- 0.5B is a very small model: it may loop or misread complex screens. Use 1.5B for anything beyond simple settings changes.
- Some `cmd`/`svc` sub-commands vary by OEM and Android version; tools report the raw error when a command isn't supported.
