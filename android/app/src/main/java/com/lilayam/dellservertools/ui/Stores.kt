package com.lilayam.dellservertools.ui

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.lilayam.dellservertools.core.HostKeyStore
import com.lilayam.dellservertools.core.ServerProfile
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Saved server profiles (no secrets), in app-private storage. */
class ProfileStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("profiles", Context.MODE_PRIVATE)

    fun load(): List<ServerProfile> = ServerProfile.listFromJson(prefs.getString(KEY, null))

    fun save(profiles: List<ServerProfile>) {
        prefs.edit().putString(KEY, ServerProfile.listToJson(profiles)).apply()
    }

    private companion object {
        const val KEY = "profiles_json"
    }
}

enum class SecretKind { PASSWORD, API_TOKEN }

/**
 * Passwords and API token secrets, encrypted with an AES key that never leaves
 * the Android Keystore.
 */
class SecretStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("secrets", Context.MODE_PRIVATE)

    fun load(profileId: String, kind: SecretKind): String? =
        prefs.getString(key(profileId, kind), null)?.let(::decrypt)

    fun save(profileId: String, kind: SecretKind, value: String?) {
        val encrypted = value?.takeIf { it.isNotEmpty() }?.let(::encrypt)
        if (encrypted != null) {
            prefs.edit().putString(key(profileId, kind), encrypted).apply()
        } else {
            prefs.edit().remove(key(profileId, kind)).apply()
        }
    }

    fun deleteAll(profileId: String) {
        SecretKind.entries.forEach { save(profileId, it, null) }
    }

    private fun key(profileId: String, kind: SecretKind) = "$profileId:${kind.name}"

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val data = Base64.encodeToString(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        "$iv:$data"
    }.getOrNull()

    private fun decrypt(stored: String): String? = runCatching {
        val (iv, data) = stored.split(':', limit = 2)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8)
    }.getOrNull()

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "dell_server_tools_secrets"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

/** Approved SSH host keys and pinned TLS certificates (trust on first use). */
class TrustStore(context: Context) : HostKeyStore {
    private val prefs = context.applicationContext.getSharedPreferences("trust", Context.MODE_PRIVATE)

    override fun fingerprintFor(hostId: String): String? = prefs.getString(hostId, null)

    override fun save(hostId: String, fingerprint: String) {
        prefs.edit().putString(hostId, fingerprint).apply()
    }

    companion object {
        fun tlsId(host: String, port: Int) = "tls:$host:$port"
    }
}
