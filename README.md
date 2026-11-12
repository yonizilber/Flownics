# Flownics

A personal workout app for Android: build your own exercise library, assemble workouts from it,
and fill the rest between strength sets with *active rest* (stretching, mobility, breathing).

Status: early development.

## Features (so far)
- Exercise library: name, type (strength, flexibility, mobility, cardio, core, balance, breathing,
  warm-up), main and secondary muscles, equipment, cues, multi-stage exercises, photos, videos and
  video links.
- Search by name, muscle or equipment; filter by type and muscle.
- Hebrew and English UI, with full right-to-left support.

## Privacy and security
- All data stays on the device. There are no accounts and no analytics.
- AI features (coming) use **your own** API key, which is encrypted with the Android Keystore and
  excluded from backups. No key is ever bundled with the app.
- Photos and videos are copied into the app's private storage through the system Photo Picker; the
  app requests no storage permission.

## Disclaimer
Flownics is not medical advice. Consult a doctor before starting a new exercise program.

## Building
Open the project in Android Studio and run the `app` configuration on a device or emulator
(minSdk 26).

## License
Apache License 2.0 — see [LICENSE](LICENSE).
