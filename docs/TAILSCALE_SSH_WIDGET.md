# Tailscale SSH sessions widget

In the home-screen menu, open **browse store → widgets → all → built in**, then tap **Tailscale SSH sessions** to add
the remote tmux session list to the current home page. It shows running
sessions and terminal previews; tap a session to resume it, or choose
**New terminal**. The **×** button ends that session and its programs.

The APK bundles both the widget and its `examples/oma-ssh-sessions` helper.
No community-store download is needed. The tmux interface is separate from the
native **Settings → machines & sessions** manager, but shares its encrypted
Tailscale API discovery configuration.

## First-time setup on another phone

1. Set up the launcher's Linux environment and install `openssh` there if
   needed (`sudo pacman -S openssh`). Install `tmux` and `python3` on the
   remote machines.
2. Connect the Android Tailscale app to your tailnet and allow the launcher
   through its VPN routing.
3. Establish SSH access from a launcher terminal to each destination. Complete
   first-time host verification and configure your SSH authentication there.
   The widget polls with `BatchMode=yes`, so it needs noninteractive SSH access.
4. Open the widget's **Expand → Settings → Set up Tailscale API discovery**.
   Open the Tailscale API-key page, generate an API access token (not a device
   auth key), and enter it in the native dialog. Keep Tailnet as `-` unless
   specifying a different tailnet. Tap **save & discover**. An existing token
   from Machines & Sessions is reused automatically; no second key is needed.
5. Choose **Machines**, select a destination, and set its SSH username under
   **Settings**. Usernames are saved separately per machine. A new machine opens
   username setup before any SSH connection. Blank usernames do not silently
   use the phone guest's root account.

The widget receives cached device names, addresses and last-seen dates from
`android.tailscale`. Native Android code contacts Tailscale's HTTPS API directly.
The encrypted API token never enters the widget, Linux, SSH or an agent.
Automatic refresh is limited to once per minute while in use; the widget's
refresh button requests a fresh list. Cached devices remain available on errors.
Devices are not filtered by online status: being listed does not promise SSH access.

No discovery laptop is needed with API discovery. SSH is used only for the tmux
operations on the selected destination. Keep its **SSH user** configured as before.

Device discovery is API-only. The former discovery SSH host, guest Tailscale CLI
and fallback toggle have been removed from this built-in widget.

## Connecting to a Mac

Use the Mac's **short account name**, as printed by `whoami` in its Terminal,
not its display name or a username saved for another Linux machine. Enable
**System Settings → General → Sharing → Remote Login** for that user. SSH must
be reachable through Tailscale. Install dependencies with `brew install tmux python`
if needed; the helper includes Apple Silicon and Intel Homebrew paths for
noninteractive SSH commands. Each host keeps its own SSH username. The active
`user@host` is displayed on the home tile.

## SSH sign-in

If Tailscale requests an additional SSH authentication check, the error panel
offers **Open authentication** and **Copy link**. Complete the check in the
browser, return to the widget and tap retry/refresh. **Copy error details**
copies the full error, including text truncated on the home tile.

For an easier live sign-in, tap **SSH sign-in** in the session error panel or
under **Expand → Sessions / Settings**. This opens a native screen, outside the
home-screen widget's drag gestures. It makes a harmless SSH connection and
keeps it open for up to three minutes while you tap **open Tailscale approval**
or **copy approval link**. Return from the browser to see the result, then go
back to the widget and refresh. No terminal session is created. Closing/cancelling
the screen stops the check. First-time SSH host verification or password setup
still needs a normal terminal connection. Policies requiring approval on every
connection also apply to later session connections; this does not change policy.

## Updates and personal settings

Fresh widgets have empty hosts, usernames and machine selections. Credentials,
SSH keys and settings are not included in the distributed APK. Installing an
APK on another phone does not copy those from an existing phone.

An existing `machines-sessions.owl` named **SSH sessions** is recognized as
the earlier personalized widget and is not replaced. New installations use
`tailscale-ssh-sessions.owl`, keeping the native session-manager widget separate.
Adding the widget never overwrites an existing file or resets its saved state.
APK updates refresh untouched built-in `tailscale-ssh-sessions.owl` copies;
personal edits and the original personalized `machines-sessions.owl` are left alone.
