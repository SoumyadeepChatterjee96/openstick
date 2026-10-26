package org.openstick.monitor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.openstick.engine.ProcessInfo
import org.openstick.engine.ResourceMonitor
import org.openstick.engine.SystemStats
import org.openstick.ui.theme.AmoledBackground
import org.openstick.ui.theme.AmoledContainer
import org.openstick.ui.theme.AmoledContainerHigh
import org.openstick.ui.theme.AmoledGlassBorder
import org.openstick.ui.theme.AmoledGlassContainer

@Composable
fun MonitorScreen(
    monitor: ResourceMonitor,
    modifier: Modifier = Modifier
) {
    var stats by remember { mutableStateOf<SystemStats?>(null) }
    val cpuHistory = remember { mutableStateListOf<Float>() }
    val ramHistory = remember { mutableStateOf(mutableListOf<Float>()) }
    var searchQuery by remember { mutableStateOf("") }
    var statusFeedback by remember { mutableStateOf("") }

    // Real-time polling loop
    LaunchedEffect(Unit) {
        while (isActive) {
            val sample = monitor.sample()
            stats = sample
            cpuHistory.add(sample.cpuPercent.toFloat())
            if (cpuHistory.size > 30) cpuHistory.removeAt(0)
            delay(1500)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AmoledBackground)
            .padding(16.dp)
    ) {
        // Compact Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "System Metrics",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0x331B5E20),
                    border = BorderStroke(1.dp, Color(0xFF00E676))
                ) {
                    Text(
                        text = "LIVE",
                        color = Color(0xFF00E676),
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            val context = androidx.compose.ui.platform.LocalContext.current
            Surface(
                onClick = {
                    org.openstick.ui.WindowActivity.launch(
                        context = context,
                        mode = org.openstick.ui.WindowActivity.MODE_MONITOR,
                        title = "Resource Monitor"
                    )
                },
                shape = RoundedCornerShape(12.dp),
                color = AmoledGlassContainer,
                border = BorderStroke(1.dp, AmoledGlassBorder),
                modifier = Modifier.height(30.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        Icons.Default.PictureInPicture,
                        contentDescription = "Pop-up Window",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                    Text("Pop-up", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        stats?.let { s ->
            // Metric Cards Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // CPU Card
                MetricCard(
                    title = "CPU Usage",
                    value = "${s.cpuPercent}%",
                    subtext = "${monitor.getCores()} Cores • ${if (s.tempCelsius > 0) "%.1f°C".format(s.tempCelsius) else "Normal"}",
                    color = MaterialTheme.colorScheme.primary,
                    history = cpuHistory,
                    modifier = Modifier.weight(1f)
                )

                // RAM Card
                val ramPercent = if (s.totalRamMb > 0) ((s.usedRamMb * 100) / s.totalRamMb).toInt() else 0
                MetricCard(
                    title = "RAM Memory",
                    value = "${s.usedRamMb} MB",
                    subtext = "$ramPercent% of ${s.totalRamMb} MB",
                    color = Color(0xFF00FF66),
                    history = cpuHistory,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Phantom Process & Storage Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val procCount = s.processes.size
                val isNearLimit = procCount >= 28

                Card(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(containerColor = AmoledGlassContainer),
                    border = BorderStroke(1.dp, AmoledGlassBorder),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Linux Processes", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "$procCount active",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isNearLimit) Color(0xFFFFB300) else Color.White
                        )
                        Text(
                            text = "Android limit: 32 max",
                            fontSize = 11.sp,
                            color = if (isNearLimit) Color(0xFFFFB300) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Card(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(containerColor = AmoledGlassContainer),
                    border = BorderStroke(1.dp, AmoledGlassBorder),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Disk Storage", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "%.1f GB free".format(s.freeStorageGb),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "%.1f GB total device".format(s.totalStorageGb),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (statusFeedback.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(statusFeedback, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Process Viewer Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Process Viewer",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search process...", fontSize = 12.sp) },
                    modifier = Modifier
                        .width(180.dp)
                        .height(48.dp),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = AmoledGlassBorder,
                        focusedContainerColor = AmoledGlassContainer,
                        unfocusedContainerColor = AmoledGlassContainer
                    ),
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary) }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Process List
            val filteredProcs = s.processes.filter {
                searchQuery.isEmpty() || it.name.contains(searchQuery, ignoreCase = true) || it.pid.toString().contains(searchQuery)
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                colors = CardDefaults.cardColors(containerColor = AmoledGlassContainer),
                border = BorderStroke(1.dp, AmoledGlassBorder),
                shape = RoundedCornerShape(18.dp)
            ) {
                if (filteredProcs.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No matching processes", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(8.dp)
                    ) {
                        items(filteredProcs, key = { it.pid }) { proc ->
                            ProcessRow(
                                proc = proc,
                                onKill = {
                                    val ok = monitor.killProcess(proc.pid)
                                    statusFeedback = if (ok) "Sent SIGKILL to PID ${proc.pid} (${proc.name})" else "Failed to kill PID ${proc.pid}"
                                }
                            )
                            HorizontalDivider(color = AmoledContainerHigh, thickness = 0.5.dp)
                        }
                    }
                }
            }
        } ?: run {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun MetricCard(
    title: String,
    value: String,
    subtext: String,
    color: Color,
    history: List<Float>,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.height(120.dp),
        colors = CardDefaults.cardColors(containerColor = AmoledGlassContainer),
        border = BorderStroke(1.dp, AmoledGlassBorder),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            Text(title, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(2.dp))
            Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text(subtext, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Spacer(modifier = Modifier.weight(1f))

            // Sparkline
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
            ) {
                if (history.size > 1) {
                    val maxVal = 100f
                    val stepX = size.width / (history.size - 1)
                    val path = Path()

                    history.forEachIndexed { i, v ->
                        val x = i * stepX
                        val y = size.height - (v / maxVal * size.height).coerceIn(0f, size.height)
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }

                    drawPath(
                        path = path,
                        color = color,
                        style = Stroke(width = 2.dp.toPx())
                    )
                }
            }
        }
    }
}

@Composable
private fun ProcessRow(
    proc: ProcessInfo,
    onKill: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = proc.name,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = Color.White
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "[${proc.state}]",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontFamily = FontFamily.Monospace
                )
            }
            Text(
                text = "PID: ${proc.pid} • RSS: ${proc.memoryKb / 1024} MB",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        IconButton(
            onClick = onKill,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Kill Process",
                tint = Color(0xFFFF5252),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
