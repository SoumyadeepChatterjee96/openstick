package org.openstick.monitor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
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
import org.openstick.ui.theme.LocalOpenStickPalette

enum class MonitorTab(val title: String) {
    OVERVIEW("Overview"),
    CORES("CPU Cores"),
    BATTERY("Battery"),
    PROCESSES("Processes")
}

@Composable
fun DetailedFloatingMonitorView(
    monitor: ResourceMonitor,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalOpenStickPalette.current
    var stats by remember { mutableStateOf<SystemStats?>(null) }
    var selectedTab by remember { mutableStateOf(MonitorTab.OVERVIEW) }
    var processSearch by remember { mutableStateOf("") }

    // Rolling History buffers (up to 30 points)
    val cpuHistory = remember { mutableStateListOf<Float>() }
    val ramHistory = remember { mutableStateListOf<Float>() }
    val batteryHistory = remember { mutableStateListOf<Float>() }

    LaunchedEffect(Unit) {
        while (isActive) {
            val sample = monitor.sample()
            stats = sample

            // Update CPU history
            cpuHistory.add(sample.cpuPercent.toFloat())
            if (cpuHistory.size > 30) cpuHistory.removeAt(0)

            // Update RAM history (percentage used)
            val ramPct = if (sample.totalRamMb > 0) (sample.usedRamMb.toFloat() / sample.totalRamMb * 100f) else 0f
            ramHistory.add(ramPct)
            if (ramHistory.size > 30) ramHistory.removeAt(0)

            // Update Battery drain history (in mA)
            batteryHistory.add(sample.battery.currentMa.toFloat())
            if (batteryHistory.size > 30) batteryHistory.removeAt(0)

            delay(1000)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(palette.background)
            .padding(10.dp)
    ) {
        // Tab Navigation Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(palette.glassContainer)
                .padding(4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (tab in MonitorTab.values()) {
                val isSelected = selectedTab == tab
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f) else Color.Transparent)
                        .clickable { selectedTab = tab }
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = tab.title,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else palette.textDim
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Content Area
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (selectedTab) {
                MonitorTab.OVERVIEW -> OverviewTabContent(
                    stats = stats,
                    cpuHistory = cpuHistory,
                    ramHistory = ramHistory,
                    batteryHistory = batteryHistory
                )
                MonitorTab.CORES -> CoresTabContent(stats = stats)
                MonitorTab.BATTERY -> BatteryTabContent(stats = stats)
                MonitorTab.PROCESSES -> ProcessesTabContent(
                    stats = stats,
                    search = processSearch,
                    onSearchChange = { processSearch = it },
                    onKillProcess = { pid -> monitor.killProcess(pid) }
                )
            }
        }
    }
}

@Composable
private fun OverviewTabContent(
    stats: SystemStats?,
    cpuHistory: List<Float>,
    ramHistory: List<Float>,
    batteryHistory: List<Float>
) {
    val palette = LocalOpenStickPalette.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. CPU Load Line Chart
        item {
            ChartCard(
                title = "Total CPU Utilization",
                valueText = "${stats?.cpuPercent ?: 0}%",
                subText = "${stats?.tempCelsius ?: 0f}°C SoC Temp",
                accentColor = MaterialTheme.colorScheme.primary
            ) {
                LineChart(
                    history = cpuHistory,
                    color = MaterialTheme.colorScheme.primary,
                    minVal = 0f,
                    maxVal = 100f,
                    unit = "%"
                )
            }
        }

        // 2. RAM Memory Line Chart
        item {
            val usedGb = String.format("%.2f", (stats?.usedRamMb ?: 0) / 1024f)
            val totalGb = String.format("%.2f", (stats?.totalRamMb ?: 0) / 1024f)
            val freeGb = String.format("%.2f", (stats?.freeRamMb ?: 0) / 1024f)
            ChartCard(
                title = "RAM Memory Allocation",
                valueText = "$usedGb / $totalGb GB",
                subText = "$freeGb GB Available",
                accentColor = Color(0xFFB388FF)
            ) {
                LineChart(
                    history = ramHistory,
                    color = Color(0xFFB388FF),
                    minVal = 0f,
                    maxVal = 100f,
                    unit = "%"
                )
            }
        }

        // 3. Active Battery Drain Line Chart
        item {
            val currentMa = stats?.battery?.currentMa ?: 0
            val voltage = (stats?.battery?.voltageMv ?: 0) / 1000f
            val powerWatts = String.format("%.2f", kotlin.math.abs(currentMa * voltage) / 1000f)
            ChartCard(
                title = "Battery Drain Rate",
                valueText = "${currentMa} mA",
                subText = "$powerWatts W Power • ${stats?.battery?.voltageMv ?: 0} mV",
                accentColor = if (currentMa > 0) Color(0xFF00E676) else Color(0xFFFFB300)
            ) {
                val minDrain = (batteryHistory.minOrNull() ?: -1000f).coerceAtMost(0f)
                val maxDrain = (batteryHistory.maxOrNull() ?: 1000f).coerceAtLeast(100f)
                LineChart(
                    history = batteryHistory,
                    color = if (currentMa > 0) Color(0xFF00E676) else Color(0xFFFFB300),
                    minVal = minDrain,
                    maxVal = maxDrain,
                    unit = "mA"
                )
            }
        }
    }
}

@Composable
private fun CoresTabContent(stats: SystemStats?) {
    val palette = LocalOpenStickPalette.current
    val cores = stats?.corePercents ?: emptyList()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = palette.glassContainer,
                border = BorderStroke(1.dp, palette.glassBorder)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Per-Core CPU Distribution", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = palette.text)
                        Text("${cores.size} Active Processing Cores", fontSize = 11.sp, color = palette.textDim)
                    }
                    Text(
                        "${stats?.cpuPercent ?: 0}% Avg",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        items(cores.indices.toList()) { index ->
            val pct = cores[index]
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = palette.glassContainer,
                border = BorderStroke(1.dp, palette.glassBorder)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Core $index",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        color = palette.text,
                        modifier = Modifier.width(64.dp)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    LinearProgressIndicator(
                        progress = { pct / 100f },
                        modifier = Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)),
                        color = when {
                            pct > 80 -> Color(0xFFFF5252)
                            pct > 50 -> Color(0xFFFFB300)
                            else -> MaterialTheme.colorScheme.primary
                        },
                        trackColor = palette.glassHigh
                    )

                    Spacer(modifier = Modifier.width(10.dp))

                    Text(
                        text = "$pct%",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = palette.text,
                        modifier = Modifier.width(36.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun BatteryTabContent(stats: SystemStats?) {
    val palette = LocalOpenStickPalette.current
    val b = stats?.battery ?: return

    val currentMa = b.currentMa
    val voltage = b.voltageMv / 1000f
    val powerWatts = String.format("%.2f", kotlin.math.abs(currentMa * voltage) / 1000f)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = palette.glassContainer,
                border = BorderStroke(1.dp, palette.glassBorder)
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(
                                if (b.isCharging) Icons.Default.BatteryChargingFull else Icons.Default.BatteryStd,
                                contentDescription = null,
                                tint = if (b.isCharging) Color(0xFF00E676) else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Column {
                                Text("Battery Health & Power", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = palette.text)
                                Text(b.statusString, fontSize = 11.sp, color = palette.textDim)
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
                        ) {
                            Text(
                                "${b.levelPercent}%",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricTile(label = "Active Current", value = "${b.currentMa} mA", modifier = Modifier.weight(1f))
                        MetricTile(label = "Power Draw", value = "$powerWatts W", modifier = Modifier.weight(1f))
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricTile(label = "Voltage", value = "${b.voltageMv} mV", modifier = Modifier.weight(1f))
                        MetricTile(label = "Battery Temp", value = "${b.tempCelsius}°C", modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun ProcessesTabContent(
    stats: SystemStats?,
    search: String,
    onSearchChange: (String) -> Unit,
    onKillProcess: (Int) -> Unit
) {
    val palette = LocalOpenStickPalette.current
    val allProcs = stats?.processes ?: emptyList()
    val filteredProcs = remember(allProcs, search) {
        if (search.isBlank()) allProcs else allProcs.filter { it.name.contains(search, ignoreCase = true) || it.pid.toString().contains(search) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Search Input
        OutlinedTextField(
            value = search,
            onValueChange = onSearchChange,
            placeholder = { Text("Filter processes by name or PID...", fontSize = 12.sp, color = palette.textDim) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = palette.textDim, modifier = Modifier.size(16.dp)) },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = palette.glassBorder,
                focusedContainerColor = palette.glassContainer,
                unfocusedContainerColor = palette.glassContainer,
                focusedTextColor = palette.text,
                unfocusedTextColor = palette.text
            ),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Table Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("PID / PROCESS", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = palette.textDim, modifier = Modifier.weight(1f))
            Text("STATE", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = palette.textDim, modifier = Modifier.width(44.dp))
            Text("RAM", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = palette.textDim, modifier = Modifier.width(55.dp))
            Text("ACTION", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = palette.textDim, modifier = Modifier.width(44.dp))
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(filteredProcs, key = { it.pid }) { proc ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = palette.glassContainer,
                    border = BorderStroke(0.5.dp, palette.glassBorder)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(proc.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = palette.text, maxLines = 1)
                            Text("PID: ${proc.pid}", fontSize = 10.sp, color = palette.textDim, fontFamily = FontFamily.Monospace)
                        }

                        Text(
                            text = proc.state,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (proc.state == "R") Color(0xFF00E676) else palette.textDim,
                            modifier = Modifier.width(44.dp)
                        )

                        val mb = proc.memoryKb / 1024
                        Text(
                            text = if (mb > 0) "${mb}M" else "${proc.memoryKb}K",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = palette.text,
                            modifier = Modifier.width(55.dp)
                        )

                        IconButton(
                            onClick = { onKillProcess(proc.pid) },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Kill Process",
                                tint = Color(0xFFFF5252),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricTile(label: String, value: String, modifier: Modifier = Modifier) {
    val palette = LocalOpenStickPalette.current
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = palette.glassHigh,
        border = BorderStroke(0.5.dp, palette.glassBorder)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(label, fontSize = 10.sp, color = palette.textDim)
            Text(value, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = palette.text)
        }
    }
}

@Composable
private fun ChartCard(
    title: String,
    valueText: String,
    subText: String,
    accentColor: Color,
    content: @Composable () -> Unit
) {
    val palette = LocalOpenStickPalette.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = palette.glassContainer,
        border = BorderStroke(1.dp, palette.glassBorder)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = palette.text)
                    Text(subText, fontSize = 10.sp, color = palette.textDim)
                }
                Text(
                    valueText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = accentColor,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Box(modifier = Modifier.fillMaxWidth().height(90.dp)) {
                content()
            }
        }
    }
}

@Composable
fun LineChart(
    history: List<Float>,
    color: Color,
    minVal: Float,
    maxVal: Float,
    unit: String,
    modifier: Modifier = Modifier.fillMaxSize()
) {
    val palette = LocalOpenStickPalette.current
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        if (history.isEmpty()) return@Canvas

        // Draw horizontal grid lines (25%, 50%, 75%)
        val gridColor = palette.glassBorder
        for (i in 1..3) {
            val y = h * (i / 4f)
            drawLine(
                color = gridColor,
                start = Offset(0f, y),
                end = Offset(w, y),
                strokeWidth = 1f
            )
        }

        val range = (maxVal - minVal).coerceAtLeast(1f)
        val stepX = if (history.size > 1) w / (history.size - 1) else w

        val path = Path()
        val fillPath = Path()

        history.forEachIndexed { i, value ->
            val clamped = value.coerceIn(minVal, maxVal)
            val normalizedY = 1f - ((clamped - minVal) / range)
            val x = i * stepX
            val y = normalizedY * h

            if (i == 0) {
                path.moveTo(x, y)
                fillPath.moveTo(x, h)
                fillPath.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }

        if (history.isNotEmpty()) {
            val lastX = (history.size - 1) * stepX
            fillPath.lineTo(lastX, h)
            fillPath.close()

            // Draw gradient background under line
            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(color.copy(alpha = 0.35f), Color.Transparent),
                    startY = 0f,
                    endY = h
                )
            )

            // Draw line
            drawPath(
                path = path,
                color = color,
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
            )

            // Draw current point indicator
            val lastVal = history.last().coerceIn(minVal, maxVal)
            val lastY = (1f - ((lastVal - minVal) / range)) * h
            drawCircle(
                color = Color.White,
                radius = 4.dp.toPx(),
                center = Offset(lastX, lastY)
            )
            drawCircle(
                color = color,
                radius = 2.5.dp.toPx(),
                center = Offset(lastX, lastY)
            )
        }
    }
}
