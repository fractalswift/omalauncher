# Mac handoff: native microphone providers

## Goal and architecture

Continue development in the user's **original Mac checkout** of `fractalswift/omalauncher`. Add **OpenAI**, **Groq** and the existing **Android speech** as selectable providers in one native **Settings → microphone** page shared by the prompt bar and terminal helper-key mic. Speech is recorded/recognized on Android; only text is inserted into Foot, whether local or SSH. The user wants to compare OpenAI with Groq.

Offline recognition is optional: use an already-available Android on-device recognizer and an installed language model, or report that offline dictation is unavailable. **Do not install/download a model or speech engine.**

## Git state

- Fork: `https://github.com/fractalswift/omalauncher`.
- Stable SSH/tmux widget checkpoint is on `main`: `a1a95b2` (`Add Tailscale SSH widget with resumable tmux sessions`). It is already deployed and working on the phone.
- Native microphone work and this handoff are on `feature/microphone-providers`, based on that checkpoint. Fetch this branch and inspect its diff before integrating it.
- Inspect the Mac checkout's status, branches and uncommitted changes first. Preserve/checkpoint any Mac-only work. Integrate normally; do not reset to the Linux branch or overwrite unrelated changes.

## What the microphone branch already contains

- `ui/Speech.kt`: shared facade, Groq AAC/M4A capture (two minutes/10 MB), asynchronous transcription/cancellation, temporary clip retry and cleanup, routing to existing on-device recognition when offline.
- `ui/AndroidSpeech.kt`: the previous Android recognition implementation, now with a strict optional offline mode that checks installed languages and never requests a model download.
- `ui/DictationSettings.kt`: Android/Groq selection, language/model/fallback preferences, API key encrypted with Android Keystore. No credentials are committed.
- `ui/GroqSpeech.kt`: cancellable fixed-endpoint HTTPS multipart upload, bounded JSON response, no credential/provider-body logging. Transcript line breaks are flattened before terminal insertion.
- `ui/MicrophoneScreen.kt` and settings navigation: provider/key/model/language fields, optional existing-model fallback, save/remove key and microphone test.
- Both mic controls use this facade and handle the processing/cancellation state. Leaving the target window cancels capture/transcription.
- `GroqSpeechTest.kt`: routing, multipart fields, cancellation, safe errors, key header validation and single-line transcript tests.
- `docs/MICROPHONE.md`, README and privacy text describe the new functionality.

**This native microphone work is not installed on the phone. No Groq/OpenAI key has been configured or real cloud transcription verified.** OpenAI support is not yet implemented in the branch.

## Add OpenAI support

Check current official documentation before coding: <https://developers.openai.com/api/docs/guides/speech-to-text>.

At handoff, that documentation recommends **`gpt-transcribe`** for recorded speech at `https://api.openai.com/v1/audio/transcriptions`; existing `gpt-4o-transcribe` and `gpt-4o-mini-transcribe` integrations remain supported. Use the recommended available model, and let the user select a model where appropriate. This is API speech-to-text, not ChatGPT account/session authentication; API access/billing is separate from a ChatGPT subscription.

- Refactor the Groq-specific request/settings assumptions into provider-specific configuration while sharing recording, lifecycle handling and the multipart transport where appropriate.
- Keep separate encrypted API keys and model selections for OpenAI and Groq. Switching providers must not send the wrong key to another endpoint or erase it.
- For **`gpt-transcribe`**, the current docs use **`languages`** (plural), rather than the singular `language` used by Groq/older transcription models. Do not blindly reuse all Groq request fields. Follow the selected model's API contract and read the JSON `text` result.
- Keep Android speech as the no-cloud option and retain optional existing-model offline fallback. No model downloads and no automatic fallback to another paid cloud provider.
- Keep transcript insertion separate from submission in the terminal; do not add Enter. Preserve the prompt bar's existing tap-versus-hold behavior.
- Add provider routing, key/endpoint separation, request-format, cancellation and failure tests. Update settings labels, docs and privacy text for OpenAI.

## Preserve the installed phone build

The phone is a **Moto g55 5G**, installed package **`dev.omalauncher.dev`**, version **0.7.2 / code 14**. It was built on the Mac. ADB wireless ports can change; run `adb devices -l` and use the currently authorized connection or ask the user to reconnect/pair.

The installed APK has additional **Machines & Sessions / `android.sessions`** functionality absent from the Linux checkout's main branch. Locate the original Mac source and preserve those changes, plus terminal dictation and screenshot attachment features. Do not replace the installed app with an older source tree that drops them.

The installed debug signing certificate SHA-256 is:

```text
636d60b35c87b9674320c801f8459462ab686d3b1eac6b8e10bc5da56ce13a33
```

Find the Mac's matching debug keystore (usually `~/.android/debug.keystore`) and verify its certificate matches. Keep the keystore, signing passwords and API keys out of Git and logs. **Do not uninstall the app or clear its data to bypass a signature mismatch**: its Arch environment and app data must survive. Build a matching-signed update, verify its certificate, and install with the normal update flow (`adb install -r`). An APK update restarts the app; remote tmux work persists, but local guest work should be saved first.

The working, personalized widget is on the phone as `~/.config/omalauncher/widgets/machines-sessions.owl`, with its helper at `widgets/examples/oma-ssh-sessions`. It lists actual remote tmux workspaces, supports create/resume/end and per-node users, and is already verified on-device. Keep its saved settings/layout and the Linux environment intact. Backups of prior widgets are in the app's `files/widget-backups` directory.

## Verification already completed on Linux

```sh
./gradlew :app:testDebugUnitTest :owl:test
```

Both suites and Android Kotlin compilation passed. The Linux environment needed an explicit JDK 17 toolchain path alongside JDK 25; use the Mac's working Java/SDK setup. SDK platform package names use the current `android-37.0` naming if setting up tools afresh.

Remaining verification on the Mac/phone:

1. Integrate with the original source and matching signing key; add OpenAI and run tests.
2. Build the update using the existing proot/native assets and install without uninstalling.
3. Test masked key save/removal and provider switching with the user entering keys directly in settings.
4. Test real microphone capture and transcription using OpenAI and Groq.
5. Confirm insertion into the launcher prompt, a local Foot shell and an SSH/OpenCode terminal without accidental submission.
6. Test stop, cancellation, focus/session changes, failed-upload retry and audio cleanup.
7. Check actual on-device recognition availability. With no internet and no installed local model, show a clear unavailable message and download nothing. If toggling connectivity drops ADB/SSH, coordinate that test with the user.
8. Recheck the working Tailscale/tmux widget after the update and report what was actually verified.

Commit/push further changes only when the user requests it. No PR has been requested.
