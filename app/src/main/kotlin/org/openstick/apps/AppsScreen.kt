package org.openstick.apps

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openstick.engine.LinuxEnvironment
import org.openstick.engine.ProotRunner
import org.openstick.ui.WindowActivity
import org.openstick.ui.theme.LocalOpenStickPalette
import java.io.File
import java.io.FileOutputStream

@Composable
fun AppsScreen(
    env: LinuxEnvironment,
    runner: ProotRunner,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val palette = LocalOpenStickPalette.current

    var selectedCategory by remember { mutableStateOf("All") }
    var searchQuery by remember { mutableStateOf("") }

    // Action Modal State (Install / Update / Remove / Service Output)
    var isActionModalOpen by remember { mutableStateOf(false) }
    var isActionRunning by remember { mutableStateOf(false) }
    var actionTitle by remember { mutableStateOf("") }
    var actionLog by remember { mutableStateOf("") }

    // Track active running services
    var runningServices by remember { mutableStateOf(setOf<String>()) }
    var refreshTrigger by remember { mutableIntStateOf(0) }

    // SAF Picker for local .deb installation
    val debLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                isActionModalOpen = true
                isActionRunning = true
                actionTitle = "Installing Local Package"
                actionLog = "Copying package from device storage...\n"

                try {
                    val debFile = File(env.tmpDir, "local_package.deb")
                    context.contentResolver.openInputStream(it)?.use { input ->
                        FileOutputStream(debFile).use { output ->
                            input.copyTo(output)
                        }
                    }

                    actionLog += "Installing package with dpkg...\n"
                    val cmd = listOf(
                        "/bin/sh",
                        "-c",
                        "export DEBIAN_FRONTEND=noninteractive && dpkg -i /tmp/local_package.deb || apt-get -f install -y"
                    )
                    val exitCode = runner.executeOneShot(
                        command = cmd,
                        asRoot = true,
                        onOutput = { line -> actionLog += "$line\n" }
                    )
                    isActionRunning = false
                    if (exitCode == 0) {
                        actionLog += "\n✓ Local package installed successfully!"
                        refreshTrigger++
                    } else {
                        actionLog += "\n✗ Package installation finished with code $exitCode."
                    }
                } catch (e: Exception) {
                    isActionRunning = false
                    actionLog += "\nError: ${e.message}"
                }
            }
        }
    }

    // Live service detection loop
    LaunchedEffect(refreshTrigger) {
        withContext(Dispatchers.IO) {
            val active = mutableSetOf<String>()
            for (app in AppCatalogue.apps) {
                if (app.isService) {
                    val checkCmd = app.checkRunningCmd ?: "pgrep -f '${app.id}'"
                    val exit = runner.executeOneShot(listOf("/bin/sh", "-c", "$checkCmd >/dev/null 2>&1"), asRoot = true)
                    if (exit == 0) active.add(app.id)
                }
            }
            runningServices = active
        }
    }

    val categories = listOf("All", "Development", "Web Browsers", "Media", "Office", "Graphics", "Utilities")

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(palette.background)
            .padding(16.dp)
    ) {
        // Compact Top Action Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "1-Click App Catalogue",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Surface(
                onClick = { debLauncher.launch(arrayOf("application/vnd.debian.binary-package", "application/octet-stream", "*/*")) },
                shape = RoundedCornerShape(14.dp),
                color = palette.glassContainer,
                border = BorderStroke(1.dp, palette.glassBorder),
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(14.dp))
                    Text("Install .deb", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Search Input
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search 1-click apps, servers, containers...", fontSize = 12.sp, color = palette.textDim) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = palette.glassBorder,
                focusedContainerColor = palette.glassContainer,
                unfocusedContainerColor = palette.glassContainer,
                focusedTextColor = palette.text,
                unfocusedTextColor = palette.text
            ),
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Category Filter Chips
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(categories) { cat ->
                FilterChip(
                    selected = selectedCategory == cat,
                    onClick = { selectedCategory = cat },
                    label = { Text(cat, fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        containerColor = palette.glassContainer,
                        labelColor = palette.text
                    ),
                    border = BorderStroke(1.dp, if (selectedCategory == cat) MaterialTheme.colorScheme.primary else palette.glassBorder)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Apps List
        val filteredApps = AppCatalogue.apps.filter { app ->
            (selectedCategory == "All" || app.category == selectedCategory) &&
                    (searchQuery.isEmpty() || app.name.contains(searchQuery, ignoreCase = true) || app.description.contains(searchQuery, ignoreCase = true))
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filteredApps, key = { it.id }) { app ->
                val binaryCandidates = when (app.id) {
                    "docker" -> listOf("podman", "docker")
                    "n8n" -> listOf("n8n")
                    "nodered" -> listOf("node-red")
                    "codeserver" -> listOf("code-server")
                    "ollama" -> listOf("ollama")
                    "jellyfin" -> listOf("jellyfin")
                    "postgres" -> listOf("psql", "postgres")
                    "redis" -> listOf("redis-server", "redis-cli")
                    "transmission" -> listOf("transmission-daemon", "transmission-remote")
                    "rust" -> listOf("rustc", "cargo")
                    "chromium" -> listOf("chromium", "chromium-browser")
                    "firefox" -> listOf("firefox-esr", "firefox")
                    "vscode_oss" -> listOf("code-oss", "code")
                    "python" -> listOf("python3", "python")
                    "nodejs_dev" -> listOf("node", "npm")
                    "build_essential" -> listOf("gcc", "g++", "make")
                    else -> listOf(app.id, app.aptPackage.split(" ").first())
                }
                val isInstalled = binaryCandidates.any { bin ->
                    File(env.rootfsDir, "usr/bin/$bin").exists() ||
                    File(env.rootfsDir, "bin/$bin").exists() ||
                    File(env.rootfsDir, "usr/local/bin/$bin").exists() ||
                    File(env.rootfsDir, "usr/lib/postgresql").exists() && app.id == "postgres"
                }

                val isServiceRunning = runningServices.contains(app.id)

                AppItemCard(
                    app = app,
                    isInstalled = isInstalled,
                    isServiceRunning = isServiceRunning,
                    onInstall = {
                        scope.launch {
                            isActionModalOpen = true
                            isActionRunning = true
                            actionTitle = "Installing ${app.name}"
                            actionLog = "Starting installation for ${app.name}...\n"
                            val installScript = app.customInstallCmd ?: "export DEBIAN_FRONTEND=noninteractive && apt-get update && apt-get install -y --no-install-recommends ${app.aptPackage}"
                            val exitCode = runner.executeOneShot(
                                command = listOf("/bin/sh", "-c", installScript),
                                asRoot = true,
                                onOutput = { line -> actionLog += "$line\n" }
                            )
                            isActionRunning = false
                            if (exitCode == 0) {
                                actionLog += "\n✓ Installation complete! ${app.name} is ready."
                                refreshTrigger++
                            } else {
                                actionLog += "\n✗ Installation failed with code $exitCode."
                            }
                        }
                    },
                    onLaunch = {
                        val launchExec = app.customLaunchCmd ?: when (app.id) {
                            "chromium" -> "chromium --no-sandbox"
                            "firefox" -> "firefox-esr"
                            "vscode_oss" -> "code-oss"
                            "python" -> "xterm -e python3"
                            else -> app.id
                        }
                        WindowActivity.launch(context, WindowActivity.MODE_APP, cmd = launchExec, title = app.name)
                    },
                    onStartService = {
                        scope.launch {
                            val startCmd = app.startServiceCmd ?: "${app.id} &"
                            runner.executeOneShot(listOf("/bin/sh", "-c", startCmd), asRoot = true)
                            delay(1000)
                            refreshTrigger++
                        }
                    },
                    onStopService = {
                        scope.launch {
                            val stopCmd = app.stopServiceCmd ?: "pkill -f '${app.id}'"
                            runner.executeOneShot(listOf("/bin/sh", "-c", stopCmd), asRoot = true)
                            delay(800)
                            refreshTrigger++
                        }
                    },
                    onOpenWebUrl = {
                        app.webUrl?.let { url ->
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            try { context.startActivity(intent) } catch (_: Exception) {}
                        }
                    },
                    onUpdate = {
                        scope.launch {
                            isActionModalOpen = true
                            isActionRunning = true
                            actionTitle = "Updating ${app.name}"
                            actionLog = "Checking for updates and upgrading ${app.name}...\n"
                            val updateScript = app.customUpdateCmd ?: "export DEBIAN_FRONTEND=noninteractive && apt-get update && apt-get --only-upgrade install -y ${app.aptPackage}"
                            val exitCode = runner.executeOneShot(
                                command = listOf("/bin/sh", "-c", updateScript),
                                asRoot = true,
                                onOutput = { line -> actionLog += "$line\n" }
                            )
                            isActionRunning = false
                            if (exitCode == 0) {
                                actionLog += "\n✓ Update completed successfully!"
                                refreshTrigger++
                            } else {
                                actionLog += "\n✗ Update finished with code $exitCode."
                            }
                        }
                    },
                    onRemove = {
                        scope.launch {
                            isActionModalOpen = true
                            isActionRunning = true
                            actionTitle = "Uninstalling ${app.name}"
                            actionLog = "Stopping any running instances and removing ${app.name}...\n"
                            if (app.isService && app.stopServiceCmd != null) {
                                runner.executeOneShot(listOf("/bin/sh", "-c", app.stopServiceCmd), asRoot = true)
                            }
                            val removeScript = app.customRemoveCmd ?: "export DEBIAN_FRONTEND=noninteractive && apt-get purge -y ${app.aptPackage} && apt-get autoremove -y"
                            val exitCode = runner.executeOneShot(
                                command = listOf("/bin/sh", "-c", removeScript),
                                asRoot = true,
                                onOutput = { line -> actionLog += "$line\n" }
                            )
                            isActionRunning = false
                            if (exitCode == 0) {
                                actionLog += "\n✓ ${app.name} removed successfully."
                                refreshTrigger++
                            } else {
                                actionLog += "\n✗ Removal finished with code $exitCode."
                            }
                        }
                    }
                )
            }
        }
    }

    // Action Live Modal (Install / Update / Remove)
    if (isActionModalOpen) {
        AlertDialog(
            onDismissRequest = { if (!isActionRunning) isActionModalOpen = false },
            confirmButton = {
                TextButton(
                    onClick = { isActionModalOpen = false },
                    enabled = !isActionRunning
                ) {
                    Text(if (isActionRunning) "Working..." else "Close")
                }
            },
            title = {
                Text(
                    text = actionTitle,
                    fontWeight = FontWeight.Bold,
                    color = palette.text
                )
            },
            text = {
                val logScrollState = rememberScrollState()
                LaunchedEffect(actionLog) {
                    if (logScrollState.maxValue > 0) {
                        logScrollState.animateScrollTo(logScrollState.maxValue)
                    }
                }
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (isActionRunning) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp),
                        colors = CardDefaults.cardColors(containerColor = palette.surface),
                        border = BorderStroke(1.dp, palette.border)
                    ) {
                        Text(
                            text = actionLog,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = Color(0xFF00FF66),
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(10.dp)
                                .verticalScroll(logScrollState)
                        )
                    }
                }
            },
            containerColor = palette.glassHigh
        )
    }
}

@Composable
private fun AppItemCard(
    app: LinuxApp,
    isInstalled: Boolean,
    isServiceRunning: Boolean,
    onInstall: () -> Unit,
    onLaunch: () -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onOpenWebUrl: () -> Unit,
    onUpdate: () -> Unit,
    onRemove: () -> Unit
) {
    val palette = LocalOpenStickPalette.current
    var showMenu by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = palette.glassContainer,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, palette.glassBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .background(palette.glassHigh, shape = RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        getCategoryIcon(app.category),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(app.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = palette.text)
                        if (isInstalled && app.isService) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isServiceRunning) Color(0xFF00E676).copy(alpha = 0.18f) else palette.glassHigh,
                                border = BorderStroke(0.5.dp, if (isServiceRunning) Color(0xFF00E676) else palette.glassBorder)
                            ) {
                                Text(
                                    text = if (isServiceRunning) "LIVE :${app.servicePort ?: "ON"}" else "STOPPED",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isServiceRunning) Color(0xFF00E676) else palette.textDim,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(app.description, fontSize = 11.sp, color = palette.textDim, maxLines = 2)
                }

                // 3-dots overflow menu for installed app lifecycle
                if (isInstalled) {
                    Box {
                        IconButton(
                            onClick = { showMenu = true },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.MoreVert, contentDescription = "App Options", tint = palette.textDim, modifier = Modifier.size(18.dp))
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            modifier = Modifier.background(palette.glassHigh).border(1.dp, palette.glassBorder, RoundedCornerShape(8.dp))
                        ) {
                            DropdownMenuItem(
                                text = { Text("Update App", fontSize = 12.sp, color = palette.text) },
                                leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp)) },
                                onClick = {
                                    showMenu = false
                                    onUpdate()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Uninstall / Remove", fontSize = 12.sp, color = Color(0xFFFF5252)) },
                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFFF5252), modifier = Modifier.size(16.dp)) },
                                onClick = {
                                    showMenu = false
                                    onRemove()
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Action Buttons Strip
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isInstalled) {
                    // Update Chip
                    OutlinedButton(
                        onClick = onUpdate,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(0.8.dp, palette.glassBorder),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(12.dp), tint = palette.textDim)
                        Spacer(modifier = Modifier.width(3.dp))
                        Text("Update", fontSize = 10.sp, color = palette.textDim)
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // Remove Chip
                    OutlinedButton(
                        onClick = onRemove,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(0.8.dp, Color(0xFFFF5252).copy(alpha = 0.4f)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(12.dp), tint = Color(0xFFFF5252))
                        Spacer(modifier = Modifier.width(3.dp))
                        Text("Remove", fontSize = 10.sp, color = Color(0xFFFF5252))
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    if (app.isService) {
                        if (isServiceRunning) {
                            if (app.webUrl != null) {
                                FilledTonalButton(
                                    onClick = onOpenWebUrl,
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = Color(0xFF00E676).copy(alpha = 0.18f),
                                        contentColor = Color(0xFF00E676)
                                    ),
                                    border = BorderStroke(1.dp, Color(0xFF00E676).copy(alpha = 0.5f)),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.height(30.dp)
                                ) {
                                    Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(12.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Open UI", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            Button(
                                onClick = onStopService,
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB71C1C)),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(30.dp)
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(12.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Stop", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        } else {
                            Button(
                                onClick = onStartService,
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(30.dp)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(12.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Start Server", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    } else {
                        Button(
                            onClick = onLaunch,
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(12.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Launch", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                } else {
                    Button(
                        onClick = onInstall,
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(12.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Install", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

private fun getCategoryIcon(cat: String): ImageVector {
    return when (cat) {
        "Web Browsers" -> Icons.Default.Language
        "Development" -> Icons.Default.Code
        "Office" -> Icons.Default.Description
        "Graphics" -> Icons.Default.Brush
        "Media" -> Icons.Default.PlayCircle
        else -> Icons.Default.Apps
    }
}
