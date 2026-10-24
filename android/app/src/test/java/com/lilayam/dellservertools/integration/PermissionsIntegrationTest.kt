package com.lilayam.dellservertools.integration

import com.lilayam.dellservertools.core.proxmox.AclEntry
import com.lilayam.dellservertools.core.proxmox.AclType
import com.lilayam.dellservertools.core.proxmox.PinnedTls
import com.lilayam.dellservertools.core.proxmox.ProxmoxClient
import com.lilayam.dellservertools.core.proxmox.ProxmoxCredentials
import com.lilayam.dellservertools.core.proxmox.UserForm
import com.lilayam.dellservertools.testing.FakeProxmox
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PermissionsIntegrationTest {
    private lateinit var pve: FakeProxmox
    private lateinit var client: ProxmoxClient

    @BeforeEach
    fun setUp() {
        pve = FakeProxmox()
        client = ProxmoxClient("127.0.0.1", pve.port, ProxmoxCredentials.Password("root@pam", "s3cret"), PinnedTls.fingerprint(pve.certificate.certificate))
        client.login()
    }

    @AfterEach
    fun tearDown() = pve.close()

    @Test
    fun `adds, edits and removes a user and sets a password`() {
        client.createUser(UserForm("carol@pve", "longenough", groups = listOf("admins")))
        assertEquals("longenough", pve.lastCall("POST", "/access/users")!!.params["password"])
        val carol = client.users().first { it.userid == "carol@pve" }
        assertEquals(listOf("admins"), carol.groups)
        assertEquals(listOf("alice@pve", "carol@pve"), client.groups().single().users)

        client.updateUser(UserForm.from(carol).copy(email = "carol@example.org"))
        assertEquals("carol@example.org", pve.lastCall("PUT", "/access/users/carol%40pve")!!.params["email"])
        client.setPassword("carol@pve", "newpassword", "s3cret")
        assertEquals(
            mapOf("userid" to "carol@pve", "password" to "newpassword", "confirmation-password" to "s3cret"),
            pve.lastCall("PUT", "/access/password")!!.params,
        )
        client.deleteUser("carol@pve")
        assertTrue("carol@pve" !in client.users().map { it.userid })
    }

    @Test
    fun `api tokens show their secret once`() {
        val token = client.createToken("root@pam", "deploy", privsep = true, expire = 0, comment = "CI")
        assertEquals("root@pam!deploy", token.fullId)
        assertEquals("11111111-2222-3333-4444-555555555555", token.secret)
        assertEquals(mapOf("privsep" to "1", "expire" to "0", "comment" to "CI"), pve.lastCall("POST", "/access/users/root%40pam/token/deploy")!!.params)
        assertEquals(listOf("ci", "deploy"), client.users().first { it.userid == "root@pam" }.tokens.map { it.tokenid })
        client.deleteToken("root@pam", "deploy")
        assertEquals(listOf("ci"), pve.tokens.getValue("root@pam"))
    }

    @Test
    fun `permissions, roles, groups, pools and two-factor`() {
        val entry = AclEntry("/vms/100", AclType.TOKEN, "root@pam!ci", "PVEAuditor", propagate = false)
        client.addAcl(entry)
        assertEquals(listOf("/", "/vms/100"), client.acl().map { it.path })
        assertEquals(entry, client.acl().last())
        client.removeAcl(entry)
        assertEquals(listOf("/"), client.acl().map { it.path })

        val all = client.roles().first { it.roleid == "Administrator" }.privs
        client.createRole("Ops", all.take(2))
        assertEquals(listOf("Datastore.Audit", "Sys.Audit"), client.roles().first { it.roleid == "Ops" }.privs)
        client.updateRole("Ops", listOf("VM.Audit"))
        assertEquals("VM.Audit", pve.customRoles["Ops"])
        client.deleteRole("Ops")

        client.createGroup("ops", "Operators")
        assertEquals(listOf("admins", "ops"), client.groups().map { it.groupid })
        client.deleteGroup("ops")

        client.createPool("lab", "")
        client.setPoolMembers("lab", listOf(100, 101), emptyList(), remove = false)
        client.setPoolMembers("lab", listOf(101), emptyList(), remove = true)
        assertEquals(listOf(100), client.pool("lab").guests)
        client.deletePool("lab")
        assertEquals(emptyList<String>(), client.pools().map { it.poolid })

        assertEquals("totp1", client.tfa().single().id)
        client.deleteTfa("alice@pve", "totp1")
        assertEquals(emptyList<String>(), client.tfa().map { it.id })
        assertEquals(listOf("pam", "pve"), client.realms())
    }
}
