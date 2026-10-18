package dev.omalauncher.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.ViewTreeObserver
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.omalauncher.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.omalauncher.wayland.Keys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One row's height, keycap and the gap around it; the bar is two rows, like Termux's default. */
internal val KeyRowHeight = 40.dp
internal val KeyGap = 3.dp
internal val FrameWidth = 2.dp

/** The gap between the bar and the keyboard, and to the screen's sides: the tiles' gap. */
private val Gap = 6.dp

/** The bar's full height, gap included; the home screen lifts a typing tile by this much more. */
val ExtraKeysHeight = KeyRowHeight * 2 + (FrameWidth + KeyGap) * 2 + Gap

private sealed interface ExtraKey

private data object Dictation : ExtraKey

private data object Screenshot : ExtraKey

/** A key that sends [code], with [held] down around it; drawn as [label], or as a chevron turned [arrow] degrees from up. */
private data class Plain(
    val label: String,
    val code: Int,
    val repeats: Boolean = false,
    val arrow: Float? = null,
    val held: Int? = null,
) : ExtraKey

private data class Sticky(
    val label: String,
    val modifier: (KeyboardBridge) -> StickyModifier,
) : ExtraKey

private val Layout: List<List<ExtraKey>> =
    listOf(
        listOf(
            Plain("esc", Keys.KEY_ESC),
            Plain("/", Keys.KEY_SLASH),
            Plain("-", Keys.KEY_MINUS),
            Plain("home", Keys.KEY_HOME),
            Plain("up", Keys.KEY_UP, repeats = true, arrow = 0f),
            Plain("end", Keys.KEY_END),
            Plain("pgup", Keys.KEY_PAGEUP, repeats = true),
            // Ctrl+Insert and Shift+Insert: the clipboard keys foot, GTK and Qt all know.
            Plain("copy", Keys.KEY_INSERT, held = Keys.KEY_LEFTCTRL),
            Dictation,
        ),
        listOf(
            Plain("tab", Keys.KEY_TAB),
            Sticky("ctrl") { it.ctrl },
            Sticky("alt") { it.alt },
            Plain("left", Keys.KEY_LEFT, repeats = true, arrow = -90f),
            Plain("down", Keys.KEY_DOWN, repeats = true, arrow = 180f),
            Plain("right", Keys.KEY_RIGHT, repeats = true, arrow = 90f),
            Plain("pgdn", Keys.KEY_PAGEDOWN, repeats = true),
            Plain("paste", Keys.KEY_INSERT, held = Keys.KEY_LEFTSHIFT),
            Screenshot,
        ),
    )

/**
 * Whether the extra-keys row belongs on screen: only over a docked soft
 * keyboard. With a hardware keyboard, Gboard shows just a floating toolbar
 * that counts as visible but takes no height, and the hardware keyboard has
 * these keys already.
 */
@Composable
fun extraKeysWanted(): Boolean {
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    // Derived, so the keyboard's slide-in animation doesn't recompose every frame.
    val docked by remember(ime, density) { derivedStateOf { ime.getBottom(density) > 0 } }
    return docked
}

/**
 * The keys a phone keyboard lacks, docked above it for terminals: a framed
 * panel of keycaps, like the tiles above it. Keys never take focus, so the
 * soft keyboard stays up; ctrl and alt latch for the next key on a tap and
 * lock on a long press.
 */
@Composable
fun ExtraKeys(
    bridge: KeyboardBridge,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(start = Gap, end = Gap, bottom = Gap)
            .frame(FrameWidth, Palette.border, Palette.background, radius = 12.dp)
            .padding(KeyGap),
    ) {
        for (row in Layout) {
            Row(Modifier.fillMaxWidth().height(KeyRowHeight)) {
                for (key in row) {
                    val cap = Modifier.weight(1f).fillMaxHeight().padding(KeyGap)
                    when (key) {
                        is Plain -> PlainKey(key, bridge, cap)
                        is Sticky -> StickyKey(key, key.modifier(bridge), cap)
                        Dictation -> DictationKey(bridge, cap)
                        Screenshot -> ScreenshotKey(bridge, cap)
                    }
                }
            }
        }
    }
}

/** Separate from Paste: imports the latest screenshot without changing the user's clipboard. */
@Composable
private fun ScreenshotKey(bridge: KeyboardBridge, modifier: Modifier) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf<dev.omalauncher.wayland.XdgToplevel?>(null) }
    val imagePermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

    fun attach() {
        val destination = target ?: return
        scope.launch {
            busy = true
            try {
                val runtime = (context.applicationContext as App).runtime
                val path = withContext(Dispatchers.IO) {
                    Screenshots.latest(context, runtime.proot.hostFile(Screenshots.GUEST_DIR))
                }
                if (view.hasWindowFocus() && bridge.dictationTarget === destination) {
                    bridge.attachImage(path, destination)
                } else {
                    Toast.makeText(context, "Window changed; tap the image button again", Toast.LENGTH_SHORT).show()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(context, e.message ?: "Could not attach screenshot", Toast.LENGTH_LONG).show()
            } finally {
                busy = false
            }
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        busy = false
        val limited = Build.VERSION.SDK_INT >= 34 && grants[Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED] == true
        if (grants[imagePermission] == true || limited) {
            // The permission dialog can hide the IME and dispose this bar. A fresh tap
            // captures the current destination instead of using a stale coroutine/window.
            val message = if (limited && grants[imagePermission] != true) {
                "Access granted to selected photos. Reopen the keyboard and tap the image button."
            } else {
                "Photo access granted. Reopen the keyboard and tap the image button."
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(context, "Photo access is needed to find your latest screenshot", Toast.LENGTH_LONG).show()
        }
    }

    KeyCap(
        modifier
            .semantics { contentDescription = "Attach latest screenshot" }
            .clickable(enabled = !busy) {
                target = bridge.dictationTarget
                if (target != null) {
                    val canReadSelected = Build.VERSION.SDK_INT >= 34 &&
                        context.checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
                    if (context.checkSelfPermission(imagePermission) == PackageManager.PERMISSION_GRANTED || canReadSelected) {
                        attach()
                    } else {
                        busy = true
                        permission.launch(
                            if (Build.VERSION.SDK_INT >= 34) arrayOf(imagePermission, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                            else arrayOf(imagePermission),
                        )
                    }
                }
            },
        fill = if (busy) Palette.accent else Palette.surface,
    ) {
        Image(Icons.Image, null, Modifier.size(20.dp), colorFilter = ColorFilter.tint(if (busy) Palette.background else Palette.foreground))
    }
}

/** Android records speech; only the completed transcript crosses into Linux. */
@Composable
private fun DictationKey(bridge: KeyboardBridge, modifier: Modifier) {
    val context = LocalContext.current
    val view = LocalView.current
    val speech = remember { Speech(context.applicationContext) }
    var target by remember { mutableStateOf<dev.omalauncher.wayland.XdgToplevel?>(null) }

    fun start() {
        val destination = target ?: return
        if (bridge.dictationTarget !== destination) return
        speech.start(hold = true) { text ->
            if (text.isNotBlank()) bridge.dictate(text, destination)
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) start()
        else Toast.makeText(context, "Microphone permission needed for dictation", Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(bridge.dictationTarget) {
        if (bridge.dictationTarget !== target) speech.cancel()
    }
    LaunchedEffect(speech.error) {
        speech.error?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            speech.error = null
        }
    }
    LaunchedEffect(speech.processing) {
        if (speech.processing) Toast.makeText(context, "Transcribing with Groq… Tap the microphone to cancel.", Toast.LENGTH_SHORT).show()
    }
    DisposableEffect(speech, view) {
        val observer = view.viewTreeObserver
        val listener = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
            if (!focused) speech.cancel()
        }
        observer.addOnWindowFocusChangeListener(listener)
        onDispose {
            if (observer.isAlive) observer.removeOnWindowFocusChangeListener(listener)
            speech.cancel()
        }
    }

    KeyCap(
        modifier
            .semantics { contentDescription = when { speech.processing -> "Cancel transcription"; speech.listening -> "Stop dictation"; else -> "Start dictation" } }
            .clickable {
                if (speech.processing) {
                    speech.cancel()
                } else if (speech.listening) {
                    speech.stop()
                } else {
                    target = bridge.dictationTarget
                    if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        start()
                    } else {
                        permission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            },
        fill = if (speech.listening || speech.processing) Palette.accent else Palette.surface,
    ) {
        MicIcon(if (speech.listening || speech.processing) Palette.background else Palette.foreground)
    }
}

@Composable
private fun PlainKey(
    key: Plain,
    bridge: KeyboardBridge,
    modifier: Modifier,
) {
    val view = LocalView.current
    var pressed by remember { mutableStateOf(false) }
    KeyCap(
        modifier.pointerInput(key) {
            presses { scope ->
                pressed = true
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                val press =
                    scope.launch {
                        bridge.tap(key.code, key.held)
                        if (!key.repeats) return@launch
                        delay(RepeatDelay)
                        while (true) {
                            bridge.tap(key.code, key.held)
                            delay(RepeatInterval)
                        }
                    }
                waitForUpOrCancellation()
                press.cancel()
                pressed = false
            }
        },
        fill = if (pressed) Palette.accent else Palette.surface,
    ) {
        val color = if (pressed) Palette.background else Palette.foreground
        if (key.arrow != null) {
            Image(Icons.Chevron, key.label, Modifier.size(20.dp).rotate(key.arrow), colorFilter = ColorFilter.tint(color))
        } else {
            BasicText(key.label, style = Mono.copy(color = color))
        }
    }
}

@Composable
private fun StickyKey(
    key: Sticky,
    sticky: StickyModifier,
    modifier: Modifier,
) {
    val view = LocalView.current
    val latch = sticky.latch
    KeyCap(
        modifier.pointerInput(sticky) {
            presses { scope ->
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                var locked = false
                val hold =
                    scope.launch {
                        delay(RepeatDelay)
                        sticky.latch = Latch.Locked
                        locked = true
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    }
                val up = waitForUpOrCancellation() != null
                hold.cancel()
                if (up && !locked) sticky.latch = if (sticky.latch == Latch.Off) Latch.Once else Latch.Off
            }
        },
        // Once: outlined in the accent, for the next key. Locked: filled, until tapped off.
        fill = if (latch == Latch.Locked) Palette.accent else Palette.surface,
        outline = if (latch == Latch.Once) Palette.accent else null,
    ) {
        BasicText(
            key.label,
            style =
                Mono.copy(
                    color =
                        when (latch) {
                            Latch.Off -> Palette.foreground
                            Latch.Once -> Palette.accent
                            Latch.Locked -> Palette.background
                        },
                ),
        )
    }
}

@Composable
internal fun KeyCap(
    modifier: Modifier,
    fill: Color,
    outline: Color? = null,
    content: @Composable () -> Unit,
) {
    val shape = LauncherStyle.shape(8.dp)
    Box(
        modifier
            .clip(shape)
            .background(fill, shape)
            .then(if (outline != null) Modifier.border(FrameWidth, outline, shape) else Modifier),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Runs [press] for each touch on the key, with a scope to start key repeat or long-press timers in. */
internal suspend fun PointerInputScope.presses(press: suspend AwaitPointerEventScope.(CoroutineScope) -> Unit) =
    coroutineScope {
        val scope = this
        awaitEachGesture {
            awaitFirstDown()
            press(scope)
        }
    }

internal const val RepeatDelay = 400L
internal const val RepeatInterval = 50L
