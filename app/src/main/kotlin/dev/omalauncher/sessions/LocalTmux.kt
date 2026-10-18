package dev.omalauncher.sessions

import android.os.Process
import dev.omalauncher.linux.Proot
import dev.omalauncher.linux.shellQuote

internal object LocalTmux {
    fun serverCommand(proot: Proot): String {
        // proot reports uid 0, but a client from another proot has Android's real
        // SO_PEERCRED uid. Give only this app's uid access to our dedicated server.
        val uid = Process.myUid()
        val passwd = proot.hostFile("/etc/passwd")
        val text = passwd.readText()
        val existing = text.lineSequence().firstOrNull { it.split(':').getOrNull(2) == uid.toString() }?.substringBefore(':')
        val user = existing ?: "omalauncher-$uid".also { name ->
            check(text.lineSequence().none { it.substringBefore(':') == name }) { "Local tmux account name is already in use" }
            passwd.appendText((if (text.endsWith('\n')) "" else "\n") + "$name:x:$uid:$uid:Android tmux client:/root:/bin/bash\n")
        }
        // An initial client on the same proot connection can configure the ACL.
        // Keep its parent shell alive: proot's --kill-on-exit would otherwise kill
        // the daemon and every session when the startup command returned.
        val script = "tmux -L omalauncher -f /dev/null start-server \\; set-option -g exit-empty off \\; server-access -w ${shellQuote(user)} || exit \$?; " +
            "while tmux -L omalauncher show-options -g >/dev/null 2>&1; do sleep 60; done"
        return "sh -c ${shellQuote(script)}"
    }
}
