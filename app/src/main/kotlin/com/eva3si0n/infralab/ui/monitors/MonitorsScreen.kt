package com.eva3si0n.infralab.ui.monitors

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.eva3si0n.infralab.data.KumaHeartbeat
import com.eva3si0n.infralab.data.MonitorStatus
import com.eva3si0n.infralab.ui.AppViewModel
import kotlinx.coroutines.launch

private val UP = Color(0xFF5CDD8B)
private val DOWN = Color(0xFFDC3D46)
private val PENDING = Color(0xFFF8A532)
private val MAINT = Color(0xFF459BFF)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonitorsScreen(vm: AppViewModel) {
    val scope = rememberCoroutineScope()
    val expanded = remember { mutableStateMapOf<String, Boolean>() }

    Scaffold(topBar = { TopAppBar(title = { Text("Monitors") }) }) { padding ->
        Box(Modifier.padding(padding)) {
            when {
                vm.kumaBaseURL.isEmpty() || vm.kumaSlug.isEmpty() ->
                    EmptyState("Kuma Not Configured", "Add Uptime Kuma URL and slug in Settings")
                vm.monitors.isEmpty() && vm.monitorsLoading ->
                    Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                !vm.monitorsError.isNullOrEmpty() && vm.monitors.isEmpty() ->
                    EmptyState("Failed to Load", vm.monitorsError ?: "")
                else -> {
                    val grouped = remember(vm.monitors) {
                        val m = LinkedHashMap<String, MutableList<MonitorStatus>>()
                        vm.monitors.forEach { m.getOrPut(it.groupName) { mutableListOf() }.add(it) }
                        m
                    }
                    PullToRefreshBox(
                        isRefreshing = vm.monitorsLoading,
                        onRefresh = { scope.launch { vm.refreshMonitors() } }
                    ) {
                        LazyColumn(
                            contentPadding = PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            grouped.forEach { (name, list) ->
                                item(key = name) {
                                    GroupHeader(name, list, expanded[name] == true) {
                                        expanded[name] = !(expanded[name] ?: false)
                                    }
                                }
                                if (expanded[name] == true) {
                                    items(list, key = { it.id }) { MonitorCard(it) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(name: String, monitors: List<MonitorStatus>, isExpanded: Boolean, onTap: () -> Unit) {
    val up = monitors.count { it.isUp }
    val total = monitors.size
    val allUp = up == total
    val dot = if (allUp) UP else if (up == 0) DOWN else PENDING

    Column(
        Modifier.fillMaxWidth().clickable(onClick = onTap).padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.KeyboardArrowRight, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp).rotate(if (isExpanded) 90f else 0f)
            )
            Spacer(Modifier.width(4.dp))
            Canvas(Modifier.size(9.dp)) { drawCircle(dot) }
            Spacer(Modifier.width(8.dp))
            Text(name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(
                "$up/$total",
                style = MaterialTheme.typography.labelMedium,
                color = if (allUp) UP else PENDING
            )
        }
        val agg = remember(monitors) { aggregateBeats(monitors) }
        if (agg.isNotEmpty()) HeartbeatBar(agg, Modifier.fillMaxWidth().height(14.dp))
    }
}

@Composable
private fun MonitorCard(m: MonitorStatus) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(8.dp)) { drawCircle(if (m.isUp) UP else DOWN) }
                Spacer(Modifier.width(8.dp))
                Text(m.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                m.latency?.let {
                    Text("$it ms", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (m.recentBeats.isNotEmpty()) HeartbeatBar(m.recentBeats, Modifier.fillMaxWidth().height(22.dp))
            Text(
                "%.2f%% · 24h".format(m.uptime24h * 100),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun HeartbeatBar(beats: List<KumaHeartbeat>, modifier: Modifier) {
    Canvas(modifier) {
        if (beats.isEmpty()) return@Canvas
        val gap = 3f
        val bw = (size.width - gap * (beats.size - 1)) / beats.size
        beats.forEachIndexed { i, b ->
            val c = when (b.status) { 1 -> UP; 0 -> DOWN; 2 -> PENDING; 3 -> MAINT; else -> Color.Gray }
            drawRoundRect(
                color = c,
                topLeft = Offset(i * (bw + gap), 0f),
                size = Size(bw, size.height),
                cornerRadius = CornerRadius(2.5f, 2.5f)
            )
        }
    }
}

// Aggregate rollup: down if ANY child check was down at that time slot.
private fun aggregateBeats(monitors: List<MonitorStatus>): List<KumaHeartbeat> {
    val lists = monitors.map { it.recentBeats }.filter { it.isNotEmpty() }
    if (lists.isEmpty()) return emptyList()
    val n = lists.maxOf { it.size }
    val out = ArrayList<KumaHeartbeat>()
    for (d in 0 until n) {
        var present = false; var anyDown = false
        for (l in lists) if (l.size > d) { present = true; if (l[l.size - 1 - d].status == 0) anyDown = true }
        if (present) out += KumaHeartbeat(if (anyDown) 0 else 1, null, "")
    }
    return out.reversed()
}

@Composable
private fun EmptyState(title: String, desc: String) {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.Sensors, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
