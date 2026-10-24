package com.lilayam.dellservertools.core.proxmox

import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NodeSystemTest {
    private val ifaces = NetworkInterface.parse(
        JSONArray(
            """[{"iface":"vmbr0","type":"bridge","active":1,"autostart":1,"cidr":"192.0.2.5/24","gateway":"192.0.2.1","bridge_ports":"eno1"},
                {"iface":"eno1","type":"eth","active":1}]""",
        ),
    )

    @Test
    fun `interfaces list nics first`() {
        assertEquals(listOf("eno1", "vmbr0"), ifaces.map { it.iface })
        assertTrue(ifaces[0].isPhysical)
        assertEquals("vmbr1", NetworkForm.nextName("vmbr", ifaces))
    }

    @Test
    fun `a new bridge sends the web ui's params`() {
        val f = NetworkForm("vmbr1", "bridge", cidr = "192.0.2.50/24", bridgePorts = " eno2  eno3 ", vlanAware = true)
        assertNull(f.validate())
        assertEquals(
            mapOf("iface" to "vmbr1", "type" to "bridge", "autostart" to "1", "cidr" to "192.0.2.50/24", "bridge_ports" to "eno2 eno3", "bridge_vlan_aware" to "1"),
            f.createParams(),
        )
    }

    @Test
    fun `an edit always sends the type and only what changed`() {
        val f = NetworkForm.fromInterface(ifaces[1])
        assertEquals(mapOf("type" to "bridge"), f.updateParams())
        assertEquals(
            mapOf("type" to "bridge", "cidr" to "192.0.2.6/24", "delete" to "gateway"),
            f.copy(cidr = "192.0.2.6/24", gateway = "").updateParams(),
        )
    }

    @Test
    fun `network validation`() {
        assertNotNull(NetworkForm("br0", "bridge").validate())
        assertNotNull(NetworkForm("vmbr1", "bridge", gateway = "192.0.2.1").validate()) // a gateway needs an address
        assertNotNull(NetworkForm("vmbr1", "bridge", cidr = "192.0.2.1").validate())
        assertNotNull(NetworkForm("bond0", "bond").validate())
        assertNull(NetworkForm("bond0", "bond", slaves = "eno1 eno2", bondMode = "802.3ad").validate())
        assertNotNull(NetworkForm("vlan10", "vlan", vlanRawDevice = "eno1", vlanId = "5000").validate())
        assertNull(NetworkForm("vlan10", "vlan", vlanRawDevice = "eno1", vlanId = "10").validate())
        assertNull(NetworkForm("eno1.20", "vlan").validate())
        assertNotNull(NetworkForm("vmbr1", "bridge", mtu = "100").validate())
        assertNotNull(NetworkForm("vmbr1", "bridge", bridgePorts = "eno1;reboot").validate())
    }

    @Test
    fun `dns settings`() {
        val d = DnsSettings.parse(JSONObject("""{"search":"example.org","dns1":"192.0.2.1","dns3":"192.0.2.3"}"""))
        assertEquals(listOf("192.0.2.1", "192.0.2.3"), d.servers)
        assertEquals(mapOf("search" to "example.org", "dns1" to "192.0.2.1", "dns2" to "192.0.2.3"), d.params())
        assertNotNull(d.copy(search = "").validate())
        assertNotNull(d.copy(servers = listOf("dns.example.org")).validate())
        assertNull(d.validate())
    }

    @Test
    fun `services, updates, repositories and timezones`() {
        val services = NodeService.parse(JSONArray("""[{"service":"pveproxy","state":"running"},{"service":"cron","state":"dead"}]"""))
        assertEquals(listOf("cron", "pveproxy"), services.map { it.name })
        assertTrue(services[1].running)
        val updates = PackageUpdate.parse(JSONArray("""[{"Package":"pve-manager","OldVersion":"8.2.4","Version":"8.2.7"}]"""))
        assertEquals("8.2.7", updates.single().newVersion)
        val repos = AptRepositories.parse(
            JSONObject(
                """{"digest":"abc","files":[{"path":"/etc/apt/sources.list","repositories":[
                    {"Types":["deb"],"URIs":["http://deb.debian.org/debian"],"Suites":["bookworm"],"Components":["main"],"Enabled":0}]}],
                   "standard-repos":[{"handle":"no-subscription","name":"No-Subscription","status":null},{"handle":"enterprise","name":"Enterprise","status":1}]}""",
            ),
        )
        assertEquals("deb http://deb.debian.org/debian bookworm main", repos.entries.single().line)
        assertEquals(false, repos.entries.single().enabled)
        assertEquals(listOf(null, true), repos.standard.map { it.enabled })
        assertTrue("Europe/Berlin" in NodeSystemJson.timezones())
    }
}
