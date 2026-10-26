package org.openstick.terminal

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.Splitscreen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.activity.compose.BackHandler
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.openstick.ui.theme.LocalOpenStickPalette

@Composable
fun TerminalScreen(
    manager: TerminalSessionManager,
    modifier: Modifier = Modifier,
    onNavigateHome: () -> Unit = {}
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    val palette = LocalOpenStickPalette.current

    BackHandler {
        onNavigateHome()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(palette.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        // Tab & Layout Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.container)
                .padding(start = 48.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onNavigateHome,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.ArrowBack,
                    contentDescription = "Back to Home",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState())
            ) {
                manager.tabs.forEachIndexed { index, tab ->
                    val isActive = index == manager.activeTabIndex
                    FilterChip(
                        selected = isActive,
                        onClick = {
                            manager.activeTabIndex = index
                            focusRequester.requestFocus()
                        },
                        label = { Text(tab.title, fontSize = 12.sp) },
                        trailingIcon = {
                            if (manager.tabs.size > 1) {
                                IconButton(
                                    onClick = { manager.closeTab(index) },
                                    modifier = Modifier.size(16.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Close Tab",
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                            }
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            containerColor = palette.containerHigh,
                            labelColor = palette.textDim
                        ),
                        modifier = Modifier.padding(end = 4.dp)
                    )
                }
            }

            // New Tab button
            IconButton(
                onClick = {
                    manager.createTab()
                    focusRequester.requestFocus()
                },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "New Tab", tint = MaterialTheme.colorScheme.primary)
            }

            // Split Mode toggle
            IconButton(
                onClick = {
                    manager.splitMode = when (manager.splitMode) {
                        SplitMode.SINGLE -> SplitMode.HORIZONTAL
                        SplitMode.HORIZONTAL -> SplitMode.VERTICAL
                        SplitMode.VERTICAL -> SplitMode.SINGLE
                    }
                },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.Splitscreen,
                    contentDescription = "Split Layout",
                    tint = if (manager.splitMode != SplitMode.SINGLE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Pop-up Floating Window
            val context = androidx.compose.ui.platform.LocalContext.current
            IconButton(
                onClick = {
                    org.openstick.ui.WindowActivity.launch(
                        context = context,
                        mode = org.openstick.ui.WindowActivity.MODE_TERMINAL,
                        title = "Linux Terminal"
                    )
                },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.PictureInPicture,
                    contentDescription = "Pop-up Window",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        HorizontalDivider(color = palette.border, thickness = 1.dp)

        // Terminal Interactive Viewport
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            when (manager.splitMode) {
                SplitMode.SINGLE -> {
                    manager.activeTab?.let { tab ->
                        InteractiveTerminalPane(
                            tab = tab,
                            fontSize = manager.fontSize,
                            focusRequester = focusRequester,
                            ctrlActive = manager.ctrlActive,
                            altActive = manager.altActive,
                            onConsumeCtrl = { manager.ctrlActive = false },
                            onConsumeAlt = { manager.altActive = false }
                        )
                    }
                }
                SplitMode.HORIZONTAL -> {
                    Row(modifier = Modifier.fillMaxSize()) {
                        val firstTab = manager.tabs.getOrNull(0)
                        val secondTab = manager.tabs.getOrNull(1) ?: firstTab

                        Box(modifier = Modifier.weight(1f)) {
                            firstTab?.let {
                                InteractiveTerminalPane(
                                    tab = it,
                                    fontSize = manager.fontSize,
                                    focusRequester = focusRequester,
                                    ctrlActive = manager.ctrlActive,
                                    altActive = manager.altActive,
                                    onConsumeCtrl = { manager.ctrlActive = false },
                                    onConsumeAlt = { manager.altActive = false }
                                )
                            }
                        }
                        Divider(modifier = Modifier.fillMaxHeight().width(1.dp), color = palette.border)
                        Box(modifier = Modifier.weight(1f)) {
                            secondTab?.let {
                                InteractiveTerminalPane(
                                    tab = it,
                                    fontSize = manager.fontSize,
                                    focusRequester = focusRequester,
                                    ctrlActive = manager.ctrlActive,
                                    altActive = manager.altActive,
                                    onConsumeCtrl = { manager.ctrlActive = false },
                                    onConsumeAlt = { manager.altActive = false }
                                )
                            }
                        }
                    }
                }
                SplitMode.VERTICAL -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        val firstTab = manager.tabs.getOrNull(0)
                        val secondTab = manager.tabs.getOrNull(1) ?: firstTab

                        Box(modifier = Modifier.weight(1f)) {
                            firstTab?.let {
                                InteractiveTerminalPane(
                                    tab = it,
                                    fontSize = manager.fontSize,
                                    focusRequester = focusRequester,
                                    ctrlActive = manager.ctrlActive,
                                    altActive = manager.altActive,
                                    onConsumeCtrl = { manager.ctrlActive = false },
                                    onConsumeAlt = { manager.altActive = false }
                                )
                            }
                        }
                        Divider(modifier = Modifier.fillMaxWidth().height(1.dp), color = palette.border)
                        Box(modifier = Modifier.weight(1f)) {
                            secondTab?.let {
                                InteractiveTerminalPane(
                                    tab = it,
                                    fontSize = manager.fontSize,
                                    focusRequester = focusRequester,
                                    ctrlActive = manager.ctrlActive,
                                    altActive = manager.altActive,
                                    onConsumeCtrl = { manager.ctrlActive = false },
                                    onConsumeAlt = { manager.altActive = false }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Mobile Quick-Key Toolbar (OpenStick Style)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.container)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Modifiers
            ModifierKeyButton(
                label = "CTRL",
                isActive = manager.ctrlActive,
                onClick = { manager.ctrlActive = !manager.ctrlActive }
            )
            ModifierKeyButton(
                label = "ALT",
                isActive = manager.altActive,
                onClick = { manager.altActive = !manager.altActive }
            )

            // Essential terminal keys
            QuickKeyButton("ESC") { manager.sendKey("ESC"); focusRequester.requestFocus() }
            QuickKeyButton("TAB") { manager.sendKey("TAB"); focusRequester.requestFocus() }

            // Common shell symbols
            QuickKeyButton("/") { manager.sendInput("/"); focusRequester.requestFocus() }
            QuickKeyButton("-") { manager.sendInput("-"); focusRequester.requestFocus() }
            QuickKeyButton("|") { manager.sendInput("|"); focusRequester.requestFocus() }
            QuickKeyButton("~") { manager.sendInput("~"); focusRequester.requestFocus() }

            // Cursor Navigation
            QuickKeyButton("↑") { manager.sendKey("UP"); focusRequester.requestFocus() }
            QuickKeyButton("↓") { manager.sendKey("DOWN"); focusRequester.requestFocus() }
            QuickKeyButton("←") { manager.sendKey("LEFT"); focusRequester.requestFocus() }
            QuickKeyButton("→") { manager.sendKey("RIGHT"); focusRequester.requestFocus() }

            // Shortcuts
            QuickKeyButton("CTRL+C") { manager.sendKey("CTRL+C"); focusRequester.requestFocus() }
            QuickKeyButton("CTRL+D") { manager.sendKey("CTRL+D"); focusRequester.requestFocus() }
            QuickKeyButton("CTRL+L") { manager.sendKey("CTRL+L"); focusRequester.requestFocus() }
        }
    }
}

@Composable
private fun ModifierKeyButton(
    label: String,
    isActive: Boolean,
    onClick: () -> Unit
) {
    val palette = LocalOpenStickPalette.current
    Button(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isActive) MaterialTheme.colorScheme.primary else palette.containerHigh,
            contentColor = if (isActive) MaterialTheme.colorScheme.onPrimary else palette.text
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (isActive) MaterialTheme.colorScheme.primary else palette.border),
        modifier = Modifier.height(32.dp)
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun QuickKeyButton(
    label: String,
    onClick: () -> Unit
) {
    val palette = LocalOpenStickPalette.current
    Button(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = palette.containerHigh,
            contentColor = palette.text
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, palette.border),
        modifier = Modifier.height(32.dp)
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
fun InteractiveTerminalPane(
    tab: TerminalTab,
    fontSize: Int,
    focusRequester: FocusRequester,
    ctrlActive: Boolean,
    altActive: Boolean,
    onConsumeCtrl: () -> Unit,
    onConsumeAlt: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val snapshot = tab.snapshot
    var inputFieldValue by remember { mutableStateOf(TextFieldValue("")) }
    val keyboardController = LocalSoftwareKeyboardController.current

    // Keep scrolled to bottom on new output if user was already at bottom
    LaunchedEffect(snapshot.screen.size, snapshot.scrollback.size) {
        val totalCount = snapshot.scrollback.size + snapshot.screen.size
        if (totalCount > 0) {
            listState.scrollToItem(totalCount - 1)
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                focusRequester.requestFocus()
                keyboardController?.show()
            }
    ) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val charWidthPx = with(density) { (fontSize * 0.62f).sp.toPx() }
        val charHeightPx = with(density) { (fontSize * 1.35f).sp.toPx() }

        // Automatically inform emulator of viewport column / row geometry
        LaunchedEffect(maxWidth, maxHeight) {
            val calcCols = (constraints.maxWidth / charWidthPx).toInt().coerceIn(20, 240)
            val calcRows = (constraints.maxHeight / charHeightPx).toInt().coerceIn(10, 100)
            tab.resize(calcCols, calcRows)
        }

        // Auto-scroll to keep active command line visible when typing or keyboard opens
        val totalLines = snapshot.scrollback.size + snapshot.screen.size
        LaunchedEffect(totalLines, snapshot.cursorRow, maxHeight) {
            if (totalLines > 0) {
                listState.scrollToItem(totalLines - 1)
            }
        }

        // Real ANSI Screen Rendering via LazyColumn
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 4.dp, vertical = 2.dp)
        ) {
            // Scrollback history
            items(snapshot.scrollback) { line ->
                Text(
                    text = line,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = fontSize.sp,
                        lineHeight = (fontSize * 1.35f).sp,
                        color = Color(0xFFE0E0E0)
                    )
                )
            }
            // Active screen lines
            items(snapshot.screen) { line ->
                Text(
                    text = line,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = fontSize.sp,
                        lineHeight = (fontSize * 1.35f).sp,
                        color = Color(0xFFE0E0E0)
                    )
                )
            }
        }

        // Invisible soft-keyboard input capture field
        BasicTextField(
            value = inputFieldValue,
            onValueChange = { newValue ->
                val oldText = inputFieldValue.text
                val newText = newValue.text
                val delta = if (newText.length > oldText.length && newText.startsWith(oldText)) {
                    newText.substring(oldText.length)
                } else if (newText == oldText) {
                    ""
                } else if (newText.length < oldText.length) {
                    val deletedCount = oldText.length - newText.length
                    repeat(deletedCount) {
                        tab.write(byteArrayOf(127)) // Backspace DEL
                    }
                    ""
                } else {
                    newText
                }

                if (delta.isNotEmpty()) {
                    if (delta == "\n" || delta == "\r\n") {
                        tab.write(byteArrayOf(13))
                    } else {
                        val mods = TerminalKeys.Mods(ctrl = ctrlActive, alt = altActive)
                        if (ctrlActive) onConsumeCtrl()
                        if (altActive) onConsumeAlt()
                        tab.write(TerminalKeys.text(delta, mods))
                    }
                }

                inputFieldValue = if (newText.length > 256) {
                    TextFieldValue("", TextRange.Zero)
                } else {
                    newValue.copy(selection = TextRange(newText.length))
                }
            },
            modifier = Modifier
                .size(1.dp)
                .alpha(0.01f)
                .focusRequester(focusRequester)
                .onKeyEvent { keyEvent ->
                    if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                        val bytes = TerminalKeys.toBytes(keyEvent.nativeKeyEvent, tab.emulator.applicationCursorKeys)
                        if (bytes != null) {
                            tab.write(bytes)
                            return@onKeyEvent true
                        }
                    }
                    false
                },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.None,
                autoCorrectEnabled = false
            ),
            keyboardActions = KeyboardActions(
                onDone = { tab.write(byteArrayOf(13)) },
                onGo = { tab.write(byteArrayOf(13)) },
                onSend = { tab.write(byteArrayOf(13)) }
            ),
            cursorBrush = SolidColor(Color.Transparent),
            textStyle = TextStyle(color = Color.Transparent)
        )
    }
}
