package dev.omalauncher.sessions

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SshSessionHelperTest {
    private val helper = File("src/main/assets/widgets/examples/oma-ssh-sessions").absolutePath

    private fun withSsh(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("ssh-helper-test").toFile()
        try {
            File(dir, "ssh").apply {
                writeText("#!/bin/sh\nprintf '%s\\000' \"\$@\"\n")
                assertTrue(setExecutable(true))
            }
            File(dir, "ssh-copy-id").apply {
                writeText("#!/bin/sh\nprintf '%s\\n' \"\$@\" > \"\$HOME/installed-args\"\n[ ! -f \"\$HOME/deny-install\" ]\n")
                assertTrue(setExecutable(true))
            }
            block(dir)
        } finally { dir.deleteRecursively() }
    }

    private fun run(dir: File, action: String, user: String): Triple<Int, String, String> {
        val process = ProcessBuilder("bash", helper, action, "100.64.0.2", user, "\$17")
            .apply {
                environment()["PATH"] = dir.path + File.pathSeparator + System.getenv("PATH")
                environment()["HOME"] = File(dir, "home").apply { mkdirs() }.path
                environment()["TMPDIR"] = File(dir, "tmp").apply { mkdirs() }.path
            }.start()
        val out = process.inputStream.bufferedReader().readText()
        val err = process.errorStream.bufferedReader().readText()
        return Triple(process.waitFor(), out, err)
    }

    @Test fun everyActionRejectsABlankUsernameBeforeStartingSsh() = withSsh { dir ->
        for (action in listOf("list", "new", "attach", "end", "shell", "check", "setup")) {
            val (code, out, err) = run(dir, action, "")
            assertEquals(2, code)
            assertEquals("", out)
            assertTrue(err.contains("Set the SSH username"))
        }
    }

    @Test fun setupCreatesOnePrivateIdentityAndReusesItOnLaterRuns() = withSsh { dir ->
        val (code, out, err) = run(dir, "setup", "mac-user")
        assertEquals(err, 0, code)
        assertTrue(out.contains("Automatic SSH is ready"))
        val identity = File(dir, "home/.ssh/omalauncher/id_ed25519")
        assertTrue(identity.isFile)
        val original = identity.readBytes()
        val installed = File(dir, "home/installed-args").readText()
        assertTrue(installed.contains(identity.path + ".pub"))
        assertTrue(installed.contains("mac-user"))
        assertTrue(installed.contains("100.64.0.2"))
        assertEquals(0, run(dir, "setup", "mac-user").first)
        assertArrayEquals(original, identity.readBytes())
        assertFalse(File(dir, "home/.ssh/omalauncher/setup.lock").exists())
    }

    @Test fun failedKeyInstallationDoesNotReportSuccessOrLoseTheLocalKey() = withSsh { dir ->
        File(dir, "home").mkdirs()
        File(dir, "home/deny-install").writeText("")
        val (code, out, _) = run(dir, "setup", "mac-user")
        assertNotEquals(0, code)
        assertFalse(out.contains("Automatic SSH is ready"))
        assertTrue(File(dir, "home/.ssh/omalauncher/id_ed25519").exists())
        assertFalse(File(dir, "home/.ssh/omalauncher/setup.lock").exists())
    }

    @Test fun terminalsAndBackgroundChecksShareConnectionsAndTheInstalledIdentity() = withSsh { dir ->
        assertEquals(0, run(dir, "setup", "mac-user").first)
        for (action in listOf("new", "attach", "shell", "list", "check")) {
            val (code, out, err) = run(dir, action, "mac-user")
            assertEquals(err, 0, code)
            val args = out.split('\u0000').dropLast(1)
            assertTrue(args.contains("ControlMaster=auto"))
            assertTrue(args.contains("ControlPersist=600"))
            assertTrue(args.contains("ControlPath=${dir.path}/tmp/oc/%C"))
            assertEquals(File(dir, "home/.ssh/omalauncher/id_ed25519").path, args[args.indexOf("-i") + 1])
            if (action == "check") {
                assertTrue(args.contains("BatchMode=yes"))
                assertTrue(args.contains("StrictHostKeyChecking=yes"))
                assertEquals("true", args.last())
            }
        }
    }

    @Test fun macCommandsUseTheSelectedAccountAndIncludeHomebrewPaths() = withSsh { dir ->
        for (action in listOf("list", "new", "attach")) {
            val (code, out, err) = run(dir, action, "mac-user")
            assertEquals(err, 0, code)
            val args = out.split('\u0000').dropLast(1)
            assertEquals("mac-user", args[args.indexOf("-l") + 1])
            assertEquals("100.64.0.2", args[args.indexOf("--") + 1])
            assertTrue(args.last().startsWith("export PATH=\"/opt/homebrew/bin:/usr/local/bin:\$PATH\"; "))
        }
    }
}
