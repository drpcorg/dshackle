package io.emeraldpay.dshackle.upstream.signature

import org.apache.commons.codec.binary.Hex
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.RSAPrivateKey

class RsaSigner(
    private val privateKey: RSAPrivateKey,
    val keyId: Long,
) : ResponseSigner {

    companion object {
        const val SIGN_SCHEME = "SHA256withRSA"
        const val MSG_PREFIX = "DSHACKLESIG"
        const val MSG_SEPARATOR = '/'
    }

    override val enabled: Boolean = true

    override fun sign(nonce: Long, message: ByteArray, source: String): ResponseSigner.Signature {
        val sig = Signature.getInstance(SIGN_SCHEME, "BC")
        sig.initSign(privateKey)
        val wrapped = wrapMessage(nonce, message, source)
        sig.update(wrapped.toByteArray())
        val value = sig.sign()
        return ResponseSigner.Signature(value, source, keyId)
    }

    /**
     * Wrapping format: `"DSHACKLESIG/" || str(nonce) || "/" || source || "/" || hex(sha256(msg))`
     *
     * `nonce` carries an unsigned uint64 bit pattern (proto3 uint64 ↔ Java long).
     * Format it as unsigned decimal so verifiers reconstruct the same wrap for
     * nonces ≥ 2^63 — otherwise `Long.toString` emits a negative number and
     * signature verification fails.
     */
    fun wrapMessage(nonce: Long, message: ByteArray, source: String): String {
        val sha256 = MessageDigest.getInstance("SHA-256")
        val formatterMsg = StringBuilder(11 + 1 + 20 + 1 + 64 + 1 + 64)
        formatterMsg.append(MSG_PREFIX)
            .append(MSG_SEPARATOR)
            .append(java.lang.Long.toUnsignedString(nonce))
            .append(MSG_SEPARATOR)
            .append(source)
            .append(MSG_SEPARATOR)
            .append(Hex.encodeHexString(sha256.digest(message)))
        return formatterMsg.toString()
    }
}
