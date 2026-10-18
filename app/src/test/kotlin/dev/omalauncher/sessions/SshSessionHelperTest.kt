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
            block(dir)
        } finally { dir.deleteRecursively() }
    }

    private fun run(dir: File, action: String, user: String): Triple<Int, String, String> {
        val process = ProcessBuilder("bash", helper, action, "100.64.0.2", user, "\$17")
            .apply { environment()["PATH"] = dir.path + File.pathSeparator + System.getenv("PATH") }.start()
        val out = process.inputStream.bufferedReader().readText()
        val err = process.errorStream.bufferedReader().readText()
        return Triple(process.waitFor(), out, err)
    }

    @Test fun everyActionRejectsABlankUsernameBeforeStartingSsh() = withSsh { dir ->
        for (action in listOf("list", "new", "attach", "end", "shell")) {
            val (code, out, err) = run(dir, action, "")
            assertEquals(2, code)
            assertEquals("", out)
            assertTrue(err.contains("Set the SSH username"))
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
