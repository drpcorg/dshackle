package io.emeraldpay.dshackle.upstream

import org.apache.commons.codec.binary.Hex
import org.bouncycastle.jcajce.provider.digest.Keccak
import java.math.BigInteger
import java.security.MessageDigest

open class ByteUtils {
    companion object {
        @JvmStatic
        fun toHexString(bytes: ByteArray) = bytes.toHexString()

        @JvmStatic
        fun fromHexString(hexString: String?): ByteArray =
            hexString?.replace("0x", "")?.let {
                runCatching { Hex.decodeHex(it) }.getOrNull()
                    ?: throw IllegalArgumentException("Invalid HEX string '$hexString'")
            } ?: byteArrayOf()

        @JvmStatic
        fun fromHexStringI(hexString: String?): BigInteger =
            hexString?.replace("0x", "")?.let {
                BigInteger(it, 16)
            } ?: BigInteger.ZERO
    }
}

fun BigInteger.asUInt64LE() = this.toByteArray().asUInt64LE()

fun ByteArray.asUInt64LE() = this.reversedArray().let {
    if (it.size >= 8) {
        it.copyOfRange(0, 8)
    } else {
        ByteArray(8 - it.size) { 0 }.plus(it)
    }
}

fun BigInteger.asUInt32LE() = this.toByteArray().asUInt32LE()

fun ByteArray.asUInt32LE() = this.reversedArray().let {
    if (it.size >= 4) {
        it.copyOfRange(0, 4)
    } else {
        ByteArray(4 - it.size) { 0 }.plus(it)
    }
}

fun ByteArray.toUInt32BE() = BigInteger(1, this.copyOfRange(0, 4))
fun ByteArray.asUInt32BE() =
    if (this.size >= 4) {
        this.copyOfRange(0, 4)
    } else {
        ByteArray(4 - this.size) { 0 }.plus(this)
    }

fun ByteArray.sha3(): ByteArray = keccak256()

fun ByteArray.sha256(): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(this)

fun ByteArray.keccak256(): ByteArray =
    Keccak.Digest256().digest(this)

fun ByteArray.keccak512(): ByteArray =
    Keccak.Digest512().digest(this)

fun ByteArray.toHexString() = joinToString(separator = "") { "%02x".format(it) }.uppercase()

fun BigInteger.asUnsignedByteArray(): ByteArray =
    toByteArray().let {
        if (it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it
    }

fun Long.asUInt32(): ByteArray =
    byteArrayOf(
        (this and 0xFF).toByte(),
        ((this shr 8) and 0xFF).toByte(),
        ((this shr 16) and 0xFF).toByte(),
        ((this shr 24) and 0xFF).toByte()
    )
