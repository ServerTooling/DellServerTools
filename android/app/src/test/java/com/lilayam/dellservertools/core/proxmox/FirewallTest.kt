package com.lilayam.dellservertools.core.proxmox

import org.json.JSONArray
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FirewallTest {
    @Test
    fun `each level has its own path and tabs`() {
        val dc = FirewallScope()
        assertEquals("/cluster/firewall/rules", dc.rulesPath)
        assertTrue(dc.hasAliases && dc.hasIpSets && !dc.hasLog)
        val node = FirewallScope(node = "pve")
        assertEquals("/nodes/pve/firewall/rules", node.rulesPath)
        assertTrue(node.hasLog && !node.hasAliases)
        val vm = FirewallScope("pve", GuestType.QEMU, 100)
        assertEquals("/nodes/pve/qemu/100/firewall", vm.path)
        assertTrue(vm.hasLog && vm.hasAliases && vm.hasIpSets)
        assertEquals("CT 200 firewall", FirewallScope("pve", GuestType.LXC, 200).title)
        val group = FirewallScope(group = "web servers")
        assertEquals("/cluster/firewall/groups/web%20servers", group.rulesPath)
        assertFalse(group.hasOptions)
    }

    @Test
    fun `options clear back to the default`() {
        val enable = FirewallScope().options.first { it.key == "enable" }
        assertEquals("0", enable.value(emptyMap()))
        assertEquals(mapOf("enable" to "1"), enable.params("1"))
        assertEquals(mapOf("delete" to "enable"), enable.params("0"))
        val policy = FirewallScope("pve", GuestType.QEMU, 100).options.first { it.key == "policy_in" }
        assertEquals("ACCEPT", policy.value(mapOf("policy_in" to "ACCEPT")))
        assertFalse(policy.isSwitch)
    }

    @Test
    fun `rules parse in order with a readable summary`() {
        val rules = FirewallRule.parse(
            JSONArray(
                """[{"pos":1,"type":"in","action":"DROP","enable":0,"macro":"Ping"},
                    {"pos":0,"type":"in","action":"ACCEPT","enable":1,"proto":"tcp","dport":"22","source":"192.0.2.0/24"}]""",
            ),
        )
        assertEquals(listOf(0, 1), rules.map { it.pos })
        assertEquals("tcp port 22 from 192.0.2.0/24", rules[0].summary)
        assertFalse(rules[1].enabled)
    }

    @Test
    fun `rule form sends what the web ui sends and only changes on edit`() {
        val add = FirewallRuleForm(proto = "tcp", dport = "443", source = "dc/lab", comment = "https")
        assertNull(add.validate())
        assertEquals(
            mapOf("type" to "in", "action" to "ACCEPT", "enable" to "1", "proto" to "tcp", "dport" to "443", "source" to "dc/lab", "comment" to "https"),
            add.createParams(),
        )
        val rule = FirewallRule(0, "in", "ACCEPT", true, proto = "tcp", dport = "22", source = "192.0.2.0/24", comment = "ssh")
        val edit = FirewallRuleForm.fromRule(rule)
        assertEquals(emptyMap<String, String>(), edit.updateParams())
        assertEquals(
            mapOf("action" to "DROP", "delete" to "source,comment"),
            edit.copy(action = "DROP", source = "", comment = "").updateParams(),
        )
        val group = FirewallRuleForm(type = "group", action = "webserver", proto = "tcp")
        assertEquals(mapOf("type" to "group", "action" to "webserver", "enable" to "1"), group.createParams())
    }

    @Test
    fun `rule validation`() {
        assertNotNull(FirewallRuleForm(dport = "22").validate()) // a port needs tcp or udp
        assertNotNull(FirewallRuleForm(macro = "SSH", proto = "tcp").validate())
        assertNotNull(FirewallRuleForm(proto = "tcp", dport = "22; reboot").validate())
        assertNull(FirewallRuleForm(proto = "tcp", dport = "80,443,8000:8100").validate())
        assertNotNull(FirewallRuleForm(source = "192.0.2.1 192.0.2.2").validate())
        assertNull(FirewallRuleForm(source = "+admins").validate())
        assertNull(FirewallRuleForm(source = "!192.0.2.0/24").validate())
        assertNotNull(FirewallRuleForm(type = "group", action = "").validate())
        assertNotNull(FirewallRuleForm(action = "ALLOW").validate())
    }

    @Test
    fun `names, addresses and lists`() {
        assertNull(FirewallNames.nameError("lab-net"))
        assertNotNull(FirewallNames.nameError("1lab"))
        assertNull(FirewallNames.cidrError("192.0.2.0/24"))
        assertNull(FirewallNames.cidrError("2001:db8::/32"))
        assertNotNull(FirewallNames.cidrError("lab"))
        assertEquals(listOf("Ping", "SSH"), FirewallNames.macros(JSONArray("""[{"macro":"SSH"},{"macro":"Ping"}]""")).map { it.first })
        assertEquals(listOf("a", "b"), FirewallNames.log(JSONArray("""[{"n":2,"t":"b"},{"n":1,"t":"a"}]""")))
        assertEquals(listOf("webserver"), SecurityGroup.parse(JSONArray("""[{"group":"webserver"}]""")).map { it.name })
    }
}
