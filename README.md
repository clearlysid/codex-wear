# Codex companion for Wear OS ⌚

This app is a focused Codex monitoring companion for Wear OS.

Use voice to start or continue a Codex task, monitor concurrent work from Home or the Tile, respond to approvals delivered to the watch, stop active work, and review compact task timelines. Codex app-server remains the source of truth; the watch keeps only a lightweight seven-day summary cache.

### Roadmap
- [x] Voice-first Assistant
- [x] Activity and Today task views
- [x] Structured task detail and approvals
- [x] Four-state monitoring Tile with usage limits
- [x] Background monitoring and task notifications
- [ ] Push relay for idle cross-device discovery

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
2. Product decisions are recorded in `DESIGN_DECISIONS.md`.
3. It's my very first Kotlin project — be kind!
