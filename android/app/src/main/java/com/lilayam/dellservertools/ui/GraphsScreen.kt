@file:OptIn(ExperimentalLayoutApi::class)

package com.lilayam.dellservertools.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lilayam.dellservertools.core.proxmox.Rrd
import com.lilayam.dellservertools.core.proxmox.RrdPoint
import com.lilayam.dellservertools.core.proxmox.RrdSeries
import com.lilayam.dellservertools.core.proxmox.Timeframe

/** The Summary graphs of a node, guest or storage, as in the web UI; tap a graph to read the values at that time. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GraphsScreen(screen: Screen.Graphs, vms: AppViewModels) {
    val vm = vms.proxmox
    val state by vm.state.collectAsStateWithLifecycle()
    var timeframe by remember { mutableStateOf(Timeframe.HOUR) }
    var version by remember { mutableIntStateOf(0) }
    var points by remember { mutableStateOf<List<RrdPoint>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(timeframe, version, state.connected) {
        vm.fetch { c ->
            when {
                screen.storage != null -> c.storageRrd(screen.node, screen.storage, timeframe)
                screen.guestType != null && screen.vmid != null -> c.guestRrd(screen.node, screen.guestType, screen.vmid, timeframe)
                else -> c.nodeRrd(screen.node, timeframe)
            }
        }.onSuccess { points = it; error = null }.onFailure { error = it.message }
    }
    // Proxmox writes a sample a minute, so refreshing more often shows nothing new.
    AutoRefresh(state.connected, 60_000) { version++ }
    val charts = when {
        screen.storage != null -> Rrd.STORAGE
        screen.guestType != null -> Rrd.GUEST
        else -> Rrd.NODE
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(screen.title) },
            navigationIcon = { IconButton(onClick = { vms.servers.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = { IconButton(onClick = { version++ }) { Icon(Icons.Filled.Refresh, "Refresh") } },
        )
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                ChipRow(Timeframe.entries.map { it.name to it.label }, timeframe.name, "graph-tf") { timeframe = Timeframe.valueOf(it) }
            }
            val data = points
            if (data == null) item { Text(error ?: "Loading…") }
            if (data != null && data.isEmpty()) item { Text("No data for this time span yet.") }
            if (!data.isNullOrEmpty()) {
                items(charts, key = { "chart-${it.title}" }) { chart -> ChartCard(Rrd.series(chart, data)) }
            }
        }
    }
}

private val LINE_COLORS = listOf(Color(0xFF1E88E5), Color(0xFFE53935), Color(0xFF43A047), Color(0xFF8E24AA))

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChartCard(series: RrdSeries) {
    val unit = series.chart.unit
    // The sample the user tapped; null shows the latest values.
    var picked by remember(series) { mutableStateOf<Long?>(null) }
    val times = series.lines.flatMap { (_, pts) -> pts.map { it.first } }.distinct().sorted()
    Card(Modifier.fillMaxWidth().testTag("graph-${series.chart.title.lowercase().replace(' ', '-')}")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(series.chart.title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(
                    picked?.let { formatStorageTime(it) } ?: series.ceiling?.let { "of ${Rrd.format(it, unit)}" }.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                series.lines.forEachIndexed { i, (line, pts) ->
                    val value = picked?.let { t -> pts.firstOrNull { it.first == t }?.second } ?: pts.lastOrNull()?.second
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Canvas(Modifier.size(8.dp)) { drawCircle(LINE_COLORS[i % LINE_COLORS.size]) }
                        Text(
                            " ${line.label} ${value?.let { Rrd.format(it, unit) } ?: "–"}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            if (series.isEmpty) {
                Text("No data.", style = MaterialTheme.typography.bodySmall)
            } else {
                val outline = MaterialTheme.colorScheme.outline
                val first = times.first()
                val last = times.last()
                val span = (last - first).coerceAtLeast(1).toFloat()
                // A missing stretch longer than a few samples is a gap (host off), not a straight line.
                val step = if (times.size > 1) span / (times.size - 1) else span
                Canvas(
                    Modifier.fillMaxWidth().height(120.dp).pointerInput(times) {
                        detectTapGestures { offset ->
                            val t = first + (offset.x / size.width) * span
                            picked = times.minByOrNull { kotlin.math.abs(it - t) }
                        }
                    },
                ) {
                    val top = series.top
                    fun x(t: Long) = (t - first) / span * size.width
                    fun y(v: Double) = size.height - (v / top).toFloat().coerceIn(0f, 1f) * size.height
                    drawLine(outline, Offset(0f, size.height), Offset(size.width, size.height))
                    series.ceiling?.let { c ->
                        drawLine(outline, Offset(0f, y(c)), Offset(size.width, y(c)), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
                    }
                    series.lines.forEachIndexed { i, (_, pts) ->
                        val path = Path()
                        var previous: Long? = null
                        pts.forEach { (t, v) ->
                            if (previous == null || t - previous!! > step * 3) path.moveTo(x(t), y(v)) else path.lineTo(x(t), y(v))
                            previous = t
                        }
                        drawPath(path, LINE_COLORS[i % LINE_COLORS.size], style = Stroke(width = 2.dp.toPx()))
                    }
                    picked?.let { t -> drawLine(outline, Offset(x(t), 0f), Offset(x(t), size.height)) }
                }
                Row {
                    Text(formatStorageTime(first), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                    Text("max ${Rrd.format(series.top, unit)}", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
