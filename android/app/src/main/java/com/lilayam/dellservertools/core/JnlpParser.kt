package com.lilayam.dellservertools.core

import java.io.StringReader
import java.net.URI
import java.net.URLDecoder
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

/**
 * What we can learn about an iDRAC6 from the `viewer.jnlp` it hands out for the
 * Java Virtual Console.
 *
 * The `user` / `passwd` arguments in that file are one-shot KVM session tokens,
 * not the iDRAC login, so they are kept only for display; SSH needs the real
 * iDRAC password.
 */
data class JnlpInfo(
    val host: String,
    val title: String,
    val serverName: String?,
    val model: String?,
    val username: String?,
    val kvmPort: Int?,
    val videoPort: Int?,
    val codebase: String?,
    val kvmSessionUser: String?,
    val kvmSessionPassword: String?,
) {
    val webUrl: String
        get() = codebase?.takeIf { it.startsWith("http") } ?: "https://${formatHostForUrl(host)}"
}

class JnlpParseException(message: String) : Exception(message)

object JnlpParser {

    fun parse(xml: String): JnlpInfo {
        val root = parseXml(xml)
        if (root.tagName != "jnlp") {
            throw JnlpParseException("Not a JNLP file (root element is <${root.tagName}>)")
        }

        val args = linkedMapOf<String, String>()
        val argNodes = root.getElementsByTagName("argument")
        for (i in 0 until argNodes.length) {
            val raw = argNodes.item(i).textContent.trim()
            val eq = raw.indexOf('=')
            if (eq > 0) {
                args[raw.substring(0, eq).trim().lowercase()] = raw.substring(eq + 1).trim()
            }
        }

        val codebase = root.getAttribute("codebase").trim().ifEmpty { null }
        val host = args["ip"]?.ifEmpty { null }
            ?: codebase?.let { hostFromUrl(it) }
            ?: throw JnlpParseException("No iDRAC address found (missing ip= argument and codebase)")

        val title = args["title"]?.let { decodeTitle(it) }.orEmpty()
        val titleParts = parseTitle(title)

        return JnlpInfo(
            host = host,
            title = title,
            serverName = titleParts.serverName,
            model = titleParts.model,
            username = titleParts.username,
            kvmPort = args["kmport"]?.toIntOrNull(),
            videoPort = args["vport"]?.toIntOrNull(),
            codebase = codebase,
            kvmSessionUser = args["user"],
            kvmSessionPassword = args["passwd"],
        )
    }

    private data class TitleParts(val serverName: String?, val model: String?, val username: String?)

    /** Title looks like `idrac-ABC1234, PowerEdge R710, User:root`. */
    private fun parseTitle(title: String): TitleParts {
        if (title.isBlank()) return TitleParts(null, null, null)
        var serverName: String? = null
        var model: String? = null
        var username: String? = null
        title.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEachIndexed { index, part ->
            when {
                part.startsWith("User:", ignoreCase = true) ->
                    username = part.substringAfter(':').trim().ifEmpty { null }
                index == 0 -> serverName = part
                model == null -> model = part
            }
        }
        return TitleParts(serverName, model, username)
    }

    private fun decodeTitle(raw: String): String =
        runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw).trim()

    private fun hostFromUrl(url: String): String? =
        runCatching { URI(url).host }.getOrNull()?.removePrefix("[")?.removeSuffix("]")?.ifEmpty { null }

    private fun parseXml(xml: String): Element {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isExpandEntityReferences = false
            // The file comes from outside the app; never resolve external entities.
            trySetFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            trySetFeature("http://xml.org/sax/features/external-general-entities", false)
            trySetFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        val doc = try {
            factory.newDocumentBuilder().parse(InputSource(StringReader(xml.removePrefix("\uFEFF").trim())))
        } catch (e: Exception) {
            throw JnlpParseException("Could not read JNLP XML: ${e.message}")
        }
        return doc.documentElement ?: throw JnlpParseException("Empty JNLP file")
    }

    private fun DocumentBuilderFactory.trySetFeature(name: String, value: Boolean) {
        runCatching { setFeature(name, value) }
    }
}

internal fun formatHostForUrl(host: String): String =
    if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
