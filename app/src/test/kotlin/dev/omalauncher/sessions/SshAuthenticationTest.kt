package dev.omalauncher.sessions

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SshAuthenticationTest {
    private val link = "https://login.tailscale.com/a/ssh/example-check?src=ssh&node=test"

    private class PendingProcess(text: String) : Process() {
        val waiting = CountDownLatch(1)
        private val finished = CountDownLatch(1)
        private var code = 0
        var destroyed = false
        private val stdout = ByteArrayInputStream(text.toByteArray())
        override fun getInputStream() = stdout
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun waitFor(): Int { waiting.countDown(); finished.await(); return code }
        override fun exitValue(): Int {
            if (finished.count != 0L) throw IllegalThreadStateException()
            return code
        }
        fun finish(code: Int) { this.code = code; finished.countDown() }
        override fun destroy() { destroyed = true; finish(143) }
    }

    @Test fun approvalLinkIsAvailableWhileTheConnectionIsStillWaiting() = runBlocking {
        val child = PendingProcess("Tailscale SSH requires an additional check.\nVisit $link\n")
        val check = SshAuthentication(this, Dispatchers.Unconfined) { child }
        val task = check.start()
        try {
            assertTrue(withContext(Dispatchers.IO) { child.waiting.await(5, TimeUnit.SECONDS) })
            assertTrue(check.running)
            assertFalse(task.isCompleted)
            assertEquals(link, check.approvalUrl)
            child.finish(0)
            task.join()
            assertTrue(check.succeeded)
            assertFalse(check.running)
            assertNull(check.error)
        } finally { check.cancel(); task.join() }
    }

    @Test fun cancellationStopsTheConnectionAndClearsTheApprovalLink() = runBlocking {
        val child = PendingProcess("Visit $link\n")
        val check = SshAuthentication(this, Dispatchers.Unconfined) { child }
        val task = check.start()
        try {
            assertTrue(withContext(Dispatchers.IO) { child.waiting.await(5, TimeUnit.SECONDS) })
            check.cancel()
            task.join()
            assertTrue(child.destroyed)
            assertFalse(check.running)
            assertFalse(check.succeeded)
            assertNull(check.approvalUrl)
        } finally { check.cancel(); task.join() }
    }

    @Test fun commandChecksAccessWithoutCreatingTmuxSessionsOrDisablingHostVerification() {
        val command = SshAuthentication.command(Machine(name = "Server", host = "100.64.0.2", user = "developer"))
        assertTrue(command.startsWith("exec timeout 180 ssh -T"))
        assertTrue(command.contains("StrictHostKeyChecking=yes"))
        assertTrue(command.contains("-l 'developer'"))
        assertTrue(command.endsWith("-- '100.64.0.2' true"))
        assertFalse(command.contains("tmux"))
        assertTrue(runCatching { SshAuthentication.command(Machine.PHONE) }.isFailure)
        assertTrue(runCatching { SshAuthentication.command(Machine(name = "Bad", host = "host; touch injected")) }.isFailure)
    }

    @Test fun parserHandlesTerminalOutputWithoutAcceptingLookalikeDomains() {
        assertEquals(link, SshAuthentication.approvalLink("\u001b[33mVisit $link\u001b[0m\n"))
        assertEquals(link, SshAuthentication.approvalLink("Visit ($link)."))
        assertNull(SshAuthentication.approvalLink("https://login.tailscale.com.example.org/a/check"))
        assertNull(SshAuthentication.approvalLink("https://example.org/a/check"))
    }
}
