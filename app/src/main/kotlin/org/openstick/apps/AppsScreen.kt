package org.openstick.apps

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import org.openstick.ui.theme.AmoledBackground
import org.openstick.ui.theme.AmoledContainer
import org.openstick.ui.theme.AmoledContainerHigh
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
    var activeAppName by remember { mutableStateOf("") }
    var installLog by remember { mutableStateOf("") }

    // SAF Picker for local .deb installation
    val debLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                isInstalling = true
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
                    runner.start(
                        command = cmd,
                        asRoot = true,
                        onOutput = { line -> installLog += "$line\n" }
                    )
                    installLog += "\nPackage installed successfully!"
                } catch (e: Exception) {
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
        // App Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "App Catalogue",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = "1-Click APT Linux Applications",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            OutlinedButton(
                onClick = { debLauncher.launch(arrayOf("application/vnd.debian.binary-package", "application/octet-stream", "*/*")) },
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Install .deb", fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Search Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search applications...") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }
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
                            activeAppName = app.name
                            installLog = "Starting APT installation for ${app.name}...\n"
                            val cmd = listOf(
                                "/bin/sh",
                                "-c",
                                "export DEBIAN_FRONTEND=noninteractive && apt-get update && apt-get install -y --no-install-recommends ${app.aptPackage}"
                            )
                            runner.start(
                                command = cmd,
                                asRoot = true,
                                onOutput = { line -> installLog += "$line\n" }
                            )
                            installLog += "\nDone! ${app.name} is installed."
                        }
                    },
                    onLaunch = {
                        val launchExec = when (app.id) {
                            "chromium" -> "chromium --no-sandbox"
                            "firefox" -> "firefox-esr"
                            "vscode" -> "code-oss"
                            "python" -> "xterm -e python3"
                            else -> app.id
                        }
                        scope.launch {
                            runner.start(
                                command = listOf(
                                    "/bin/sh",
                                    "-c",
                                    "export DISPLAY=:0; eval $(dbus-launch --sh-syntax 2>/dev/null); (exec $launchExec 2>&1 &)"
                                )
                            )
                        }
                    }
                )
            }
        }
    }

    // Installation Live Modal
    if (isInstalling) {
        AlertDialog(
            onDismissRequest = { isInstalling = false },
            confirmButton = {
                TextButton(onClick = { isInstalling = false }) {
                    Text("Close")
                }
            },
            title = {
                Text("Installing $activeAppName", fontWeight = FontWeight.Bold)
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AmoledContainer),
        shape = RoundedCornerShape(12.dp)
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
                    .background(AmoledContainerHigh, shape = RoundedCornerShape(10.dp)),
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
                Text("apt: ${app.aptPackage}", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary, fontFamily = FontFamily.Monospace)
            }

            Spacer(modifier = Modifier.width(8.dp))

            if (isInstalled) {
                FilledTonalButton(
                    onClick = onLaunch,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Color(0xFF1B5E20),
                        contentColor = Color.White
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Launch", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                Button(
                    onClick = onInstall,
                    shape = RoundedCornerShape(8.dp),
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
