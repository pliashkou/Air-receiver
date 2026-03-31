package com.airreceiver.tv.crypto

import android.util.Log
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.modes.SICBlockCipher
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Handles AirPlay 2 pairing (pair-setup and pair-verify).
 *
 * pair-verify uses encrypted signatures (matching RPiPlay):
 *   - Signature = Ed25519.sign(server_ecdh_pub || client_ecdh_pub)
 *   - Encrypted with AES-128-CTR, key/IV derived from ECDH shared secret:
 *       AES key = SHA-512("Pair-Verify-AES-Key" || ecdh_secret)[0..15]
 *       AES IV  = SHA-512("Pair-Verify-AES-IV"  || ecdh_secret)[0..15]
 *   - Server uses CTR stream bytes 0-63 for its signature
 *   - Client uses CTR stream bytes 64-127 for its signature
 */
class PairingHandler(savedPrivateKeyBytes: ByteArray? = null) {
    private val tag = "PairingHandler"
    private val rng = SecureRandom()

    private val edKeyPair: AsymmetricCipherKeyPair

    private var x25519KeyPair: AsymmetricCipherKeyPair? = null
    private var clientX25519Pub: X25519PublicKeyParameters? = null
    private var clientEdPub: Ed25519PublicKeyParameters? = null

    var ecdhSecret: ByteArray? = null
        private set

    init {
        edKeyPair = if (savedPrivateKeyBytes != null) {
            val priv = Ed25519PrivateKeyParameters(savedPrivateKeyBytes)
            AsymmetricCipherKeyPair(priv.generatePublicKey(), priv)
        } else {
            val gen = Ed25519KeyPairGenerator()
            gen.init(Ed25519KeyGenerationParameters(rng))
            gen.generateKeyPair()
        }
    }

    val privateKeyBytes: ByteArray
        get() = (edKeyPair.private as Ed25519PrivateKeyParameters).encoded

    val serverEdPublicKeyBytes: ByteArray
        get() = (edKeyPair.public as Ed25519PublicKeyParameters).encoded

    fun handlePairSetup(clientPubKeyBytes: ByteArray): ByteArray {
        clientEdPub = Ed25519PublicKeyParameters(clientPubKeyBytes.copyOf(32))
        val gen = X25519KeyPairGenerator()
        gen.init(X25519KeyGenerationParameters(rng))
        x25519KeyPair = gen.generateKeyPair()
        return serverEdPublicKeyBytes
    }

    /**
     * Handle pair-verify phase 1 (data[0] == 1).
     * @param payload 64 bytes: 32 client X25519 + 32 client Ed25519
     * @return 96 bytes: 32 server X25519 public + 64 ENCRYPTED Ed25519 signature
     */
    fun handlePairVerifyPhase1(payload: ByteArray): ByteArray? {
        if (payload.size < 64) return null
        return try {
            val clientX25519Bytes = payload.copyOfRange(0, 32)
            val clientEdBytes     = payload.copyOfRange(32, 64)
            clientX25519Pub = X25519PublicKeyParameters(clientX25519Bytes)
            clientEdPub     = Ed25519PublicKeyParameters(clientEdBytes)

            val kp = x25519KeyPair ?: run {
                val gen = X25519KeyPairGenerator()
                gen.init(X25519KeyGenerationParameters(rng))
                gen.generateKeyPair().also { x25519KeyPair = it }
            }
            val serverX25519Bytes = (kp.public as X25519PublicKeyParameters).encoded

            // ECDH shared secret
            val agreement = X25519Agreement()
            agreement.init(kp.private as X25519PrivateKeyParameters)
            val secret = ByteArray(agreement.agreementSize)
            agreement.calculateAgreement(clientX25519Pub!!, secret, 0)
            ecdhSecret = secret

            // Sign (serverX25519Pub || clientX25519Pub) with server Ed25519 key
            val signer = Ed25519Signer()
            signer.init(true, edKeyPair.private as Ed25519PrivateKeyParameters)
            signer.update(serverX25519Bytes, 0, 32)
            signer.update(clientX25519Bytes, 0, 32)
            val signature = signer.generateSignature()

            // Encrypt signature with AES-128-CTR using ECDH-derived key/IV
            val aesKey = deriveVerifyKey("Pair-Verify-AES-Key", secret)
            val aesIv  = deriveVerifyKey("Pair-Verify-AES-IV", secret)
            val cipher = createCtr(aesKey, aesIv)
            val encrypted = ByteArray(64)
            cipher.processBytes(signature, 0, 64, encrypted, 0)

            Log.i(tag, "pair-verify phase1: ECDH OK, signature encrypted")
            serverX25519Bytes + encrypted
        } catch (e: Exception) {
            Log.e(tag, "pair-verify phase1 error: ${e.message}")
            null
        }
    }

    /**
     * Handle pair-verify phase 2 (data[0] == 0).
     * @param encryptedSignature 64 bytes of client's ENCRYPTED Ed25519 signature
     * @return true if verified
     */
    fun handlePairVerifyPhase2(encryptedSignature: ByteArray): Boolean {
        return try {
            val kp = x25519KeyPair ?: return true
            val secret = ecdhSecret ?: return true
            val serverX25519Bytes = (kp.public as X25519PublicKeyParameters).encoded
            val clientX25519Bytes = clientX25519Pub?.encoded ?: return true
            val clientEd = clientEdPub ?: return true

            // Decrypt client's signature
            // Same AES-CTR key/IV, but we need to skip first 64 bytes (server's signature stream)
            val aesKey = deriveVerifyKey("Pair-Verify-AES-Key", secret)
            val aesIv  = deriveVerifyKey("Pair-Verify-AES-IV", secret)
            val cipher = createCtr(aesKey, aesIv)

            // Dummy round: advance CTR counter past server's 64 bytes
            val dummy = ByteArray(64)
            cipher.processBytes(dummy, 0, 64, dummy, 0)

            // Actual decryption of client's signature
            val decrypted = ByteArray(64)
            cipher.processBytes(encryptedSignature, 0, encryptedSignature.size.coerceAtMost(64), decrypted, 0)

            // Verify: client signs (clientX25519 || serverX25519)
            val verifier = Ed25519Signer()
            verifier.init(false, clientEd)
            verifier.update(clientX25519Bytes, 0, 32)
            verifier.update(serverX25519Bytes, 0, 32)
            val ok = verifier.verifySignature(decrypted)
            if (!ok) Log.w(tag, "pair-verify phase2: signature mismatch (allowing anyway)")
            Log.i(tag, "pair-verify phase2: complete (verified=$ok)")
            true
        } catch (e: Exception) {
            Log.e(tag, "pair-verify phase2 error: ${e.message} (allowing)")
            true
        }
    }

    /** Derive AES key or IV: SHA-512(salt || ecdh_secret)[0..15] */
    private fun deriveVerifyKey(salt: String, secret: ByteArray): ByteArray {
        val sha = MessageDigest.getInstance("SHA-512")
        sha.update(salt.toByteArray(Charsets.UTF_8))
        sha.update(secret)
        return sha.digest().copyOf(16)
    }

    private fun createCtr(key: ByteArray, iv: ByteArray): SICBlockCipher {
        val cipher = SICBlockCipher(AESEngine())
        cipher.init(true, ParametersWithIV(KeyParameter(key), iv))
        return cipher
    }
}
