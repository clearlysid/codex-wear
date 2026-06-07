# Sidekick (for Wear OS ⌚)

Sidekick brings your AI Assistant to your wrist. A claw should be attached to your limbs after all 🤪

It's a small app for WearOS that let you interact with your Clawdbot, Openclaw, etc. through voice or text. You can start conversations, track old ones, get notified via haptics when your bot responds. It's really just meant to be a nice, clean, efficent way of summoning your clankers — with a UI that feels unobtrusive.

I built this because I needed an "Agent Babysitter" that I could tap into, even if I was on a walk or on a train. Situations where access to a laptop or phone is either impractical or uncomfortable.

### Roadmap
- [x] Basic UI
- [x] Spacebot support
- [x] Openclaw support
- [ ] Haptics for replies
- [ ] Voice mode quick launch
- [ ] Tile + Complication
- [ ] Nicer landing page

## Developer Guide

```text
sidekick/
|── android/
|   └── wear/        | Wear OS app
|── web/             | Landing page
└── package.json
```

### Commands

```bash
# For landing page
cd web
bun install
bun run build
bun run serve

# For watch app
cd android
./gradlew :wear:assembleDebug

# For creating builds
bun run web:build
bun run android:wear:build
```

### Wear Voice Input

- Voice input source is configurable in watch Settings.
- Default source: Sarvam realtime STT.
- Fallback source: Android speech recognizer.
- Sarvam defaults:
  - URL: `wss://api.sarvam.ai`
  - model: `saaras:v3`
  - language: `unknown`
  - mode: `transcribe`
  - hard cap: 20s
- Local default token comes from `.env`:

```bash
DEFAULT_STT_AUTH_TOKEN=...
```

Sarvam transcript flow:

```text
mic -> realtime transcription preview -> Send -> agent
```

Failed sends should keep the preview visible and avoid empty conversations.

### Notes

1. Pushing to `main` deploys landing page to Github pages.
2. PRs for bugfixes and support for other agents are welcome.
3. It's my very first Kotlin project — be kind!
