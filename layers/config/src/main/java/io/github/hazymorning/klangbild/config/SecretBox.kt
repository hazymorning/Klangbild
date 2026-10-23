package io.github.hazymorning.klangbild.config

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Seals the tokens Klangbild keeps (Home Assistant's and Music Assistant's) before they are stored. */
interface SecretBox {
    fun seal(plain: String): String

    /** The secret in [stored]: a sealed one opened, one stored before sealing as it is; null when it cannot be opened. */
    fun open(stored: String): String?

    /** Whether [stored] was written before secrets were sealed (it is sealed on the next write). */
    fun isPlain(stored: String): Boolean
}

/**
 * Seals secrets with AES-GCM under a key that lives in the Android Keystore and never leaves it,
 * on top of the app's private storage (which is excluded from backups). A file copied off the
 * device is of no use without the device's keystore.
 *
 * Where the platform has no Android Keystore (the JVM tests), secrets stay as they are.
 */
class KeystoreSecretBox(private val alias: String = KEY_ALIAS) : SecretBox {
    private val key: SecretKey? by lazy { loadKey() }

    override fun seal(plain: String): String {
        val k = key ?: return plain
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, k)
        val sealed = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    override fun open(stored: String): String? {
        if (isPlain(stored)) return stored
        val k = key ?: return null
        return try {
            val bytes = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            Log.w(LOG_TAG, "A stored secret could not be opened; it has to be entered again")
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    override fun isPlain(stored: String): Boolean = !stored.startsWith(PREFIX)

    private fun loadKey(): SecretKey? = try {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).run {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(KEY_BITS)
                    .build(),
            )
            generateKey()
        }
    } catch (e: GeneralSecurityException) {
        Log.w(LOG_TAG, "No Android Keystore: secrets are stored unsealed")
        null
    } catch (e: java.io.IOException) {
        Log.w(LOG_TAG, "No Android Keystore: secrets are stored unsealed")
        null
    }

    companion object {
        const val KEY_ALIAS = "klangbild-secrets"
        private const val PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_BITS = 256
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128

        /** Marks a sealed secret (format version 1: IV, then ciphertext with its tag, in Base64). */
        private const val PREFIX = "sealed1:"
        private const val LOG_TAG = "Klangbild"
    }
}
