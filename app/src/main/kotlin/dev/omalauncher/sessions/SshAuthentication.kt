package dev.omalauncher.sessions

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.omalauncher.linux.shellQuote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

/** One live SSH check: approval output is visible while SSH is still waiting for the browser. */
class SshAuthentication(
    private val scope: CoroutineScope,
    private val updates: CoroutineDispatcher = Dispatchers.Main,
    private val connect: () -> Process,
) {
    var running by mutableStateOf(false)
        private set
    var succeeded by mutableStateOf(false)
        private set
    var output by mutableStateOf("")
        private set
    var approvalUrl by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private val process = AtomicReference<Process?>(null)
    private var job: Job? = null

    fun start(): Job {
        cancel()
        output = ""
        error = null
        succeeded = false
        running = true
        val next = scope.launch {
            try {
                val code = withContext(Dispatchers.IO) {
                    val child = connect()
                    process.set(child)
                    try {
                        ensureActive()
                        child.outputStream.close()
                        child.inputStream.bufferedReader().use { reader ->
                            val buffer = CharArray(1024)
                            while (true) {
                                ensureActive()
                                val size = reader.read(buffer)
                                if (size < 0) break
                                val text = String(buffer, 0, size)
                                withContext(updates) {
                                    output = (output + text).takeLast(8192)
                                    approvalLink(output)?.let { approvalUrl = it }
                                }
                            }
                        }
                        child.waitFor()
                    } finally {
                        process.compareAndSet(child, null)
                        child.destroy()
                    }
                }
                running = false
                succeeded = code == 0
                error = when (code) {
                    0 -> null
                    124 -> "SSH sign-in timed out. Start again to get a fresh approval link."
                    else -> "SSH connection ended (exit $code). Check the details below, then retry."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                running = false
                error = "Could not start SSH sign-in. Check Linux is running and openssh is installed."
            }
        }
        job = next
        return next
    }

    fun cancel() {
        job?.cancel()
        job = null
        process.getAndSet(null)?.destroy()
        running = false
        approvalUrl = null
    }

    companion object {
        /** Fixed approval origin; excludes terminal escape sequences and surrounding punctuation. */
        fun approvalLink(text: String): String? = Regex("https://login[.]tailscale[.]com/[A-Za-z0-9_./?=&%+#~-]+")
            .find(text)?.value?.trimEnd('.')

        fun command(machine: Machine): String {
            require(!machine.local) { "Choose a remote SSH machine" }
            require(machine.user.isNotBlank()) { "Set the SSH username for this machine first" }
            machine.validate()
            val user = machine.user.takeIf { it.isNotEmpty() }?.let { " -l ${shellQuote(it)}" }.orEmpty()
            // No terminal or tmux session is created, and no API credentials are involved.
            return "exec timeout 180 ssh -T -o BatchMode=yes -o StrictHostKeyChecking=yes" +
                " -o ConnectTimeout=10 -o ConnectionAttempts=1 -o ServerAliveInterval=15 -o ServerAliveCountMax=3" +
                " -p ${machine.port}$user -- ${shellQuote(machine.host)} true"
        }
    }
}
