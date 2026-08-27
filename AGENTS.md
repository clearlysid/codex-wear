# Codex Wear

Wear OS remote companion for monitoring and controlling Codex.

## Android / Wear OS

- Gradle wrapper: `./gradlew`
- JAVA_HOME: `/Applications/Android Studio.app/Contents/jbr/Contents/Home` (no system JDK installed)
- adb: `~/Library/Android/sdk/platform-tools/adb`
- Build release APK: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :wear:assembleRelease`
- APK output: `wear/build/outputs/apk/release/wear-release.apk`
- Deploy to watch:
  1. Watch: Settings → About → tap Build number 7x → enable Developer Options
  2. Watch: Settings → Developer Options → enable ADB debugging + Debug over Wi-Fi
  3. Watch: use "Pair new device" option, note the IP:port and pairing code
  4. Pair: `adb pair <ip>:<pairing-port>` (enter pairing code when prompted)
  5. Connect: `adb connect <ip>:<port>` (port shown under Debug over Wi-Fi, different from pairing port)
  6. Install: `~/Library/Android/sdk/platform-tools/adb -s <device> install -r <apk>`
  7. Re-set assistant settings (Samsung Wear OS clears these on every reinstall):
     ```
     adb -s <device> shell settings put secure voice_interaction_service com.codex.wear/com.codex.wear.voice.SidekickVoiceInteractionService
     adb -s <device> shell settings put secure assistant com.codex.wear/com.codex.wear.presentation.MainActivity
     ```
- Signing config is in `wear/build.gradle.kts` (hardcoded keystore, not committed)

### Architecture

- Codex app-server is the durable source of truth; the watch is a thin client.
- `CodexRpcClient` uses WebSocket JSON-RPC for tasks, approvals, and usage limits.
- `CodexTaskRepository` maintains task state and a lightweight watch cache.
- Settings (server URL, authentication, and voice input) use DataStore.
- `MainActivity` hosts Home, Task Detail, Image Viewer, and Settings navigation.
- `AssistantActivity` provides voice-first task creation and continuation.
