# Repository Guidelines

## Project Structure & Module Organization
The Android app lives in `app/`, with the Jetpack Compose entry point at `app/src/main/java/com/example/emotibitconnector/MainActivity.kt`. Shared theme utilities reside in `ui/theme/`, while resources remain under `app/src/main/res`. JVM unit tests are in `app/src/test`, instrumentation tests in `app/src/androidTest`, and Gradle version catalogs in `gradle/libs.versions.toml`. Keep local SDK paths and secrets in `local.properties` and never commit sensitive values.

## Build, Test, and Development Commands
- `./gradlew assembleDebug` — compile a debuggable APK.
- `./gradlew installDebug` — deploy the latest debug build to an attached device or emulator.
- `./gradlew testDebugUnitTest` (or `./gradlew test`) — run JVM unit tests.
- `./gradlew connectedAndroidTest` — execute instrumentation tests; requires an unlocked device.
- `./gradlew lint` — launch Android lint for Kotlin and Compose checks.

## Coding Style & Naming Conventions
Follow the official Kotlin style guide: 4-space indentation, meaningful identifiers, and trailing commas where they reduce diffs. Name classes and composables with UpperCamelCase, functions/locals with lowerCamelCase, and resource files using lowercase underscores. Group new Compose features inside `ui/` subpackages and keep reusable colors/typography updates inside `ui/theme/*`. Prefer immutable state holders and hoist state out of composables. Use Android Studio’s formatter before committing.

## Testing Guidelines
JUnit 4 backs the unit tests in `app/src/test`; mirror production packages and name files `<Subject>Test`. Compose or Espresso-based instrumentation tests in `app/src/androidTest` should clarify device-only dependencies in method names (e.g., `requiresDevice`). Cover new branches for view models and business logic, and provide Compose previews or parameterized tests when practical.

## Commit & Pull Request Guidelines
The repository has minimal history, so adopt imperative, present-tense commit titles capped at 72 characters (e.g., `Add BLE scan placeholder`). Reference issues with `Refs #123` or `Fixes #123` in the body. Every pull request should explain the motivation, summarize the solution, include before/after screenshots for UI changes, and list the Gradle tasks or tests executed. Request review only after CI and lint tasks pass.

## Environment & Configuration Tips
Develop with Android Studio Koala or newer on JDK 17. Ensure `ANDROID_HOME` points to the SDK declared in `local.properties`. Load secrets or API keys from Gradle properties or BuildConfig, and never hardcode credentials or addresses in the source tree.
