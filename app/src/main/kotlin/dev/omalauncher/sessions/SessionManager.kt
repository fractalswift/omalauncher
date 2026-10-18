package dev.omalauncher.sessions

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.omalauncher.Runtime
import dev.omalauncher.linux.ShellPool
import dev.omalauncher.ui.SettingsPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.NetworkInterface

enum class Presence { Unknown, Attached, Detached, Missing, Unavailable }

/** Durable machine/session identities; live windows and remote tmux state are separate observations. */
class SessionManager(
    context: Context,
    private val runtime: Runtime,
    private val scope: CoroutineScope,
) {
    private val prefs = context.getSharedPreferences("terminal-sessions", Context.MODE_PRIVATE)
    private val credentials = DiscoveryCredentials(context)
    private val shells = ShellPool(runtime.proot, 2)
    val machines = mutableStateListOf<Machine>()
    val sessions = mutableStateListOf<TerminalSession>()
    val presence = mutableStateMapOf<String, Presence>()
    val hostStatus = mutableStateMapOf<String, String>()
    val opening = mutableStateMapOf<String, Boolean>()
    private val launches = mutableMapOf<String, Any>()
    private val ending = mutableSetOf<String>()
    var message by mutableStateOf<String?>(null)
    var refreshing by mutableStateOf(false)
        private set
    var discovering by mutableStateOf(false)
        private set
    var discoveryConfigured by mutableStateOf(credentials.configured)
        private set
    var tailnet by mutableStateOf(credentials.tailnet)
        private set
    val discoveredMachines = mutableStateListOf<Machine>()
    var discoveryError by mutableStateOf<String?>(null)
        private set
    var discoveryUpdatedAt by mutableStateOf(prefs.getLong("discovery_updated_at", 0))
        private set
    private var discoveryJob: Job? = null
    private var discoveryGeneration = 0
    private var lastDiscoveryAttempt: Long? = null
    private var loadFailed = false
    var authenticationTarget by mutableStateOf<Machine?>(null)
        private set

    init {
        try {
            val saved = JSONObject(prefs.getString("catalog", "{}").orEmpty())
            saved.optJSONArray("machines")?.mapObjects(Machine::from)?.let(machines::addAll)
            saved.optJSONArray("sessions")?.mapObjects(TerminalSession::from)?.let(sessions::addAll)
        } catch (e: Exception) {
            loadFailed = true
            message = "Could not read saved sessions; existing data has been preserved."
        }
        if (machines.none { it.local }) machines.add(0, Machine.PHONE)
        val cached = runCatching {
            prefs.getString("discovery_devices", null)?.let { JSONArray(it).mapObjects(Machine::from) }
        }.getOrNull() ?: machines.filter { it.id.startsWith("tailscale-") }
        discoveredMachines.addAll(cached)
    }

    private fun save() {
        check(!loadFailed) { "Saved session data could not be read; it has not been overwritten" }
        val data = JSONObject().put("machines", JSONArray(machines.map { it.json() }))
            .put("sessions", JSONArray(sessions.map { it.json() }))
        prefs.edit().putString("catalog", data.toString()).apply()
    }

    private fun task(action: suspend () -> Unit) = scope.launch {
        try { action() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { message = e.message ?: "Session operation failed" }
    }

    fun machine(id: String) = machines.firstOrNull { it.id == id }
    fun authenticate(host: String, user: String) {
        require(user.isNotBlank()) { "Set the SSH username for this machine first" }
        val target = Machine(name = host, host = host, user = user)
        target.validate()
        authenticationTarget = target
        runtime.openSettings(SettingsPage.SshAuthentication)
    }
    fun live(session: TerminalSession) = runtime.wm.appWindows.any { it.appId == session.appId }

    fun label(session: TerminalSession): String = when {
        live(session) -> "open"
        opening[session.id] == true -> "opening…"
        !session.persistent -> "closed · new shell on reopen"
        else -> when (presence[session.id] ?: Presence.Unknown) {
            Presence.Attached -> "running · attached elsewhere"
            Presence.Detached -> "running · detached"
            Presence.Missing -> "ended / not started"
            Presence.Unavailable -> "unreachable · state unknown"
            Presence.Unknown -> "saved · state not checked"
        }
    }

    fun saveMachine(machine: Machine) {
        machine.validate()
        check(!loadFailed) { "Cannot change unreadable saved data" }
        val index = machines.indexOfFirst { it.id == machine.id }
        if (index < 0) machines += machine else machines[index] = machine
        hostStatus.remove(machine.id)
        sessions.filter { it.machineId == machine.id }.forEach { presence.remove(it.id) }
        save()
    }

    fun removeMachine(machine: Machine) {
        require(!machine.local) { "This phone is always available" }
        require(sessions.none { it.machineId == machine.id }) { "Forget this machine's saved sessions first" }
        machines.remove(machine)
        save()
    }

    fun create(machine: Machine, name: String, command: String, persistent: Boolean) {
        check(runtime.widgets.linux) { "Install/start Linux before opening a session" }
        require(name.isNotBlank()) { "Give the session a name" }
        val session = TerminalSession(machineId = machine.id, name = name.trim(), command = command.trim(), persistent = persistent)
        sessions += session
        save()
        open(session.id, create = true)
    }

    /** Reopening an existing record attaches only. A restart is always an explicit user action. */
    fun open(id: String, create: Boolean = false) {
        val session = sessions.firstOrNull { it.id == id } ?: return
        if (ending.contains(id)) return
        if (runtime.focusSessionWindow(session.appId)) return
        if (live(session)) { message = "This terminal is already open in Android Recents"; return }
        if (opening[id] == true) return
        val machine = machine(session.machineId) ?: return
        if (!runtime.widgets.linux) { runtime.openSettings(SettingsPage.Linux); return }
        val index = sessions.indexOfFirst { it.id == id }
        sessions[index] = session.copy(lastUsed = System.currentTimeMillis())
        save()
        opening[id] = true
        val launch = Any()
        launches[id] = launch
        presence.remove(id)
        if (machine.local && session.persistent) runtime.ensureLocalSessionServer()
        runtime.sessionTerminal(session.appId, "${machine.name} · ${session.name}", SessionCommands.open(machine, session, create)) {
            if (launches[id] === launch) {
                launches.remove(id)
                opening.remove(id)
            }
            if (session.persistent) refresh()
        }
        task {
            // Window tracking takes over once foot has mapped. Keep a pending launch unique until then.
            repeat(100) {
                if (launches[id] !== launch) return@task
                if (live(session) || opening[id] != true) { opening.remove(id); return@task }
                delay(100)
            }
            message = "Terminal has not appeared yet. Check Linux is running before trying again."
        }
    }

    fun detach(session: TerminalSession) { runtime.closeSessionWindow(session.appId) }

    fun forget(session: TerminalSession) {
        require(!live(session) && opening[session.id] != true && session.id !in ending) { "Close this terminal window first" }
        sessions.removeAll { it.id == session.id }
        presence.remove(session.id)
        save()
    }

    fun rename(session: TerminalSession, name: String) {
        require(name.isNotBlank()) { "Give the session a name" }
        val index = sessions.indexOfFirst { it.id == session.id }
        if (index >= 0) sessions[index] = sessions[index].copy(name = name.trim())
        save()
    }

    fun end(session: TerminalSession) = task {
        check(opening[session.id] != true) { "Wait for the terminal to open before ending it" }
        if (!ending.add(session.id)) return@task
        try {
            val machine = machine(session.machineId) ?: return@task
            if (session.persistent) {
                val result = shells.run(SessionCommands.end(machine, session), timeoutSeconds = 15, retry = false)
                check(result.code == 0) { result.err.ifBlank { "Could not end session. It may already have ended; refresh or forget its saved entry." } }
            }
            runtime.closeSessionWindow(session.appId)
            sessions.removeAll { it.id == session.id }
            presence.remove(session.id)
            save()
        } finally { ending.remove(session.id) }
    }

    fun refresh() {
        if (refreshing || !runtime.widgets.linux) return
        refreshing = true
        task {
            try {
                machines.toList().filter { machine -> sessions.any { it.machineId == machine.id && it.persistent } }.map { machine ->
                    scope.async {
                        val result = shells.run(SessionCommands.probe(machine), timeoutSeconds = 15)
                        if (this@SessionManager.machine(machine.id) != machine) return@async
                        val found = result.out.lineSequence().mapNotNull { line ->
                            val parts = line.trim().split(' ')
                            parts.getOrNull(1)?.toIntOrNull()?.let { parts[0] to it }
                        }.toMap()
                        hostStatus[machine.id] = when (result.code) {
                            0 -> "SSH/local connection checked"
                            127 -> "tmux missing · install it on this machine"
                            124 -> "Connection timed out"
                            else -> result.err.ifBlank { "Could not check sessions; connect once to complete SSH setup" }.take(300)
                        }
                        sessions.filter { it.machineId == machine.id && it.persistent }.forEach { session ->
                            presence[session.id] = when {
                                result.code != 0 -> Presence.Unavailable
                                !found.containsKey(session.tmuxName) -> Presence.Missing
                                found.getValue(session.tmuxName) > 0 -> Presence.Attached
                                else -> Presence.Detached
                            }
                        }
                    }
                }.awaitAll()
            } finally { refreshing = false }
        }
    }

    fun configureDiscovery(tailnet: String, token: String) {
        credentials.save(tailnet, token)
        resetDiscoveryRequest()
        discoveredMachines.clear()
        discoveryUpdatedAt = 0
        prefs.edit().remove("discovery_devices").remove("discovery_updated_at").apply()
        this.tailnet = credentials.tailnet
        discoveryConfigured = credentials.configured
    }

    fun clearDiscovery() {
        credentials.clear()
        resetDiscoveryRequest()
        tailnet = "-"
        discoveryConfigured = false
        message = "Discovery token removed. Saved machines and sessions are still available."
    }

    fun discover() {
        if (discovering) return
        if (!discoveryConfigured) {
            discoveryError = "Configure a Tailscale API token to discover devices"
            message = discoveryError
            return
        }
        val generation = discoveryGeneration
        lastDiscoveryAttempt = SystemClock.elapsedRealtime()
        discoveryError = null
        discovering = true
        discoveryJob = scope.launch {
            try {
                val found = withContext(Dispatchers.IO) { TailscaleDiscovery.fetch(credentials.tailnet, credentials.token()) }
                val localAddresses = withContext(Dispatchers.IO) {
                    runCatching { NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }.map { it.hostAddress }.toSet() }
                        .getOrDefault(emptySet())
                }
                if (generation != discoveryGeneration) return@launch
                val devices = found.filter { it.host !in localAddresses }
                for (discovered in devices) {
                    val index = machines.indexOfFirst { it.id == discovered.id }
                    if (index < 0) machines += discovered
                    else machines[index] = machines[index].copy(host = discovered.host, lastSeen = discovered.lastSeen)
                }
                save()
                discoveredMachines.clear()
                discoveredMachines.addAll(devices)
                discoveryUpdatedAt = System.currentTimeMillis()
                prefs.edit().putString("discovery_devices", JSONArray(devices.map { it.json() }).toString())
                    .putLong("discovery_updated_at", discoveryUpdatedAt).apply()
                message = "Device list updated. Set an SSH username for each machine, or use ~/.ssh/config."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == discoveryGeneration) {
                    // Never expose provider responses, headers or credentials to widgets.
                    discoveryError = e.message?.takeIf { it.startsWith("Tailscale ") }
                        ?: "Could not refresh Tailscale devices. Check your internet connection and discovery settings."
                    message = discoveryError
                }
            } finally {
                if (generation == discoveryGeneration) discovering = false
            }
        }
    }

    /** Widgets can poll cached data frequently without polling the control-plane API every time. */
    fun discoverIfStale() {
        if (!discoveryConfigured || discovering) return
        val now = SystemClock.elapsedRealtime()
        if (lastDiscoveryAttempt?.let { now - it < 60_000 } == true) return
        discover()
    }

    private fun resetDiscoveryRequest() {
        discoveryGeneration++
        discoveryJob?.cancel()
        discoveryJob = null
        discovering = false
        discoveryError = null
        lastDiscoveryAttempt = null
    }
}
