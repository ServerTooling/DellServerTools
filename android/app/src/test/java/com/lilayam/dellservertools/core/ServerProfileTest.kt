package com.lilayam.dellservertools.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ServerProfileTest {

    @Test
    fun `profiles survive a json round trip`() {
        val profiles = listOf(
            ServerProfile(
                type = ServerType.IDRAC6,
                name = "R710",
                host = "192.0.2.15",
                username = "root",
                rememberPassword = true,
                consoleUrl = "http://192.0.2.30:5800",
            ),
            ServerProfile(
                type = ServerType.PROXMOX,
                name = "pve",
                host = "192.0.2.20",
                username = "root",
                port = 8006,
                sshPort = 2222,
                realm = "pve",
                apiTokenId = "root@pam!phone",
            ),
        )
        assertEquals(profiles, ServerProfile.listFromJson(ServerProfile.listToJson(profiles)))
    }

    @Test
    fun `bad or unknown json is ignored`() {
        assertEquals(emptyList<ServerProfile>(), ServerProfile.listFromJson(null))
        assertEquals(emptyList<ServerProfile>(), ServerProfile.listFromJson("not json"))
        assertEquals(emptyList<ServerProfile>(), ServerProfile.listFromJson("""[{"type":"TOASTER","host":"x"}]"""))
    }

    @Test
    fun `default ports depend on the server type`() {
        assertEquals(22, ServerProfile(type = ServerType.IDRAC6, name = "", host = "h", username = "u").port)
        assertEquals(8006, ServerProfile(type = ServerType.PROXMOX, name = "", host = "h", username = "u").port)
    }

    @Test
    fun `proxmox user gets the realm appended unless already present`() {
        val pam = ServerProfile(type = ServerType.PROXMOX, name = "", host = "h", username = "root")
        assertEquals("root@pam", pam.proxmoxUser)
        assertEquals("root", pam.sshUsername)
        val pve = pam.copy(username = "admin", realm = "pve")
        assertEquals("admin@pve", pve.proxmoxUser)
        val explicit = pam.copy(username = "ops@pve")
        assertEquals("ops@pve", explicit.proxmoxUser)
        assertEquals("ops", explicit.sshUsername)
    }

    @Test
    fun `api token is only used for proxmox`() {
        val pve = ServerProfile(type = ServerType.PROXMOX, name = "", host = "h", username = "root", apiTokenId = "root@pam!t")
        assertTrue(pve.usesApiToken)
        assertFalse(pve.copy(apiTokenId = " ").usesApiToken)
        assertFalse(pve.copy(type = ServerType.IDRAC6).usesApiToken)
    }

    @Test
    fun `a jnlp file becomes an iDRAC6 profile`() {
        val info = JnlpInfo(
            host = "192.0.2.15",
            title = "idrac-ABC1234, PowerEdge R710, User:operator",
            serverName = "idrac-ABC1234",
            model = "PowerEdge R710",
            username = "operator",
            kvmPort = 5900,
            videoPort = 5900,
            codebase = null,
            kvmSessionUser = null,
            kvmSessionPassword = null,
        )
        val profile = ServerProfile.fromJnlp(info)
        assertEquals(ServerType.IDRAC6, profile.type)
        assertEquals("192.0.2.15", profile.host)
        assertEquals("operator", profile.username)
        assertEquals("idrac-ABC1234 · PowerEdge R710", profile.name)
        assertEquals(22, profile.port)
    }
}
