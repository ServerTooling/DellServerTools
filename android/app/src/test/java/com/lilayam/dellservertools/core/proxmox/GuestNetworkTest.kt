package com.lilayam.dellservertools.core.proxmox

import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GuestNetworkTest {

    @Test
    fun `container interfaces use ip-addresses and drop loopback and link-local`() {
        val json = JSONArray(
            """
            [{"name":"lo","inet":"127.0.0.1/8","inet6":"::1/128",
              "ip-addresses":[{"ip-address":"127.0.0.1","ip-address-type":"inet","prefix":8},
                              {"ip-address":"::1","ip-address-type":"inet6","prefix":128}]},
             {"name":"eth0","inet":"192.0.2.50/24","inet6":"fe80::1/64",
              "ip-addresses":[{"ip-address":"2001:db8::50","ip-address-type":"inet6","prefix":64},
                              {"ip-address":"fe80::1","ip-address-type":"inet6","prefix":64},
                              {"ip-address":"192.0.2.50","ip-address-type":"inet","prefix":24},
                              {"ip-address":"192.0.2.51","ip-address-type":"inet","prefix":24}]}]
            """,
        )
        assertEquals(
            listOf(
                GuestAddress("eth0", "192.0.2.50", 24),
                GuestAddress("eth0", "192.0.2.51", 24),
                GuestAddress("eth0", "2001:db8::50", 64),
            ),
            GuestNetwork.lxcInterfaces(json),
        )
    }

    @Test
    fun `older container interfaces fall back to inet and inet6`() {
        val json = JSONArray(
            """[{"name":"eth0","hwaddr":"bc:24:11:00:00:01","inet":"192.0.2.50/24","inet6":"2001:db8::50/64"},
                {"name":"eth1","hwaddr":"bc:24:11:00:00:02"}]""",
        )
        assertEquals(
            listOf(GuestAddress("eth0", "192.0.2.50", 24), GuestAddress("eth0", "2001:db8::50", 64)),
            GuestNetwork.lxcInterfaces(json),
        )
    }

    @Test
    fun `agent interfaces skip docker bridges`() {
        val json = JSONObject(
            """
            {"result":[
              {"name":"lo","ip-addresses":[{"ip-address":"127.0.0.1","ip-address-type":"ipv4","prefix":8}]},
              {"name":"ens18","hardware-address":"bc:24:11:00:00:03",
               "ip-addresses":[{"ip-address":"192.0.2.60","ip-address-type":"ipv4","prefix":24},
                               {"ip-address":"fe80::3","ip-address-type":"ipv6","prefix":64}]},
              {"name":"docker0","ip-addresses":[{"ip-address":"172.17.0.1","ip-address-type":"ipv4","prefix":16}]},
              {"name":"veth1a2b"}
            ]}
            """,
        )
        assertEquals(listOf(GuestAddress("ens18", "192.0.2.60", 24)), GuestNetwork.agentInterfaces(json))
        assertEquals(emptyList<GuestAddress>(), GuestNetwork.agentInterfaces(JSONObject("{}")))
    }

    @Test
    fun `static addresses come from netN and ipconfigN, never dhcp`() {
        val config = listOf(
            "net0" to "name=eth0,bridge=vmbr0,hwaddr=BC:24:11:00:00:01,ip=192.0.2.5/24,gw=192.0.2.1,ip6=auto,type=veth",
            "net1" to "name=eth1,bridge=vmbr4,ip=dhcp,ip6=2001:db8::5/64,type=veth",
            "ipconfig0" to "ip=192.0.2.6/24,gw=192.0.2.1",
            "netmask" to "ip=192.0.2.99/24",
            "memory" to "2048",
        )
        assertEquals(
            listOf(
                GuestAddress("eth0", "192.0.2.5", 24),
                GuestAddress("net0", "192.0.2.6", 24),
                GuestAddress("eth1", "2001:db8::5", 64),
            ),
            GuestNetwork.staticAddresses(config),
        )
        assertEquals("192.0.2.5/24", GuestNetwork.staticAddresses(config).first().cidr)
    }

    @Test
    fun `agent option parsing`() {
        assertTrue(GuestNetwork.agentEnabled(listOf("agent" to "1")))
        assertTrue(GuestNetwork.agentEnabled(listOf("agent" to "1,fstrim_cloned_disks=1")))
        assertTrue(GuestNetwork.agentEnabled(listOf("agent" to "enabled=1,type=virtio")))
        assertFalse(GuestNetwork.agentEnabled(listOf("agent" to "0")))
        assertFalse(GuestNetwork.agentEnabled(listOf("agent" to "enabled=0")))
        assertFalse(GuestNetwork.agentEnabled(listOf("memory" to "2048")))
    }
}
