package dev.omalauncher.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.omalauncher.Runtime
import dev.omalauncher.sessions.SshAuthentication
import dev.omalauncher.linux.shellQuote

@Composable
fun SshAuthenticationScreen(runtime: Runtime, onDismiss: () -> Unit) {
    val target = runtime.sessions.authenticationTarget
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val check = remember(target) {
        SshAuthentication(scope) {
            checkNotNull(target) { "Choose a remote machine first" }
            require(runtime.widgets.linux) { "Start Linux first" }
            runtime.proot.process(SshAuthentication.command(target)).start()
        }
    }
    LaunchedEffect(check) { if (target != null) check.start() }
    DisposableEffect(check) { onDispose { check.cancel() } }

    fun copy(text: String) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("SSH sign-in", text))
        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
    }

    OverlayPanel("SSH sign-in", onDismiss, contentWidth = ReadingWidth) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BasicText(target?.let { "${it.user.ifBlank { "SSH config user" }}@${it.host}" } ?: "Choose a machine in the SSH sessions widget first.", style = Mono)
            BasicText("This checks SSH access without creating a terminal session. If Tailscale requests approval, use the buttons here. Leave this screen open while approving in your browser, then return.", style = Mono.copy(color = Palette.muted))
            BasicText("For password-based computers such as a Mac, enable automatic SSH once. The app creates a private key on this phone and installs its public key on the selected computer. Enter the computer's password in the terminal prompt; it is not saved. Future previews and terminals use the key automatically, including after app updates and restarts.", style = Mono.copy(color = Palette.muted))
            if (target != null) BracketAction("enable automatic SSH", Palette.accent) {
                check.cancel()
                onDismiss()
                runtime.terminal("bash ~/.config/omalauncher/widgets/examples/oma-ssh-sessions setup ${shellQuote(target.host)} ${shellQuote(target.user)}; printf '\\nPress Enter to close'; read -r _", "SSH setup · ${target.host}")
            }
            BasicText(when {
                check.succeeded -> "SSH sign-in completed. Return to the widget and tap Retry or refresh."
                check.running && check.approvalUrl != null -> "Waiting for Tailscale approval…"
                check.running -> "Connecting…"
                check.error != null -> check.error.orEmpty()
                else -> "Ready to check SSH access."
            }, style = Mono.copy(color = if (check.succeeded) Palette.green else Palette.accent))
            if (check.running) check.approvalUrl?.let { link ->
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BracketAction("open Tailscale approval", Palette.accent) {
                        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }
                        catch (_: android.content.ActivityNotFoundException) { Toast.makeText(context, "No browser available; copy the link instead", Toast.LENGTH_LONG).show() }
                    }
                    BracketAction("copy approval link", Palette.accent) { copy(link) }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (target != null) BracketAction(if (check.running) "cancel check" else "check again", Palette.accent) {
                    if (check.running) check.cancel() else check.start()
                }
                if (check.output.isNotEmpty()) BracketAction("copy connection details", Palette.muted) { copy(check.output) }
                BracketAction("back to widget", Palette.accent, onDismiss)
            }
            if (check.output.isNotEmpty()) BasicText(check.output, style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
            BasicText("The Tailscale API key lists devices; SSH approval is separate. First-time host-key verification or password setup may still need a normal terminal connection.", style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
        }
    }
}
