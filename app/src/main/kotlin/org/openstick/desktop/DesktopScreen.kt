package org.openstick.desktop

import android.content.Context
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import java.io.File
import org.openstick.terminal.TerminalTab
import org.openstick.terminal.InteractiveTerminalPane
import io.breadstick.x11.LorieView
import io.breadstick.x11.X11Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.openstick.engine.GpuServer
import org.openstick.engine.HardwareBridges
import org.openstick.engine.LinuxEnvironment
import org.openstick.engine.ProcessInfo
import org.openstick.engine.ProotRunner
import org.openstick.engine.ResourceMonitor
import org.openstick.ui.theme.AmoledBackground
import org.openstick.ui.theme.AmoledContainer
import org.openstick.ui.theme.AmoledContainerHigh
import kotlin.math.roundToInt

enum class DesktopDisplayMode {
    FULL_DESKTOP,
    WINDOWED_MODE
}

enum class MiniWindowType {
    TERMINAL,
    MONITOR,
    UPDATER,
    CAMERA,
    X11
}

class MiniWindowState(
    val id: String,
    val type: MiniWindowType,
    val title: String,
    val icon: ImageVector,
    offsetX: Float,
    offsetY: Float,
    widthDp: Float = 320f,
    heightDp: Float = 360f,
    zIndex: Float = 1f
) {
    var offsetX by mutableFloatStateOf(offsetX)
    var offsetY by mutableFloatStateOf(offsetY)
    var widthDp by mutableFloatStateOf(widthDp)
    var heightDp by mutableFloatStateOf(heightDp)
    var isMinimized by mutableStateOf(false)
    var isMaximized by mutableStateOf(false)
    var zIndex by mutableFloatStateOf(zIndex)
}

@Composable
fun DesktopScreen(
    env: LinuxEnvironment,
    x11Server: X11Server,
    runner: ProotRunner,
    initialWindowed: Boolean = false,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var isX11Running by remember { mutableStateOf(X11Server.isServerRunning) }
    var isGpuRunning by remember { mutableStateOf(GpuServer.isRunning) }
    var displayMode by remember { mutableStateOf(if (initialWindowed) DesktopDisplayMode.WINDOWED_MODE else DesktopDisplayMode.FULL_DESKTOP) }
    var desktopStatus by remember { mutableStateOf("Desktop ready") }
    var showSurface by remember { mutableStateOf(false) }

    // Desktop View & Input State
    var lorieViewRef by remember { mutableStateOf<LorieView?>(null) }
    var isKeyboardVisible by remember { mutableStateOf(false) }
    var ctrlHeld by remember { mutableStateOf(false) }
    var altHeld by remember { mutableStateOf(false) }
    var lastTapX by remember { mutableFloatStateOf(500f) }
    var lastTapY by remember { mutableFloatStateOf(400f) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    var inputFieldValue by remember { mutableStateOf(TextFieldValue("")) }

    // Windowed Mini Floating Windows list
    var nextZIndex by remember { mutableFloatStateOf(2f) }
    val miniWindows = remember {
        mutableStateListOf(
            MiniWindowState(
                id = "term-1",
                type = MiniWindowType.TERMINAL,
                title = "Terminal Shell",
                icon = Icons.Default.Terminal,
                offsetX = 20f,
                offsetY = 10f,
                widthDp = 330f,
                heightDp = 360f,
                zIndex = 1f
            )
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AmoledBackground)
            .padding(8.dp)
    ) {
        // Controls Header Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = AmoledContainer),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "X11 Graphical Subsystem",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "Status: $desktopStatus",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Badge(
                        containerColor = if (isX11Running) Color(0xFF1B5E20) else Color(0xFF424242)
                    ) {
                        Text(
                            text = if (isX11Running) "Display :0 Ready" else "Standby",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Mode Selector Chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = displayMode == DesktopDisplayMode.FULL_DESKTOP,
                        onClick = { displayMode = DesktopDisplayMode.FULL_DESKTOP },
                        label = { Text("Full Desktop", fontSize = 11.sp) },
                        leadingIcon = { Icon(Icons.Default.DesktopWindows, contentDescription = null, modifier = Modifier.size(14.dp)) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = displayMode == DesktopDisplayMode.WINDOWED_MODE,
                        onClick = { displayMode = DesktopDisplayMode.WINDOWED_MODE },
                        label = { Text("Windowed Floating", fontSize = 11.sp) },
                        leadingIcon = { Icon(Icons.Default.PictureInPicture, contentDescription = null, modifier = Modifier.size(14.dp)) },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            if (!isX11Running) {
                                if (!env.isInstalled()) {
                                    desktopStatus = "Linux system not installed. Complete setup on Home tab first."
                                    return@Button
                                }
                                desktopStatus = "Starting X11 server..."
                                x11Server.start { success ->
                                    isX11Running = success
                                    if (success) {
                                        showSurface = true
                                        desktopStatus = "X11 :0 Ready • Launching Desktop..."
                                        scope.launch {
                                            runner.start(
                                                command = listOf(
                                                    "/bin/sh",
                                                    "-c",
                                                    "DISPLAY=:0 startxfce4 --disable-tcp 2>&1 || ((sleep 1; DISPLAY=:0 xfce4-terminal 2>/dev/null || DISPLAY=:0 xterm 2>/dev/null &); DISPLAY=:0 exec openbox)"
                                                ),
                                                onOutput = { desktopStatus = "Desktop session active" }
                                            )
                                        }
                                    } else {
                                        desktopStatus = "Failed to start X11 server"
                                    }
                                }
                            } else {
                                runner.kill()
                                x11Server.stop()
                                isX11Running = false
                                showSurface = false
                                desktopStatus = "X11 server standby"
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isX11Running) Color(0xFFB71C1C) else MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 4.dp)
                    ) {
                        Icon(
                            if (isX11Running) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isX11Running) "Stop Display" else "Start Display", fontSize = 12.sp)
                    }

                    OutlinedButton(
                        onClick = {
                            if (!isGpuRunning) {
                                val ok = GpuServer.start(env)
                                isGpuRunning = ok
                                desktopStatus = if (ok) "VirGL 3D active (virpipe)" else "VirGL failed"
                            } else {
                                GpuServer.stop()
                                isGpuRunning = false
                                desktopStatus = "GPU acceleration off"
                            }
                        },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = if (isGpuRunning) Color(0xFF00FF66) else MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 4.dp)
                    ) {
                        Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isGpuRunning) "3D GPU: ON" else "3D GPU: OFF", fontSize = 12.sp)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        if (displayMode == DesktopDisplayMode.WINDOWED_MODE) {
            // Windowed Mini Floating Workspace Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ActionChip(
                    icon = Icons.Default.Add,
                    label = "Terminal",
                    onClick = {
                        miniWindows.add(
                            MiniWindowState(
                                id = "term-${System.currentTimeMillis()}",
                                type = MiniWindowType.TERMINAL,
                                title = "Terminal Shell",
                                icon = Icons.Default.Terminal,
                                offsetX = (20 + (miniWindows.size * 25) % 80).toFloat(),
                                offsetY = (20 + (miniWindows.size * 25) % 120).toFloat(),
                                zIndex = nextZIndex++
                            )
                        )
                    }
                )

                ActionChip(
                    icon = Icons.Default.Add,
                    label = "Monitor",
                    onClick = {
                        miniWindows.add(
                            MiniWindowState(
                                id = "mon-${System.currentTimeMillis()}",
                                type = MiniWindowType.MONITOR,
                                title = "Process Monitor",
                                icon = Icons.Default.Speed,
                                offsetX = (30 + (miniWindows.size * 20) % 90).toFloat(),
                                offsetY = (40 + (miniWindows.size * 20) % 130).toFloat(),
                                zIndex = nextZIndex++
                            )
                        )
                    }
                )

                ActionChip(
                    icon = Icons.Default.Add,
                    label = "Updater",
                    onClick = {
                        miniWindows.add(
                            MiniWindowState(
                                id = "upd-${System.currentTimeMillis()}",
                                type = MiniWindowType.UPDATER,
                                title = "Package Tool",
                                icon = Icons.Default.SystemUpdate,
                                offsetX = (40 + (miniWindows.size * 20) % 100).toFloat(),
                                offsetY = (60 + (miniWindows.size * 20) % 140).toFloat(),
                                zIndex = nextZIndex++
                            )
                        )
                    }
                )

                ActionChip(
                    icon = Icons.Default.Add,
                    label = "Camera",
                    onClick = {
                        miniWindows.add(
                            MiniWindowState(
                                id = "cam-${System.currentTimeMillis()}",
                                type = MiniWindowType.CAMERA,
                                title = "Camera Bridge",
                                icon = Icons.Default.CameraAlt,
                                offsetX = (50 + (miniWindows.size * 20) % 100).toFloat(),
                                offsetY = (70 + (miniWindows.size * 20) % 140).toFloat(),
                                zIndex = nextZIndex++
                            )
                        )
                    }
                )

                ActionChip(
                    icon = Icons.Default.Add,
                    label = "X11 Viewport",
                    onClick = {
                        if (!isX11Running) {
                            x11Server.start { isX11Running = it }
                        }
                        miniWindows.add(
                            MiniWindowState(
                                id = "x11-${System.currentTimeMillis()}",
                                type = MiniWindowType.X11,
                                title = "X11 Canvas",
                                icon = Icons.Default.DesktopWindows,
                                offsetX = 20f,
                                offsetY = 20f,
                                widthDp = 340f,
                                heightDp = 380f,
                                zIndex = nextZIndex++
                            )
                        )
                    }
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Multi-window Floating Desktop Canvas
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black)
                    .border(1.dp, AmoledContainerHigh, RoundedCornerShape(12.dp))
            ) {
                val canvasWidth = maxWidth
                val canvasHeight = maxHeight

                // Render each active floating mini window
                miniWindows.forEach { win ->
                    if (!win.isMinimized) {
                        key(win.id) {
                            MiniFloatingWindow(
                                windowState = win,
                                env = env,
                                x11Server = x11Server,
                                runner = runner,
                                canvasWidthDp = canvasWidth.value,
                                canvasHeightDp = canvasHeight.value,
                                onFocus = {
                                    win.zIndex = nextZIndex++
                                },
                                onMinimize = {
                                    win.isMinimized = true
                                },
                                onToggleMaximize = {
                                    win.isMaximized = !win.isMaximized
                                    win.zIndex = nextZIndex++
                                },
                                onClose = {
                                    miniWindows.remove(win)
                                }
                            )
                        }
                    }
                }

                // Minimized Windows Taskbar Dock at bottom of canvas
                val minimizedList = miniWindows.filter { it.isMinimized }
                if (minimizedList.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(8.dp)
                            .background(AmoledContainer.copy(alpha = 0.9f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Dock:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        minimizedList.forEach { win ->
                            AssistChip(
                                onClick = {
                                    win.isMinimized = false
                                    win.zIndex = nextZIndex++
                                },
                                label = { Text(win.title, fontSize = 11.sp) },
                                leadingIcon = { Icon(win.icon, contentDescription = null, modifier = Modifier.size(14.dp)) },
                                modifier = Modifier.height(28.dp)
                            )
                        }
                    }
                }
            }
        } else {
            // FULL DESKTOP MODE
            // Quick Launch Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilledTonalButton(
                    onClick = {
                        scope.launch {
                            try {
                                File(env.tmpDir, ".X0-lock").delete()
                                File(env.tmpDir, ".X11-unix/X0").delete()
                                File(env.rootfsDir, "tmp/.X0-lock").delete()
                                File(env.rootfsDir, "tmp/.X11-unix/X0").delete()
                            } catch (_: Exception) {}

                            if (!isX11Running) x11Server.start { isX11Running = it }
                            showSurface = true
                            desktopStatus = "Starting XFCE4 Desktop..."
                            runner.kill()
                            runner.start(
                                command = listOf(
                                    "/bin/sh",
                                    "-c",
                                    "export DISPLAY=:0; eval $(dbus-launch --sh-syntax 2>/dev/null); exec startxfce4 --disable-tcp 2>&1"
                                ),
                                onOutput = { desktopStatus = "XFCE session active" }
                            )
                        }
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                ) {
                    Text("XFCE4", fontSize = 12.sp)
                }

                FilledTonalButton(
                    onClick = {
                        scope.launch {
                            try {
                                File(env.tmpDir, ".X0-lock").delete()
                                File(env.tmpDir, ".X11-unix/X0").delete()
                                File(env.rootfsDir, "tmp/.X0-lock").delete()
                                File(env.rootfsDir, "tmp/.X11-unix/X0").delete()
                            } catch (_: Exception) {}

                            if (!isX11Running) x11Server.start { isX11Running = it }
                            showSurface = true
                            desktopStatus = "Starting Openbox + Terminal..."
                            runner.kill()
                            runner.start(
                                command = listOf(
                                    "/bin/sh",
                                    "-c",
                                    "export DISPLAY=:0; eval $(dbus-launch --sh-syntax 2>/dev/null); (sleep 1; xfce4-terminal 2>/dev/null || xterm 2>/dev/null &); exec openbox"
                                ),
                                onOutput = { desktopStatus = "Openbox active" }
                            )
                        }
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                ) {
                    Text("Openbox", fontSize = 12.sp)
                }

                OutlinedButton(
                    onClick = {
                        scope.launch {
                            if (!isX11Running) x11Server.start { isX11Running = it }
                            showSurface = true
                            runner.start(
                                command = listOf(
                                    "/bin/sh",
                                    "-c",
                                    "DISPLAY=:0 xfce4-terminal 2>/dev/null || DISPLAY=:0 xterm 2>/dev/null"
                                ),
                                onOutput = { desktopStatus = "X11 Terminal launched" }
                            )
                        }
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                ) {
                    Text("Terminal", fontSize = 12.sp)
                }

                OutlinedButton(
                    onClick = {
                        scope.launch {
                            if (!isX11Running) x11Server.start { isX11Running = it }
                            showSurface = true
                            runner.start(
                                command = listOf(
                                    "/bin/sh",
                                    "-c",
                                    "DISPLAY=:0 chromium --no-sandbox || DISPLAY=:0 firefox-esr || DISPLAY=:0 xfce4-appfinder || DISPLAY=:0 xfce4-terminal"
                                ),
                                onOutput = { desktopStatus = "App launched" }
                            )
                        }
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                ) {
                    Text("App", fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Fullscreen Surface Viewport with Floating Input Controls
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                colors = CardDefaults.cardColors(containerColor = Color.Black),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (showSurface && isX11Running) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        // 1. Soft Keyboard Invisible Capture
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
                                        lorieViewRef?.sendKeyEvent(14, 67, true, 0)
                                        lorieViewRef?.sendKeyEvent(14, 67, false, 0)
                                    }
                                    ""
                                } else {
                                    newText
                                }

                                if (delta.isNotEmpty()) {
                                    if (delta == "\n" || delta == "\r\n") {
                                        lorieViewRef?.sendKeyEvent(28, 66, true, 0)
                                        lorieViewRef?.sendKeyEvent(28, 66, false, 0)
                                    } else {
                                        lorieViewRef?.sendTextEvent(delta.toByteArray(Charsets.UTF_8))
                                    }
                                }
                                inputFieldValue = newValue.copy(selection = androidx.compose.ui.text.TextRange(newText.length))
                            },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.None,
                                autoCorrect = false
                            ),
                            modifier = Modifier
                                .size(1.dp)
                                .alpha(0.01f)
                                .focusRequester(focusRequester),
                            cursorBrush = SolidColor(Color.Transparent)
                        )

                        // 2. Gesture-enabled LorieView
                        var twoFingerDownTime by remember { mutableLongStateOf(0L) }
                        var isTwoFingerScrolling by remember { mutableStateOf(false) }
                        var lastTwoFingerY by remember { mutableFloatStateOf(0f) }

                        AndroidView(
                            factory = { context ->
                                LorieView(context).apply {
                                    lorieViewRef = this
                                    try {
                                        val fd = x11Server.openRenderFd()
                                        if (fd >= 0) {
                                            connectAsync(fd)
                                        }
                                    } catch (e: Throwable) {
                                        android.util.Log.e("DesktopScreen", "Failed to connect LorieView to X11", e)
                                    }
                                    setOnTouchListener { _, event ->
                                        lastTapX = event.x
                                        lastTapY = event.y

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
                                                        // Mouse Wheel scroll
                                                        sendMouseEvent(0f, -deltaY * 5f, 4, false, true)
                                                        lastTwoFingerY = currentY
                                                        return@setOnTouchListener true
                                                    }
                                                }
                                            }
                                        }

                                        sendTouchEvent(
                                            event.actionMasked,
                                            event.getPointerId(event.actionIndex),
                                            event.x.toInt(),
                                            event.y.toInt()
                                        )
                                        true
                                    }
                                    setOnGenericMotionListener { _, event ->
                                        if (event.isFromSource(android.view.InputDevice.SOURCE_MOUSE)) {
                                            sendMouseEvent(
                                                event.x,
                                                event.y,
                                                event.buttonState,
                                                event.action == MotionEvent.ACTION_DOWN,
                                                false
                                            )
                                            true
                                        } else false
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )

                        // 3. Floating Quick Input Toolbar
                        Card(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 8.dp)
                                .height(44.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xEE141414)),
                            shape = RoundedCornerShape(22.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, AmoledContainerHigh)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(horizontal = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                IconButton(
                                    onClick = {
                                        isKeyboardVisible = !isKeyboardVisible
                                        if (isKeyboardVisible) {
                                            focusRequester.requestFocus()
                                            keyboardController?.show()
                                        } else {
                                            keyboardController?.hide()
                                        }
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Keyboard,
                                        contentDescription = "Toggle Soft Keyboard",
                                        tint = if (isKeyboardVisible) MaterialTheme.colorScheme.primary else Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                DesktopKeyChip("L") {
                                    lorieViewRef?.sendMouseEvent(lastTapX, lastTapY, 1, true, false)
                                    lorieViewRef?.sendMouseEvent(lastTapX, lastTapY, 1, false, false)
                                }
                                DesktopKeyChip("M") {
                                    lorieViewRef?.sendMouseEvent(lastTapX, lastTapY, 2, true, false)
                                    lorieViewRef?.sendMouseEvent(lastTapX, lastTapY, 2, false, false)
                                }
                                DesktopKeyChip("R") {
                                    lorieViewRef?.sendMouseEvent(lastTapX, lastTapY, 3, true, false)
                                    lorieViewRef?.sendMouseEvent(lastTapX, lastTapY, 3, false, false)
                                }

                                VerticalDivider(modifier = Modifier.height(18.dp), color = AmoledContainerHigh)

                                DesktopKeyChip("ESC") {
                                    lorieViewRef?.sendKeyEvent(1, 111, true, 0)
                                    lorieViewRef?.sendKeyEvent(1, 111, false, 0)
                                }
                                DesktopKeyChip("TAB") {
                                    lorieViewRef?.sendKeyEvent(15, 61, true, 0)
                                    lorieViewRef?.sendKeyEvent(15, 61, false, 0)
                                }
                                DesktopKeyChip("CTRL", active = ctrlHeld) {
                                    ctrlHeld = !ctrlHeld
                                    lorieViewRef?.sendKeyEvent(29, 113, ctrlHeld, 0)
                                }
                                DesktopKeyChip("ALT", active = altHeld) {
                                    altHeld = !altHeld
                                    lorieViewRef?.sendKeyEvent(56, 57, altHeld, 0)
                                }
                                DesktopKeyChip("SUPER") {
                                    lorieViewRef?.sendKeyEvent(125, 119, true, 0)
                                    lorieViewRef?.sendKeyEvent(125, 119, false, 0)
                                }
                                DesktopKeyChip("↑") {
                                    lorieViewRef?.sendKeyEvent(103, 19, true, 0)
                                    lorieViewRef?.sendKeyEvent(103, 19, false, 0)
                                }
                                DesktopKeyChip("↓") {
                                    lorieViewRef?.sendKeyEvent(108, 20, true, 0)
                                    lorieViewRef?.sendKeyEvent(108, 20, false, 0)
                                }
                            }
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.DesktopWindows,
                                contentDescription = null,
                                tint = AmoledContainerHigh,
                                modifier = Modifier.size(64.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = if (isX11Running) "Display is running. Tap XFCE4 or Openbox above to launch desktop." else "X11 Display is standby.\nTap 'Start Display' or pick an environment above.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 13.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 24.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionChip(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.height(34.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun MiniFloatingWindow(
    windowState: MiniWindowState,
    env: LinuxEnvironment,
    x11Server: X11Server,
    runner: ProotRunner,
    canvasWidthDp: Float,
    canvasHeightDp: Float,
    onFocus: () -> Unit,
    onMinimize: () -> Unit,
    onToggleMaximize: () -> Unit,
    onClose: () -> Unit
) {
    val actualX = if (windowState.isMaximized) 0f else windowState.offsetX
    val actualY = if (windowState.isMaximized) 0f else windowState.offsetY
    val actualWidth = if (windowState.isMaximized) canvasWidthDp else windowState.widthDp
    val actualHeight = if (windowState.isMaximized) canvasHeightDp else windowState.heightDp

    val density = LocalDensity.current

    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    with(density) { actualX.dp.roundToPx() },
                    with(density) { actualY.dp.roundToPx() }
                )
            }
            .size(actualWidth.dp, actualHeight.dp)
            .zIndex(windowState.zIndex)
            .shadow(12.dp, RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF121212))
            .border(1.dp, Color(0xFF2C2C2C), RoundedCornerShape(10.dp))
            .clickable { onFocus() }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Window Header Bar (Draggable)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1E1E1E))
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            if (!windowState.isMaximized) {
                                windowState.offsetX = (windowState.offsetX + dragAmount.x / density.density).coerceAtLeast(0f)
                                windowState.offsetY = (windowState.offsetY + dragAmount.y / density.density).coerceAtLeast(0f)
                            }
                            onFocus()
                        }
                    }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        windowState.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = windowState.title,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Minimize
                    IconButton(
                        onClick = onMinimize,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(Icons.Default.Remove, contentDescription = "Minimize", tint = Color.LightGray, modifier = Modifier.size(14.dp))
                    }
                    // Maximize
                    IconButton(
                        onClick = onToggleMaximize,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            if (windowState.isMaximized) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                            contentDescription = "Maximize",
                            tint = Color.LightGray,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    // Close
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color(0xFFFF5252), modifier = Modifier.size(14.dp))
                    }
                }
            }

            // Window Content View
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.Black)
            ) {
                when (windowState.type) {
                    MiniWindowType.TERMINAL -> MiniTerminalView(env)
                    MiniWindowType.MONITOR -> MiniMonitorView()
                    MiniWindowType.UPDATER -> MiniUpdaterView(env)
                    MiniWindowType.CAMERA -> MiniCameraView()
                    MiniWindowType.X11 -> MiniX11View(x11Server)
                }
            }
        }

        // Resize Grip at Bottom-Right
        if (!windowState.isMaximized) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(24.dp)
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            windowState.widthDp = (windowState.widthDp + dragAmount.x / density.density).coerceAtLeast(220f)
                            windowState.heightDp = (windowState.heightDp + dragAmount.y / density.density).coerceAtLeast(180f)
                            onFocus()
                        }
                    }
                    .padding(4.dp),
                contentAlignment = Alignment.BottomEnd
            ) {
                Icon(
                    Icons.Default.AspectRatio,
                    contentDescription = "Resize",
                    tint = Color.DarkGray,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}

@Composable
private fun MiniTerminalView(env: LinuxEnvironment) {
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val tab = remember {
        TerminalTab(
            id = "mini-term",
            title = "Shell",
            env = env,
            scope = scope
        ).apply { start() }
    }

    DisposableEffect(Unit) {
        onDispose { tab.stop() }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            InteractiveTerminalPane(
                tab = tab,
                fontSize = 11,
                focusRequester = focusRequester,
                ctrlActive = false,
                altActive = false,
                onConsumeCtrl = {},
                onConsumeAlt = {}
            )
        }

        // Mini Quick Key Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .background(Color(0xFF161616))
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val keys = listOf(
                "ESC" to "\u001b",
                "TAB" to "\t",
                "CTRL+C" to "\u0003",
                "CTRL+D" to "\u0004",
                "↑" to "\u001b[A",
                "↓" to "\u001b[B"
            )
            keys.forEach { (label, code) ->
                FilledTonalButton(
                    onClick = {
                        tab.write(code)
                        focusRequester.requestFocus()
                    },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                    modifier = Modifier.height(24.dp)
                ) {
                    Text(label, fontSize = 9.sp)
                }
            }
        }
    }
}

@Composable
private fun MiniMonitorView() {
    val context = LocalContext.current
    val monitor = remember { ResourceMonitor(context) }
    var stats by remember { mutableStateOf(monitor.sample()) }

    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            stats = monitor.sample()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("CPU: ${stats.cpuPercent}%", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
            Text("RAM: ${stats.usedRamMb} MB", fontSize = 11.sp, color = Color(0xFF00FF66), fontWeight = FontWeight.Bold)
            Text("Storage: ${stats.freeStorageGb} GB free", fontSize = 11.sp, color = Color(0xFFFFB300))
        }

        Spacer(modifier = Modifier.height(6.dp))
        Text("Active Linux Processes (${stats.processes.size}):", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(stats.processes) { proc ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1E1E1E), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(proc.name, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Color.White)
                        Text("PID ${proc.pid} • ${proc.memoryKb / 1024} MB", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    IconButton(
                        onClick = {
                            monitor.killProcess(proc.pid)
                            stats = monitor.sample()
                        },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Kill", tint = Color(0xFFFF5252), modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniUpdaterView(env: LinuxEnvironment) {
    val scope = rememberCoroutineScope()
    var logText by remember { mutableStateOf("Package Maintenance Tool.\nReady to update or repair.\n") }
    var isRunning by remember { mutableStateOf(false) }
    val runner = remember { ProotRunner(env, scope) }

    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Button(
                onClick = {
                    isRunning = true
                    logText += "\n> apt-get update\n"
                    runner.start(
                        command = listOf("apt-get", "update"),
                        onOutput = { line -> logText += "$line\n" }
                    )
                    isRunning = false
                },
                enabled = !isRunning,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                Text("apt update", fontSize = 11.sp)
            }

            FilledTonalButton(
                onClick = {
                    isRunning = true
                    logText += "\n> dpkg --configure -a\n"
                    runner.start(
                        command = listOf("dpkg", "--configure", "-a"),
                        onOutput = { line -> logText += "$line\n" }
                    )
                    isRunning = false
                },
                enabled = !isRunning,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                Text("Repair", fontSize = 11.sp)
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(0xFF141414), RoundedCornerShape(6.dp))
                .verticalScroll(rememberScrollState())
                .padding(6.dp)
        ) {
            Text(
                text = logText,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = Color(0xFF00FF66)
            )
        }
    }
}

@Composable
private fun MiniCameraView() {
    var isBridgeActive by remember { mutableStateOf(HardwareBridges.cameraBridgeEnabled) }

    Column(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.Videocam, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
        Spacer(modifier = Modifier.height(8.dp))
        Text("Hardware Camera Bridge", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text("Piped to /run/bsl/camera socket", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = {
                isBridgeActive = !isBridgeActive
                HardwareBridges.cameraBridgeEnabled = isBridgeActive
            }
        ) {
            Text(if (isBridgeActive) "Disable Camera Bridge" else "Enable Camera Bridge", fontSize = 11.sp)
        }
    }
}

@Composable
private fun MiniX11View(x11Server: X11Server) {
    AndroidView(
        factory = { context ->
            LorieView(context).apply {
                try {
                    val fd = x11Server.openRenderFd()
                    if (fd >= 0) {
                        connectAsync(fd)
                    }
                } catch (e: Throwable) {
                    android.util.Log.e("DesktopScreen", "Failed to connect MiniX11View to X11", e)
                }
                setOnTouchListener { _, event ->
                    sendTouchEvent(
                        event.actionMasked,
                        event.getPointerId(event.actionIndex),
                        event.x.toInt(),
                        event.y.toInt()
                    )
                    true
                }
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
private fun DesktopKeyChip(
    label: String,
    active: Boolean = false,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (active) MaterialTheme.colorScheme.primary else AmoledContainer,
        modifier = Modifier.height(28.dp)
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (active) MaterialTheme.colorScheme.onPrimary else Color.White
            )
        }
    }
}
