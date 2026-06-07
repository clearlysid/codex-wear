# Sidekick (for Wear OS ⌚)

Sidekick brings your AI Assistant to your wrist. A claw should be attached to your limbs after all 🤪

It's a small app for WearOS that lets you interact with Hermes, OpenClaw, and other OpenAI-compatible agents through voice or text. You can start conversations, track old ones, get notified via haptics when your bot responds. It's really just meant to be a nice, clean, efficient way of summoning your clankers — with a UI that feels unobtrusive.

I built this because I needed an "Agent Babysitter" that I could tap into, even if I was on a walk or on a train. Situations where access to a laptop or phone is either impractical or uncomfortable.

### Roadmap
- [x] Basic UI
- [x] Hermes support
- [x] OpenClaw support
- [x] Haptics for replies
- [x] Voice mode quick launch
- [x] Tile + Complication
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

### Notes

1. Pushing to `main` deploys landing page to Github pages.
2. PRs for bugfixes and support for other agents are welcome.
3. It's my very first Kotlin project — be kind!
