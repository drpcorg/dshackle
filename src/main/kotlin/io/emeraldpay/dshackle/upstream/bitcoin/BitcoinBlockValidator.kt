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
import java.util.concurrent.atomic.AtomicReference

class BitcoinBlockValidator : BlockValidator {
    companion object {
        private val log = LoggerFactory.getLogger(BitcoinBlockValidator::class.java)

        private val targetRef = AtomicReference<ChainTargetState>()

        private val MAX_HASH = BigInteger.ONE.shiftLeft(256)
        private const val DAY = 60L * 60L * 24L
        private const val TWO_WEEKS = 14L * DAY
    }

    override fun isValid(currentHead: BlockContainer?, newHead: BlockContainer): Boolean =
        newHead.getParsed(Map::class.java)?.let { data ->
            val time = data["time"] as Number
            val bits = data["bits"] as String
            val hash = data["hash"] as String

            val hashValid = validateHash(bits, hash, data)
            if (!hashValid) {
                log.warn("Invalid header hash for block {}", hash)
            }

            val bitsValid = validateBits(bits, time.toLong())
            if (!bitsValid) {
                log.warn("Invalid bits for block {}. Too early to change difficulty target", hash)
            }

            val target = checkUpdateChainTarget(bits, time.toLong())
            val powValid = validatePoW(target, hash)
            if (!powValid) {
                log.warn("Invalid PoW for block {}", hash)
            }

            val workValid = currentHead?.let { prev ->
                prev.getParsed(Map::class.java)?.let {
                    val work = MAX_HASH.divide(target.add(BigInteger.ONE))
                    validateChainWork(work, it["chainwork"] as String, data["chainwork"] as String)
                } ?: false
            } ?: true
            if (!workValid) {
                log.warn("Invalid chainwork for block {}", hash)
            }

            hashValid && bitsValid && powValid && workValid
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

    private fun validatePoW(target: BigInteger, hash: String): Boolean {
        val h = BigInteger(hash, 16)
        return h <= target
    }

    private fun validateChainWork(work: BigInteger, prevChainWork: String, chainWork: String): Boolean {
        return BigInteger(chainWork, 16) == BigInteger(prevChainWork, 16) + work
    }

    private fun checkUpdateChainTarget(bits: String, time: Long): BigInteger =
        targetRef.updateAndGet { old ->
            old?.takeIf { it.raw.equals(bits, ignoreCase = true) } ?: ChainTargetState(
                bits,
                readCompactForm(bits.toLong(16)),
                time,
                old != null
            )
        }.target

    private fun validateBits(bits: String, time: Long) =
        targetRef.get()?.let { it.raw == bits || !it.tracked || time - it.time > TWO_WEEKS - DAY } ?: true

    /**
     * https://developer.bitcoin.org/reference/block_chain.html#target-nbits
     */
    fun readCompactForm(bits: Long): BigInteger {
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

    data class ChainTargetState(
        val raw: String,
        val target: BigInteger,
        val time: Long,
        val tracked: Boolean
    )
}
