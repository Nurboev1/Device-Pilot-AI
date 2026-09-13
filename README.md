# DevicePilot AI

> **Autonomous On-Device AI Assistant with Deep Device Control (Android Native)**

DevicePilot AI is a native Android application built in Kotlin with Jetpack Compose (Material 3 Dark Slate theme). It integrates an on-device local LLM (**Qwen2.5-0.5B-Instruct** in GGUF format via native `llama.cpp` JNI) and utilizes **Shizuku API** (elevated ADB permissions via Wireless Debugging) to execute privileged system-level actions via natural language instructions.

---

## 📱 Features

- **On-Device Intelligence**: Local inference with Qwen2.5-0.5B-Instruct using ARM64-v8a native `llama.cpp` JNI bindings.
- **Privileged System Control via Shizuku**:
  - `set_screen_brightness(level: 0..255)` (`settings put system screen_brightness <val>`)
  - `set_media_volume(level: 0..15)` (`cmd media_session volume --stream 3 --set <val>`)
  - `toggle_wifi(state: Boolean)` (`svc wifi [enable|disable]`)
  - `silent_uninstall_app(packageName: String)` (`pm uninstall <packageName>`)
  - `list_installed_apps(filter: String)` (`pm list packages -3`)
- **Real-Time Token Streaming**: Real-time token flow emission directly to Jetpack Compose chat bubbles with animated typing indicators.
- **Tool Routing & Execution Cards**: Dedicated visual cards embedded in bot bubbles displaying tool names, arguments, command details, and real-time execution badges (`SUCCESS`, `FAILED`, `EXECUTING`).
- **Dark Slate Aesthetic**: Designed with Material 3 dark palette, glassmorphism status chips, and high-contrast accessibility.
- **Resilient Fallback Mode**: If the GGUF model weights are not yet downloaded, DevicePilot AI automatically activates its simulated grammar engine so you can test and operate Shizuku commands immediately.

---

## 🏗️ System Architecture

```
┌────────────────────────────────────────────────────────────────┐
│                   Jetpack Compose UI (Chat)                    │
│   - Real-time token streaming                                  │
│   - Shizuku Status Badge (Connected / Missing / Waiting)       │
│   - Model Status Badge (Loaded / Memory Usage / Speed)         │
└───────────────────────────────┬────────────────────────────────┘
                                │
                                ▼
┌────────────────────────────────────────────────────────────────┐
│           Orchestrator & Tool Routing Engine                   │
│   1. Receives user natural language prompt.                    │
│   2. Calls native llama.cpp (Qwen2.5-0.5B-Instruct).           │
│   3. Detects if output is an actionable tool JSON call.        │
│      - If Tool: Dispatches to Shizuku DeviceController.        │
│      - If Conversation: Streams response directly to UI.       │
└──────────────────┬───────────────────────────┬─────────────────┘
                   │                           │
                   ▼                           ▼
┌────────────────────────────────┐ ┌─────────────────────────────┐
│    llama.cpp Android JNI       │ │   Shizuku DeviceController  │
│   - ARM64 native binaries      │ │   - Shizuku.newProcess()    │
│   - Loads Qwen2.5-0.5B GGUF    │ │   - `pm uninstall`          │
│   - Prompt template formatter  │ │   - `settings put system`   │
│   - Token inference loop       │ │   - `svc wifi`              │
└────────────────────────────────┘ └─────────────────────────────┘
```

---

## 📂 Directory Layout

```
DevicePilotAI/
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/
│       └── main/
│           ├── AndroidManifest.xml
│           ├── cpp/
│           │   ├── CMakeLists.txt
│           │   ├── llama-jni.cpp
│           │   └── include/
│           │       └── llama.h
│           ├── java/com/devicepilot/ai/
│           │   ├── DevicePilotApp.kt
│           │   ├── MainActivity.kt
│           │   ├── llm/
│           │   │   ├── LlamaEngine.kt
│           │   │   └── ToolRouter.kt
│           │   ├── shizuku/
│           │   │   ├── DeviceController.kt
│           │   │   └── ShizukuManager.kt
│           │   └── ui/
│           │       ├── ChatScreen.kt
│           │       ├── ChatViewModel.kt
│           │       └── theme/
│           │           ├── Color.kt
│           │           └── Theme.kt
│           └── res/
│               ├── values/
│               │   ├── colors.xml
│               │   ├── strings.xml
│               │   └── themes.xml
│               └── xml/
│                   ├── backup_rules.xml
│                   └── data_extraction_rules.xml
├── gradle/wrapper/
│   └── gradle-wrapper.properties
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradlew
└── gradlew.bat
```

---

## 🚀 Getting Started

### 1. Requirements
- **Android Studio**: Iguana (2023.2.1) or Koala (2024.1.1+)
- **Android SDK**: API 34 (Android 14)
- **NDK**: Version 25.x or 26.x
- **Device / Emulator**: Android 8.0+ (API 26+) with `arm64-v8a` architecture

### 2. Setting up Shizuku on your Android Device
1. Install **Shizuku** from Google Play Store or GitHub releases.
2. Open **Developer Options** on your phone and enable **Wireless Debugging**.
3. Open Shizuku, tap **Start via Wireless Debugging**, and pair using the pairing code notification.
4. Once Shizuku shows *"Shizuku is running"*, launch DevicePilot AI.
5. Tap the yellow **Shizuku: Tap to Authorize** chip at the top of DevicePilot AI and grant permission.

### 3. Deploying Qwen2.5-0.5B-Instruct Model (Optional)
To run full local neural inference:
1. Download `qwen2.5-0.5b-instruct-q4_k_m.gguf` (~390 MB) from Hugging Face:
   ```bash
   curl -L -o qwen2.5-0.5b-instruct-q4_k_m.gguf \
     https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf
   ```
2. Push the model to your device's Download folder:
   ```bash
   adb push qwen2.5-0.5b-instruct-q4_k_m.gguf /sdcard/Download/
   ```
3. DevicePilot AI automatically discovers the file on startup.

### 4. Build & Run
Run from terminal:
```bash
./gradlew assembleDebug
```
Or open the `DevicePilotAI` directory in Android Studio and press **Run** (`Shift + F10`).
