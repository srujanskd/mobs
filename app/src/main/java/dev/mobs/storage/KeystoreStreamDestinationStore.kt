package dev.mobs.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import dev.mobs.stream.DestinationResult
import dev.mobs.stream.StreamDestination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the server in plain preferences and the stream key encrypted with an AES-GCM key that
 * never leaves the Android Keystore. Backup is disabled for the app, so neither leaves the device.
 */
class KeystoreStreamDestinationStore(context: Context) : StreamDestinationStore {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override suspend fun load(): StreamDestination? = withContext(Dispatchers.IO) {
        val server = prefs.getString(KEY_SERVER, null) ?: return@withContext null
        val sealed = prefs.getString(KEY_STREAM_KEY, null) ?: return@withContext null
        val streamKey = try {
            decrypt(sealed)
        } catch (e: Exception) {
            when {
                e is IllegalArgumentException -> Log.w(TAG, "Saved stream key is corrupt", e)
                // The Keystore key can be invalidated, e.g. by a lock-screen reset. Ask again.
                e.isKeystoreFailure() -> Log.w(TAG, "Saved stream key is unreadable", e)
                else -> throw e
            }
            return@withContext null
        }
        (StreamDestination.parse(server, streamKey) as? DestinationResult.Valid)?.destination
    }

    override suspend fun save(destination: StreamDestination) = withContext(Dispatchers.IO) {
        try {
            val sealed = encrypt(destination.streamKey)
            // Already on the IO dispatcher, so write synchronously and know it reached disk.
            prefs.edit(commit = true) {
                putString(KEY_SERVER, destination.server)
                putString(KEY_STREAM_KEY, sealed)
            }
        } catch (e: Exception) {
            if (!e.isKeystoreFailure()) throw e
            // The destination still works for this session; it just will not survive a restart.
            // Drop the old one too, or the next launch would quietly bring back a replaced key.
            Log.w(TAG, "Unable to save stream key", e)
            prefs.edit(commit = true) {
                remove(KEY_SERVER)
                remove(KEY_STREAM_KEY)
            }
        }
        Unit
    }

    /** Keystore operations can also fail with [ProviderException] on some devices, and with [IOException] on load. */
    private fun Exception.isKeystoreFailure() =
        this is GeneralSecurityException || this is ProviderException || this is IOException

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(byteArrayOf(iv.size.toByte()) + iv + sealed, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        require(bytes.isNotEmpty()) { "Empty ciphertext" }
        val ivLength = bytes[0].toInt()
        require(ivLength in 1 until bytes.size) { "Bad IV length" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            existingKey() ?: throw GeneralSecurityException("No Keystore key"),
            GCMParameterSpec(TAG_BITS, bytes, 1, ivLength),
        )
        return String(cipher.doFinal(bytes, 1 + ivLength, bytes.size - 1 - ivLength), Charsets.UTF_8)
    }

    private fun existingKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return keyStore.getKey(KEY_ALIAS, null) as SecretKey?
    }

    private fun secretKey(): SecretKey = existingKey() ?: KeyGenerator
        .getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        .apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }
        .generateKey()

    private companion object {
        const val TAG = "DestinationStore"
        const val PREFS = "stream_destination"
        const val KEY_SERVER = "server"
        const val KEY_STREAM_KEY = "stream_key_sealed"
        const val KEY_ALIAS = "dev.mobs.stream_key"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}
