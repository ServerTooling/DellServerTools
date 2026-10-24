package com.lilayam.dellservertools.core.proxmox

import org.json.JSONArray
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RrdDataTest {
    private val points = Rrd.parse(
        JSONArray(
            """[{"time":120,"cpu":0.5,"mem":"2048","maxmem":4096},
                {"time":60},
                {"time":0,"cpu":0.25,"mem":1024,"maxmem":4096,"netin":"NaN"}]""",
        ),
    )

    @Test
    fun `samples are sorted and gaps and NaN are left out`() {
        assertEquals(listOf(0L, 60L, 120L), points.map { it.time })
        assertEquals(mapOf("cpu" to 0.25, "mem" to 1024.0, "maxmem" to 4096.0), points[0].values)
        assertEquals(emptyMap<String, Double>(), points[1].values)
        assertEquals(2048.0, points[2].values["mem"])
    }

    @Test
    fun `cpu becomes a percentage and memory has its ceiling`() {
        val cpu = Rrd.series(Rrd.GUEST[0], points)
        assertEquals(listOf(0L to 25.0, 120L to 50.0), cpu.lines.single().second)
        assertEquals(100.0, cpu.top)
        assertNull(cpu.ceiling)
        val mem = Rrd.series(Rrd.GUEST[1], points)
        assertEquals(4096.0, mem.ceiling)
        assertEquals(4096.0, mem.top)
        assertEquals(listOf(Rrd.GUEST[1].lines.single() to 2048.0), mem.latest())
        val net = Rrd.series(Rrd.GUEST[2], points)
        assertEquals(true, net.isEmpty)
        assertEquals(1.0, net.top)
    }

    @Test
    fun `values read in their unit`() {
        assertEquals("12.5%", Rrd.format(12.5, RrdUnit.PERCENT))
        assertEquals("0.70", Rrd.format(0.7, RrdUnit.NUMBER))
        assertEquals(Format.bytes(2048) + "/s", Rrd.format(2048.0, RrdUnit.BYTES_PER_SECOND))
    }
}
