# Arin

An AI assistant for Android (Kotlin + Jetpack Compose) with its own design.

## Install

Every push to `main` builds an APK in GitHub Actions and publishes it as the latest release:
**Releases → latest → `Arin-N.apk`**. Each build installs over the previous one.

## What it does (version 0.2)

- Chat with replies that stream in, a Stop button, and the model's reasoning when it shares it
- Free models by default, with no key or sign-up; optional Claude, Gemini and ChatGPT keys in Settings
- Model menu at the top of the chat
- Chats saved on the phone, grouped by day, with search, rename and delete
- Attach photos, PDFs, and text, CSV, JSON or code files (photos and PDFs need a Claude, Gemini or ChatGPT key)
- Edit a sent message and resend it
- Share to Arin from other apps
- Light, dark or match-phone theme

## Code layout

```
com.arin
├── MainActivity.kt   Starts the app, receives shares
├── app/              AppShell (which screen shows), IncomingShare
├── chat/             Chat screen, messages, view model
├── chatlist/         Side panel with saved chats
├── settings/         Settings screen
├── design/           Theme, Arin logo, Markdown
├── llm/              Providers and streaming
└── data/             Chats on disk, settings, encrypted keys, file reading
```

## Planned

Projects, an artifact viewer, web search, memory, voice mode, a home-screen widget,
notifications when a reply finishes, and replies that keep streaming in the background.
