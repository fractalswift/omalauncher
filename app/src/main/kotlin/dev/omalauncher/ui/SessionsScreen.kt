package dev.omalauncher.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.omalauncher.Runtime
import dev.omalauncher.sessions.Machine
import dev.omalauncher.sessions.Presence
import dev.omalauncher.sessions.TerminalSession
import kotlinx.coroutines.launch

@Composable
fun SessionsScreen(runtime: Runtime, homePage: Int, onDismiss: () -> Unit) {
    val manager = runtime.sessions
    val scope = rememberCoroutineScope()
    var editMachine by remember { mutableStateOf<Machine?>(null) }
    var newSession by remember { mutableStateOf<Machine?>(null) }
    var selectedMachine by remember { mutableStateOf<String?>(null) }
    var editSession by remember { mutableStateOf<TerminalSession?>(null) }
    var endSession by remember { mutableStateOf<TerminalSession?>(null) }
    var restartSession by remember { mutableStateOf<TerminalSession?>(null) }
    var showDiscovery by remember { mutableStateOf(false) }

    fun attempt(block: () -> Unit) {
        try { block() } catch (e: Exception) { manager.message = e.message ?: "Could not complete action" }
    }

    LaunchedEffect(Unit) { manager.refresh() }
    OverlayPanel("machines & sessions", onDismiss, contentWidth = ReadingWidth) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            BracketAction(if (manager.refreshing) "checking…" else "refresh", Palette.accent) { manager.refresh() }
            BracketAction("add host", Palette.accent) { editMachine = Machine(name = "") }
            BracketAction("tailscale", Palette.accent) { showDiscovery = true }
            if (manager.discoveryConfigured) BracketAction(if (manager.discovering) "discovering…" else "sync machines", Palette.accent) { manager.discover() }
            BracketAction("add widget", Palette.muted) {
                scope.launch {
                    try {
                        runtime.widgets.addSessionsWidget(homePage)
                        manager.message = "Sessions widget added to home page $homePage"
                    } catch (e: Exception) { manager.message = e.message }
                }
            }
        }
        manager.message?.let { message ->
            BasicText(message, Modifier.fillMaxWidth().clickable { manager.message = null }.padding(vertical = 8.dp),
                style = Mono.copy(color = Palette.accent, fontSize = TextSize.Small))
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicText(selectedMachine?.let { manager.machine(it)?.name } ?: "your sessions", Modifier.weight(1f), style = Mono.copy(color = Palette.muted))
                    if (selectedMachine != null) BracketAction("all", Palette.accent) { selectedMachine = null }
                }
            }
            val visible = manager.sessions.filter { selectedMachine == null || it.machineId == selectedMachine }.sortedByDescending { it.lastUsed }
            if (visible.isEmpty()) item { BasicText("No sessions yet. Choose + on a machine below.", style = Mono.copy(color = Palette.muted)) }
            items(visible, key = { it.id }) { session ->
                val machine = manager.machine(session.machineId)
                SessionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            BasicText(session.name, style = Mono.copy(fontSize = TextSize.Item))
                            BasicText("${machine?.name ?: "unknown machine"} · ${manager.label(session)}", style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
                        }
                        BracketAction(if (manager.live(session)) "open" else if (session.persistent) "resume" else "new shell", Palette.accent) {
                            attempt { manager.open(session.id) }
                        }
                        BracketAction("…", Palette.muted) { editSession = session }
                    }
                    if (manager.presence[session.id] == Presence.Missing && !manager.live(session)) {
                        BracketAction("start again", Palette.accent) { restartSession = session }
                    }
                }
            }
            item { BasicText("machines", Modifier.padding(top = 12.dp), style = Mono.copy(color = Palette.muted)) }
            items(manager.machines.toList(), key = { it.id }) { machine ->
                SessionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable { selectedMachine = machine.id }) {
                            BasicText(machine.name, style = Mono.copy(fontSize = TextSize.Item))
                            BasicText(if (machine.local) "local Linux · no SSH needed" else
                                "${machine.user.ifBlank { "SSH config user" }}@${machine.host}:${machine.port}",
                                style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
                            if (machine.lastSeen.isNotBlank()) BasicText("Tailscale last seen: ${machine.lastSeen}", style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
                        }
                        BracketAction("+", Palette.accent) { newSession = machine }
                        if (!machine.local) BracketAction("edit", Palette.muted) { editMachine = machine }
                    }
                    manager.hostStatus[machine.id]?.let { BasicText(it, style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small)) }
                }
            }
            item {
                BasicText("Persistent sessions use tmux on the destination. Remote work survives a phone disconnect; local work may stop if Android kills Omalauncher.",
                    style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
                BracketAction("local SSH / tmux setup", Palette.accent) {
                    runtime.terminal("pacman -S --needed openssh tmux; exec bash", "Terminal setup")
                }
                BasicText("SSH keys and aliases live in ~/.ssh. First connections ask for host verification/authentication in foot. Discovery does not configure SSH access.",
                    style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
            }
        }
    }

    editMachine?.let { machine ->
        MachineDialog(machine, onDismiss = { editMachine = null }, onSave = {
            attempt { manager.saveMachine(it); editMachine = null }
        }, onRemove = if (manager.machines.any { it.id == machine.id }) ({
            attempt { manager.removeMachine(machine); editMachine = null }
        }) else null)
    }
    newSession?.let { machine ->
        NewSessionDialog(machine, onDismiss = { newSession = null }) { name, command, persistent ->
            attempt { manager.create(machine, name, command, persistent); newSession = null }
        }
    }
    editSession?.let { session ->
        var name by remember(session.id) { mutableStateOf(session.name) }
        DialogFrame(onDismiss = { editSession = null }) {
            BasicText("session", style = Mono.copy(fontSize = TextSize.Title))
            SessionField("Name", name) { name = it }
            BracketAction("rename", Palette.accent) { attempt { manager.rename(session, name); editSession = null } }
            if (manager.live(session)) BracketAction(if (session.persistent) "detach terminal" else "close terminal", Palette.accent) {
                manager.detach(session); editSession = null
            }
            BracketAction("end session", Palette.red) { endSession = session; editSession = null }
            BracketAction("forget saved entry", Palette.muted) { attempt { manager.forget(session); editSession = null } }
            BasicText("Forgetting removes the shortcut only. Detaching leaves a tmux session running.", style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
        }
    }
    endSession?.let { session ->
        ConfirmDialog("end ${session.name}?", "This closes the session and programs running inside it.", "end", onDismiss = { endSession = null }) {
            manager.end(session); endSession = null
        }
    }
    restartSession?.let { session ->
        ConfirmDialog("start ${session.name} again?", "Create a new tmux session using the saved initial command. The old session is not restored.", "start", onDismiss = { restartSession = null }, confirmColor = Palette.accent) {
            attempt { manager.open(session.id, create = true); restartSession = null }
        }
    }
    if (showDiscovery) DiscoveryDialog(runtime, onDismiss = { showDiscovery = false })
}

@Composable
private fun SessionCard(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(LauncherStyle.shape(12.dp)).background(Palette.surface).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
}

@Composable
private fun SessionField(label: String, value: String, secret: Boolean = false, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BasicText(label, style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
        BasicTextField(value, onChange, Modifier.fillMaxWidth().clip(LauncherStyle.shape(8.dp)).background(Palette.background).padding(10.dp),
            singleLine = true, textStyle = Mono, cursorBrush = SolidColor(Palette.accent),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None)
    }
}

@Composable
private fun MachineDialog(machine: Machine, onDismiss: () -> Unit, onSave: (Machine) -> Unit, onRemove: (() -> Unit)?) {
    var name by remember { mutableStateOf(machine.name) }
    var host by remember { mutableStateOf(machine.host) }
    var user by remember { mutableStateOf(machine.user) }
    var port by remember { mutableStateOf(machine.port.toString()) }
    var persistent by remember { mutableStateOf(machine.persistent) }
    var error by remember { mutableStateOf<String?>(null) }
    DialogFrame(onDismiss) {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            BasicText("SSH machine", style = Mono.copy(fontSize = TextSize.Title))
            SessionField("Name", name) { name = it }
            SessionField("Tailscale IP, hostname, or SSH alias", host) { host = it }
            SessionField("SSH user (blank uses ~/.ssh/config)", user) { user = it }
            SessionField("Port", port) { port = it }
            BracketAction(if (persistent) "tmux persistence: on" else "tmux persistence: off", Palette.accent) { persistent = !persistent }
            error?.let { BasicText(it, style = Mono.copy(color = Palette.red)) }
            Row {
                BracketAction("cancel", Palette.muted, onDismiss)
                BracketAction("save", Palette.accent) {
                    try {
                        val updated = machine.copy(name = name.trim(), host = host.trim(), user = user.trim(), port = port.toIntOrNull() ?: 0, persistent = persistent)
                        updated.validate()
                        onSave(updated)
                    } catch (e: Exception) { error = e.message }
                }
                onRemove?.let { BracketAction("remove", Palette.red, it) }
            }
        }
    }
}

@Composable
private fun NewSessionDialog(machine: Machine, onDismiss: () -> Unit, onCreate: (String, String, Boolean) -> Unit) {
    var name by remember { mutableStateOf("Shell") }
    var command by remember { mutableStateOf("") }
    var persistent by remember { mutableStateOf(machine.persistent) }
    DialogFrame(onDismiss) {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            BasicText("new · ${machine.name}", style = Mono.copy(fontSize = TextSize.Title))
            SessionField("Session name", name) { name = it }
            SessionField("Initial command (optional, e.g. opencode)", command) { command = it }
            BracketAction(if (persistent) "tmux persistence: on" else "tmux persistence: off", Palette.accent) { persistent = !persistent }
            BasicText(if (persistent) "Requires tmux on ${machine.name}. Closing the terminal detaches; Resume returns to the same workspace."
                else "A plain shell ends when its terminal closes. Reopening starts a fresh shell.", style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
            Row {
                BracketAction("cancel", Palette.muted, onDismiss)
                BracketAction("create", Palette.accent) { if (name.isNotBlank()) onCreate(name, command, persistent) }
            }
        }
    }
}

@Composable
internal fun DiscoveryDialog(runtime: Runtime, onDismiss: () -> Unit) {
    val manager = runtime.sessions
    val context = LocalContext.current
    var tailnet by remember { mutableStateOf(manager.tailnet) }
    var token by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    DialogFrame(onDismiss) {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            BasicText("Tailscale discovery", style = Mono.copy(fontSize = TextSize.Title))
            BasicText("Keep Tailscale connected on Android, with Omalauncher included in its VPN routing. Generate an API access token (tskey-api-), not a device auth key. Personal API tokens inherit your account's permissions; this app only reads the device list.", style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
            SessionField("Tailnet (- uses the token's tailnet)", tailnet) { tailnet = it }
            BasicText(manager.discoveryCredentialStatus(), style = Mono.copy(color = Palette.accent))
            manager.discoveryError?.let { BasicText(it, style = Mono.copy(color = Palette.red)) }
            BasicText("The token box stays empty for privacy. Blank keeps the saved token. Saved tokens survive app updates and restarts; expiry or revoked access can still require a replacement.", style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
            SessionField(if (manager.discoveryConfigured) "API token (blank keeps saved token)" else "API access token", token, secret = true) { token = it }
            BracketAction("open Tailscale API keys", Palette.accent) {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.tailscale.com/admin/settings/keys")))
            }
            BasicText("The encrypted token stays on Android. Device names/addresses are cached; saved hosts still work without discovery. Tailscale last-seen status does not guarantee SSH access.", style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
            error?.let { BasicText(it, style = Mono.copy(color = Palette.red)) }
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                BracketAction("cancel", Palette.muted, onDismiss)
                BracketAction("save & discover", Palette.accent) {
                    try {
                        require(token.isNotBlank() || manager.discoveryConfigured) { "Enter an API access token" }
                        manager.configureDiscovery(tailnet, token)
                        manager.discover()
                        onDismiss()
                    } catch (e: Exception) { error = e.message }
                }
                if (manager.discoveryConfigured) BracketAction("remove token", Palette.red) {
                    try { manager.clearDiscovery(); onDismiss() }
                    catch (_: Exception) { error = "Could not remove the saved token" }
                }
            }
        }
    }
}
