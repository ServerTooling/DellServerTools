package com.lilayam.dellservertools.core.proxmox

import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** The server presented a certificate the user hasn't approved yet. */
class UntrustedCertificateException(
    val fingerprint: String,
    val changed: Boolean,
) : CertificateException(
    if (changed) "The server's TLS certificate has CHANGED" else "Untrusted (self-signed) TLS certificate",
)

/**
 * Trust for a Proxmox host. Proxmox ships a self-signed certificate, so besides
 * the normal system CAs we accept exactly one pinned certificate (SHA-256
 * fingerprint), approved by the user the first time they connect.
 */
class PinnedTls(private val pinnedFingerprint: String?) {

    /** Set when the last handshake failed because of an unknown certificate. */
    @Volatile
    var rejected: UntrustedCertificateException? = null
        private set

    private val systemTrust: X509TrustManager = TrustManagerFactory
        .getInstance(TrustManagerFactory.getDefaultAlgorithm())
        .apply { init(null as KeyStore?) }
        .trustManagers
        .filterIsInstance<X509TrustManager>()
        .first()

    private val trustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
            throw CertificateException("Client certificates are not supported")

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
            val leaf = chain.firstOrNull() ?: throw CertificateException("Empty certificate chain")
            val fingerprint = fingerprint(leaf)
            if (fingerprint == pinnedFingerprint) return
            try {
                systemTrust.checkServerTrusted(chain, authType)
            } catch (_: CertificateException) {
                val error = UntrustedCertificateException(fingerprint, changed = pinnedFingerprint != null)
                rejected = error
                throw error
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = systemTrust.acceptedIssuers
    }

    val socketFactory: SSLSocketFactory = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(trustManager), null)
    }.socketFactory

    /**
     * A pinned certificate is trusted for whatever address the user typed (usually
     * an IP); CA-issued certificates still get normal hostname checks.
     */
    val hostnameVerifier = HostnameVerifier { hostname, session ->
        val leaf = runCatching { session.peerCertificates.firstOrNull() as? X509Certificate }.getOrNull()
        if (leaf != null && fingerprint(leaf) == pinnedFingerprint) {
            true
        } else {
            HttpsURLConnection.getDefaultHostnameVerifier().verify(hostname, session)
        }
    }

    companion object {
        /** `AB:CD:...` SHA-256 fingerprint, the format Proxmox shows under Certificates. */
        fun fingerprint(cert: X509Certificate): String = fingerprint(cert.encoded)

        fun fingerprint(der: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(der).joinToString(":") { "%02X".format(it) }
    }
}
