package dev.omalauncher.sessions

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SessionCommandsTest {
    private val remote = Machine(id = "test-host", name = "Server", host = "100.100.1.2", user = "matt", port = 2222)
    private val session = TerminalSession(id = "test-session", machineId = remote.id, name = "OpenCode", persistent = true,
        command = "cd '/a project' && opencode --prompt \"don't lose this\"")

    /** Execute the actual generated shell with stand-in ssh/tmux; preserve every argument boundary. */
    private fun execute(command: String, result: Int = 0, tmuxOutput: String = ""): Pair<Int, List<String>> {
        val dir = Files.createTempDirectory("session-command-test").toFile()
        try {
            File(dir, "tmux").apply {
                writeText("#!/bin/sh\nprintf '%s\\n' \"\$@\" >> \"\$CAPTURE\"\nprintf '%s' \"\$TMUX_OUTPUT\"\nexit $result\n")
                setExecutable(true)
            }
            File(dir, "ssh").apply {
                writeText("#!/bin/sh\nfor last do :; done\nexec /bin/sh -c \"\$last\"\n")
                setExecutable(true)
            }
            val capture = File(dir, "args")
            val process = ProcessBuilder("/bin/sh", "-c", command).redirectErrorStream(true).apply {
                environment()["PATH"] = "${dir.path}:/usr/bin:/bin"
                environment()["CAPTURE"] = capture.path
                environment()["TMUX_OUTPUT"] = tmuxOutput
            }.start()
            process.outputStream.close()
            process.inputStream.bufferedReader().readText()
            return process.waitFor() to if (capture.exists()) capture.readLines() else emptyList()
        } finally { dir.deleteRecursively() }
    }

    @Test fun newRemoteSessionPreservesInitialCommandAsOneArgument() {
        val (code, args) = execute(SessionCommands.open(remote, session, create = true))
        assertEquals(0, code)
        assertEquals(listOf("new-session", "-s", "oma-test-session", session.command), args)
    }

    @Test fun resumeNeverCreatesASessionOrReplaysInitialCommand() {
        val (code, args) = execute(SessionCommands.open(remote, session, create = false))
        assertEquals(0, code)
        assertEquals(listOf("attach-session", "-t", "=oma-test-session"), args)
        assertFalse(args.contains(session.command))
    }

    @Test fun missingRemoteSessionDoesNotFallBackToCreate() {
        val (_, args) = execute(SessionCommands.open(remote, session, create = false), result = 1)
        assertEquals(listOf("attach-session", "-t", "=oma-test-session"), args)
    }

    @Test fun endingTargetsExactSessionRatherThanMatchingPrefix() {
        val (_, args) = execute(SessionCommands.end(remote, session))
        assertEquals(listOf("kill-session", "-t", "=oma-test-session"), args)
    }

    @Test fun localSessionsUseDedicatedServerAndDoNotInvokeSsh() {
        val (_, args) = execute(SessionCommands.open(Machine.PHONE, session, create = false))
        assertEquals(listOf("-L", "omalauncher", "show-options", "-g", "-L", "omalauncher", "attach-session", "-t", "=oma-test-session"), args)
    }

    @Test fun sshOptionsUseSavedPortUserAndNoninteractiveChecks() {
        val command = SessionCommands.probe(remote)
        assertTrue(command.contains("-p 2222"))
        assertTrue(command.contains("-l 'matt'"))
        assertTrue(command.contains("BatchMode=yes"))
        assertTrue(command.contains("StrictHostKeyChecking=yes"))
    }

    @Test fun probeDoesNotReportAccessErrorsAsEndedSessions() {
        assertEquals(1, execute(SessionCommands.probe(remote), result = 1, tmuxOutput = "access not allowed").first)
        assertEquals(0, execute(SessionCommands.probe(remote), result = 1, tmuxOutput = "no server running on /tmp/tmux-0/default").first)
        assertEquals(0, execute(SessionCommands.probe(remote), result = 1, tmuxOutput = "error connecting to /tmp/tmux-0/default (No such file or directory)").first)
    }

    @Test fun duplicateNamesStillGetIndependentSessionIdentities() {
        val first = TerminalSession(machineId = remote.id, name = "Shell", persistent = true)
        val second = TerminalSession(machineId = remote.id, name = "Shell", persistent = true)
        assertNotEquals(first.tmuxName, second.tmuxName)
        assertNotEquals(first.appId, second.appId)
    }

    @Test fun metadataRoundTripKeepsIdentityAndConnectionPreferences() {
        assertEquals(remote, Machine.from(remote.json()))
        assertEquals(session, TerminalSession.from(session.json()))
    }

    @Test fun hostCannotInjectOptionsOrShellCommands() {
        for (host in listOf("-oProxyCommand=bad", "host;touch /tmp/bad", "user@host", "$(whoami)")) {
            assertThrows(IllegalArgumentException::class.java) { remote.copy(host = host).validate() }
        }
        remote.copy(host = "::1").validate()
    }
}
