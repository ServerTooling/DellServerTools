package com.lilayam.dellservertools.core.proxmox

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RunnersJsonTest {
    // Shaped like pve-runners' GET /api/v1/settings (internal/setup/api.go).
    private val settings = RunnersJson.settings(
        JSONObject(
            """{"host_id":"pve1",
            "sections":[{"id":"s-this-host","title":"This host","fields":[
              {"name":"proxmox.max_runners","label":"Max runners","help":"Most runners at once.","kind":"number","value":"4"},
              {"name":"autoscale.enabled","label":"Autoscale","kind":"bool","value":"true"}]},
             {"id":"s-updates","title":"Updates","fields":[
              {"name":"update.channel","label":"Channel","kind":"select","value":"edge","options":["edge","stable"]}]},
             {"id":"s-logs","title":"Logs (Grafana Loki)","fields":[
              {"name":"loki_token","label":"Token","kind":"secret","value":"","set":true}]}],
            "pools":[{"id":"pool.default","title":"Pool default","fields":[
              {"name":"pool.default.cores","label":"Cores","kind":"number","value":"4"},
              {"name":"pool.default.delete","label":"Delete this pool on save","kind":"bool","value":"false"}]}],
            "targets":[]}""",
        ),
    )

    @Test
    fun `settings parse into groups of fields`() {
        assertEquals("pve1", settings.hostId)
        assertEquals(listOf("This host", "Updates", "Logs (Grafana Loki)"), settings.sections.map { it.title })
        val channel = settings.sections[1].fields.single()
        assertEquals(listOf("edge", "stable"), channel.options)
        assertEquals("select", channel.kind)
        assertEquals("Most runners at once.", settings.sections[0].fields[0].help)
        val token = settings.sections[2].fields.single()
        assertTrue(token.set)
        assertFalse(settings.sections[0].fields[0].set)
        assertEquals("4", settings.value("pool.default.cores"))
    }

    @Test
    fun `a save sends every field as shown, with edits, and secrets only when typed`() {
        assertEquals(
            mapOf(
                "proxmox.max_runners" to "4",
                "autoscale.enabled" to "true",
                "update.channel" to "edge",
                "loki_token" to "",
                "pool.default.cores" to "4",
                "pool.default.delete" to "false",
            ),
            settings.saveValues(emptyMap()),
        )
        val values = settings.saveValues(
            mapOf("proxmox.max_runners" to "8", "autoscale.enabled" to "false", "loki_token" to "glc_x", "loki_token_clear" to "true"),
            newPool = " big ",
        )
        assertEquals("8", values["proxmox.max_runners"])
        assertEquals("false", values["autoscale.enabled"])
        assertEquals("glc_x", values["loki_token"])
        assertEquals("true", values["loki_token_clear"])
        assertEquals("big", values["new_pool"])
        assertEquals("edge", values["update.channel"])
    }

    @Test
    fun `status and actions parse`() {
        val status = RunnersJson.status(
            JSONObject(
                """{"version":"build-30","host":"pve1","max":10,"runners":[{"id":9000,"name":"gh-pve1-default-9000",
                "status":"running","pool":"default","owner":"example-org","age":"3m"}],"pending":null,"storage":"local-zfs",
                "warnings":["w1"]}""",
            ),
        )
        assertEquals(RunnerRow(9000, "gh-pve1-default-9000", "running", "default", "example-org", "3m"), status.runners.single())
        assertEquals(emptyList<String>(), status.pending)
        assertEquals(listOf("w1"), status.warnings)
        assertEquals(10, status.max)

        val idle = RunnersJson.action(JSONObject("""{"name":"spawn","label":"Start a runner","group":"Runners","args":["pool"],"confirm":"Sure?"}"""))
        assertEquals(listOf("pool"), idle.args)
        assertEquals("Sure?", idle.confirm)
        assertNull(idle.last)
        val ran = RunnersJson.action(
            JSONObject("""{"name":"pull","label":"Pull","group":"Images","args":[],"last":{"running":false,"output":"ok\n","error":"disk full","started":"04:30:00","ended":"04:31:00"}}"""),
        )
        assertEquals(ActionRun(false, "ok\n", "disk full", "04:30:00", "04:31:00"), ran.last)
    }
}
