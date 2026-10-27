# Jarvis for Android

A hands-free Jarvis for Android. Say "Hey Jarvis" any time, even with the screen off.

- **Wake word:** [openWakeWord](https://github.com/dscripka/openWakeWord) "hey jarvis" model, on-device, no account needed. The pre-trained models are licensed CC BY-NC-SA 4.0 (personal, non-commercial use) and are downloaded at build time.
- **Hearing and speaking:** Android's own speech recognition and text-to-speech.
- **Brain:** the Claude API (Haiku). Personality lives in `config/persona.json`.

## Updates
Every push to `main` builds a new app and publishes it under **Releases**. Changes to `config/persona.json` don't need a new build: Jarvis downloads the personality file when he starts (works when the repo is public).

## Keys
The Claude API key is typed into the app and stay on the phone. It never goes in this repo.
