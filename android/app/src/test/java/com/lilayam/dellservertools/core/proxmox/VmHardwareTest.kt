package com.lilayam.dellservertools.core.proxmox

import org.json.JSONArray
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VmHardwareTest {
    private val config = mapOf(
        "name" to "win11",
        "vga" to "virtio,memory=64",
        "cpu" to "host,flags=+aes",
        "scsi0" to "local-zfs:vm-100-disk-0,size=64G",
        "ide2" to "local:iso/Win11.iso,media=cdrom,size=6G",
        "sshkeys" to "ssh-ed25519%20AAAAC3Nza%20me%40laptop",
    )
    private val edit = GuestEdit(GuestType.QEMU, config)

    @Test
    fun `display and cpu type keep their options`() {
        assertEquals("virtio", VmHardware.main(edit["vga"]))
        assertEquals("std,memory=64", VmHardware.withMain(edit, "vga", "std")["vga"])
        assertEquals("memory=64", VmHardware.withMain(edit, "vga", "")["vga"])
        assertEquals("x86-64-v3,flags=+aes", VmHardware.withMain(edit, "cpu", "x86-64-v3")["cpu"])
        // Default CPU type: the key goes, since flags alone aren't valid.
        assertEquals(mapOf("delete" to "cpu"), VmHardware.withMain(edit, "cpu", "").changedParams())
        assertEquals("", VmHardware.main("memory=32"))
        assertEquals("qxl,memory=32", VmHardware.withMain(edit.with("vga", "memory=32"), "vga", "qxl")["vga"])
        assertEquals("serial0", VmHardware.withMain(edit.without("vga"), "vga", "serial0")["vga"])
    }

    @Test
    fun `unknown values stay selectable`() {
        val items = VmHardware.withCurrent(VmHardware.MACHINES, "pc-q35-8.1")
        assertEquals("pc-q35-8.1", items.last().first)
        assertEquals(VmHardware.MACHINES, VmHardware.withCurrent(VmHardware.MACHINES, "q35"))
    }

    @Test
    fun `cd drives take an iso and can be emptied without a grow-disk error`() {
        assertEquals(listOf("ide2"), VmHardware.cdDrives(edit))
        assertEquals("local:iso/Win11.iso", VmHardware.cdIso(edit["ide2"]))

        val swapped = VmHardware.withCdIso(edit, "ide2", "local:iso/virtio-win.iso")
        assertEquals(mapOf("ide2" to "local:iso/virtio-win.iso,media=cdrom"), swapped.changedParams())
        assertNull(swapped.validate())

        val empty = VmHardware.withCdIso(edit, "ide2", "")
        assertEquals("none,media=cdrom", empty["ide2"])
        assertEquals("", VmHardware.cdIso(empty["ide2"]))

        assertEquals("ide0", VmHardware.nextCdKey(edit))
        assertEquals("ide2", VmHardware.nextCdKey(edit.without("ide2").copy(current = config - "ide2")))
    }

    @Test
    fun `device values match the web ui`() {
        assertEquals("local-zfs:1,efitype=4m,pre-enrolled-keys=1", VmHardware.efiDisk("local-zfs"))
        assertEquals("local-zfs:1,version=v2.0", VmHardware.tpmState("local-zfs"))
        assertEquals("host=0781:5581", VmHardware.usb(UsbDevice("0781", "5581", "SanDisk Ultra")))
        val gpu = PciDevice("0000:04:00.0", "NVIDIA", 15)
        assertEquals("0000:04:00,pcie=1", VmHardware.pci(gpu, allFunctions = true, pcie = true))
        assertEquals("0000:04:00.0", VmHardware.pci(gpu, allFunctions = false, pcie = false))
        assertTrue(VmHardware.isDevice("usb0"))
        assertTrue(VmHardware.isDevice("hostpci3"))
        assertTrue(VmHardware.isDevice("rng0"))
        assertFalse(VmHardware.isDevice("net0"))
        assertFalse(VmHardware.isDevice("tpmstate0"))
    }

    @Test
    fun `host device lists skip hubs`() {
        val usb = VmHardware.usbDevices(
            JSONArray(
                """[{"vendid":"0x1d6b","prodid":"0x0002","product":"EHCI Host Controller","class":9},
                    {"vendid":"0x0781","prodid":"0x5581","manufacturer":"SanDisk","product":"Ultra","class":0}]""",
            ),
        )
        assertEquals(listOf(UsbDevice("0781", "5581", "SanDisk Ultra")), usb)
        val pci = VmHardware.pciDevices(JSONArray("""[{"id":"0000:04:00.1","vendor_name":"NVIDIA"},{"id":"0000:04:00.0","device_name":"GP107","iommugroup":15}]"""))
        assertEquals(listOf("0000:04:00.0", "0000:04:00.1"), pci.map { it.id })
        assertEquals(15, pci[0].iommuGroup)
        assertEquals(-1, pci[1].iommuGroup)
    }

    @Test
    fun `cloud-init ssh keys are url encoded`() {
        assertEquals("ssh-ed25519 AAAAC3Nza me@laptop", VmHardware.sshKeys(edit))
        val two = VmHardware.withSshKeys(edit, "ssh-ed25519 AAAA a@b\n\n  ssh-rsa BBBB c@d  \n")
        assertEquals("ssh-ed25519%20AAAA%20a%40b%0Assh-rsa%20BBBB%20c%40d", two["sshkeys"])
        assertEquals("ssh-ed25519 AAAA a@b\nssh-rsa BBBB c@d", VmHardware.sshKeys(two))
        assertEquals(mapOf("delete" to "sshkeys"), VmHardware.withSshKeys(edit, "  ").changedParams())
    }

    @Test
    fun `cloud-init drive and password mask`() {
        assertNull(VmHardware.cloudInitDrive(edit))
        assertEquals("ide0", VmHardware.nextCloudInitKey(edit))
        val withDrive = edit.with("ide0", VmHardware.cloudInitDisk("local-zfs"))
        assertEquals("ide0", VmHardware.cloudInitDrive(withDrive))
        assertEquals(mapOf("ide0" to "local-zfs:cloudinit"), withDrive.changedParams())
        assertTrue(VmHardware.passwordIsMasked("**********"))
        assertFalse(VmHardware.passwordIsMasked(""))
        assertFalse(VmHardware.passwordIsMasked("secret"))
    }

    @Test
    fun `ip config parses, formats and validates`() {
        assertEquals("ip=dhcp", IpConfig.parse("").format())
        val static = IpConfig.parse("ip=192.0.2.5/24,gw=192.0.2.1,ip6=auto")
        assertEquals(IpConfig("192.0.2.5/24", "192.0.2.1", "auto"), static)
        assertEquals("ip=192.0.2.5/24,gw=192.0.2.1,ip6=auto", static.format())
        assertNull(static.validate())
        // A gateway makes no sense with DHCP and is dropped.
        assertEquals("ip=dhcp", IpConfig("dhcp", "192.0.2.1").format())
        assertNotNull(IpConfig("192.0.2.5").validate())
        assertNotNull(edit.with("ipconfig0", "ip=").validate())
        assertNull(edit.with("ipconfig0", "ip=192.0.2.5/24").validate())
    }
}
