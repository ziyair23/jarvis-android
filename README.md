# Jarvis for Android

A hands-free Jarvis for Android. Say "Jarvis" any time, even with the screen off.

- **Wake word:** Picovoice Porcupine, on-device, built-in "Jarvis" keyword.
- **Hearing and speaking:** Android's own speech recognition and text-to-speech.
- **Brain:** the Claude API (Haiku). Personality lives in `config/persona.json`.

## Updates
Every push to `main` builds a new app and publishes it under **Releases**. Changes to `config/persona.json` don't need a new build: Jarvis downloads the personality file when he starts (works when the repo is public).

## Keys
The Claude API key and Picovoice AccessKey are typed into the app and stay on the phone. They never go in this repo.
