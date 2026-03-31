package com.airreceiver.tv.crypto

/**
 * JNI bridge to the playfair C code (ported from RPiPlay, LGPL v2.1).
 *
 * The native library "airplay_crypto" is compiled from:
 *   app/src/main/cpp/playfair/playfair.c  (RPiPlay's fairplay/playfair implementation)
 *   app/src/main/cpp/airplay_jni.c (JNI entry points)
 */
object PlayFairNative {
    init {
        System.loadLibrary("airplay_crypto")
    }

    /**
     * FairPlay Phase 1 (fp-setup):
     * @param req 16-byte request from iOS/macOS
     * @return 142-byte pre-computed response, or null on error
     */
    external fun setup(req: ByteArray): ByteArray?

    /**
     * FairPlay Phase 2 (fp-setup handshake):
     * @param req 164-byte request from iOS/macOS
     * @return 32-byte response (fp_header + last 20 bytes of req), or null on error
     */
    external fun handshake(req: ByteArray): ByteArray?

    /**
     * Decrypts the 72-byte encrypted AES key from the SETUP plist "ekey" field.
     * @param message3 the 164-byte Phase-2 fp-setup request (keymsg)
     * @param cipherText the 72-byte "ekey" value from SETUP
     * @return 16-byte AES key, or null on error
     */
    external fun decrypt(message3: ByteArray, cipherText: ByteArray): ByteArray?
}
