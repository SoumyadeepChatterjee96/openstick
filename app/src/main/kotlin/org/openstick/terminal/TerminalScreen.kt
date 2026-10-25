package org.openstick.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Splitscreen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openstick.ui.theme.AmoledBackground
import org.openstick.ui.theme.AmoledBorder
import org.openstick.ui.theme.AmoledContainer

@Composable
fun TerminalScreen(
    manager: TerminalSessionManager,
    modifier: Modifier = Modifier
) {
    var commandText by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AmoledBackground)
    ) {
        // Tab & Layout Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(AmoledContainer)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState())
            ) {
                manager.tabs.forEachIndexed { index, tab ->
                    val isActive = index == manager.activeTabIndex
                    FilterChip(
                        selected = isActive,
                        onClick = { manager.activeTabIndex = index },
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
                            containerColor = AmoledBackground,
                            labelColor = MaterialTheme.colorScheme.onSurface
                        ),
                        modifier = Modifier.padding(end = 4.dp)
                    )
                }
            }

            // New Tab button
            IconButton(
                onClick = { manager.createTab() },
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
        }

        HorizontalDivider(color = AmoledBorder, thickness = 1.dp)

        // Terminal Output Viewport
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            when (manager.splitMode) {
                SplitMode.SINGLE -> {
                    manager.activeTab?.let { tab ->
                        TerminalPane(tab = tab, fontSize = manager.fontSize)
                    }
                }
                SplitMode.HORIZONTAL -> {
                    Row(modifier = Modifier.fillMaxSize()) {
                        val firstTab = manager.tabs.getOrNull(0)
                        val secondTab = manager.tabs.getOrNull(1) ?: firstTab

                        Box(modifier = Modifier.weight(1f)) {
                            firstTab?.let { TerminalPane(tab = it, fontSize = manager.fontSize) }
                        }
                        Divider(modifier = Modifier.fillMaxHeight().width(1.dp), color = AmoledBorder)
                        Box(modifier = Modifier.weight(1f)) {
                            secondTab?.let { TerminalPane(tab = it, fontSize = manager.fontSize) }
                        }
                    }
                }
                SplitMode.VERTICAL -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        val firstTab = manager.tabs.getOrNull(0)
                        val secondTab = manager.tabs.getOrNull(1) ?: firstTab

                        Box(modifier = Modifier.weight(1f)) {
                            firstTab?.let { TerminalPane(tab = it, fontSize = manager.fontSize) }
                        }
                        Divider(modifier = Modifier.fillMaxWidth().height(1.dp), color = AmoledBorder)
                        Box(modifier = Modifier.weight(1f)) {
                            secondTab?.let { TerminalPane(tab = it, fontSize = manager.fontSize) }
                        }
                    }
                }
            }
        }

        // Mobile Shortcut Keys Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(AmoledContainer)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp, vertical = 2.dp)
        ) {
            val shortcuts = listOf(
                "ESC", "TAB", "CTRL+C", "CTRL+D", "CTRL+L",
                "|", "-", "/", "~", "^", "$",
                "UP", "DOWN", "LEFT", "RIGHT"
            )
            for (key in shortcuts) {
                Button(
                    onClick = { manager.sendKey(key) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF1E1E1E),
                        contentColor = Color(0xFFEDE9E4)
                    ),
                    modifier = Modifier
                        .height(30.dp)
                        .padding(horizontal = 2.dp)
                ) {
                    val label = when (key) {
                        "UP" -> "↑"
                        "DOWN" -> "↓"
                        "LEFT" -> "←"
                        "RIGHT" -> "→"
                        else -> key
                    }
                    Text(label, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }

        // Command Input Field
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(AmoledBackground)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = commandText,
                onValueChange = { commandText = it },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                placeholder = { Text("Command...", fontSize = 13.sp) },
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    if (commandText.isNotEmpty()) {
                        manager.sendInput("$commandText\n")
                        commandText = ""
                    }
                }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = AmoledBorder,
                    focusedTextColor = Color(0xFFEDE9E4),
                    unfocusedTextColor = Color(0xFFEDE9E4)
                )
            )

            Spacer(modifier = Modifier.width(6.dp))

            IconButton(
                onClick = {
                    if (commandText.isNotEmpty()) {
                        manager.sendInput("$commandText\n")
                        commandText = ""
                    }
                },
                modifier = Modifier.size(44.dp)
            ) {
                Icon(Icons.Default.Send, contentDescription = "Send", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
fun TerminalPane(
    tab: TerminalTab,
    fontSize: Int,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    LaunchedEffect(tab.outputText) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF000000))
            .padding(8.dp)
            .verticalScroll(scrollState)
    ) {
        Text(
            text = if (tab.outputText.isEmpty()) "OpenStick Linux Terminal ready.\nType a command or use shortcuts below.\n" else tab.outputText,
            color = Color(0xFF00FF66), // Retro matrix / terminal green on pure black
            fontFamily = FontFamily.Monospace,
            fontSize = fontSize.sp,
            lineHeight = (fontSize + 5).sp
        )
    }
}
