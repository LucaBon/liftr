# Liftr

An Android app that turns your phone camera into a barbell-tracking coach. Uses on-device computer vision (MediaPipe Pose) to count reps, measure bar velocity, score rep quality, and validate form in real time — no wearables or sensors required.

## Features

- **Real-time rep counting** with exercise-specific phase detection
- **Velocity-Based Training (VBT)** zones and velocity-loss tracking
- **Per-rep quality scoring** and form validation
- **Bar-path visualization and replay**
- **Tempo training mode**
- **Supported lifts:** back squat, front squat, bench press, deadlift, clean, snatch
- **Offline-first:** all vision processing runs on-device; no video leaves your phone
- **Analytics:** volume, tonnage, estimated 1RM, velocity trends, quality score history

## Tech stack

- **Kotlin** + **Jetpack Compose**
- **CameraX** + **MediaPipe Pose Landmarker** for vision
- **Hilt** for dependency injection
- **Room** for local persistence
- **DataStore** for preferences
- **MPAndroidChart** for analytics visualization
- Clean Architecture: `:domain` module (pure Kotlin JVM, no Android) → `data/` + `vision/` → `ui/` in `:app`

## Build

Requires Android Studio Hedgehog+ with JDK 17.

```bash
./gradlew assembleDebug            # Build debug APK
./gradlew installDebug             # Install on connected device
./gradlew :domain:test testDebugUnitTest  # Run unit tests (domain + app)
./gradlew jacocoTestReport         # Coverage report
```

### Release signing

Release builds are signed with the Play upload key. Copy `keystore.properties.example`
to `keystore.properties` (gitignored) and fill it in, or set `LIFTR_KEYSTORE_FILE`,
`LIFTR_KEYSTORE_PASSWORD`, `LIFTR_KEY_ALIAS` and `LIFTR_KEY_PASSWORD`. Then:

```bash
./gradlew bundleRelease            # Signed AAB for Google Play
```

Without either, release builds are produced unsigned.

See [`CLAUDE.md`](./CLAUDE.md) for architecture notes and development conventions.

## License

Liftr is licensed under the **GNU Affero General Public License v3.0** (AGPL-3.0).

See [`LICENSE`](./LICENSE) for the full text.

**TL;DR:**
- You are free to use, study, modify, and redistribute this software.
- Any modified version — including one offered as a network service — must also be released under AGPL-3.0, with complete source code made available to its users.
- This repository is **open source**, not public domain: the copyleft terms are enforceable.

### Commercial licensing

If you want to use Liftr (or derivative works) in a closed-source product, internal deployment, or any context where AGPL-3.0 obligations are not acceptable, a **commercial license** is available.

Contact: **luca.bonura@gmail.com**

### Contributing

Contributions are welcome. Please read [`CONTRIBUTING.md`](./CONTRIBUTING.md) before opening a pull request — in particular, all commits must be signed off under the Developer Certificate of Origin (`git commit -s`).

---

Copyright © 2025–2026 Luca Bonura. All rights reserved under AGPL-3.0.
