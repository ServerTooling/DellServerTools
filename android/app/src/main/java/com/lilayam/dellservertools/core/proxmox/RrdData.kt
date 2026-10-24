package com.lilayam.dellservertools.core.proxmox

import org.json.JSONArray

/** The time spans of the web UI's Summary graphs; [api] is the `timeframe` parameter of `rrddata`. */
enum class Timeframe(val api: String, val label: String) {
    HOUR("hour", "Hour"), DAY("day", "Day"), WEEK("week", "Week"), MONTH("month", "Month"), YEAR("year", "Year")
}

/** How a chart's values read: CPU as a percentage, memory and disk in bytes, traffic in bytes per second. */
enum class RrdUnit { PERCENT, BYTES, BYTES_PER_SECOND, NUMBER }

/** One line of a chart: [key] is the rrddata field, [scale] turns its value into the unit (CPU fraction to %). */
data class RrdLine(val key: String, val label: String, val scale: Double = 1.0)

/** One graph: [maxKey] names the field that is the ceiling (e.g. total memory), drawn as the top when present. */
data class RrdChart(val title: String, val unit: RrdUnit, val lines: List<RrdLine>, val maxKey: String? = null)

/** One sample; a field the server had no data for (a gap) is missing from [values]. */
data class RrdPoint(val time: Long, val values: Map<String, Double>)

/** A chart's data, ready to draw: per line the (time, value) pairs, gaps left out. */
data class RrdSeries(val chart: RrdChart, val lines: List<Pair<RrdLine, List<Pair<Long, Double>>>>, val ceiling: Double?) {
    val isEmpty: Boolean get() = lines.all { it.second.isEmpty() }

    /** The largest value drawn, so the y axis fits it; at least the ceiling, and 100 for percentages. */
    val top: Double
        get() {
            val max = lines.flatMap { it.second }.maxOfOrNull { it.second } ?: 0.0
            val floor = if (chart.unit == RrdUnit.PERCENT) 100.0 else 0.0
            return maxOf(max, ceiling ?: 0.0, floor).takeIf { it > 0 } ?: 1.0
        }

    /** The latest value of each line, for the legend. */
    fun latest(): List<Pair<RrdLine, Double?>> = lines.map { (line, points) -> line to points.lastOrNull()?.second }
}

object Rrd {
    fun parse(data: JSONArray): List<RrdPoint> = (0 until data.length()).mapNotNull { data.optJSONObject(it) }.map { o ->
        val values = o.keys().asSequence().filter { it != "time" }.mapNotNull { k ->
            o.opt(k).let { v -> (v as? Number)?.toDouble() ?: (v as? String)?.toDoubleOrNull() }?.takeIf { !it.isNaN() }?.let { k to it }
        }.toMap()
        RrdPoint(o.optLong("time"), values)
    }.sortedBy { it.time }

    val NODE = listOf(
        RrdChart("CPU", RrdUnit.PERCENT, listOf(RrdLine("cpu", "CPU", 100.0), RrdLine("iowait", "IO wait", 100.0))),
        RrdChart("Load", RrdUnit.NUMBER, listOf(RrdLine("loadavg", "Load average"))),
        RrdChart("Memory", RrdUnit.BYTES, listOf(RrdLine("memused", "Used"), RrdLine("swapused", "Swap used")), maxKey = "memtotal"),
        RrdChart("Network", RrdUnit.BYTES_PER_SECOND, listOf(RrdLine("netin", "In"), RrdLine("netout", "Out"))),
        RrdChart("Root disk", RrdUnit.BYTES, listOf(RrdLine("rootused", "Used")), maxKey = "roottotal"),
    )

    val GUEST = listOf(
        RrdChart("CPU", RrdUnit.PERCENT, listOf(RrdLine("cpu", "CPU", 100.0))),
        RrdChart("Memory", RrdUnit.BYTES, listOf(RrdLine("mem", "Used")), maxKey = "maxmem"),
        RrdChart("Network", RrdUnit.BYTES_PER_SECOND, listOf(RrdLine("netin", "In"), RrdLine("netout", "Out"))),
        RrdChart("Disk IO", RrdUnit.BYTES_PER_SECOND, listOf(RrdLine("diskread", "Read"), RrdLine("diskwrite", "Write"))),
    )

    val STORAGE = listOf(RrdChart("Usage", RrdUnit.BYTES, listOf(RrdLine("used", "Used")), maxKey = "total"))

    fun series(chart: RrdChart, points: List<RrdPoint>): RrdSeries = RrdSeries(
        chart,
        chart.lines.map { line -> line to points.mapNotNull { p -> p.values[line.key]?.let { p.time to it * line.scale } } },
        chart.maxKey?.let { k -> points.mapNotNull { it.values[k] }.maxOrNull() },
    )

    fun format(value: Double, unit: RrdUnit): String = when (unit) {
        RrdUnit.PERCENT -> String.format(java.util.Locale.ROOT, "%.1f%%", value)
        RrdUnit.BYTES -> Format.bytes(value.toLong())
        RrdUnit.BYTES_PER_SECOND -> Format.bytes(value.toLong()) + "/s"
        RrdUnit.NUMBER -> String.format(java.util.Locale.ROOT, "%.2f", value)
    }
}
