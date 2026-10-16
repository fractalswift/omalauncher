package dev.omalauncher.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AgentsTest {
    /** Existing guests need a new registry before Node and OpenCode can be installed. */
    @Test fun oldMiseIsUpdatedBeforePreparingOpenCode() {
        val (code, output) = prepareOpenCode("2026.10.8")
        assertEquals(output, 0, code)
        assertEquals(listOf("self-update --yes", "use -g node@lts"), output.lines())
    }

    @Test fun currentMiseDoesNotNeedSelfUpdate() {
        for (version in listOf("2026.10.9", "2026.10.11", "2026.11.1")) {
            val (code, output) = prepareOpenCode(version)
            assertEquals(output, 0, code)
            assertEquals("use -g node@lts", output)
        }
    }

    @Test fun failedMiseUpdateStopsInstallation() {
        val (code, output) = prepareOpenCode("2026.10.8", updateExit = 1)
        assertEquals(1, code)
        assertEquals("self-update --yes", output)
    }

    private fun prepareOpenCode(version: String, updateExit: Int = 0): Pair<Int, String> {
        val script =
            """
            mise() {
              case "${'$'}1" in
                --version) echo '$version linux-arm64' ;;
                self-update) echo "${'$'}*"; return $updateExit ;;
                *) echo "${'$'}*" ;;
              esac
            }
            $PREPARE_OPENCODE
            """.trimIndent()
        val process = ProcessBuilder("bash", "-c", script).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        return process.waitFor() to output
    }

    /** A missing alias would make opening that agent's window throw. */
    @Test fun everyAgentHasAWindowAlias() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val aliases = Regex("""<activity-alias(.*?)/>""", RegexOption.DOT_MATCHES_ALL).findAll(manifest).map { it.groupValues[1] }.toList()
        for (agent in AGENTS) {
            val alias = aliases.firstOrNull { "android:name=\".${agent.windowAlias}\"" in it }
            assertTrue("${agent.bin}: no .${agent.windowAlias} in the manifest", alias != null)
            assertTrue("${agent.bin}: not labelled \"${agent.name}\"", "android:label=\"${agent.name}\"" in alias!!)
            assertTrue("${agent.bin}: no logo", "android:icon=\"@drawable/agent_${agent.bin.replace('-', '_')}\"" in alias)
        }
    }
}
