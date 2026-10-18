# Direct SSH session list

`app/src/main/assets/widgets/examples/65-tailscale.owl` is a live list of **actual tmux sessions** on the selected machine. Tap a session card to attach directly in Foot. **New terminal** immediately opens a new persistent tmux session: no naming form, saved-session catalog or separate Resume menu.

Each card shows a stable short tmux ID (for example `$0`), running program, current folder and up to two short lines of recent visible terminal text. This is a pane preview, not shell-history scraping. It is read over SSH, shown on the phone and not uploaded to any service.

## Install

The widget uses the bundled Bash helper `oma-ssh-sessions`, also under `widgets/examples`. Copy the widget to the watched directory while keeping the helper in examples:

```sh
cp ~/.config/omalauncher/widgets/examples/65-tailscale.owl ~/.config/omalauncher/widgets/65-tailscale.owl
owl-check ~/.config/omalauncher/widgets/65-tailscale.owl
```

This widget requires this fork's native API discovery and SSH sign-in support.
Install the fork APK, then use **browse store → widgets → all → built in →
Tailscale SSH sessions**. The helper is invoked through Bash and needs no
executable bit. A widget import link alone does not install the helper or add the
required native functionality to an older launcher.

The phone needs `openssh`, declared by the widget. Each destination needs **tmux and python3**. Tailscale's Android VPN must include the launcher. Device discovery uses a Tailscale API token saved on Android; no extra daemon inside proot is required. See [current setup and authentication instructions](TAILSCALE_SSH_WIDGET.md).

## Configure once

Open the widget, then **Settings**:

- **Selected machine IP or SSH alias:** the laptop's Tailscale IP. Numeric IPs avoid MagicDNS problems inside proot.
- **SSH username for this machine:** the remote account, required explicitly. Usernames are saved per destination, so changing another node's username does not change your laptop login.
- **Tailscale API discovery:** configure the token in the native dialog. The phone fetches devices directly; no discovery laptop or SSH fallback is used.

Press Enter to save each field. Use the actual installed filename if configuring states with `owl-send`. API tokens must be configured through native settings, never widget state.

First-time host verification and Tailscale SSH approval still apply. Authorize the destination interactively before expecting background listing to work. Polling uses `BatchMode=yes`; it cannot repeatedly ask for a password. SSH keys/agent or authorized Tailscale SSH access are required. The widget never disables host-key checks.

## Everyday use

1. **New terminal** creates a tmux workspace with an automatic session number.
2. Run commands or `opencode2` in it normally.
3. Return home, detach, close the phone terminal, or disconnect from the network.
4. Tap the session's card to return to the same running shell/agent and scrollback.

The home tile and its expanded view show the same list. **Machines**, directly on the home tile, lists devices returned by the API; last-seen status does not guarantee reachability. A new machine asks for its SSH username first. Tap a configured machine's name to view its sessions, or **New terminal** beside it to create a persistent tmux workspace immediately. Creating one also selects that machine and switches the widget to **Sessions**, so the new workspace appears when you return from Foot. All normal new-terminal buttons use the same persistent creation path.

For ordinary SSH servers without tmux/Python, select the machine and use **Settings → Temporary SSH (not saved)**. That explicitly temporary connection is not included in the persistent-session list. SSH authentication, access rules and a working SSH server are still required; set an explicit username in Settings. **SSH sign-in → enable automatic SSH** handles one-time phone-key setup for password-based computers.

Each running session has an **×** button. It ends that entire tmux session and the programs running in it, on the selected machine, then refreshes the list. It is not merely a client disconnect. Other sessions are unaffected. A failed end request is shown in the widget. Return Home instead of pressing × when you want to keep work running.

The list refreshes every five seconds while visible; refresh manually after creating a session if necessary. List results carry the requested host and user. Changing either hides the previous results immediately, before the next response arrives, so old cards cannot attach to or end a session on the wrong node.

The helper lists the selected account's default tmux server, including workspaces created elsewhere or through another launcher UI. It does not trust stale Android catalog entries. A session that has ended disappears; attaching never silently creates a replacement. Other connected clients are not forcibly detached when resuming.

Persistent means the workspace survives a client disconnect **while the destination keeps running**. Exiting its last shell or rebooting the destination ends it. Existing plain SSH windows or arbitrary desktop terminals are not automatically captured into tmux. Start new persistent work using **New terminal**.

## Implementation

- `oma-ssh-sessions list HOST USER` uses a read-only Python query over SSH to return session metadata and bounded active-pane previews as JSON. No server is started by listing an empty tmux socket.
- `oma-ssh-sessions new HOST USER` allocates a terminal and runs `tmux -u new-session`.
- `oma-ssh-sessions attach HOST USER '$ID'` attaches to that exact tmux session. IDs are validated before sending any command.
- `oma-ssh-sessions end HOST USER '$ID'` ends only that exact session, without opening another terminal. Invalid IDs are rejected before SSH runs.
- `oma-ssh-sessions shell HOST USER` opens ordinary interactive SSH without any tmux/Python requirement.
- OWL passes shell values through `sh()`. SSH uses argument arrays and `--` before destinations. Remote tmux arguments are fixed or strictly validated.

## Validation

```sh
./gradlew :owl:test :owl:owlJar --configure-on-demand --no-configuration-cache
java -jar owl/build/cli/owl.jar check app/src/main/assets/widgets/examples/65-tailscale.owl
python3 scripts/test-ssh-sessions.py
```

The helper tests use an isolated tmux socket and fake SSH transport, with no network access. On a phone, `owl-check` additionally evaluates live sources and renders a preview. Reconnection is verified by comparing the remote pane/shell process before and after detaching and tapping its card.
