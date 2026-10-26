package org.openstick.ui

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.breadstick.x11.LorieView
import io.breadstick.x11.X11Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.openstick.OpenStickApplication
import org.openstick.engine.LinuxEnvironment
import org.openstick.engine.ProotRunner
import org.openstick.terminal.InteractiveTerminalPane
import org.openstick.terminal.TerminalSessionManager
import org.openstick.ui.theme.AmoledBackground
import org.openstick.ui.theme.AmoledBorder
import org.openstick.ui.theme.AmoledContainer
import org.openstick.ui.theme.OpenStickTheme

class WindowActivity : ComponentActivity() {

    companion object {
        const val EXTRA_MODE = "org.openstick.window.MODE"
        const val EXTRA_CMD = "org.openstick.window.CMD"
        const val EXTRA_TITLE = "org.openstick.window.TITLE"

        const val MODE_DESKTOP = "DESKTOP"
        const val MODE_TERMINAL = "TERMINAL"
        const val MODE_MONITOR = "MONITOR"
        const val MODE_APP = "APP"

        fun launch(context: Context, mode: String = MODE_TERMINAL, cmd: String? = null, title: String? = null) {
            val intent = Intent(context, WindowActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                putExtra(EXTRA_MODE, mode)
                if (cmd != null) putExtra(EXTRA_CMD, cmd)
                if (title != null) putExtra(EXTRA_TITLE, title)
            }
            val bounds = WindowPlacement.calculateDefaultBounds(context)
            val options = WindowPlacement.makeOptionsBundle(context, bounds)
            context.startActivity(intent, options)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setVolumeControlStream(AudioManager.STREAM_MUSIC)

        val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_TERMINAL
        val initialCmd = intent.getStringExtra(EXTRA_CMD)
        val initialTitle = intent.getStringExtra(EXTRA_TITLE) ?: when (mode) {
            MODE_TERMINAL -> "Terminal Window"
            MODE_MONITOR -> "Resource Monitor"
            MODE_APP -> initialCmd ?: "App Window"
            else -> "Linux Desktop"
        }

        val app = OpenStickApplication.instance
        val env = app.env
        val x11Server = app.x11Server
        val runner = app.runner

        setContent {
            OpenStickTheme {
                WindowContent(
                    mode = mode,
                    title = initialTitle,
                    cmd = initialCmd,
                    env = env,
                    x11Server = x11Server,
                    runner = runner,
                    onClose = { finish() }
                )
            }
        }
    }
}

@Composable
fun WindowContent(
    mode: String,
    title: String,
    cmd: String?,
    env: LinuxEnvironment,
    x11Server: X11Server,
    runner: ProotRunner,
    onClose: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var isKeyboardOpen by remember { mutableStateOf(false) }
    var ctrlHeld by remember { mutableStateOf(false) }
    var altHeld by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    var inputFieldValue by remember { mutableStateOf(TextFieldValue("")) }
    var lorieRef by remember { mutableStateOf<LorieView?>(null) }
    var statusText by remember { mutableStateOf("Initializing...") }

    // Auto-start display and session for Desktop / App mode
    LaunchedEffect(Unit) {
        if (mode == WindowActivity.MODE_DESKTOP || mode == WindowActivity.MODE_APP) {
            statusText = "Starting graphics engine..."
            x11Server.start { success ->
                if (success) {
                    statusText = "Display ready"
                    val launchCmd = if (mode == WindowActivity.MODE_APP && !cmd.isNullOrBlank()) {
                        "export DISPLAY=:0; export PULSE_SERVER=tcp:127.0.0.1:4713; $cmd"
                    } else {
                        env.startDesktopCommand(0, "root")
                    }
                    scope.launch(Dispatchers.IO) {
                        try {
                            runner.start(listOf("/bin/sh", "-c", launchCmd), asRoot = true)
                        } catch (e: Exception) {
                            android.util.Log.e("WindowActivity", "Failed to start window command", e)
                        }
                    }
                } else {
                    statusText = "X11 engine standby"
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AmoledBackground)
            .imePadding()
    ) {
        // Native Window Header Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(AmoledContainer)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = when (mode) {
                    WindowActivity.MODE_TERMINAL -> Icons.Default.Terminal
                    WindowActivity.MODE_MONITOR -> Icons.Default.Speed
                    WindowActivity.MODE_APP -> Icons.Default.OpenInNew
                    else -> Icons.Default.DesktopWindows
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )

            Spacer(modifier = Modifier.width(6.dp))

            Text(
                text = title,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )

            if (mode != WindowActivity.MODE_MONITOR) {
                // Keyboard Toggle
                IconButton(
                    onClick = {
                        isKeyboardOpen = !isKeyboardOpen
                        if (isKeyboardOpen) {
                            focusRequester.requestFocus()
                            keyboardController?.show()
                        } else {
                            keyboardController?.hide()
                        }
                    },
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        Icons.Default.Keyboard,
                        contentDescription = "Toggle Keyboard",
                        tint = if (isKeyboardOpen) MaterialTheme.colorScheme.primary else Color.LightGray,
                        modifier = Modifier.size(16.dp)
                    )
                }

                // Reload / Restart
                IconButton(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            runner.kill()
                            val launchCmd = if (mode == WindowActivity.MODE_APP && !cmd.isNullOrBlank()) {
                                "export DISPLAY=:0; export PULSE_SERVER=tcp:127.0.0.1:4713; $cmd"
                            } else {
                                env.startDesktopCommand(0, "root")
                            }
                            runner.start(listOf("/bin/sh", "-c", launchCmd), asRoot = true)
                        }
                    },
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Restart Desktop",
                        tint = Color.LightGray,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // Close Window
            IconButton(
                onClick = onClose,
                modifier = Modifier.size(30.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Close",
                    tint = Color(0xFFFF5252),
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        // Invisible soft-keyboard input listener
        BasicTextField(
            value = inputFieldValue,
            onValueChange = { newValue ->
                val oldText = inputFieldValue.text
                val newText = newValue.text
                val delta = if (newText.length > oldText.length && newText.startsWith(oldText)) {
                    newText.substring(oldText.length)
                } else if (newText.length < oldText.length) {
                    val deletedCount = oldText.length - newText.length
                    repeat(deletedCount) {
                        lorieRef?.sendKeyEvent(14, 67, true, 0)
                        lorieRef?.sendKeyEvent(14, 67, false, 0)
                    }
                    ""
                } else {
                    newText
                }

                if (delta.isNotEmpty()) {
                    if (delta == "\n" || delta == "\r\n") {
                        lorieRef?.sendKeyEvent(28, 66, true, 0)
                        lorieRef?.sendKeyEvent(28, 66, false, 0)
                    } else {
                        lorieRef?.sendTextEvent(delta.toByteArray(Charsets.UTF_8))
                    }
                }
                inputFieldValue = newValue.copy(selection = androidx.compose.ui.text.TextRange(newText.length))
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.None,
                autoCorrectEnabled = false
            ),
            modifier = Modifier
                .size(1.dp)
                .alpha(0.01f)
                .focusRequester(focusRequester),
            cursorBrush = SolidColor(Color.Transparent)
        )

        // Window Viewport Area
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black)
        ) {
            when (mode) {
                WindowActivity.MODE_TERMINAL -> {
                    val termManager = remember { TerminalSessionManager(env, scope) }
                    termManager.activeTab?.let { tab ->
                        InteractiveTerminalPane(
                            tab = tab,
                            fontSize = termManager.fontSize,
                            focusRequester = focusRequester,
                            ctrlActive = ctrlHeld,
                            altActive = altHeld,
                            onConsumeCtrl = { ctrlHeld = false },
                            onConsumeAlt = { altHeld = false }
                        )
                    }
                }
                WindowActivity.MODE_MONITOR -> {
                    val context = androidx.compose.ui.platform.LocalContext.current
                    val monitor = remember { org.openstick.engine.ResourceMonitor(context) }
                    org.openstick.monitor.MonitorScreen(monitor = monitor)
                }
                else -> {
                    // DESKTOP & APP mode: Native LorieView Surface
                    var twoFingerDownTime by remember { mutableLongStateOf(0L) }
                    var isTwoFingerScrolling by remember { mutableStateOf(false) }
                    var lastTwoFingerY by remember { mutableFloatStateOf(0f) }

                    AndroidView(
                        factory = { context ->
                            LorieView(context).apply {
                                lorieRef = this
                                try {
                                    val fd = x11Server.openRenderFd()
                                    if (fd >= 0) {
                                        connectAsync(fd)
                                    }
                                } catch (e: Throwable) {
                                    android.util.Log.e("WindowActivity", "Failed to connect LorieView to X11", e)
                                }

                                setOnTouchListener { _, event ->
                                    val pointerCount = event.pointerCount
                                    when (event.actionMasked) {
                                        MotionEvent.ACTION_POINTER_DOWN -> {
                                            if (pointerCount == 2) {
                                                twoFingerDownTime = System.currentTimeMillis()
                                                isTwoFingerScrolling = false
                                                lastTwoFingerY = (event.getY(0) + event.getY(1)) / 2f
                                            }
                                        }
                                        MotionEvent.ACTION_POINTER_UP -> {
                                            if (pointerCount == 2 && !isTwoFingerScrolling) {
                                                val duration = System.currentTimeMillis() - twoFingerDownTime
                                                if (duration < 350) {
                                                    // 2-finger tap -> Right Click!
                                                    val tapX = (event.getX(0) + event.getX(1)) / 2f
                                                    val tapY = (event.getY(0) + event.getY(1)) / 2f
                                                    sendMouseEvent(tapX, tapY, 3, true, false)
                                                    sendMouseEvent(tapX, tapY, 3, false, false)
                                                    return@setOnTouchListener true
                                                }
                                            }
                                        }
                                        MotionEvent.ACTION_MOVE -> {
                                            if (pointerCount == 2) {
                                                val currentY = (event.getY(0) + event.getY(1)) / 2f
                                                val deltaY = currentY - lastTwoFingerY
                                                if (kotlin.math.abs(deltaY) > 15) {
                                                    isTwoFingerScrolling = true
                                                    val button = if (deltaY > 0) 4 else 5
                                                    sendMouseEvent(event.x, event.y, button, true, false)
                                                    sendMouseEvent(event.x, event.y, button, false, false)
                                                    lastTwoFingerY = currentY
                                                }
                                                return@setOnTouchListener true
                                            }
                                            sendMouseEvent(event.x, event.y, 0, false, false)
                                        }
                                        MotionEvent.ACTION_DOWN -> {
                                            sendMouseEvent(event.x, event.y, 1, true, false)
                                        }
                                        MotionEvent.ACTION_UP -> {
                                            sendMouseEvent(event.x, event.y, 1, false, false)
                                        }
                                    }
                                    true
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        if (mode != WindowActivity.MODE_MONITOR) {
            // Quick Shortcut Strip (Pinned directly above the soft keyboard)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AmoledContainer)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 4.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { ctrlHeld = !ctrlHeld },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (ctrlHeld) MaterialTheme.colorScheme.primary else Color(0xFF2B2B2B)
                    ),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text("CTRL", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }

                Button(
                    onClick = { altHeld = !altHeld },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (altHeld) MaterialTheme.colorScheme.primary else Color(0xFF2B2B2B)
                    ),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text("ALT", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }

                SmallKey("ESC") { lorieRef?.sendKeyEvent(1, 111, true, 0); lorieRef?.sendKeyEvent(1, 111, false, 0) }
                SmallKey("TAB") { lorieRef?.sendKeyEvent(15, 61, true, 0); lorieRef?.sendKeyEvent(15, 61, false, 0) }
                SmallKey("↑") { lorieRef?.sendKeyEvent(103, 19, true, 0); lorieRef?.sendKeyEvent(103, 19, false, 0) }
                SmallKey("↓") { lorieRef?.sendKeyEvent(108, 20, true, 0); lorieRef?.sendKeyEvent(108, 20, false, 0) }
                SmallKey("←") { lorieRef?.sendKeyEvent(105, 21, true, 0); lorieRef?.sendKeyEvent(105, 21, false, 0) }
                SmallKey("→") { lorieRef?.sendKeyEvent(106, 22, true, 0); lorieRef?.sendKeyEvent(106, 22, false, 0) }
                SmallKey("Enter") { lorieRef?.sendKeyEvent(28, 66, true, 0); lorieRef?.sendKeyEvent(28, 66, false, 0) }
            }
        }
    }
}

@Composable
private fun SmallKey(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
        shape = RoundedCornerShape(4.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF222222), contentColor = Color.White),
        modifier = Modifier.height(28.dp)
    ) {
        Text(label, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}
