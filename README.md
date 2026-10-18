# Omalauncher

An Android home screen with the look of [Omarchy](https://omarchy.org), and an
optional Arch Linux ARM system next to your phone apps. Omalauncher runs the
system under proot, without root, and is its Wayland compositor: every Linux
window opens as its own Android task, with its own card in Recents.

<a href="https://play.google.com/store/apps/details?id=dev.omalauncher"><img src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png" alt="Get it on Google Play" height="80"></a>

![The home screen in twelve Omarchy themes, each with different widgets](art/collage.png)

<p>
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/01.png" width="24%" alt="Screenshot">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/02.png" width="24%" alt="Screenshot">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/03.png" width="24%" alt="Screenshot">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/04.png" width="24%" alt="Screenshot">
</p>

- **Themes**: all 22 Omarchy themes, with their wallpapers, applied to the
  launcher and to the Linux apps, plus the community themes from
  [omarchy.org/themes](https://omarchy.org/themes/), or your own in
  `~/.config/omarchy/themes`
- **Widgets** written in OWL, a small declarative language you can edit on the
  phone, plus a [widget store](https://github.com/SimonSchubert/omarchy-widgets)
- **Linux, optional**: pacman and the AUR, Linux apps in their own windows,
  terminal widgets, a file manager, and opt-in GPU acceleration on Mali and Adreno 6xx+ GPUs
- **Coding agents**: the bar at the bottom sends a prompt to Claude Code, Codex,
  Copilot, opencode and others, which open in a terminal
- No account, no ads, no tracking

Needs an arm64 phone with Android 10 or newer. Without Linux, the launcher still
has the themes, wallpapers, Android apps and the widgets that don't run commands.

## Using it

The bar: the one input at the bottom of the home screen opens apps and asks
coding agents. Focusing it opens the list of Linux and Android apps; typing
narrows the list, and Enter opens the highlighted app or sends the text to the
agent. Short queries that name an app open it, sentences go to the agent; Tab
and the arrow keys move the highlight. Tap the agent's name to pick another
installed one; the choice is saved in `~/.config/omalauncher/agent`. Hold the mic to talk and release to send, or tap it to dictate
into the field. Settings › microphone selects Android speech (the default) or Groq Whisper with your own key. Only text reaches Linux.

Touch input in Linux windows: tap is a left click, long press is a right
click, drag scrolls; apps with touch support (foot, GTK) get real touch.
Hardware keyboard shortcuts: Super+Enter (terminal), Super+Space or
Super+A (the bar), Super+1..9 (home pages) on the home screen; Super+Enter
and Super+W (close) in Linux windows.

The terminal's helper-key panel has a microphone button: tap to dictate, then
tap again to finish and insert the transcript without pressing Enter. It uses
the same microphone settings as the prompt bar. An optional offline fallback uses only an existing on-device recognizer/model: nothing is downloaded or installed. Without one, offline dictation is unavailable. Leaving the window cancels recording or transcription.

The image button below the microphone attaches the **latest Android screenshot**
to a local agent prompt. Grant photo access on first use, then reopen the keyboard
and tap the image button again. With limited photo access, it uses the latest
screenshot among the photos you allowed. It imports the original image into
`~/.local/share/omalauncher/attachments` and pastes its local path; OpenCode 2
converts that paste into an `[Image 1]` attachment. It does not press Enter.
The separate **paste** key still pastes your clipboard, which the image button
does not replace. This has been verified with local OpenCode 2 in foot; an agent
running over SSH needs the image transferred to that remote machine first.

### Machines & Sessions

Open **Settings → machines & sessions** to manage local terminals and SSH
workspaces. **This phone** opens Linux directly; saved remote machines use normal
SSH through Android's existing network/VPN. Multiple sessions on one machine get
separate foot windows and Android Recents cards.

For automatic Tailscale discovery:

1. Keep the **Tailscale Android app** connected, with Omalauncher included in its
   VPN routing. No second Tailscale installation is needed inside Linux.
2. In [Tailscale's Keys settings](https://console.tailscale.com/admin/settings/keys),
   generate an **API access token** (`tskey-api-`, not an auth key).
3. Tap **tailscale**, leave Tailnet as `-`, paste the token, and **save & discover**.
   Use **sync machines** later to update the cached device list. The token is
   encrypted with Android Keystore and never passed to Linux or widget sources.
   Replace it when it expires. Personal API tokens inherit the account's
   permissions; this integration only calls the device-list endpoint.
4. **Edit** a discovered machine to set its SSH username, then tap **+** for a new
   named session. Discovery supplies addresses, not SSH credentials or access.

Manual hosts and SSH config aliases are also supported. Addresses default to
Tailscale IPs to avoid depending on guest MagicDNS. SSH keys, aliases and known
hosts live in the guest's `~/.ssh`; first-time host verification and authentication
happen in foot. Install `openssh` and `tmux` locally using **local SSH / tmux setup**
if needed, and install tmux on each destination used for persistent sessions.

Persistent sessions run in named tmux sessions. **Open** focuses an existing
window; **Resume** attaches to its running session without rerunning the initial
command. Closing/detaching a terminal leaves tmux running. **New session** creates
an independent workspace; **end session** terminates its programs, whereas
**forget saved entry** only removes the shortcut. An ended session is never
silently recreated: **start again** is explicit. Plain SSH is available by turning
off tmux persistence when creating a session.

Remote tmux sessions survive phone disconnections, but normally not a destination
reboot. Local tmux runs separately from foot and survives closing its window, but
cannot survive Android killing the whole app/Linux environment. **Refresh** checks
saved sessions without prompting for SSH authentication; unreachable means state
unknown, not that the session ended.

Tap **add widget** for a home-screen entry point with three recent sessions and a
link back to all machines. The widget is also shipped as `examples/84-sessions.owl`.

## Building

```sh
./scripts/fetch-proot.sh          # Termux's proot -> app/src/main/jniLibs (needs bsdtar)
./gradlew :app:installDebug
# optional: skip the 830 MB download on the device
adb push ArchLinuxARM-aarch64-latest.tar.gz /sdcard/Android/data/dev.omalauncher.dev/files/
```

Tests: `./gradlew :owl:test :app:testDebugUnitTest`. `./gradlew :owl:owlJar`
builds `owl/build/cli/owl.jar`, the checker the widget store's CI runs.

Debug builds install as **Omalauncher Dev** (`dev.omalauncher.dev`), alongside
the release app, with separate data and Linux setup. You can launch guest commands with
`adb shell am start -n dev.omalauncher.dev/dev.omalauncher.MainActivity --es exec foot`.

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): the compositor, rendering, GPU
  acceleration, and how updates reach existing installs
- [docs/RELEASING.md](docs/RELEASING.md): Play releases and store assets
- [app/src/main/assets/widgets/README.md](app/src/main/assets/widgets/README.md):
  the widget language
- [docs/TAILSCALE.md](docs/TAILSCALE.md): the live SSH/tmux session list with terminal previews and one-tap resumption
- [docs/MICROPHONE.md](docs/MICROPHONE.md): Groq credentials, recording, optional offline recognition and testing
- [docs/TAILSCALE_SSH_WIDGET.md](docs/TAILSCALE_SSH_WIDGET.md): built-in tmux session widget, discovery and setup on another phone

## Layout

- `app/`: the Android app (Kotlin, Jetpack Compose, and the JNI bridge in `src/main/cpp`)
- `owl/`: the widget language and the `owl` command line, plain Kotlin
- `art/`: SVG masters for the app, agent and store icons
- `store/`: Play listing sources (screenshot frames, privacy policy)
- `fastlane/`: the Play listing and the upload lanes
- `scripts/`: fetching proot and theme colors, rendering icons, screenshots and widget previews

## License

MIT, see [LICENSE](LICENSE), for Omalauncher's own code and artwork. The
coding agents' logos, the symbol font and Omarchy's theme colors are not
covered; [THIRD_PARTY.md](THIRD_PARTY.md) lists them with their sources and
licenses. The app also ships proot, talloc and libandroid-shmem (GPL-2.0,
LGPL-3.0 and BSD-3-Clause, prebuilt by Termux), listed in the app under
settings › licenses.

Omalauncher is an independent project and is not affiliated with or endorsed
by Omarchy, Arch Linux, or the makers of the coding agents it installs.
