# Microphone settings

This feature requires an app build containing the microphone changes. It is native Android functionality, not a Linux application, widget-only update or browser proxy. Both the home prompt bar and terminal helper-key microphone use the same settings; terminal dictation works in local and SSH terminals.

## Providers

Open **Settings → microphone** (or the `launcher("microphone")` widget action):

- **Android speech** is the default, preserving the existing recognition path.
- **Groq** uploads a short recording directly from the phone to `https://api.groq.com/openai/v1/audio/transcriptions` using your API key. Supported models are `whisper-large-v3-turbo` (default) and `whisper-large-v3`. Other providers are not implemented in this native settings page yet.

Enter your Groq key, pick a model and optional language, and tap **save**. Leaving the key field blank retains the saved key; **remove saved key** deletes it. Keys are encrypted with Android Keystore and stored only in app-private preferences. They are not passed to Linux, SSH, an agent, the repository or a proxy.

Language is automatic when empty, or a language code such as `en`. **Test microphone** records, **stop test** transcribes, and the result appears on the settings screen without executing a command.

## Offline is optional, without downloads

When Groq is selected, **use existing on-device speech when offline** permits an offline recognizer only if Android reports one already available. On API 33+, recognition support is checked against *installed* on-device languages. Missing models are reported, not downloaded; older versions attempt only the existing on-device recognizer and report unsupported languages. The code never calls a model-download API or installs a speech engine.

Without an existing recognizer/model, there is no offline dictation. A platform recognizer being available does not itself prove that the required language model is installed. Offline recognition never falls back to the system's potentially network-based recognizer.

## Recording and cancellation

Groq uses tap-to-record/tap-to-stop in the terminal and the existing tap/hold controls in the home prompt bar. It records AAC/M4A in the app's private cache, bounded to two minutes/10 MB. Audio is sent only when recording stops. Tap while processing to cancel. Terminal text is inserted without Enter; cloud-produced line breaks are flattened to avoid accidentally submitting a shell command.

Successful uploads delete the recording. A failed upload retains it for a microphone retry while the same window remains active. Leaving the window or cancelling discards it; it is not a persistent recording library. If connectivity fails after recording, the existing clip is retried with Groq rather than pretending Android can reliably consume the same recording offline. Provider errors do not expose provider bodies or credentials.

## Verification and installation

```sh
./gradlew :app:testDebugUnitTest :owl:test
```

JVM tests verify provider routing, optional/missing offline recognition, multipart requests, cancellation, single-line terminal insertion and safe error handling. Real recording, Keystore storage, platform model support and Groq accuracy require an installed build and a real key.

Before replacing an installed debug APK, use the matching signing keystore and source revision. The phone used during development has extra session-manager functionality not present in the current main checkout. A replacement must retain those changes and be signed with the same key; do not uninstall the working app to bypass a signature mismatch, because doing so deletes its Linux environment and data.
