package com.lilayam.dellservertools.core.proxmox

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LxcRequestTest {

    private fun base() = LxcRequest(
        vmid = 110,
        hostname = "gh-runner-1",
        ostemplate = "local:vztmpl/debian-12-standard_12.7-1_amd64.tar.zst",
        storage = "local-zfs",
        diskGb = 16,
        cores = 4,
        memoryMb = 8192,
        swapMb = 2048,
        bridge = "vmbr0",
        useDhcp = true,
        password = "s3cret",
    )

    @Test
    fun `dhcp request maps to the expected api params`() {
        val p = base().toParams()
        assertEquals("110", p["vmid"])
        assertEquals("gh-runner-1", p["hostname"])
        assertEquals("local:vztmpl/debian-12-standard_12.7-1_amd64.tar.zst", p["ostemplate"])
        assertEquals("local-zfs:16", p["rootfs"])
        assertEquals("4", p["cores"])
        assertEquals("8192", p["memory"])
        assertEquals("2048", p["swap"])
        assertEquals("name=eth0,bridge=vmbr0,ip=dhcp", p["net0"])
        assertEquals("1", p["unprivileged"])
        assertEquals("nesting=1", p["features"])
        assertEquals("1", p["start"])
        assertEquals("0", p["onboot"])
        assertEquals("s3cret", p["password"])
    }

    @Test
    fun `static ip includes address and gateway`() {
        val p = base().copy(useDhcp = false, staticCidr = "192.0.2.50/24", gateway = "192.0.2.1").toParams()
        assertEquals("name=eth0,bridge=vmbr0,ip=192.0.2.50/24,gw=192.0.2.1", p["net0"])
    }

    @Test
    fun `privileged and no nesting drop the extras`() {
        val p = base().copy(unprivileged = false, nesting = false).toParams()
        assertEquals("0", p["unprivileged"])
        assertNull(p["features"])
    }

    @Test
    fun `ssh key is sent and password omitted when empty`() {
        val p = base().copy(password = "", sshPublicKey = "ssh-ed25519 AAAAC3Nz test").toParams()
        assertNull(p["password"])
        assertEquals("ssh-ed25519 AAAAC3Nz test", p["ssh-public-keys"])
    }

    @Test
    fun `validation accepts a good request`() {
        assertNull(base().validate())
    }

    @Test
    fun `validation rejects bad hostnames`() {
        assertNotNull(base().copy(hostname = "").validate())
        assertNotNull(base().copy(hostname = "-bad").validate())
        assertNotNull(base().copy(hostname = "has space").validate())
        assertNull(base().copy(hostname = "ok-1").validate())
    }

    @Test
    fun `validation rejects missing template, storage and small sizes`() {
        assertNotNull(base().copy(ostemplate = "").validate())
        assertNotNull(base().copy(storage = "").validate())
        assertNotNull(base().copy(diskGb = 0).validate())
        assertNotNull(base().copy(cores = 0).validate())
        assertNotNull(base().copy(memoryMb = 8).validate())
        assertNotNull(base().copy(vmid = 50).validate())
    }

    @Test
    fun `validation requires a credential and a valid static ip`() {
        assertNotNull(base().copy(password = "", sshPublicKey = "").validate())
        assertNotNull(base().copy(password = "123").validate())
        assertNotNull(base().copy(useDhcp = false, staticCidr = "nope").validate())
        assertNull(base().copy(useDhcp = false, staticCidr = "10.0.0.5/24").validate())
    }

    @Test
    fun `defaults are sensible`() {
        val d = LxcRequest.defaults(110)
        assertEquals(110, d.vmid)
        assertEquals("ct-110", d.hostname)
        assertEquals(16, d.diskGb)
        assertEquals("vmbr0", d.bridge)
        assertEquals(true, d.unprivileged)
        assertEquals(true, d.nesting)
    }
}
