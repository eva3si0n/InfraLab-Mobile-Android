package com.eva3si0n.infralab.ui.cascade

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eva3si0n.infralab.ui.AppViewModel
import kotlinx.coroutines.launch

// VPN Cascade — богатая панель egress-каскада. Данные из Prometheus через Grafana
// datasource-прокси (vm.promInstant): активное плечо + сколько держится, RTT до плеч
// (с узла и из дома), throughput WG, месячный трафик Vultr STO/AMS против лимита 2 ТБ.
private val STO = Color(0xFF5CDD8B)
private val AMS = Color(0xFFF8A532)
private val FI = Color(0xFFDC3D46)
private fun legColor(l: String) = when (l) { "sto" -> STO; "ams" -> AMS; "fi" -> FI; else -> Color.Gray }

private data class Seg(
    val host: String, val label: String, val activeLeg: String,
    val activeSeconds: Double, val rtt: Map<String, Double>,
    val txBps: Double?, val rxBps: Double?
)
private data class Leg(
    val leg: String, val homeRtt: Double?, val txBytes: Double?, val limitBytes: Double?
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CascadeScreen(vm: AppViewModel) {
    var segments by remember { mutableStateOf<List<Seg>>(emptyList()) }
    var legs by remember { mutableStateOf<List<Leg>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun load() {
        if (vm.grafanaBaseURL.isEmpty()) return
        loading = true
        try {
            val active = vm.promInstant("vpn_egress_active_leg == 1", "")
            val durQ = vm.promInstant("vpn_egress_active_seconds", "")
            val rtt = vm.promInstant("vpn_leg_rtt_ms", "")
            val txbps = vm.promInstant("sum by (host) (rate(wireguard_sent_bytes[5m]))", "")
            val rxbps = vm.promInstant("sum by (host) (rate(wireguard_received_bytes[5m]))", "")
            val home = vm.promInstant("home_node_rtt_ms", "")
            val tx = vm.promInstant("vds_month_tx_bytes", "")
            val lim = vm.promInstant("vds_month_limit_bytes", "")
            segments = listOf("node-a" to "Проводной", "node-b" to "Мобильный").map { (h, label) ->
                val al = active.firstOrNull { it.labels["host"] == h }?.labels?.get("leg") ?: "—"
                val ds = durQ.firstOrNull { it.labels["host"] == h }?.value ?: 0.0
                val rm = rtt.filter { it.labels["host"] == h }
                    .mapNotNull { r -> r.labels["leg"]?.let { it to r.value } }.toMap()
                Seg(h, label, al, ds, rm,
                    txbps.firstOrNull { it.labels["host"] == h }?.value,
                    rxbps.firstOrNull { it.labels["host"] == h }?.value)
            }
            legs = listOf("sto", "ams", "fi").map { l ->
                val host = when (l) { "sto" -> "egress-a"; "ams" -> "egress-b"; else -> "" }
                Leg(l,
                    home.firstOrNull { it.labels["node"] == l }?.value,
                    if (host.isEmpty()) null else tx.firstOrNull { it.labels["host"] == host }?.value,
                    if (host.isEmpty()) null else lim.firstOrNull { it.labels["host"] == host }?.value)
            }
            error = null
        } catch (e: Exception) {
            error = e.message ?: "error"
        } finally {
            loading = false
        }
    }

    LaunchedEffect(Unit) { load() }

    Scaffold(topBar = { TopAppBar(title = { Text("VPN Cascade") }) }) { padding ->
        Box(Modifier.padding(padding)) {
            when {
                vm.grafanaBaseURL.isEmpty() -> EmptyState("Grafana Not Configured", "Set Grafana URL in Settings")
                segments.isEmpty() && loading ->
                    Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                segments.isEmpty() && error != null -> EmptyState("Failed to Load", error!!)
                else -> PullToRefreshBox(isRefreshing = loading, onRefresh = { scope.launch { load() } }) {
                    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(segments) { SegCard(it) }
                        item(key = "legs") { LegsCard(legs) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SegCard(s: Seg) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${s.label} · ${s.host}", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Активное плечо", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                LegBadge(s.activeLeg)
                Spacer(Modifier.width(8.dp))
                Text(fmtDur(s.activeSeconds), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (s.txBps != null && s.rxBps != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Throughput WG", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text("↑${fmtBps(s.txBps)}  ↓${fmtBps(s.rxBps)}", style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            listOf("sto", "ams", "fi").forEach { l ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("RTT ${l.uppercase()}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    val r = s.rtt[l]
                    Text(if (r != null) "${r.toInt()} ms" else "—",
                        style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
private fun LegsCard(legs: List<Leg>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Egress-плечи · из дома / трафик за месяц", style = MaterialTheme.typography.titleSmall)
            legs.forEach { lg ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LegBadge(lg.leg)
                        Spacer(Modifier.weight(1f))
                        lg.homeRtt?.let {
                            Text("дом → ${it.toInt()} ms", style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (lg.txBytes != null && lg.limitBytes != null && lg.limitBytes > 0) {
                        val frac = (lg.txBytes / lg.limitBytes).coerceIn(0.0, 1.0)
                        LinearProgressIndicator(
                            progress = { frac.toFloat() },
                            modifier = Modifier.fillMaxWidth(),
                            color = if (frac > 0.85) FI else if (frac > 0.6) AMS else STO
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${fmtBytes(lg.txBytes)} / ${fmtBytes(lg.limitBytes)}",
                                style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                            Text("%.1f%%".format(frac * 100), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            Text(
                "Лимит Vultr 2 ТБ на инстанс (STO и AMS отдельно), считается outbound (tx), " +
                    "сброс 1-го числа. FI — cold-standby, квота не отслеживается.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LegBadge(l: String) {
    val c = legColor(l)
    Surface(color = c.copy(alpha = 0.2f), shape = RoundedCornerShape(8.dp)) {
        Text(l.uppercase(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = c,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

private fun fmtDur(s: Double): String {
    val t = s.toInt(); val h = t / 3600; val m = (t % 3600) / 60
    return if (h > 0) "${h}ч ${m}м" else "${m}м"
}
private fun fmtBps(bytesPerSec: Double): String {
    var v = bytesPerSec * 8; val u = listOf("bps", "Kbps", "Mbps", "Gbps"); var i = 0
    while (v >= 1000 && i < u.size - 1) { v /= 1000; i++ }
    return "%.1f %s".format(v, u[i])
}
private fun fmtBytes(b: Double): String {
    var v = b; val u = listOf("B", "KB", "MB", "GB", "TB"); var i = 0
    while (v >= 1000 && i < u.size - 1) { v /= 1000; i++ }
    return "%.1f %s".format(v, u[i])
}

@Composable
private fun EmptyState(title: String, msg: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(msg, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
