package com.airreceiver.tv.crypto

import android.util.Log
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.SICBlockCipher
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import java.security.MessageDigest

/**
 * AirPlay 2 stream key derivation and AES-128-CTR decryption.
 *
 * Key derivation (from RPiPlay mirror_buffer.c):
 *   1. sha512digest = SHA-512(aeskey[0..15] || ecdhSecret[0..31])
 *      eaeskey = sha512digest[0..15]
 *   2. decrypt_aeskey = SHA-512("AirPlayStreamKey" + streamConnectionId + eaeskey)[0..15]
 *   3. decrypt_aesiv  = SHA-512("AirPlayStreamIV"  + streamConnectionId + eaeskey)[0..15]
 */
object AirPlayCrypto {
    private const val TAG = "AirPlayCrypto"

    /**
     * Derives the actual AES-128 key and IV used to decrypt the video/audio stream.
     *
     * @param aesKey         16-byte raw AES key (from PlayFairNative.decrypt)
     * @param ecdhSecret     32-byte ECDH shared secret (from PairingHandler)
     * @param connectionId   streamConnectionID from the SETUP plist (uint64)
     * @return Pair(key[16], iv[16]) or null on error
     */
    fun deriveStreamKey(aesKey: ByteArray, ecdhSecret: ByteArray, connectionId: Long): Pair<ByteArray, ByteArray>? {
        return try {
            val sha = MessageDigest.getInstance("SHA-512")

            // Step 1: SHA512(aesKey || ecdhSecret) → use first 16 bytes
            sha.reset()
            sha.update(aesKey, 0, 16)
            sha.update(ecdhSecret, 0, 32)
            val eaesKey = sha.digest().copyOf(16)

            // Step 2: derive stream encryption key
            // connectionId is uint64 — use unsigned string representation
            val connIdStr = java.lang.Long.toUnsignedString(connectionId)
            sha.reset()
            sha.update("AirPlayStreamKey$connIdStr".toByteArray(Charsets.UTF_8))
            sha.update(eaesKey, 0, 16)
            val decryptKey = sha.digest().copyOf(16)

            // Step 3: derive stream IV
            sha.reset()
            sha.update("AirPlayStreamIV$connIdStr".toByteArray(Charsets.UTF_8))
            sha.update(eaesKey, 0, 16)
            val decryptIv = sha.digest().copyOf(16)

            Pair(decryptKey, decryptIv)
        } catch (e: Exception) {
            Log.e(TAG, "Key derivation failed: ${e.message}")
            null
        }
    }

    /**
     * Creates an AES-128-CTR cipher ready to decrypt a stream.
     * The cipher is stateful — do NOT recreate it per-frame; share it across the whole session.
     */
    fun createStreamCipher(key: ByteArray, iv: ByteArray): SICBlockCipher {
        val cipher = SICBlockCipher(AESEngine())
        cipher.init(false, ParametersWithIV(KeyParameter(key), iv))
        return cipher
    }

    /**
     * Decrypts [length] bytes in-place using AES-128-CTR.
     * The cipher state advances; call this sequentially for each frame.
     */
    fun decryptInPlace(cipher: SICBlockCipher, data: ByteArray, offset: Int = 0, length: Int = data.size - offset) {
        val tmp = ByteArray(length)
        cipher.processBytes(data, offset, length, tmp, 0)
        System.arraycopy(tmp, 0, data, offset, length)
    }
}
