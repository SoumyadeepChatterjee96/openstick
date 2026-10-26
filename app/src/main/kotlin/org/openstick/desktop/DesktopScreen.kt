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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
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
                                    desktopStatus = if (success) "X11 listening on :0" else "Failed to start X11"
                                    if (success) showSurface = true
                                }
                            } else {
                                x11Server.stop()
                                isX11Running = false
                                showSurface = false
                                desktopStatus = "X11 server stopped"
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
                            if (!isX11Running) x11Server.start { isX11Running = it }
                            showSurface = true
                            desktopStatus = "Starting XFCE4..."
                            runner.start(
                                command = listOf("/bin/sh", "-c", "DISPLAY=:0 startxfce4 || DISPLAY=:0 xfce4-session"),
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
                            if (!isX11Running) x11Server.start { isX11Running = it }
                            showSurface = true
                            desktopStatus = "Starting Openbox..."
                            runner.start(
                                command = listOf("/bin/sh", "-c", "DISPLAY=:0 openbox"),
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
                                command = listOf("/bin/sh", "-c", "DISPLAY=:0 chromium --no-sandbox || DISPLAY=:0 firefox-esr || DISPLAY=:0 xterm"),
                                onOutput = { desktopStatus = "Browser launched" }
                            )
                        }
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                ) {
                    Text("Browser", fontSize = 12.sp)
                }

                OutlinedButton(
                    onClick = {
                        scope.launch {
                            if (!isX11Running) x11Server.start { isX11Running = it }
                            showSurface = true
                            runner.start(
                                command = listOf("/bin/sh", "-c", "DISPLAY=:0 code --no-sandbox || DISPLAY=:0 gimp || DISPLAY=:0 xfce4-terminal"),
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

            // Fullscreen Surface Viewport
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                colors = CardDefaults.cardColors(containerColor = Color.Black),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (showSurface && isX11Running) {
                    AndroidView(
                        factory = { context ->
                            LorieView(context).apply {
                                try {
                                    val fd = x11Server.openRenderFd()
                                    if (fd >= 0) {
                                        connectAsync(fd)
                                    }
                                } catch (e: Throwable) {
                                    android.util.Log.e("DesktopScreen", "Failed to connect LorieView to X11", e)
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
    var outputText by remember { mutableStateOf("OpenStick Terminal Shell ready.\n") }
    var commandText by remember { mutableStateOf("") }
    val runner = remember {
        ProotRunner(env, scope).apply {
            start(
                onOutput = { newText ->
                    val clean = newText.replace(Regex("\u001B\\[[;?0-9]*[a-zA-Z]"), "")
                    outputText += clean
                    if (outputText.length > 8000) {
                        outputText = outputText.takeLast(5000)
                    }
                }
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose { runner.stop() }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(6.dp)
        ) {
            Text(
                text = outputText,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = Color(0xFF00FF66),
                lineHeight = 15.sp
            )
        }

        // Key shortcuts
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .background(Color(0xFF161616))
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val keys = listOf("ESC" to "\u001b", "TAB" to "\t", "CTRL+C" to "\u0003", "CTRL+D" to "\u0004", "UP" to "\u001b[A", "DOWN" to "\u001b[B")
            keys.forEach { (label, code) ->
                FilledTonalButton(
                    onClick = { runner.write(code) },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                    modifier = Modifier.height(24.dp)
                ) {
                    Text(label, fontSize = 9.sp)
                }
            }
        }

        // Input
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF161616))
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = commandText,
                onValueChange = { commandText = it },
                modifier = Modifier.weight(1f).height(38.dp),
                placeholder = { Text("Command...", fontSize = 11.sp) },
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color.White),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    if (commandText.isNotEmpty()) {
                        runner.write("$commandText\n")
                        commandText = ""
                    }
                })
            )
            IconButton(
                onClick = {
                    if (commandText.isNotEmpty()) {
                        runner.write("$commandText\n")
                        commandText = ""
                    }
                },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(Icons.Default.Send, contentDescription = "Send", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
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
