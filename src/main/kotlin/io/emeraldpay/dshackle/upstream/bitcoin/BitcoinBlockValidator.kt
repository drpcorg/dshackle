package io.emeraldpay.dshackle.upstream.bitcoin

import io.emeraldpay.dshackle.data.BlockContainer
import io.emeraldpay.dshackle.upstream.BlockValidator
import io.emeraldpay.dshackle.upstream.ByteUtils.Companion.fromHexString
import io.emeraldpay.dshackle.upstream.asUInt32
import io.emeraldpay.dshackle.upstream.asUInt32LE
import io.emeraldpay.dshackle.upstream.sha256
import io.emeraldpay.dshackle.upstream.toHexString
import org.apache.commons.codec.binary.Hex
import org.slf4j.LoggerFactory
import java.math.BigInteger

class BitcoinBlockValidator : BlockValidator {
    companion object {
        private val log = LoggerFactory.getLogger(BitcoinBlockValidator::class.java)
    }

    override fun isValid(currentHead: BlockContainer?, newHead: BlockContainer): Boolean =
        newHead.getParsed(Map::class.java)?.let { data ->
            val bits = data["bits"] as String
            val hash = data["hash"] as String
            val hashValid = validateHash(bits, hash, data)
            if (!hashValid) {
                log.warn("Invalid header hash for block {}", hash)
            }
            val powValid = validatePoW(bits, hash)
            if (!powValid) {
                log.warn("Invalid PoW for block {}", hash)
            }
            hashValid && powValid
        } ?: false

    private fun validateHash(bits: String, hash: String, data: Map<*, *>): Boolean {
        val version = data["version"] as Number
        val prev = data["previousblockhash"] as String
        val merkleRoot = data["merkleroot"] as String
        val time = data["time"] as Number
        val nonce = data["nonce"] as Number

        val headerBytes = version.toLong().asUInt32()
            .plus(fromHexString(prev).reversedArray())
            .plus(fromHexString(merkleRoot).reversedArray())
            .plus(time.toLong().asUInt32())
            .plus(Hex.decodeHex(bits).asUInt32LE())
            .plus(nonce.toLong().asUInt32())

        return headerBytes.sha256().sha256().reversedArray().toHexString().lowercase() == hash
    }

    private fun validatePoW(bits: String, hash: String): Boolean {
        val target = parseTarget(bits.toLong(16))
        val h = BigInteger(hash, 16)
        return h <= target
    }

    private fun parseTarget(bits: Long): BigInteger {
        val size: Int = (bits shr 24).toInt() and 0xFF
        if (size == 0) {
            return BigInteger.ZERO
        }
        val bytes = ByteArray(size) { 0 }
        bytes[0] = (bits shr 16 and 0xFFL).toByte()
        if (size >= 2) bytes[1] = (bits shr 8 and 0xFFL).toByte()
        if (size >= 3) bytes[2] = (bits and 0xFFL).toByte()

        val isNegative = bytes[0].toInt() and 0x80 == 0x80
        if (isNegative) bytes[0] = (bytes[0].toInt() and 0x7f).toByte()
        val result = BigInteger(bytes)
        return if (isNegative) result.negate() else result
    }
}
