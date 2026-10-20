# OmniHear (SafeHear)

Offline Android app that detects emergency sounds (car horns and sirens) in real time and alerts hearing-impaired users with visual, audio and haptic feedback.

## How it works
- Captures 16 kHz mic audio with `AudioRecord` in `VOICE_RECOGNITION` mode (raw audio, no OS noise suppression).
- Runs a sliding window of 15,600 samples (0.975 s) through a TensorFlow Lite model.
- Model: YAMNet (AudioSet) transfer learning plus a custom 521x3 mapping matrix that routes class scores to Background / Car Horn / Siren.
- If Car Horn or Siren confidence exceeds 30%, it triggers a red screen flash, an alert tone and vibration, with a 5 s cooldown.

## Results
- ~4.46 ms inference latency (Float32).
- INT8 quantization gave a 73.6% smaller model (14.32 MB to 3.77 MB); Float32 was kept for better sensitivity to faint sounds.

## Project layout
- `app/` - Android app (Java, min SDK 24, TensorFlow Lite 2.17)
- `app/src/main/assets/custom_safehear.tflite` - exported model
- `build_perfect_model.py`, `rebuild_model.py`, `EIS.ipynb` - model construction and export scripts

## Build
Open in Android Studio, let Gradle sync, and run on a device with a microphone (Android 7.0+).

## Team
Shivraj Jirli, Pradnya J. Purohit, Achyut Bidkar, Bhavana A.
KLE Technological University, Hubballi. Guides: Prof. Sunil Gurlahosur and Prof. Satish Chikkamath.
