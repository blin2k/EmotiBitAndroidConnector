# EmotiBit Connector (Android)

EmotiBit Connector is an Android application written in Kotlin with Jetpack Compose that
acts as a host for EmotiBit sensors on Wi-Fi networks. The app manages the ASCII control
protocol (HE/EC/PN/PO) and collects UDP payloads streamed by the device for diagnostics,
visualisation and CSV logging.

## Capabilities
- Establishes the EmotiBit Wi-Fi session: selects local data/control ports, sends EC
  heartbeats to port 3131, and accepts the reverse TCP control connection.
- Receives OSC/UDP payloads on the chosen data port while maintaining a MulticastLock.
- Discovers devices with a robust **Scan** feature that binds to the current Wi-Fi
  network, probes global/directed broadcasts, and unicasts HE+EC packets across the
  subnet using a single socket so HH replies are captured reliably (even on phone
  hotspots with client isolation).
- Provides manual start/stop controls for streaming, PN/PO helpers, and HE debug
  commands.
- Records incoming payloads to CSV with buffered writes and app-scoped storage.
- Uploads saved CSV recordings to Firebase Storage for cloud backup/sharing.
- Presents diagnostics in Jetpack Compose with live logs, packet counters, and network
  information (local IPv4, prefix, broadcast IP).

## Project Structure
```
app/
  src/main/java/com/example/emotibitconnector/
    MainActivity.kt         // MulticastLock lifecycle and Compose entry point
    EmotiBitClient.kt       // Networking (UDP/TCP, Wi-Fi binding, scan)
    EmotiBitViewModel.kt    // Session state, logging, CSV recorder glue
    ui/EmotiBitScreen.kt    // Compose UI
    network/EmotiBitProto.kt// Protocol constants & helpers
    CsvRecorder.kt          // Buffered CSV writer
```
Additional assets live under `app/src/main/res`. Version catalogs are defined in
`gradle/libs.versions.toml`.

## Build & Run
Ensure Android Studio Koala (or newer) with JDK 17. Drop your Firebase project's
`google-services.json` into `app/` so the Google Services plugin can wire up Firebase
Storage. Useful Gradle tasks:
- `./gradlew assembleDebug` — Build a debuggable APK.
- `./gradlew installDebug` — Install the debug build on a connected device/emulator.
- `./gradlew testDebugUnitTest` — Run JVM unit tests.
- `./gradlew lint` — Run lint checks.

The app relies on standard Wi-Fi permissions (`INTERNET`, `ACCESS_NETWORK_STATE`,
`ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`, `WAKE_LOCK`). No runtime storage
permission is required because CSV files are written to the app-specific external
Documents directory.

## Usage Tips
1. Connect the phone (host) and EmotiBit to the same Wi-Fi access point (hotspot or
   router). The phone can serve as the hotspot.
2. Tap **Scan** to discover EmotiBit devices. The scan logs subnet details and lists
   IP/Device ID pairs; selecting a result populates the Device IP field.
3. Press **Start listening & connect** to begin the session. The app sends EC heartbeats
   ~1 Hz, accepts the TCP back-channel, and receives UDP payloads on the selected port.
4. Use **Start Recording** to capture incoming packets to CSV. Files are appended in
   app-private storage and listed in the Saved recordings section.
5. Use the **Upload** button beside a saved recording to push it to Firebase Storage.
   Files are stored under `gs://<your-bucket>/recordings/<user-or-anonymous>/`. Ensure
   your Storage rules allow the intended access (development often uses liberal rules;
   production should require auth).
6. Review live logs and diagnostics; the **Copy diagnostics** button copies a concise
   snapshot for support.

## Troubleshooting
- If scan results are empty on a hotspot, verify client isolation settings. You can
  still enter the EmotiBit IP manually (from the hotspot client list) and start a
  session.
- Restart the session after switching Wi-Fi networks—the ViewModel automatically stops
  active sockets on network change to avoid stale bindings.
- Use the logs (tag `EmotiBit`) for insights into network binding, discovery, control
  packets, and UDP reception.
- For Firebase uploads, double-check that `google-services.json` targets the correct
  project and that Storage rules permit your use case. The common
  `StorageException: Object does not exist at location` usually indicates a mismatch in
  bucket name or insufficient write/read permissions.

## Contributing
The repository has minimal history. Follow Kotlin style conventions, prefer immutable
state, and add focused logs. When submitting changes, provide imperative commit titles
and document executed Gradle tasks.
