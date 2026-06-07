# Sidekick

AI assistant app. Monorepo with `android` (Wear OS) and `web`.

## Android / Wear OS

- Gradle wrapper: `android/gradlew` (not project root)
- JAVA_HOME: `/Applications/Android Studio.app/Contents/jbr/Contents/Home` (no system JDK installed)
- adb: `~/Library/Android/sdk/platform-tools/adb`
- Build release APK: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" android/gradlew -p android :wear:assembleRelease`
- APK output: `android/wear/build/outputs/apk/release/wear-release.apk`
- Deploy to watch:
  1. Watch: Settings → About → tap Build number 7x → enable Developer Options
  2. Watch: Settings → Developer Options → enable ADB debugging + Debug over Wi-Fi
  3. Watch: use "Pair new device" option, note the IP:port and pairing code
  4. Pair: `adb pair <ip>:<pairing-port>` (enter pairing code when prompted)
  5. Connect: `adb connect <ip>:<port>` (port shown under Debug over Wi-Fi, different from pairing port)
  6. Install: `~/Library/Android/sdk/platform-tools/adb -s <device> install -r <apk>`
  7. Re-set assistant settings (Samsung Wear OS clears these on every reinstall):
     ```
     adb -s <device> shell settings put secure voice_interaction_service com.sidekick.watch/com.sidekick.watch.voice.SidekickVoiceInteractionService
     adb -s <device> shell settings put secure assistant com.sidekick.watch/com.sidekick.watch.presentation.MainActivity
     ```
- Signing config is in `android/wear/build.gradle.kts` (hardcoded keystore, not committed)

### Architecture

- OkHttp for HTTP, no Retrofit
- Two backends: Spacebot (`SpacebotRepository`) and OpenAI-compatible (`OpenAIRepository`)
- OpenAI backend uses SSE streaming (`sendMessageStreaming`) with `Flow<String>`
- ViewModel: `ChatViewModel` — manages conversations, collects streaming chunks into UI state
- Settings (base URL, model, auth token) stored via `SettingsRepository` (DataStore)
- Entry point: `MainActivity` — HorizontalPager with home (chat) + settings pages
