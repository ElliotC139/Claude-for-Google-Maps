# Maps Voice — Phase 1

A small always-on-top bubble that lets you talk to Claude hands-free while
Google Maps handles navigation full-screen. Two independent pieces run at once:
the official Maps app does the driving, and this app floats on top and listens.
They never talk to each other — no Maps API, no modified APK.

This repository is **Phase 1 of the build plan**: the core loop, no polish.

```
long-press the bubble → speak → Claude answers → the reply is spoken aloud
```

Nothing else. The bubble does not drag, there is no wake word, and the app has
no idea what Maps is showing.

## Layout

```
app/src/main/java/dev/elliotc/mapsvoice/
  overlay/
    OverlayService.kt        foreground service, window management, the loop
    BubbleView.kt            the circle and its four state colours
  voice/
    SpeechListener.kt        SpeechRecognizer wrapper
    TextToSpeechManager.kt   TextToSpeech wrapper
  claude/
    ClaudeClient.kt          Messages API call
    ConversationState.kt     rolling history
    ApiKeyStore.kt           the key, kept in app-private storage
  MainActivity.kt            permissions setup, first run only
```

## Getting it onto your phone

You do not need a computer. GitHub builds the APK; you install it from your
phone's browser.

### Build it

`.github/workflows/build-apk.yml` builds a debug APK on every push to this
branch and publishes it as the repo's **latest release**. To run it by hand:
**Actions → Build APK → Run workflow**. It takes about five minutes.

### Install it

Download the APK straight from the release:

```
https://github.com/ElliotC139/Claude-for-Google-Maps/releases/latest/download/app-debug.apk
```

Your browser will warn that this file type can harm your device, and Android
will ask permission to install unknown apps from that browser — both are the
normal prompts for sideloading, and both have to be accepted.

### Give it your key

Get one at **https://console.anthropic.com/settings/keys** (the API is billed
separately from a Claude subscription; add credit under Billing first). Open
Maps Voice, paste the key into the first field, tap **Save key**.

The key is stored in app-private storage on the phone. No other app on a
non-rooted device can read it, and it survives app updates. It never goes into
the repo, the build, or the APK.

A build-time key still works if you prefer it — put `CLAUDE_API_KEY=sk-ant-...`
in `local.properties` locally, or add it as a repository secret and reference
it in the workflow. The in-app key wins if both are set.

### Building locally instead

If you do have a computer: Android Studio (https://developer.android.com/studio),
open the project, add `CLAUDE_API_KEY` to `local.properties` or just type the
key into the app, then Run. JDK 17, `compileSdk 34`.

## Using it

- **Long-press the bubble** (~half a second, with a haptic tick) to start
  listening. You don't need to look at it.
- **Speak, then stop.** The recogniser ends the utterance on ~1.5s of silence;
  there is nothing to release.
- **The reply is spoken.** The system prompt keeps answers to one or two
  sentences and forbids follow-up questions that would need a typed reply.

The bubble's colour is the whole interface:

| Colour | State |
| --- | --- |
| Grey | Idle |
| Blue (pulsing) | Listening |
| Amber | Waiting on Claude |
| Green | Speaking |
| Red | Something failed — the reason is spoken aloud |

Conversation history is kept in memory, capped at three exchanges, and cleared
after ten minutes of silence.

**Test this stationary before you use it in a car.**

## Implementation notes

- **`claude-sonnet-5`, thinking disabled.** Thinking would add seconds of
  silence to a conversation someone is waiting on out loud, and these are short
  factual answers. `max_tokens` is 300 as a hard ceiling on top of the
  brevity instruction in the system prompt.
- **Raw HTTP over OkHttp rather than the Anthropic Java SDK.** The SDK is a JVM
  library that isn't shipped for Android, and one `POST /v1/messages` doesn't
  justify pulling it in.
- **Foreground service, type `microphone`.** Required from Android 10 to use
  the mic from a service, and Android 14 additionally requires the
  `FOREGROUND_SERVICE_MICROPHONE` permission. The service is started from the
  activity while it is visible, which is what grants it while-in-use mic access.
- **Bottom-left corner.** Clear of the next-turn banner at the top of Maps and
  of the ETA sheet's controls on the right. It is fixed there until Phase 2
  makes it draggable.
- **TTS uses `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`**, so it routes the same
  way Maps' own guidance does on a car stereo.

## Not in this phase

Phase 2 (driving-safe) — drag and position memory, a cancel gesture to stop a
reply mid-sentence, audio-focus handling so Claude and Maps don't talk over
each other, and silence-timeout tuning.

Phase 3 (quality of life) — wake word instead of long-press, whole-trip
conversation memory.

Phase 4 — Android Auto, which is the real "designed for driving" surface but
needs Google's distraction-guidelines review.
