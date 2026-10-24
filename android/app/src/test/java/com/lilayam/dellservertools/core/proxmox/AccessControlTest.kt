package com.lilayam.dellservertools.core.proxmox

import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AccessControlTest {
    @Test
    fun `users with groups as array or string and their tokens`() {
        val users = PveUser.parse(
            JSONArray(
                """[{"userid":"root@pam","enable":1,"tokens":[{"tokenid":"ci","privsep":0,"expire":0}]},
                    {"userid":"alice@pve","enable":0,"expire":1830297600,"groups":"admins,ops","email":"alice@example.org"},
                    {"userid":"bob@pve","groups":["ops"]}]""",
            ),
        )
        assertEquals(listOf("alice@pve", "bob@pve", "root@pam"), users.map { it.userid })
        assertEquals(listOf("admins", "ops"), users[0].groups)
        assertEquals(listOf("ops"), users[1].groups)
        assertEquals(false, users[0].enabled)
        assertEquals(ApiToken("root@pam", "ci", privsep = false), users[2].tokens.single())
        assertEquals("root@pam!ci", users[2].tokens.single().fullId)
    }

    @Test
    fun `user form sends every field, the password only for pve users on create`() {
        val form = UserForm("carol@pve", "longenough", groups = listOf("ops", "admins"), email = " carol@example.org ", expire = 1830297600)
        assertNull(form.validate())
        assertEquals(
            mapOf(
                "userid" to "carol@pve", "password" to "longenough", "enable" to "1", "expire" to "1830297600", "firstname" to "",
                "lastname" to "", "email" to "carol@example.org", "comment" to "", "groups" to "ops,admins",
            ),
            form.createParams(),
        )
        assertEquals(false, UserForm("dave@pam", "ignored").createParams().containsKey("password"))
        assertEquals("The password needs at least 8 characters.", UserForm("carol@pve", "short").validate())
        assertEquals("User name@realm, e.g. alice@pve.", UserForm("carol").validate())
        assertNull(UserForm("dave@pam").validate())
        val edit = UserForm.from(PveUser("alice@pve", enabled = false, comment = "x"))
        assertEquals(mapOf("enable" to "0", "expire" to "0", "firstname" to "", "lastname" to "", "email" to "", "comment" to "x", "groups" to ""), edit.updateParams())
    }

    @Test
    fun `acl lines and the changes sent for them`() {
        val acl = AclEntry.parse(
            JSONArray(
                """[{"path":"/vms/100","type":"token","ugid":"root@pam!ci","roleid":"PVEVMUser","propagate":0},
                    {"path":"/","type":"group","ugid":"admins","roleid":"Administrator","propagate":1}]""",
            ),
        )
        assertEquals(listOf("/", "/vms/100"), acl.map { it.path })
        val token = acl[1]
        assertEquals(AclType.TOKEN, token.type)
        assertEquals(mapOf("path" to "/vms/100", "roles" to "PVEVMUser", "tokens" to "root@pam!ci", "propagate" to "0"), token.addParams())
        assertEquals(mapOf("path" to "/", "roles" to "Administrator", "groups" to "admins", "delete" to "1"), acl[0].removeParams())
        assertEquals("The path starts with /, e.g. / or /vms/100.", token.copy(path = "vms").validate())
        assertEquals(
            listOf("/", "/vms", "/storage", "/nodes", "/pool", "/sdn", "/access", "/access/groups", "/access/realm", "/nodes/pve", "/vms/100", "/vms/200", "/storage/local", "/pool/lab"),
            AclEntry.suggestedPaths(listOf("pve"), listOf(200, 100), listOf("local"), listOf("lab")),
        )
    }

    @Test
    fun `roles, built-in first, and the full privilege list`() {
        val roles = PveRole.parse(
            JSONArray(
                """[{"roleid":"Custom","privs":"VM.Audit","special":0},
                    {"roleid":"Administrator","privs":"VM.Audit,Sys.Audit,VM.PowerMgmt","special":1}]""",
            ),
        )
        assertEquals(listOf("Administrator", "Custom"), roles.map { it.roleid })
        assertEquals(listOf("Sys.Audit", "VM.Audit", "VM.PowerMgmt"), PveRole.allPrivileges(roles))
        assertEquals(mapOf("roleid" to "Ops", "privs" to "Sys.Audit,VM.Audit"), PveRole.createParams("Ops", setOf("VM.Audit", "Sys.Audit")))
    }

    @Test
    fun `pools, members and token answers`() {
        val pool = PvePool.detail(
            "lab",
            JSONObject("""{"comment":"x","members":[{"type":"qemu","vmid":101},{"type":"lxc","vmid":100},{"type":"storage","storage":"local"}]}"""),
        )
        assertEquals(PvePool("lab", "x", listOf(100, 101), listOf("local")), pool)
        assertEquals(mapOf("vms" to "100", "delete" to "1"), PvePool.membersParams(listOf(100), emptyList(), remove = true))
        assertEquals(mapOf("storage" to "local"), PvePool.membersParams(emptyList(), listOf("local"), remove = false))
        assertEquals(
            NewApiToken("root@pam!ci", "secret-uuid"),
            AccessJson.newToken(JSONObject("""{"full-tokenid":"root@pam!ci","value":"secret-uuid","info":{"privsep":1}}""")),
        )
        assertEquals(mapOf("privsep" to "1", "expire" to "0"), ApiToken.createParams(true, 0, " "))
    }

    @Test
    fun `two-factor entries and expiry dates`() {
        val tfa = TfaEntry.parse(JSONArray("""[{"userid":"alice@pve","entries":[{"id":"t1","type":"totp","description":"phone","created":5}]}]"""))
        assertEquals(TfaEntry("alice@pve", "t1", "totp", "phone", 5, true), tfa.single())
        assertEquals(0L, ExpiryDate.parse(""))
        assertEquals(1830297600L, ExpiryDate.parse("2028-01-01"))
        assertNull(ExpiryDate.parse("next year"))
        assertEquals("2028-01-01", ExpiryDate.format(1830297600))
        assertEquals("", ExpiryDate.format(0))
    }
}
