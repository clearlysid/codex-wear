# ChatGPT Work(wear)

This is a Wear OS companion app for Codex/ChatGPT Work. It keeps ongoing threads within reach when you are away from your computer.

Use it to start or continue tasks by voice, monitor progress, respond to approvals and review activity. The Codex app server remains the source of truth; the watch is a lightweight client connected over WebSocket.

## Status

This is an in-development app, mostly for my own personal use. I'm not planning to make it generally available, unless there's outsized interest in the form-factor.

## Setup Guide

Short one. If you're so inclined:

1. Run the [codex app-server](https://learn.chatgpt.com/docs/app-server).
2. [IMPORTANT] Make the URL reachable via the internet with appropriate security/auth mechanisms in place.
3. Add the URL and some (optional) API keys in a `.env` file in the project root
4. Build the APK and install it on your watch using ADB

```
# sample .env file
DEFAULT_CODEX_APP_SERVER_URL=
DEFAULT_CODEX_AUTH_TOKEN=
DEFAULT_SARVAM_API_KEY=
DEFAULT_GEMINI_API_KEY=
```

## Build

```bash
./gradlew :wear:assembleDebug
```
