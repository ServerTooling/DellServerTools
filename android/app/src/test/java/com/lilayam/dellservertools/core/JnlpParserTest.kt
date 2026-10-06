package com.lilayam.dellservertools.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class JnlpParserTest {

    // Same shape as the viewer.jnlp an iDRAC6 hands out (addresses changed).
    private val sample = """
        <?xml version="1.0" encoding="UTF-8"?>
        <jnlp codebase="https://192.0.2.15:443" spec="1.0+">
        <information>
          <title>iDRAC6 Virtual Console Client</title>
          <vendor>Dell Inc.</vendor>
        </information>
        <application-desc main-class="com.avocent.idrac.kvm.Main">
          <argument>ip=192.0.2.15</argument>
          <argument>vmprivilege=true</argument>
          <argument>title=idrac-ABC1234%2C+PowerEdge+R710%2C+User%3Aoperator</argument>
          <argument>user=1804289383</argument>
          <argument>passwd=846930886</argument>
          <argument>kmport=5900</argument>
          <argument>vport=5900</argument>
          <argument>apcp=1</argument>
          <argument>version=2</argument>
        </application-desc>
        <resources>
          <j2se version="1.6+"/>
          <jar href="https://192.0.2.15:443/software/avctKVM.jar" download="eager" main="true" />
        </resources>
        </jnlp>
    """.trimIndent()

    @Test
    fun `parses an iDRAC6 viewer jnlp`() {
        val info = JnlpParser.parse(sample)
        assertEquals("192.0.2.15", info.host)
        assertEquals("idrac-ABC1234, PowerEdge R710, User:operator", info.title)
        assertEquals("idrac-ABC1234", info.serverName)
        assertEquals("PowerEdge R710", info.model)
        assertEquals("operator", info.username)
        assertEquals(5900, info.kvmPort)
        assertEquals(5900, info.videoPort)
        assertEquals("1804289383", info.kvmSessionUser)
        assertEquals("846930886", info.kvmSessionPassword)
        assertEquals("https://192.0.2.15:443", info.webUrl)
    }

    @Test
    fun `falls back to the codebase host when ip argument is missing`() {
        val xml = """<jnlp codebase="https://idrac.example.test:443"><application-desc/></jnlp>"""
        val info = JnlpParser.parse(xml)
        assertEquals("idrac.example.test", info.host)
        assertNull(info.username)
        assertNull(info.kvmPort)
    }

    @Test
    fun `tolerates a byte order mark and leading whitespace`() {
        val info = JnlpParser.parse("﻿\n  " + sample)
        assertEquals("192.0.2.15", info.host)
    }

    @Test
    fun `rejects files that are not jnlp`() {
        assertThrows<JnlpParseException> { JnlpParser.parse("<html><body>login</body></html>") }
        assertThrows<JnlpParseException> { JnlpParser.parse("not xml at all") }
        assertThrows<JnlpParseException> { JnlpParser.parse("<jnlp><application-desc/></jnlp>") }
    }

    @Test
    fun `does not resolve external entities`() {
        val xml = """
            <?xml version="1.0"?>
            <!DOCTYPE jnlp [<!ENTITY x SYSTEM "file:///etc/passwd">]>
            <jnlp codebase="https://192.0.2.1"><application-desc><argument>title=&x;</argument></application-desc></jnlp>
        """.trimIndent()
        assertThrows<JnlpParseException> { JnlpParser.parse(xml) }
    }

    @Test
    fun `wraps IPv6 hosts for URLs`() {
        assertEquals("[fd00::1]", formatHostForUrl("fd00::1"))
        assertEquals("192.0.2.1", formatHostForUrl("192.0.2.1"))
    }
}
