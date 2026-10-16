package dev.omalauncher.sessions

import dev.omalauncher.linux.shellQuote
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Machine(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String = "",
    val user: String = "",
    val port: Int = 22,
    val persistent: Boolean = true,
    val lastSeen: String = "",
) {
    val local get() = id == LOCAL_ID

    fun validate() {
        require(name.isNotBlank()) { "Give the machine a name" }
        if (local) return
        require(host.matches(Regex("[A-Za-z0-9_:][A-Za-z0-9_.:%-]*"))) { "Enter a hostname, SSH config alias, or IP address" }
        require(user.isEmpty() || user.matches(Regex("[A-Za-z0-9_][A-Za-z0-9_.@\\\\-]*[$]?"))) { "Enter a valid SSH username" }
        require(port in 1..65535) { "Port must be between 1 and 65535" }
    }

    fun json() = JSONObject().put("id", id).put("name", name).put("host", host).put("user", user)
        .put("port", port).put("persistent", persistent).put("lastSeen", lastSeen)

    companion object {
        const val LOCAL_ID = "local"
        val PHONE = Machine(id = LOCAL_ID, name = "This phone")
        fun from(j: JSONObject) = Machine(j.getString("id"), j.getString("name"), j.optString("host"), j.optString("user"),
            j.optInt("port", 22), j.optBoolean("persistent", true), j.optString("lastSeen"))
    }
}

data class TerminalSession(
    val id: String = UUID.randomUUID().toString(),
    val machineId: String,
    val name: String,
    val persistent: Boolean,
    val command: String = "",
    val lastUsed: Long = System.currentTimeMillis(),
) {
    init {
        require(id.matches(Regex("[a-zA-Z0-9-]+"))) { "Invalid session ID" }
    }
    val tmuxName get() = "oma-$id"
    val appId get() = "omarchy.session-$id"
    fun json() = JSONObject().put("id", id).put("machineId", machineId).put("name", name)
        .put("persistent", persistent).put("command", command).put("lastUsed", lastUsed)

    companion object {
        fun from(j: JSONObject) = TerminalSession(j.getString("id"), j.getString("machineId"), j.getString("name"),
            j.getBoolean("persistent"), j.optString("command"), j.optLong("lastUsed"))
    }
}

/** SSH owns authentication/known_hosts. Discovery credentials never enter these commands. */
object SessionCommands {
    fun ssh(machine: Machine, remote: String, interactive: Boolean): String {
        machine.validate()
        val options = if (interactive) "-tt" else "-T -o BatchMode=yes -o StrictHostKeyChecking=yes"
        val user = machine.user.takeIf { it.isNotEmpty() }?.let { " -l ${shellQuote(it)}" }.orEmpty()
        return "ssh $options -o ConnectTimeout=8 -o ServerAliveInterval=15 -o ServerAliveCountMax=3" +
            " -p ${machine.port}$user -- ${shellQuote(machine.host)} ${shellQuote(remote)}"
    }

    fun open(machine: Machine, session: TerminalSession, create: Boolean): String {
        val tmux = if (machine.local) "tmux -L omalauncher" else "tmux"
        val command = if (session.persistent) {
            val action = if (create) {
                "new-session -s ${shellQuote(session.tmuxName)}" + session.command.takeIf { it.isNotBlank() }?.let { " ${shellQuote(it)}" }.orEmpty()
            } else {
                // Attach never creates a missing session or reruns the initial command.
                "attach-session -t ${shellQuote("=" + session.tmuxName)}"
            }
            val wait = if (machine.local) "i=0; until $tmux show-options -g >/dev/null 2>&1; do i=\$((i+1)); [ \"\$i\" -lt 50 ] || { printf '%s\\n' 'Local tmux server did not start'; exit 1; }; sleep 0.1; done; " else ""
            "command -v tmux >/dev/null || { printf '%s\\n' 'tmux is not installed on this machine'; exit 127; }; ${wait}exec $tmux $action"
        } else {
            session.command.takeIf { it.isNotBlank() }?.let { "exec \"\${SHELL:-/bin/sh}\" -lc ${shellQuote(it)}" }
                ?: "exec \"\${SHELL:-/bin/sh}\" -l"
        }
        val launch = if (machine.local) "sh -lc ${shellQuote(command)}" else ssh(machine, command, interactive = true)
        // Keep failures readable in the same foot window instead of closing it immediately.
        return "$launch; code=\$?; if [ \"\$code\" -ne 0 ]; then printf '\\nConnection ended (exit %s). Return to Machines & Sessions to reconnect.\\nPress Enter to close.\\n' \"\$code\"; read -r _; fi"
    }

    fun probe(machine: Machine): String {
        val tmux = if (machine.local) "tmux -L omalauncher" else "tmux"
        val cmd = "command -v tmux >/dev/null || exit 127; " +
            "result=\$($tmux list-sessions -F '#{session_name} #{session_attached}' 2>&1); code=\$?; " +
            "if [ \"\$code\" -eq 0 ]; then printf '%s\\n' \"\$result\"; else " +
            "case \"\$result\" in 'no server running on '*|'error connecting to '*'(No such file or directory)'|'no sessions') exit 0;; " +
            "*) printf '%s\\n' \"\$result\" >&2; exit \"\$code\";; esac; fi"
        return if (machine.local) cmd else ssh(machine, cmd, interactive = false)
    }

    fun end(machine: Machine, session: TerminalSession): String {
        val tmux = if (machine.local) "tmux -L omalauncher" else "tmux"
        val cmd = "$tmux kill-session -t ${shellQuote("=" + session.tmuxName)}"
        return if (machine.local) cmd else ssh(machine, cmd, interactive = false)
    }
}

internal fun <T> JSONArray.mapObjects(read: (JSONObject) -> T): List<T> = (0 until length()).map { read(getJSONObject(it)) }
