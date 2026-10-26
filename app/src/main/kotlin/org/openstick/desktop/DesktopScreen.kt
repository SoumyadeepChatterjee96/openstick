package org.openstick.desktop

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.breadstick.x11.LorieView
import io.breadstick.x11.X11Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.view.HapticFeedbackConstants
import org.openstick.engine.GpuServer
import org.openstick.engine.HardwareBridges
import org.openstick.engine.LinuxEnvironment
import org.openstick.engine.ProotRunner
import org.openstick.engine.WebVncServer
import org.openstick.ui.theme.AmoledBackground
import org.openstick.ui.theme.AmoledContainer
import org.openstick.ui.theme.AmoledContainerHigh
import org.openstick.ui.theme.AmoledGlassBorder
import org.openstick.ui.theme.AmoledGlassContainer
import java.net.Inet4Address
import java.net.NetworkInterface

@Composable
fun DesktopScreen(
    env: LinuxEnvironment,
    x11Server: X11Server,
    runner: ProotRunner,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

    var isX11Running by remember { mutableStateOf(X11Server.isServerRunning) }
    var isGpuRunning by remember { mutableStateOf(GpuServer.isRunning) }
    var isWebVncRunning by remember { mutableStateOf(WebVncServer.current != null) }
    var desktopStatus by remember { mutableStateOf("Desktop ready") }

    var lorieViewRef by remember { mutableStateOf<LorieView?>(null) }
    var isKeyboardVisible by remember { mutableStateOf(false) }
    var ctrlHeld by remember { mutableStateOf(false) }
    var altHeld by remember { mutableStateOf(false) }
    var lastTapX by remember { mutableFloatStateOf(500f) }
    var lastTapY by remember { mutableFloatStateOf(400f) }
    var inputFieldValue by remember { mutableStateOf(TextFieldValue("")) }

    var isToolbarExpanded by remember { mutableStateOf(true) }
    var isTrackpadMode by remember { mutableStateOf(false) }
    var showQuickMenu by remember { mutableStateOf(false) }
    var showScreenShareDialog by remember { mutableStateOf(false) }

    // Auto-boot graphical desktop session on screen entry
    LaunchedEffect(Unit) {
        if (!env.isInstalled()) return@LaunchedEffect

        if (!X11Server.isServerRunning) {
            desktopStatus = "Starting graphics engine..."
            x11Server.start { success ->
                isX11Running = success
                if (success) {
                    desktopStatus = "X11 Display :0 Ready • Launching Desktop..."
                    val user = env.getConfiguredUsername()
                    scope.launch(Dispatchers.IO) {
                        runner.killSync()
                        runner.start(
                            command = listOf(
                                "/bin/sh",
                                "-c",
                                env.startDesktopCommand(0, user)
                            ),
                            asRoot = true,
                            onOutput = { desktopStatus = "Desktop session active" }
                        )
                    }
                } else {
                    desktopStatus = "X11 server standby"
                }
            }
        } else {
            desktopStatus = "Display active (:0)"
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (!env.isInstalled()) {
            // Linux Not Installed Card
            Card(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                colors = CardDefaults.cardColors(containerColor = AmoledContainer),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.DesktopWindows,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Linux Not Installed",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Install Debian from the Setup/Home tab first before launching the graphical desktop.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            // 1. Soft Keyboard Invisible Input Capture
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

            // 2. Fullscreen Edge-to-Edge LorieView Canvas
            var downX by remember { mutableFloatStateOf(0f) }
            var downY by remember { mutableFloatStateOf(0f) }
            var prevTouchX by remember { mutableFloatStateOf(0f) }
            var prevTouchY by remember { mutableFloatStateOf(0f) }
            var cursorX by remember { mutableFloatStateOf(540f) }
            var cursorY by remember { mutableFloatStateOf(1170f) }
            var lastPhysicalMouseButton by remember { mutableIntStateOf(1) }
            var lastTapUpTime by remember { mutableLongStateOf(0L) }
            var lastTapUpX by remember { mutableFloatStateOf(0f) }
            var lastTapUpY by remember { mutableFloatStateOf(0f) }
            var isDragging by remember { mutableStateOf(false) }
            var hasTriggeredLongPress by remember { mutableStateOf(false) }
            var longPressJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
            var twoFingerDownTime by remember { mutableLongStateOf(0L) }
            var isTwoFingerScrolling by remember { mutableStateOf(false) }
            var lastTwoFingerY by remember { mutableFloatStateOf(0f) }
            val touchSlop = remember { 10f * context.resources.displayMetrics.density }
            val longPressTimeout = 400L

            AndroidView(
                factory = { ctx ->
                    LorieView(ctx).apply {
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
                            if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
                                val button = when {
                                    event.buttonState and MotionEvent.BUTTON_PRIMARY != 0 -> 1
                                    event.buttonState and MotionEvent.BUTTON_SECONDARY != 0 -> 3
                                    event.buttonState and MotionEvent.BUTTON_TERTIARY != 0 -> 2
                                    else -> lastPhysicalMouseButton
                                }
                                when (event.actionMasked) {
                                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_BUTTON_PRESS -> {
                                        lastPhysicalMouseButton = button
                                        sendMouseEvent(event.x, event.y, button, true, false)
                                    }
                                    MotionEvent.ACTION_MOVE -> {
                                        val isDown = event.buttonState != 0
                                        val btn = if (isDown) lastPhysicalMouseButton else 0
                                        sendMouseEvent(event.x, event.y, btn, isDown, false)
                                    }
                                    MotionEvent.ACTION_UP, MotionEvent.ACTION_BUTTON_RELEASE -> {
                                        sendMouseEvent(event.x, event.y, lastPhysicalMouseButton, false, false)
                                    }
                                }
                                return@setOnTouchListener true
                            }

                            lastTapX = if (isTrackpadMode) cursorX else event.x
                            lastTapY = if (isTrackpadMode) cursorY else event.y

                            val pointerCount = event.pointerCount
                            when (event.actionMasked) {
                                MotionEvent.ACTION_DOWN -> {
                                    downX = event.x
                                    downY = event.y
                                    prevTouchX = event.x
                                    prevTouchY = event.y
                                    isDragging = false
                                    hasTriggeredLongPress = false

                                    if (!isTrackpadMode) {
                                        val now = System.currentTimeMillis()
                                        val isDoubleTap = (now - lastTapUpTime < 450L) &&
                                            (kotlin.math.hypot(event.x - lastTapUpX, event.y - lastTapUpY) < touchSlop * 2.5f)

                                        if (isDoubleTap) {
                                            // Snap to first tap coordinates to prevent motion jitter from breaking double clicks
                                            cursorX = lastTapUpX
                                            cursorY = lastTapUpY
                                            lastTapX = lastTapUpX
                                            lastTapY = lastTapUpY
                                        } else {
                                            cursorX = event.x
                                            cursorY = event.y
                                            lastTapX = event.x
                                            lastTapY = event.y
                                        }
                                        sendMouseEvent(cursorX, cursorY, 0, false, false)

                                        longPressJob?.cancel()
                                        longPressJob = scope.launch {
                                            kotlinx.coroutines.delay(longPressTimeout)
                                            if (!isDragging) {
                                                hasTriggeredLongPress = true
                                                sendMouseEvent(downX, downY, 3, true, false)
                                                sendMouseEvent(downX, downY, 3, false, false)
                                                try {
                                                    performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                                                } catch (_: Exception) {}
                                            }
                                        }
                                    }
                                }

                                MotionEvent.ACTION_POINTER_DOWN -> {
                                    longPressJob?.cancel()
                                    if (pointerCount == 2) {
                                        twoFingerDownTime = System.currentTimeMillis()
                                        isTwoFingerScrolling = false
                                        lastTwoFingerY = (event.getY(0) + event.getY(1)) / 2f
                                        if (isDragging) {
                                            sendMouseEvent(cursorX, cursorY, 1, false, false)
                                            isDragging = false
                                        }
                                    }
                                }

                                MotionEvent.ACTION_MOVE -> {
                                    if (pointerCount == 1) {
                                        if (isTrackpadMode) {
                                            val dx = (event.x - prevTouchX) * 1.5f
                                            val dy = (event.y - prevTouchY) * 1.5f
                                            prevTouchX = event.x
                                            prevTouchY = event.y
                                            val w = if (width > 0) width.toFloat() else 1080f
                                            val h = if (height > 0) height.toFloat() else 2340f
                                            cursorX = (cursorX + dx).coerceIn(0f, w)
                                            cursorY = (cursorY + dy).coerceIn(0f, h)
                                            lastTapX = cursorX
                                            lastTapY = cursorY
                                            sendMouseEvent(cursorX, cursorY, 0, false, false)
                                        } else {
                                            val dx = event.x - downX
                                            val dy = event.y - downY
                                            val distSq = dx * dx + dy * dy
                                            if (distSq > touchSlop * touchSlop) {
                                                longPressJob?.cancel()
                                                if (!hasTriggeredLongPress) {
                                                    cursorX = event.x
                                                    cursorY = event.y
                                                    lastTapX = event.x
                                                    lastTapY = event.y
                                                    if (!isDragging) {
                                                        isDragging = true
                                                        sendMouseEvent(downX, downY, 1, true, false)
                                                    }
                                                    sendMouseEvent(cursorX, cursorY, 1, true, false)
                                                }
                                            } else {
                                                cursorX = event.x
                                                cursorY = event.y
                                                lastTapX = event.x
                                                lastTapY = event.y
                                                sendMouseEvent(cursorX, cursorY, 0, false, false)
                                            }
                                        }
                                    } else if (pointerCount == 2) {
                                        longPressJob?.cancel()
                                        val currentY = (event.getY(0) + event.getY(1)) / 2f
                                        val deltaY = currentY - lastTwoFingerY
                                        if (kotlin.math.abs(deltaY) > 8f) {
                                            isTwoFingerScrolling = true
                                            val button = if (deltaY > 0) 4 else 5
                                            sendMouseEvent(lastTapX, lastTapY, button, true, false)
                                            sendMouseEvent(lastTapX, lastTapY, button, false, false)
                                            lastTwoFingerY = currentY
                                        }
                                    }
                                }

                                MotionEvent.ACTION_POINTER_UP -> {
                                    if (pointerCount == 2 && !isTwoFingerScrolling) {
                                        val duration = System.currentTimeMillis() - twoFingerDownTime
                                        if (duration < 350) {
                                            val tapX = (event.getX(0) + event.getX(1)) / 2f
                                            val tapY = (event.getY(0) + event.getY(1)) / 2f
                                            sendMouseEvent(tapX, tapY, 3, true, false)
                                            sendMouseEvent(tapX, tapY, 3, false, false)
                                            return@setOnTouchListener true
                                        }
                                    }
                                }

                                MotionEvent.ACTION_UP -> {
                                    longPressJob?.cancel()
                                    if (pointerCount == 1 && !hasTriggeredLongPress) {
                                        if (isTrackpadMode) {
                                            val dx = event.x - downX
                                            val dy = event.y - downY
                                            if (dx * dx + dy * dy <= touchSlop * touchSlop) {
                                                sendMouseEvent(cursorX, cursorY, 1, true, false)
                                                sendMouseEvent(cursorX, cursorY, 1, false, false)
                                            }
                                        } else {
                                            if (isDragging) {
                                                sendMouseEvent(cursorX, cursorY, 1, false, false)
                                                isDragging = false
                                            } else {
                                                val now = System.currentTimeMillis()
                                                val isDoubleTap = (now - lastTapUpTime < 450L) &&
                                                    (kotlin.math.hypot(event.x - lastTapUpX, event.y - lastTapUpY) < touchSlop * 2.5f)
                                                val clickX = if (isDoubleTap) lastTapUpX else event.x
                                                val clickY = if (isDoubleTap) lastTapUpY else event.y
                                                cursorX = clickX
                                                cursorY = clickY
                                                lastTapX = clickX
                                                lastTapY = clickY
                                                sendMouseEvent(cursorX, cursorY, 0, false, false)
                                                sendMouseEvent(cursorX, cursorY, 1, true, false)
                                                sendMouseEvent(cursorX, cursorY, 1, false, false)
                                                lastTapUpTime = now
                                                lastTapUpX = clickX
                                                lastTapUpY = clickY
                                            }
                                        }
                                    }
                                    hasTriggeredLongPress = false
                                }

                                MotionEvent.ACTION_CANCEL -> {
                                    longPressJob?.cancel()
                                    if (isDragging) {
                                        sendMouseEvent(cursorX, cursorY, 1, false, false)
                                        isDragging = false
                                    }
                                    hasTriggeredLongPress = false
                                }
                            }
                            true
                        }

                        setOnGenericMotionListener { _, event ->
                            if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
                                when (event.action) {
                                    MotionEvent.ACTION_HOVER_MOVE -> {
                                        cursorX = event.x
                                        cursorY = event.y
                                        lastTapX = event.x
                                        lastTapY = event.y
                                        sendMouseEvent(event.x, event.y, 0, false, false)
                                        true
                                    }
                                    MotionEvent.ACTION_SCROLL -> {
                                        val vscroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                                        if (vscroll != 0f) {
                                            val button = if (vscroll > 0) 4 else 5
                                            sendMouseEvent(event.x, event.y, button, true, false)
                                            sendMouseEvent(event.x, event.y, button, false, false)
                                        }
                                        true
                                    }
                                    else -> false
                                }
                            } else false
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // 3. Floating Collapsible AMOLED Pill Toolbar
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 12.dp)
            ) {
                if (!isToolbarExpanded) {
                    // Collapsed Minimal Pill
                    Surface(
                        onClick = { isToolbarExpanded = true },
                        shape = RoundedCornerShape(20.dp),
                        color = AmoledGlassContainer,
                        border = BorderStroke(1.dp, AmoledGlassBorder),
                        shadowElevation = 8.dp,
                        modifier = Modifier.height(36.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Default.Keyboard,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Tools ▲",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                        }
                    }
                } else {
                    // Expanded Floating Toolbar Pill
                    Surface(
                        shape = RoundedCornerShape(22.dp),
                        color = AmoledGlassContainer,
                        border = BorderStroke(1.dp, AmoledGlassBorder),
                        shadowElevation = 10.dp,
                        modifier = Modifier.height(44.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxHeight()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            // Collapse button
                            IconButton(
                                onClick = { isToolbarExpanded = false },
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(
                                    Icons.Default.KeyboardArrowDown,
                                    contentDescription = "Collapse Toolbar",
                                    tint = Color.LightGray,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            // Soft keyboard toggle
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
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(
                                    Icons.Default.Keyboard,
                                    contentDescription = "Toggle Keyboard",
                                    tint = if (isKeyboardVisible) MaterialTheme.colorScheme.primary else Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            // Mode toggle chip: Direct Touch vs Trackpad
                            DesktopKeyChip(
                                label = if (isTrackpadMode) "Trackpad" else "Touch",
                                active = isTrackpadMode
                            ) {
                                isTrackpadMode = !isTrackpadMode
                            }

                            // Mouse buttons
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

                            VerticalDivider(modifier = Modifier.height(18.dp).padding(horizontal = 2.dp), color = AmoledContainerHigh)

                            // Modifier keys
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

                            VerticalDivider(modifier = Modifier.height(18.dp).padding(horizontal = 2.dp), color = AmoledContainerHigh)

                            // Window Management Controls (Un-stick, Maximize, Close)
                            DesktopKeyChip("✕ Close") {
                                scope.launch {
                                    runner.executeOneShot(
                                        command = listOf("/bin/sh", "-c", "DISPLAY=:0 wmctrl -c :ACTIVE: 2>/dev/null || DISPLAY=:0 xdotool getactivewindow windowkill 2>/dev/null"),
                                        asRoot = false
                                    )
                                }
                                lorieViewRef?.sendKeyEvent(56, 57, true, 0)
                                lorieViewRef?.sendKeyEvent(62, 134, true, 0)
                                lorieViewRef?.sendKeyEvent(62, 134, false, 0)
                                lorieViewRef?.sendKeyEvent(56, 57, false, 0)
                            }
                            DesktopKeyChip("⤢ Max") {
                                scope.launch {
                                    runner.executeOneShot(
                                        command = listOf("/bin/sh", "-c", "DISPLAY=:0 wmctrl -r :ACTIVE: -b toggle,maximized_vert,maximized_horz 2>/dev/null"),
                                        asRoot = false
                                    )
                                }
                            }
                            DesktopKeyChip("⎚ Min") {
                                scope.launch {
                                    runner.executeOneShot(
                                        command = listOf("/bin/sh", "-c", "DISPLAY=:0 wmctrl -r :ACTIVE: -b add,hidden 2>/dev/null"),
                                        asRoot = false
                                    )
                                }
                            }
                            DesktopKeyChip("⇲ Move") {
                                scope.launch {
                                    runner.executeOneShot(
                                        command = listOf("/bin/sh", "-c", "DISPLAY=:0 wmctrl -r :ACTIVE: -e 0,50,120,-1,-1 2>/dev/null"),
                                        asRoot = false
                                    )
                                }
                            }

                            VerticalDivider(modifier = Modifier.height(18.dp).padding(horizontal = 2.dp), color = AmoledContainerHigh)

                            // Screen Sharing / WebVNC
                            IconButton(
                                onClick = { showScreenShareDialog = true },
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(
                                    Icons.Default.ScreenShare,
                                    contentDescription = "Screen Sharing",
                                    tint = if (isWebVncRunning) Color(0xFF00FF66) else Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            // Quick Launcher Menu
                            Box {
                                IconButton(
                                    onClick = { showQuickMenu = true },
                                    modifier = Modifier.size(30.dp)
                                ) {
                                    Icon(
                                        Icons.Default.MoreVert,
                                        contentDescription = "Quick Menu",
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                DropdownMenu(
                                    expanded = showQuickMenu,
                                    onDismissRequest = { showQuickMenu = false },
                                    modifier = Modifier.background(AmoledContainer)
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Open Terminal", color = Color.White, fontSize = 13.sp) },
                                        leadingIcon = { Icon(Icons.Default.Terminal, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)) },
                                        onClick = {
                                            showQuickMenu = false
                                            scope.launch {
                                                runner.executeOneShot(
                                                    command = listOf("/bin/sh", "-c", "DISPLAY=:0 xfce4-terminal 2>/dev/null || DISPLAY=:0 xterm &"),
                                                    asRoot = false
                                                )
                                            }
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Open File Manager", color = Color.White, fontSize = 13.sp) },
                                        leadingIcon = { Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)) },
                                        onClick = {
                                            showQuickMenu = false
                                            scope.launch {
                                                runner.executeOneShot(
                                                    command = listOf("/bin/sh", "-c", "DISPLAY=:0 thunar 2>/dev/null &"),
                                                    asRoot = false
                                                )
                                            }
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Open App Finder", color = Color.White, fontSize = 13.sp) },
                                        leadingIcon = { Icon(Icons.Default.Apps, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)) },
                                        onClick = {
                                            showQuickMenu = false
                                            scope.launch {
                                                runner.executeOneShot(
                                                    command = listOf("/bin/sh", "-c", "DISPLAY=:0 xfce4-appfinder 2>/dev/null &"),
                                                    asRoot = false
                                                )
                                            }
                                        }
                                    )
                                    HorizontalDivider(color = AmoledContainerHigh)
                                    DropdownMenuItem(
                                        text = { Text("Close Active Window (Alt+F4)", color = Color(0xFFFF5252), fontSize = 13.sp) },
                                        leadingIcon = { Icon(Icons.Default.Close, null, tint = Color(0xFFFF5252), modifier = Modifier.size(18.dp)) },
                                        onClick = {
                                            showQuickMenu = false
                                            scope.launch {
                                                runner.executeOneShot(
                                                    command = listOf("/bin/sh", "-c", "DISPLAY=:0 wmctrl -c :ACTIVE: 2>/dev/null || DISPLAY=:0 xdotool getactivewindow windowkill 2>/dev/null"),
                                                    asRoot = false
                                                )
                                            }
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Un-stick Window (Move Down)", color = Color.White, fontSize = 13.sp) },
                                        leadingIcon = { Icon(Icons.Default.OpenWith, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)) },
                                        onClick = {
                                            showQuickMenu = false
                                            scope.launch {
                                                runner.executeOneShot(
                                                    command = listOf("/bin/sh", "-c", "DISPLAY=:0 wmctrl -r :ACTIVE: -e 0,50,120,-1,-1 2>/dev/null"),
                                                    asRoot = false
                                                )
                                            }
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Toggle Maximize Window", color = Color.White, fontSize = 13.sp) },
                                        leadingIcon = { Icon(Icons.Default.Fullscreen, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)) },
                                        onClick = {
                                            showQuickMenu = false
                                            scope.launch {
                                                runner.executeOneShot(
                                                    command = listOf("/bin/sh", "-c", "DISPLAY=:0 wmctrl -r :ACTIVE: -b toggle,maximized_vert,maximized_horz 2>/dev/null"),
                                                    asRoot = false
                                                )
                                            }
                                        }
                                    )
                                    HorizontalDivider(color = AmoledContainerHigh)
                                    DropdownMenuItem(
                                        text = { Text("Restart XFCE Desktop", color = Color.White, fontSize = 13.sp) },
                                        leadingIcon = { Icon(Icons.Default.Refresh, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)) },
                                        onClick = {
                                            showQuickMenu = false
                                            scope.launch {
                                                val user = env.getConfiguredUsername()
                                                runner.killSync()
                                                runner.start(
                                                    command = listOf("/bin/sh", "-c", env.startDesktopCommand(0, user)),
                                                    asRoot = true,
                                                    onOutput = { desktopStatus = "Desktop session active" }
                                                )
                                            }
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(if (isGpuRunning) "Turn 3D GPU OFF" else "Turn 3D GPU ON", color = if (isGpuRunning) Color(0xFF00FF66) else Color.White, fontSize = 13.sp) },
                                        leadingIcon = { Icon(Icons.Default.Speed, null, tint = if (isGpuRunning) Color(0xFF00FF66) else Color.Gray, modifier = Modifier.size(18.dp)) },
                                        onClick = {
                                            showQuickMenu = false
                                            if (!isGpuRunning) {
                                                isGpuRunning = GpuServer.start(env)
                                            } else {
                                                GpuServer.stop()
                                                isGpuRunning = false
                                            }
                                        }
                                    )
                                    HorizontalDivider(color = AmoledContainerHigh)
                                    DropdownMenuItem(
                                        text = { Text("Stop Desktop Session", color = Color(0xFFFF5252), fontSize = 13.sp) },
                                        leadingIcon = { Icon(Icons.Default.Stop, null, tint = Color(0xFFFF5252), modifier = Modifier.size(18.dp)) },
                                        onClick = {
                                            showQuickMenu = false
                                            scope.launch {
                                                runner.kill()
                                                x11Server.stop()
                                                isX11Running = false
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 4. WebVNC Screen Sharing Dialog
    if (showScreenShareDialog) {
        val localIp = remember { getLocalIpAddress() }
        val webUrl = "http://$localIp:6080/vnc.html"

        AlertDialog(
            onDismissRequest = { showScreenShareDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.ScreenShare,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("HTML5 Screen Sharing", fontWeight = FontWeight.Bold, color = Color.White)
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Access this full Linux graphical desktop from any web browser on your Wi-Fi network without installing software.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Server Status:", fontSize = 12.sp, color = Color.White)
                        Badge(containerColor = if (isWebVncRunning) Color(0xFF1B5E20) else Color(0xFF424242)) {
                            Text(
                                text = if (isWebVncRunning) "Streaming on :6080" else "Offline",
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color.Black),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, AmoledContainerHigh)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("Direct Browser URL:", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = webUrl,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF00FF66)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(webUrl))
                            },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Copy Link", fontSize = 11.sp)
                        }

                        OutlinedButton(
                            onClick = {
                                try {
                                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(webUrl)).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(browserIntent)
                                } catch (_: Exception) {}
                            },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Open Tab", fontSize = 11.sp)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (!isWebVncRunning) {
                            WebVncServer.startServer { path ->
                                try {
                                    context.assets.open(path)
                                } catch (_: Exception) {
                                    null
                                }
                            }
                            HardwareBridges.startVncServer(runner, scope)
                            isWebVncRunning = true
                        } else {
                            WebVncServer.stopServer()
                            HardwareBridges.stopVncServer(runner, scope)
                            isWebVncRunning = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isWebVncRunning) Color(0xFFB71C1C) else MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(if (isWebVncRunning) "Stop Sharing" else "Start Sharing", fontSize = 12.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { showScreenShareDialog = false }) {
                    Text("Close", color = Color.LightGray)
                }
            },
            containerColor = AmoledContainer
        )
    }
}

@Composable
private fun DesktopKeyChip(
    label: String,
    active: Boolean = false,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (active) MaterialTheme.colorScheme.primary else Color(0xCC242424),
        border = BorderStroke(1.dp, if (active) MaterialTheme.colorScheme.primary else AmoledGlassBorder),
        modifier = Modifier.height(30.dp)
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
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

private fun getLocalIpAddress(): String {
    try {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        for (intf in interfaces) {
            if (!intf.isUp || intf.isLoopback) continue
            val addrs = intf.inetAddresses
            for (addr in addrs) {
                if (!addr.isLoopbackAddress && addr is Inet4Address) {
                    val host = addr.hostAddress
                    if (host != null && host.isNotEmpty() && !host.startsWith("127.")) {
                        return host
                    }
                }
            }
        }
    } catch (_: Exception) {}
    return "127.0.0.1"
}
