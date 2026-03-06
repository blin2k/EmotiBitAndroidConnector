# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test Commands

```bash
./gradlew assembleDebug          # Build debug APK
./gradlew installDebug           # Deploy to connected device/emulator
./gradlew testDebugUnitTest      # Run JVM unit tests
./gradlew connectedAndroidTest   # Run instrumentation tests (device required)
./gradlew lint                   # Android lint checks
```

**Prerequisite:** Place `google-services.json` in `app/` for Firebase Storage integration.

## What This App Does

EmotiBit Connector is an Android app that acts as a Wi-Fi host for EmotiBit biosensor devices. It discovers sensors on the local subnet, manages an ASCII control protocol (HE/EC/PN/PO) over UDP/TCP, receives streaming data, records payloads to CSV, logs GPS coordinates, and uploads recordings to Firebase Storage.

## Architecture (MVVM)

Single-module Kotlin + Jetpack Compose app under `app/src/main/java/com/example/emotibitconnector/`.

**View:** `ui/EmotiBitScreen.kt` — All Compose UI. Two screens (Home, SavedRecordings) managed by a simple enum state variable, no navigation library. State is hoisted; all callbacks delegate to ViewModel.

**ViewModel:** `EmotiBitViewModel.kt` — Extends `AndroidViewModel`. Exposes a single `StateFlow<EmotiBitUiState>` with immutable updates via `_uiState.update { it.copy(...) }`. Contains all data classes (`EmotiBitUiState`, `UiLogEntry`, `UiDiscovered`, `RecordFileInfo`, `RecordExportTarget`). Manages wake locks, Wi-Fi locks, and network change detection.

**Model/Network layer:**
- `network/EmotiBitClient.kt` — Raw Java sockets (DatagramSocket, ServerSocket). UDP receive loop, TCP server, EC heartbeat, Wi-Fi network binding, subnet scanning. No third-party HTTP libraries.
- `network/EmotiBitProto.kt` — Protocol constants (ports 3131/3132/3133) and ASCII packet builders.
- `CsvRecorder.kt` — Coroutine-based buffered CSV writer using a `Channel<CsvRow>` (capacity 8192, DROP_OLDEST). Batches 200 rows or 250ms.
- `LocationLogger.kt` — GPS/Network location provider writing 1Hz CSV rows.
- `SessionService.kt` — Foreground service (`foregroundServiceType="dataSync"`) for background streaming.
- `osc/OscModels.kt` — OSC binary parser/encoder (retained but not used in current ASCII protocol path).

**No DI framework** — dependencies are manually constructed in the ViewModel.

## Key Conventions

- Kotlin style: 4-space indentation, trailing commas, UpperCamelCase for classes/composables, lowerCamelCase for functions/locals
- All logging uses `Logx` singleton with Logcat tag `"EmotiBit"`
- Prefer immutable state; hoist state out of composables
- Compose theme files in `ui/theme/` (Color.kt, Theme.kt, Type.kt) — Material3 with dynamic color on Android 12+
- Groovy DSL for Gradle files (not KTS); version catalog in `gradle/libs.versions.toml`
- Target/compile SDK 36, min SDK 24, Java 11 source/target

## Commit Style

Imperative, present-tense titles capped at 72 characters. Reference issues with `Refs #123` or `Fixes #123` in the body.
