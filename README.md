# VoiceToCAD Studio (Android • Pixel 9 Pro)

Standalone on-device **Voice-to-CAD** Android application with an interactive 3D viewport, searchable/editable parametric dimension table, mechanical engineering guardrails, and on-device **OpenCASCADE (ISO-10303-21 `.STEP` AP214)** & **`.STL`** export.

## 📲 Download APK on Pixel 9 Pro
👉 **[Download `VoiceToCAD-pixel9pro.apk` (arm64-v8a)](https://github.com/WillNunez/CadApp/raw/main/releases/VoiceToCAD-pixel9pro.apk)**

1. Open the link above in Chrome or GitHub Mobile on your **Pixel 9 Pro**.
2. Tap **Download anyway** and open the downloaded `VoiceToCAD-pixel9pro.apk` to install.

---

## ✨ Key Features
- **3 Speech-to-Text (STT) Engines:**
  - **Pixel 9 Pro Tensor G4 Offline Speech** (`EXTRA_PREFER_OFFLINE` + CAD vocabulary biasing)
  - **Vosk Open-Source Offline STT** (`com.alphacephei:vosk-android` Apache 2.0 Kaldi engine with 1-tap model download & strict CAD grammar)
  - **Open-Source Whisper** (`16 kHz` PCM WAV recorder for `faster-whisper` / `whisper.cpp`)
- **Named, Searchable & Editable Parametric Dimensions Table:**
  - Every dimension created on the solid (`block_width_x`, `block_depth_y`, `block_height_z`, `top_fillet_radius`, `bottom_chamfer_dist`, `hole_bore_diameter`, `shell_wall_thickness`) is searchable and editable via numeric input, `-`/`+` steppers, or 120Hz live thumb sliders.
- **Deterministic Mechanical Engineering Guardrails:**
  - Automatically clamps excessive fillet/chamfer radii ($\le 42\%$ of shortest edge) and enforces $\ge 2.0\text{ mm}$ minimum wall thickness around holes.
- **On-Device OpenCASCADE `.STEP` & `.STL` Export:**
  - Exports valid **ISO-10303-21 (`.STEP` AP214)** B-Rep solids and **ASCII/Binary `.STL`** meshes directly on the phone, plus generates **OpenCASCADE C++** and **CadQuery (Python)** scripts.
