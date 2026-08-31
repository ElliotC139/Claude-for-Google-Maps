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
    AudioFocusHolder.kt      pauses other audio for the length of a session
    SpeechListener.kt        SpeechRecognizer wrapper
    TextToSpeechManager.kt   TextToSpeech wrapper
    WakeWordListener.kt      offline wake word (Vosk)
  claude/
    ClaudeClient.kt          Messages API call
    ConversationState.kt     rolling context sent to Claude
    ApiKeyStore.kt           the key, kept in app-private storage
  data/
    Settings.kt              bubble size, position, personal context
    ConversationLog.kt       durable transcript on disk
    ForegroundAppWatcher.kt  is Maps on screen?
  HistoryActivity.kt         reads the transcript back
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

**Scope the key to a single workspace when you create it.** A personal or
service-account key that spans workspaces has to name one on every request, and
the API rejects a request without it:

> `anthropic-workspace-id is required when authenticating with an
> identity-linked API key; send the id of the workspace this request acts in.`

If you already have such a key, put its workspace ID (`wrkspc_…`, from
Settings → Workspaces) in the second field and the app will send the header.
Leave that field blank for a workspace-scoped key.

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
- **Tap it** to cancel — stops the mic, drops an in-flight request, and cuts a
  reply off mid-sentence. A tap while idle does nothing, so a knock can't
  trigger anything.
- **Drag it** anywhere; the position is remembered.
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
- **Audio focus is held for the whole session**, from the moment the mic opens
  to the last word of the reply, so music and podcasts pause once rather than
  stuttering between the question and the answer. Requested as a transient
  *gain* rather than "may duck": ducked audio keeps playing underneath, and a
  quiet reply competing with a podcast is the problem being solved. Losing
  focus — an incoming call, say — cancels the session rather than talking into
  it. The wake word never holds focus; it listens for minutes at a time.
- **TTS and the focus request both use `USAGE_ASSISTANT`**, so the system
  routes and mixes them the same way. Navigation-guidance usage is mixed
  quietly over music on many car systems, which is the opposite of what a
  spoken answer needs.

## Settings

On the setup screen, below the permissions:

- **Bubble size** — 44 to 120 dp. Takes effect immediately on a running bubble.
- **Reset position** — puts it back in the bottom-left corner (restarts the
  bubble, since position is read when the window is created).
- **About you** — free text sent to Claude with every question. Paste an export
  of your Claude memory here: the API has no access to the memories held by
  your Claude account, so this is the manual equivalent. Capped at 4000
  characters, because it rides along with every question and is billed each time.
- **View conversations** — every exchange, newest first, with share and clear.
  Stored as JSON lines in app-private storage, trimmed to the newest 1000 once
  the file passes 1 MB.

## Wake word

Optional; off by default. Uses [Vosk](https://alphacephei.com/vosk/) — a small
offline recogniser bundled into the APK. No account, no API key, and no audio
leaves the phone to detect the phrase.

Turn it on in the app, adjust the phrases if you like, and Save. The default is:

```
hey claude, hey cloud, hey clawed
```

Any of those starts a session. The extra spellings are not padding: the small
model has no "claude" in its vocabulary, so the word comes back as "cloud" or
"clawed" nearly every time, and matching only the correct spelling would mean
it almost never fires. Matching is done on partial results, so it triggers as
you finish saying the phrase rather than a second later.

The model (~40 MB) is downloaded by CI into `app/src/main/assets/model-en-us`
at build time rather than committed, so the repo stays small while the APK
stays self-contained and works offline.

Vosk holds the microphone while it listens, and Android will not give the same
mic to `SpeechRecognizer` at the same time, so the service stops the wake word
for the length of a question and restarts it afterwards. That handoff is why
the wake word can't fire while Claude is already listening or speaking — tap
the bubble to interrupt instead.

### Only while Maps is open

On by default, and the reason the wake word is affordable at all: the
recogniser only runs while Google Maps (or Android Auto's projected Maps) is on
screen, plus a three-minute grace period so switching to music doesn't stop it.

This needs **usage access** — Android has no ordinary permission for reading
which app is in front, and the only alternative is an accessibility service,
which is far more invasive. Grant it from the button in the app; it opens a
system settings screen rather than a runtime prompt.

Until it is granted the gate is treated as open, so the wake word still works —
silently never listening would look like a broken feature rather than a missing
permission.

Even so, listening keeps the mic indicator lit and costs more battery than the
long-press. Turn it off when you're not driving.

## Not in this phase

Silence-timeout tuning.

Note that audio focus pauses *music*; it does not stop Google Maps from
speaking a turn instruction over an answer. Maps requests focus for its own
guidance, and an app cannot refuse another app's prompt — that is the system
working as intended, and the tap-to-cancel gesture is the way out of a
collision.

Phase 4 — Android Auto, which is the real "designed for driving" surface but
needs Google's distraction-guidelines review.
