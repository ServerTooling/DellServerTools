package com.lilayam.dellservertools.core.proxmox

import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class NodeCertificatesTest {
    @Test
    fun `the custom certificate is the one served, the root CA is left out`() {
        val certs = CertificateInfo.list(
            JSONArray(
                """[{"filename":"pve-root-ca.pem","fingerprint":"aa"},
                    {"filename":"pve-ssl.pem","fingerprint":"bb:01","notafter":2000000000,"san":["pve.example.org"]},
                    {"filename":"pveproxy-ssl.pem","fingerprint":"CC:02"}]""",
            ),
        )
        assertEquals(listOf("pve-ssl.pem", "pveproxy-ssl.pem"), certs.map { it.filename })
        assertEquals("BB:01", certs.first().fingerprint)
        assertEquals(listOf("pve.example.org"), certs.first().san)
        assertEquals("pveproxy-ssl.pem", CertificateInfo.served(certs)!!.filename)
        assertEquals("pve-ssl.pem", CertificateInfo.served(certs.take(1))!!.filename)
    }

    @Test
    fun `custom upload needs a PEM certificate and keeps the key when none is given`() {
        assertEquals("Paste the certificate (and chain) in PEM form.", CustomCertificateForm("junk").validate())
        val cert = "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----"
        assertEquals("The key must be a PEM private key.", CustomCertificateForm(cert, "nope").validate())
        assertNull(CustomCertificateForm(cert, "-----BEGIN EC PRIVATE KEY-----\nx\n-----END EC PRIVATE KEY-----").validate())
        assertEquals(mapOf("certificates" to "$cert\n", "force" to "1", "restart" to "1"), CustomCertificateForm("  $cert  ").params())
    }

    @Test
    fun `acme domains from the node config, and the changes sent back`() {
        val acme = AcmeSettings.parse(
            JSONObject("""{"acme":"account=prod","acmedomain0":"domain=pve.example.org","acmedomain2":"domain=lab.example.org,plugin=cf","digest":"d1"}"""),
        )
        assertEquals("prod", acme.account)
        assertEquals(
            listOf(AcmeDomain("acmedomain0", "pve.example.org"), AcmeDomain("acmedomain2", "lab.example.org", "cf")),
            acme.domains,
        )
        assertEquals("acmedomain1", acme.freeSlot)
        assertEquals(
            mapOf("acme" to "account=prod", "acmedomain1" to "domain=new.example.org,plugin=cf", "digest" to "d1"),
            acme.setDomain(AcmeDomain("acmedomain1", "new.example.org", "cf")),
        )
        assertEquals(mapOf("delete" to "acmedomain2", "digest" to "d1"), acme.removeDomain("acmedomain2"))
    }

    @Test
    fun `old style domains and a node without acme`() {
        val legacy = AcmeSettings.parse(JSONObject("""{"acme":"account=default,domains=a.example.org;b.example.org"}"""))
        assertEquals(listOf("a.example.org", "b.example.org"), legacy.domains.map { it.domain })
        val none = AcmeSettings.parse(JSONObject("{}"))
        assertEquals(mapOf("acme" to "account=default", "acmedomain0" to "domain=x.example.org"), none.setDomain(AcmeDomain("acmedomain0", "x.example.org")))
        assertEquals(true, AcmeSettings.validDomain("pve.example.org"))
        assertEquals(false, AcmeSettings.validDomain("pve"))
    }

    @Test
    fun `account registration`() {
        val form = AcmeAccountForm("default", "admin@example.org", "https://acme.example.org/directory", "https://acme.example.org/terms.pdf")
        assertNull(form.validate())
        assertEquals(
            mapOf(
                "name" to "default", "contact" to "admin@example.org", "directory" to "https://acme.example.org/directory",
                "tos_url" to "https://acme.example.org/terms.pdf",
            ),
            form.params(),
        )
        assertEquals("Enter the e-mail address for expiry notices.", form.copy(contact = "admin").validate())
        assertEquals(listOf("cf"), AcmeJson.dnsPlugins(JSONArray("""[{"plugin":"standalone","type":"standalone"},{"plugin":"cf","type":"dns"}]""")))
    }
}
