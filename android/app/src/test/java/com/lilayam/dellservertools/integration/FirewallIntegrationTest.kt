package com.lilayam.dellservertools.integration

import com.lilayam.dellservertools.core.proxmox.FirewallAlias
import com.lilayam.dellservertools.core.proxmox.FirewallRuleForm
import com.lilayam.dellservertools.core.proxmox.FirewallScope
import com.lilayam.dellservertools.core.proxmox.GuestType
import com.lilayam.dellservertools.core.proxmox.IpSetEntry
import com.lilayam.dellservertools.core.proxmox.PinnedTls
import com.lilayam.dellservertools.core.proxmox.ProxmoxClient
import com.lilayam.dellservertools.core.proxmox.ProxmoxCredentials
import com.lilayam.dellservertools.testing.FakeProxmox
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class FirewallIntegrationTest {
    private lateinit var pve: FakeProxmox
    private lateinit var client: ProxmoxClient
    private val dc = FirewallScope()

    @BeforeEach
    fun setUp() {
        pve = FakeProxmox()
        client = ProxmoxClient("127.0.0.1", pve.port, ProxmoxCredentials.Password("root@pam", "s3cret"), PinnedTls.fingerprint(pve.certificate.certificate))
        client.login()
    }

    @AfterEach
    fun tearDown() = pve.close()

    @Test
    fun `adds, edits, moves and deletes rules`() {
        assertEquals(listOf("ACCEPT", "DROP"), client.firewallRules(dc).map { it.action })

        client.addFirewallRule(dc, FirewallRuleForm(proto = "tcp", dport = "443"))
        assertEquals(mapOf("type" to "in", "action" to "ACCEPT", "enable" to "1", "proto" to "tcp", "dport" to "443"), pve.lastCall("POST", "/cluster/firewall/rules")!!.params)
        assertEquals(listOf("443", "22", ""), client.firewallRules(dc).map { it.dport })

        // Down by one is "before the rule two further on".
        client.moveFirewallRule(dc, 0, 2)
        assertEquals(mapOf("moveto" to "2"), pve.lastCall("PUT", "/cluster/firewall/rules/0")!!.params)
        assertEquals(listOf("22", "443", ""), client.firewallRules(dc).map { it.dport })
        client.moveFirewallRule(dc, 2, 0)
        assertEquals(listOf("", "22", "443"), client.firewallRules(dc).map { it.dport })

        client.updateFirewallRule(dc, 0, mapOf("enable" to "1"))
        assertEquals(true, client.firewallRules(dc)[0].enabled)
        client.deleteFirewallRule(dc, 0)
        assertEquals(2, client.firewallRules(dc).size)
    }

    @Test
    fun `guest options, aliases, ip sets and groups`() {
        val vm = FirewallScope("pve", GuestType.QEMU, 100)
        client.setFirewallOptions(vm, mapOf("enable" to "1"))
        assertEquals("1", client.firewallOptions(vm)["enable"])
        client.setFirewallOptions(vm, mapOf("delete" to "enable"))
        assertEquals(null, client.firewallOptions(vm)["enable"])

        client.addFirewallAlias(dc, FirewallAlias("office", "198.51.100.0/24", "office"))
        assertEquals(mapOf("cidr" to "198.51.100.0/24", "comment" to "office", "name" to "office"), pve.lastCall("POST", "/cluster/firewall/aliases")!!.params)
        client.updateFirewallAlias(dc, FirewallAlias("office", "198.51.100.0/25", ""))
        assertEquals(mapOf("cidr" to "198.51.100.0/25"), pve.lastCall("PUT", "/cluster/firewall/aliases/office")!!.params)
        client.deleteFirewallAlias(dc, "lab")
        assertEquals(listOf("office"), client.firewallAliases(dc).map { it.name })

        client.addIpSetEntry(dc, "admins", IpSetEntry("192.0.2.11", nomatch = true, comment = ""))
        assertEquals(mapOf("cidr" to "192.0.2.11", "nomatch" to "1"), pve.lastCall("POST", "/cluster/firewall/ipset/admins")!!.params)
        client.deleteIpSetEntry(dc, "admins", "192.0.2.10")
        assertEquals(listOf("192.0.2.11"), client.ipSetEntries(dc, "admins").map { it.cidr })
        client.addIpSet(dc, "blocked", "")
        assertEquals(listOf("admins", "blocked"), client.ipSets(dc).map { it.name })

        client.addSecurityGroup("db", "databases")
        assertEquals(listOf("db", "webserver"), client.securityGroups().map { it.name })
        client.addFirewallRule(FirewallScope(group = "db"), FirewallRuleForm(proto = "tcp", dport = "5432"))
        assertEquals("5432", client.firewallRules(FirewallScope(group = "db")).single().dport)

        assertEquals(1, client.firewallLog(FirewallScope(node = "pve")).size)
        assertEquals(listOf("Ping", "SSH"), client.firewallMacros().map { it.first })
    }
}
