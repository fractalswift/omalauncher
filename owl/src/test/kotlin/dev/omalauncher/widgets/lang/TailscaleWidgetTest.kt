package dev.omalauncher.widgets.lang

import dev.omalauncher.widgets.market.Share
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TailscaleWidgetTest {
    private val program = Compiler.compile(File("../app/src/main/assets/widgets/examples/65-tailscale.owl").readText())

    private class WidgetHost(val program: Program) : Host {
        val slots = Array<Value>(program.globals.size) { Null }
        val actions = mutableListOf<Pair<String, List<Value>>>()
        override val budget = Budget(200_000)
        init { program.globals.forEachIndexed { i, g -> if (g is Global.State) slots[i] = g.init(Env.root(this)) } }
        override fun read(slot: Int): Value = (program.globals[slot] as? Global.Let)?.value?.invoke(Env.root(this)) ?: slots[slot]
        override fun write(slot: Int, value: Value) { slots[slot] = value }
        override fun theme(name: String): Int? = null
        override fun time() = 0.0
        override fun act(name: String, args: List<Value>) { actions += name to args }
        override fun runInto(command: String, slot: Int) { actions += "run" to listOf(Str(command)) }
        fun set(name: String, value: Value) { slots[program.globals.indexOfFirst { it.name == name }] = value }
        fun get(name: String) = read(program.globals.indexOfFirst { it.name == name })
    }

    private fun host() = WidgetHost(program).apply {
        set("machine", Str("100.64.0.1")); set("machineName", Str("Laptop")); set("sshUser", Str("developer"))
        set("work", Json.parse("""{"ok":true,"out":{"ok":true,"host":"100.64.0.1","user":"developer","sessions":[{"id":"${'$'}17","name":"17","command":"opencode2","folder":"project","preview":"Explain this function","attached":0,"windows":1}]}}"""))
        set("devices", Json.parse("""{"configured":true,"syncing":false,"items":[{"id":"self","name":"Laptop","host":"100.64.0.1"},{"id":"a","name":"Other","host":"100.64.0.2"}]}"""))
    }

    private fun elements(nodes: List<WNode>, env: Env): List<Pair<WNode.Element, Env>> = nodes.flatMap { node ->
        when (node) {
            is WNode.Element -> listOf(node to env) + elements(node.children, env)
            is WNode.If -> elements(if (node.cond(env).truthy) node.then else node.orElse, env)
            is WNode.For -> ((node.list(env) as? Lst)?.items ?: emptyList()).flatMapIndexed { i, item -> elements(node.body, env.child(item, Num(i.toDouble()))) }
            is WNode.Lets -> elements(node.children, env.lets(node.values))
            is WNode.Use -> elements(node.body, env.childLazy(node.args.map { arg -> { arg(env) } }.toTypedArray()))
            is WNode.Draw -> emptyList()
        }
    }
    private fun root(host: WidgetHost) = elements(program.root, Env.root(host))
    private fun text(host: WidgetHost) = root(host).mapNotNull { (n, env) -> n.props["text"]?.invoke(env)?.toString() }
    private fun tapSession(host: WidgetHost): String {
        val (node, env) = root(host).first { (n, _) -> n.kind == "Column" && "onTap" in n.handlers }
        node.handlers.getValue("onTap").run(env, emptyList())
        assertEquals("terminal", host.actions.single().first)
        return host.actions.single().second[0].toString()
    }
    private fun args(command: String): List<String> {
        val call = command.removePrefix("exec ")
        val p = ProcessBuilder("bash", "-c", "bash() { printf '%s\\0' \"\$@\"; }; $call").start()
        val output = p.inputStream.bufferedReader().readText()
        assertEquals(p.errorStream.bufferedReader().readText(), 0, p.waitFor())
        return output.split('\u0000').dropLast(1)
    }

    @Test fun rootShowsClickableSessionIdsProgramFolderAndPreview() {
        val h = host()
        assertTrue(text(h).contains("${'$'}17 · opencode2 · project"))
        assertTrue(text(h).contains("Explain this function"))
        val command = tapSession(h)
        assertTrue(command.startsWith("exec bash "))
        assertEquals(listOf("attach", "100.64.0.1", "developer", "${'$'}17"), args(command).takeLast(4))
        assertEquals("Laptop · ${'$'}17", h.actions.single().second[1].toString())
    }
    @Test fun newTerminalStartsDirectlyWithoutNamingFormOrNativeSessionCatalog() {
        val h = host()
        val (button, env) = root(h).first { (n, e) -> n.props["text"]?.invoke(e)?.toString() == "New terminal" }
        button.handlers.getValue("onTap").run(env, emptyList())
        assertEquals("terminal", h.actions.single().first)
        assertEquals(listOf("new", "100.64.0.1", "developer"), args(h.actions.single().second[0].toString()).takeLast(3))
        assertFalse(root(h).any { (n, _) -> n.kind == "Input" })
    }
    @Test fun failedOrEmptyDiscoveryDoesNotOfferStaleResumeRows() {
        val h = host()
        for (value in listOf("""{"ok":false,"err":"SSH unavailable"}""", """{"ok":true,"out":{"ok":false,"host":"100.64.0.1","user":"developer","error":"tmux missing","sessions":[]}}""", """{"ok":true,"out":{"ok":true,"host":"100.64.0.1","user":"developer","sessions":[]}}""")) {
            h.set("work", Json.parse(value))
            assertFalse(text(h).contains("Resume"))
            assertFalse(root(h).any { (n, _) -> n.kind == "Column" && "onTap" in n.handlers })
        }
        assertTrue(text(h).contains("No running terminals"))
    }
    @Test fun machineSelectorUsesTailnetIpAndRefreshesSessionList() {
        val h = host(); h.set("view", Str("machines"))
        assertEquals(2, (h.get("machines") as Lst).items.size)
        val (button, env) = elements(program.screen!!, Env.root(h)).first { (n, e) -> n.kind == "Button" && n.props["text"]?.invoke(e)?.toString() == "Other" }
        button.handlers.getValue("onTap").run(env, emptyList())
        assertEquals(Str("100.64.0.2"), h.get("machine")); assertEquals(Str("Other"), h.get("machineName"))
        assertEquals(Str("settings"), h.get("view")); assertEquals("refresh", h.actions.single().first)
        assertEquals(Str(""), h.get("login"))
    }
    @Test fun shellArgumentsStayDataIncludingDollarSessionIdsAndQuotes() {
        val h = host(); val machine = "host'; printf INJECTED; #"; val user = "user'; printf INJECTED; #"
        h.set("machine", Str(machine)); h.set("sshUser", Str(user))
        val work = h.get("work") as Obj
        val out = work.fields.getValue("out") as Obj
        h.set("work", Obj(work.fields + ("out" to Obj(out.fields + ("host" to Str(machine)) + ("user" to Str(user))))))
        assertEquals(listOf("attach", machine, user, "${'$'}17"), args(tapSession(h)).takeLast(4))
    }
    @Test fun previewRemainsShortAndSessionPollingIsReadOnly() {
        val h = host()
        h.set("work", Json.parse("""{"ok":true,"out":{"ok":true,"host":"100.64.0.1","user":"developer","sessions":[{"id":"${'$'}1","command":"bash","folder":"home","preview":"${"x".repeat(500)}"}]}}"""))
        assertTrue(text(h).contains("x".repeat(150)))
        val source = program.globals.filterIsInstance<Global.Source>().first { it.name == "work" }
        assertEquals(5.0, source.every, 0.0); assertTrue(source.status && source.json)
        assertEquals(listOf("list", "100.64.0.1", "developer"), args(source.arg!!(Env.root(h)).toString()).takeLast(3))
    }
    @Test fun sharedWidgetStillCompilesAfterRoundTrip() {
        val text = Share.minify(File("../app/src/main/assets/widgets/examples/65-tailscale.owl").readText())
        assertEquals(text, Share.minify(Share.decode(Share.link(text))))
        assertEquals("SSH sessions", Compiler.compile(Share.decode(Share.link(text))).name)
    }
    @Test fun machinesAreAccessibleFromTheHomeTile() {
        val h = host()
        val (button, env) = root(h).first { (n, e) -> n.kind == "Button" && n.props["text"]?.invoke(e)?.toString() == "Machines" }
        button.handlers.getValue("onTap").run(env, emptyList())
        assertEquals(Str("machines"), h.get("view"))
        assertEquals(listOf("screen", "refresh"), h.actions.map { it.first })
    }
    @Test fun closeRunsOnlyTheEndActionAndDoesNotResumeTheParentCard() {
        val h = host()
        val (button, env) = root(h).first { (n, e) -> n.kind == "Button" && n.props["text"]?.invoke(e)?.toString() == "×" }
        button.handlers.getValue("onTap").run(env, emptyList())
        assertEquals("run", h.actions.single().first)
        assertEquals(listOf("end", "100.64.0.1", "developer", "${'$'}17"), args(h.actions.single().second[0].toString()).takeLast(4))
    }
    @Test fun switchingMachineOrUserHidesPreviousMachineResumeAndEndButtons() {
        val h = host()
        h.set("machine", Str("100.64.0.2"))
        assertFalse(text(h).contains("Resume")); assertFalse(text(h).contains("×"))
        h.set("machine", Str("100.64.0.1")); h.set("sshUser", Str("another-user"))
        assertFalse(text(h).contains("Resume")); assertFalse(text(h).contains("×"))
        assertTrue(text(h).contains("Loading sessions…"))
    }
    @Test fun settingsKeepUsernamesPerMachineWithoutBreakingTheOriginalHost() {
        val h = host(); h.set("accounts", Json.parse("""[{"host":"100.64.0.1","user":"developer"}]""")); h.set("machine", Str("100.64.0.2")); h.set("view", Str("settings"))
        val (input, env) = elements(program.screen!!, Env.root(h)).first { (n, _) -> n.kind == "Input" && "onSubmit" in n.handlers && n.props["text"]?.invoke(Env.root(h)) == Str("developer") }
        input.handlers.getValue("onSubmit").run(env, listOf(Str("other-login")))
        assertEquals(Str("other-login"), h.get("login"))
        h.set("machine", Str("100.64.0.1"))
        assertEquals(Str("developer"), h.get("login"))
        h.set("machine", Str("100.64.0.2"))
        assertEquals(Str("other-login"), h.get("login"))
    }
    @Test fun machineTabNewTerminalSelectsItsHostAndCreatesATrackedSession() {
        val h = host(); h.set("view", Str("machines"))
        h.set("accounts", Json.parse("""[{"host":"100.64.0.2","user":"other-login"}]"""))
        val rendered = elements(program.screen!!, Env.root(h))
        assertFalse(rendered.any { (n, e) -> n.props["text"]?.invoke(e)?.toString() == "SSH" })
        val (button, env) = rendered.last { (n, e) -> n.kind == "Button" && n.props["text"]?.invoke(e)?.toString() == "New terminal" }
        button.handlers.getValue("onTap").run(env, emptyList())
        assertEquals(Str("100.64.0.2"), h.get("machine"))
        assertEquals(Str("Other"), h.get("machineName"))
        assertEquals(Str("other-login"), h.get("login"))
        assertEquals(Str("sessions"), h.get("view"))
        assertEquals(listOf("terminal", "refresh"), h.actions.map { it.first })
        val command = h.actions.first().second.first().toString()
        assertEquals(listOf("new", "100.64.0.2", "other-login"), args(command).takeLast(3))
        assertFalse(text(h).contains("Resume")) // Previous host's cards stay hidden during refresh.
    }
}
