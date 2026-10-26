package org.openstick.apps

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import kotlinx.coroutines.launch
import org.openstick.engine.LinuxEnvironment
import org.openstick.engine.ProotRunner
import org.openstick.ui.WindowActivity
import org.openstick.ui.theme.*
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
    var selectedCategory by remember { mutableStateOf("All") }
    var searchQuery by remember { mutableStateOf("") }
    var isInstalling by remember { mutableStateOf(false) }
    var isRunningInstall by remember { mutableStateOf(false) }
    var activeAppName by remember { mutableStateOf("") }
    var installLog by remember { mutableStateOf("") }

    // SAF Picker for local .deb installation
    val debLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                isInstalling = true
                isRunningInstall = true
                activeAppName = "Local Package"
                installLog = "Copying package from device storage...\n"

                try {
                    val debFile = File(env.tmpDir, "local_package.deb")
                    context.contentResolver.openInputStream(it)?.use { input ->
                        FileOutputStream(debFile).use { output ->
                            input.copyTo(output)
                        }
                    }

                    installLog += "Installing local package with dpkg...\n"
                    val cmd = listOf(
                        "/bin/sh",
                        "-c",
                        "export DEBIAN_FRONTEND=noninteractive && dpkg -i /tmp/local_package.deb || apt-get -f install -y"
                    )
                    val exitCode = runner.executeOneShot(
                        command = cmd,
                        asRoot = true,
                        onOutput = { line -> installLog += "$line\n" }
                    )
                    isRunningInstall = false
                    if (exitCode == 0) {
                        installLog += "\n✓ Package installed successfully!"
                    } else {
                        installLog += "\n✗ Package installation finished with code $exitCode."
                    }
                } catch (e: Exception) {
                    isRunningInstall = false
                    installLog += "\nError: ${e.message}"
                }
            }
        }
    }

    val categories = listOf("All", "Web Browsers", "Development", "Office", "Graphics", "Media", "Utilities")

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AmoledBackground)
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
                color = AmoledGlassContainer,
                border = BorderStroke(1.dp, AmoledGlassBorder),
                modifier = Modifier.height(32.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(Icons.Default.UploadFile, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(15.dp))
                    Text("Install .deb", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Search Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search applications...", fontSize = 13.sp) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = AmoledGlassBorder,
                focusedContainerColor = AmoledGlassContainer,
                unfocusedContainerColor = AmoledGlassContainer
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
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                    )
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
                    "chromium" -> listOf("chromium", "chromium-browser")
                    "firefox" -> listOf("firefox-esr", "firefox")
                    "vscode" -> listOf("code-oss", "code")
                    "python" -> listOf("python3", "python")
                    "build_essential" -> listOf("gcc", "g++", "make")
                    else -> listOf(app.id, app.aptPackage.split(" ").first())
                }
                val isInstalled = binaryCandidates.any { bin ->
                    File(env.rootfsDir, "usr/bin/$bin").exists() ||
                    File(env.rootfsDir, "bin/$bin").exists() ||
                    File(env.rootfsDir, "usr/local/bin/$bin").exists()
                }

                AppItemCard(
                    app = app,
                    isInstalled = isInstalled,
                    onInstall = {
                        scope.launch {
                            isInstalling = true
                            isRunningInstall = true
                            activeAppName = app.name
                            installLog = "Starting installation for ${app.name}...\n"
                            val installScript = app.customInstallCmd ?: "export DEBIAN_FRONTEND=noninteractive && apt-get update && apt-get install -y --no-install-recommends ${app.aptPackage}"
                            val cmd = listOf(
                                "/bin/sh",
                                "-c",
                                installScript
                            )
                            val exitCode = runner.executeOneShot(
                                command = cmd,
                                asRoot = true,
                                onOutput = { line -> installLog += "$line\n" }
                            )
                            isRunningInstall = false
                            if (exitCode == 0) {
                                installLog += "\n✓ Installation complete! ${app.name} is installed."
                            } else {
                                installLog += "\n✗ Installation failed with code $exitCode."
                            }
                        }
                    },
                    onLaunch = {
                        val launchExec = app.customLaunchCmd ?: when (app.id) {
                            "chromium" -> "chromium --no-sandbox"
                            "firefox" -> "firefox-esr"
                            "vscode" -> "code-oss"
                            "python" -> "xterm -e python3"
                            else -> app.id
                        }
                        WindowActivity.launch(context, WindowActivity.MODE_APP, cmd = launchExec, title = app.name)
                    }
                )
            }
        }
    }

    // Installation Live Modal
    if (isInstalling) {
        AlertDialog(
            onDismissRequest = { if (!isRunningInstall) isInstalling = false },
            confirmButton = {
                TextButton(
                    onClick = { isInstalling = false },
                    enabled = !isRunningInstall
                ) {
                    Text(if (isRunningInstall) "Installing..." else "Close")
                }
            },
            title = {
                Text(
                    text = if (isRunningInstall) "Installing $activeAppName" else "$activeAppName Installation Complete",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (isRunningInstall) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.Black)
                    ) {
                        Text(
                            text = installLog,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = Color(0xFF00FF66),
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(10.dp)
                                .verticalScroll(rememberScrollState(Int.MAX_VALUE))
                        )
                    }
                }
            },
            containerColor = AmoledContainer
        )
    }
}

@Composable
private fun AppItemCard(
    app: LinuxApp,
    isInstalled: Boolean,
    onInstall: () -> Unit,
    onLaunch: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = AmoledGlassContainer,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, AmoledGlassBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(AmoledGlassHigh, shape = RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    getCategoryIcon(app.category),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(app.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Color.White)
                Spacer(modifier = Modifier.height(2.dp))
                Text(app.description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                Spacer(modifier = Modifier.height(4.dp))
                Text("package: ${app.aptPackage}", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary, fontFamily = FontFamily.Monospace)
            }

            Spacer(modifier = Modifier.width(8.dp))

            if (isInstalled) {
                FilledTonalButton(
                    onClick = onLaunch,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                        contentColor = MaterialTheme.colorScheme.primary
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Launch", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                Button(
                    onClick = onInstall,
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Install", fontSize = 12.sp)
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
