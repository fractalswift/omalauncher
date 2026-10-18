package dev.omalauncher.widgets.lang

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class TailscaleAuthenticationTest {
    private val program = Compiler.compile(File("../app/src/main/assets/widgets/examples/65-tailscale.owl").readText())
    private val approval = "https://login.tailscale.com/a/ssh/example-check?src=ssh&node=test"

    private class WidgetHost(private val program: Program) : Host {
        private val slots = Array<Value>(program.globals.size) { Null }
        val actions = mutableListOf<Pair<String, List<Value>>>()
        override val budget = Budget(200_000)
        init { program.globals.forEachIndexed { i, g -> if (g is Global.State) slots[i] = g.init(Env.root(this)) } }
        override fun read(slot: Int): Value = (program.globals[slot] as? Global.Let)?.value?.invoke(Env.root(this)) ?: slots[slot]
        override fun write(slot: Int, value: Value) { slots[slot] = value }
        override fun theme(name: String): Int? = null
        override fun time() = 0.0
        override fun act(name: String, args: List<Value>) { actions += name to args }
        override fun runInto(command: String, slot: Int) {}
        fun set(name: String, value: Value) { slots[program.globals.indexOfFirst { it.name == name }] = value }
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

    private fun host(error: String) = WidgetHost(program).apply {
        set("machine", Str("100.64.0.1"))
        set("devices", Obj(mapOf("configured" to Bool(false), "syncing" to Bool(false), "items" to Lst(emptyList()))))
        set("work", Obj(mapOf("ok" to Bool(false), "err" to Str(error))))
    }

    private fun tap(host: WidgetHost, label: String, screen: Boolean = false) {
        val (node, env) = elements(if (screen) program.screen!! else program.root, Env.root(host))
            .first { (node, env) -> node.kind == "Button" && node.props["text"]?.invoke(env) == Str(label) }
        node.handlers.getValue("onTap").run(env, emptyList())
    }

    @Test fun sessionErrorOpensAndCopiesOnlyTheFullApprovalUrl() {
        val error = "Tailscale SSH requires an additional check to authenticate.\nVisit: $approval\nConnection closed."
        val h = host(error)
        tap(h, "Open authentication")
        tap(h, "Copy link")
        tap(h, "Copy error details")
        assertEquals(listOf("url" to listOf(Str(approval)), "copy" to listOf(Str(approval)), "copy" to listOf(Str(error))), h.actions)
    }

    @Test fun discoveryErrorAlsoOffersApprovalActions() {
        val h = host("")
        h.set("view", Str("machines"))
        h.set("tailnet", Obj(mapOf("ok" to Bool(false), "err" to Str("Visit $approval\n"))))
        tap(h, "Copy link", screen = true)
        assertEquals(listOf("copy" to listOf(Str(approval))), h.actions)
    }

    @Test fun ordinaryErrorsCanBeCopiedWithoutOfferingAnAuthenticationLink() {
        for (error in listOf("Permission denied (publickey).", "Visit https://login.tailscale.com.example.org/a/check")) {
            val h = host(error)
            val labels = elements(program.root, Env.root(h)).mapNotNull { (n, e) -> n.props["text"]?.invoke(e)?.toString() }
            assertFalse(labels.contains("Open authentication"))
            assertFalse(labels.contains("Copy link"))
            tap(h, "Copy error details")
            assertEquals(listOf("copy" to listOf(Str(error))), h.actions)
        }
    }

    @Test fun providerErrorWithoutStderrStillShowsFullDetails() {
        val h = host("")
        h.set("work", Obj(mapOf("ok" to Bool(true), "out" to Obj(mapOf(
            "ok" to Bool(false), "host" to Str("100.64.0.1"), "user" to Str(""), "error" to Str("tmux is missing"),
        )))))
        tap(h, "Copy error details")
        assertEquals(listOf("copy" to listOf(Str("tmux is missing"))), h.actions)
    }

    private fun apiHost(error: String? = null) = host("").apply {
        set("discovery", Str("old-user@old-discovery-laptop"))
        set("devices", Obj(mapOf(
            "configured" to Bool(true), "syncing" to Bool(false),
            "error" to (error?.let(::Str) ?: Null),
            "items" to Json.parse("""[{"id":"remote","name":"Server","host":"100.64.0.2","lastSeen":""}]"""),
        )))
    }

    @Test fun apiDiscoveryDoesNotConnectToTheSavedDiscoveryLaptop() {
        val h = apiHost()
        val source = program.globals.filterIsInstance<Global.Source>().first { it.name == "tailnet" }
        val command = source.arg!!(Env.root(h)).toString()
        assertFalse(command.contains("ssh"))
        assertFalse(command.contains("old-discovery-laptop"))
        h.set("view", Str("machines"))
        tap(h, "Server", screen = true)
        val work = program.globals.filterIsInstance<Global.Source>().first { it.name == "work" }
        assertTrue(work.arg!!(Env.root(h)).toString().contains("100.64.0.2"))
        assertFalse(work.arg!!(Env.root(h)).toString().contains("old-discovery-laptop"))
    }

    @Test fun selectingApiDeviceKeepsItsPreviouslySavedSshUsername() {
        val h = apiHost()
        h.set("accounts", Json.parse("""[{"host":"100.64.0.2","user":"server-user"}]"""))
        h.set("view", Str("machines"))
        tap(h, "Server", screen = true)
        val work = program.globals.filterIsInstance<Global.Source>().first { it.name == "work" }
        assertTrue(work.arg!!(Env.root(h)).toString().contains("server-user"))
    }

    @Test fun cachedApiDevicesRemainSelectableAfterARefreshFailure() {
        val h = apiHost("Tailscale denied discovery. Check the API token.")
        h.set("view", Str("machines"))
        tap(h, "Copy error details", screen = true)
        assertEquals("copy", h.actions.single().first)
        tap(h, "Server", screen = true)
        assertEquals("refresh", h.actions.last().first)
    }

    @Test fun refreshRequestsNativeDiscoveryWhileSetupOpensTheEncryptedTokenDialog() {
        val h = apiHost()
        tap(h, "↻")
        assertEquals(listOf("launcher" to listOf(Str("tailscale-sync")), "refresh" to emptyList<Value>()), h.actions)
        val unconfigured = host("")
        unconfigured.set("view", Str("machines"))
        tap(unconfigured, "Set up device discovery", screen = true)
        assertEquals(listOf("launcher" to listOf(Str("tailscale"))), unconfigured.actions)
    }

    @Test fun existingSshDiscoveryRemainsAvailableWhenExplicitlySelected() {
        val h = apiHost()
        h.set("preferApi", Bool(false))
        val source = program.globals.filterIsInstance<Global.Source>().first { it.name == "tailnet" }
        assertTrue(source.arg!!(Env.root(h)).toString().contains("old-user@old-discovery-laptop"))
    }

    @Test fun waitingForNativeConfigurationDoesNotStartAnUnnecessarySshConnection() {
        val h = apiHost()
        h.set("devices", Null)
        val source = program.globals.filterIsInstance<Global.Source>().first { it.name == "tailnet" }
        assertFalse(source.arg!!(Env.root(h)).toString().contains("ssh"))
    }

    @Test fun sshErrorOffersNativeSignInForTheSelectedHostAndUser() {
        val h = host("Tailscale SSH requires an additional check")
        h.set("sshUser", Str("developer"))
        tap(h, "SSH sign-in")
        assertEquals(listOf("sshAuth" to listOf(Str("100.64.0.1"), Str("developer"))), h.actions)
    }

    @Test fun expandedSettingsOfferNativeSignInWithoutSelectingTerminalText() {
        val h = host("")
        h.set("view", Str("settings"))
        tap(h, "SSH sign-in", screen = true)
        assertEquals(listOf("sshAuth" to listOf(Str("100.64.0.1"), Str(""))), h.actions)
    }
}
