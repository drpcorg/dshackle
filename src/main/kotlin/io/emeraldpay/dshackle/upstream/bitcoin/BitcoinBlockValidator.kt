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
import java.util.concurrent.ConcurrentHashMap

class BitcoinBlockValidator : BlockValidator {

    private val targetsCache = ConcurrentHashMap<String, BigInteger>()
    private val worksCache = ConcurrentHashMap<String, BigInteger>()

    companion object {
        private val log = LoggerFactory.getLogger(BitcoinBlockValidator::class.java)

        private const val BITS = "bits"
        private const val HASH = "hash"
        private const val TIME = "time"
        private const val NONCE = "nonce"
        private const val HEIGHT = "height"
        private const val VERSION = "version"
        private const val CHAIN_WORK = "chainwork"
        private const val MERKLE_ROOT = "merkleroot"
        private const val PREV_BLOCK_HASH = "previousblockhash"

        private val MAX_HASH = BigInteger.ONE.shiftLeft(256)

        /**
         * Block target difficulty is being recalculated every 2016 blocks
         */
        private const val DIFFICULTY_CHANGE_PERIOD = 2016L
    }

    override fun isValid(currentHead: BlockContainer?, newHead: BlockContainer): Boolean =
        newHead.getParsed(Map::class.java)?.let { data ->
            val curData = currentHead?.getParsed(Map::class.java)
            val bits = data[BITS] as String
            val hash = data[HASH] as String

            val hashValid = validateHash(bits, hash, data)
            if (!hashValid) {
                log.warn("Invalid header hash for block {}", hash)
            }

            val difficultyValid = validateDifficultyAdjustment(curData, data)
            if (!difficultyValid) {
                log.warn("Invalid difficulty for block {}. Adjustment not allowed", hash)
            }

            val target = calculateTarget(bits)
            val powValid = validatePoW(target, hash)
            if (!powValid) {
                log.warn("Invalid PoW for block {}", hash)
            }

            val work = calculateWork(bits)
            val workValid = validateChainWork(work, curData, data)
            if (!workValid) {
                log.warn("Invalid chainwork for block {}", hash)
            }

            hashValid && difficultyValid && powValid && workValid
        } ?: false

    /**
     * Calculates and caches the difficulty target
     */
    private fun calculateTarget(bits: String): BigInteger =
        targetsCache.computeIfAbsent(bits.lowercase()) { readCompactForm(bits.toLong(16)) }

    /**
     * Calculates the estimated work for the new block generation based in the target difficulty
     *
     * work = 2^256 / (target + 1)
     */
    private fun calculateWork(bits: String): BigInteger =
        worksCache.computeIfAbsent(bits.lowercase()) {
            val target = calculateTarget(it)
            MAX_HASH.divide(target.add(BigInteger.ONE))
        }

    /**
     * Checks if block hash is properly calculated for given block content
     */
    private fun validateHash(bits: String, hash: String, data: Map<*, *>): Boolean {
        val version = data[VERSION] as Number
        val prev = data[PREV_BLOCK_HASH] as String
        val merkleRoot = data[MERKLE_ROOT] as String
        val time = data[TIME] as Number
        val nonce = data[NONCE] as Number

        val headerBytes = version.toLong().asUInt32()
            .plus(fromHexString(prev).reversedArray())
            .plus(fromHexString(merkleRoot).reversedArray())
            .plus(time.toLong().asUInt32())
            .plus(Hex.decodeHex(bits).asUInt32LE())
            .plus(nonce.toLong().asUInt32())

        return headerBytes.sha256().sha256().reversedArray().toHexString().lowercase() == hash
    }

    /**
     * Checks if block hash is less than the target difficulty
     */
    private fun validatePoW(target: BigInteger, hash: String): Boolean =
        BigInteger(hash, 16) <= target

    /**
     * Checks if bitcoin chainwork calculated properly for the new block, which means:
     * chainwork = prev chainwork + work
     */
    private fun validateChainWork(work: BigInteger, prevData: Map<*, *>?, data: Map<*, *>): Boolean =
        prevData?.let { prev ->
            val prevHeight = (prev[HEIGHT] as Number).toLong()
            val prevChainWork = prev[CHAIN_WORK] as String
            val newHeight = (data[HEIGHT] as Number).toLong()
            val chainWork = data[CHAIN_WORK] as String

            val prevGen = prevHeight / DIFFICULTY_CHANGE_PERIOD
            val newGen = newHeight / DIFFICULTY_CHANGE_PERIOD
            val distance = newHeight - prevHeight
            val totalWork = if (distance == 1L) {
                work
            } else if (prevGen == newGen) {
                work.multiply(distance.toBigInteger())
            } else {
                val blocks = LongArray(distance.toInt()) { prevHeight + it + 1 }
                val prevWork = calculateWork(prev[BITS] as String)
                val adjBlock = blocks.first { it % DIFFICULTY_CHANGE_PERIOD == 0L }
                blocks.sumOf { if (it < adjBlock) prevWork else work }
            }
            distance > 0L && BigInteger(chainWork, 16) == BigInteger(prevChainWork, 16) + totalWork
        } ?: true

    /**
     * Checks if difficulty adjustment is done in time (2016 blocks after the previous one)
     * https://wiki.bitcoinsv.io/index.php/Target
     */
    private fun validateDifficultyAdjustment(prevData: Map<*, *>?, data: Map<*, *>): Boolean =
        prevData?.let {
            val prevBits = prevData[BITS] as String
            val newBits = data[BITS] as String
            val prevHeight = (prevData[HEIGHT] as Number).toLong()
            val newHeight = (data[HEIGHT] as Number).toLong()

            prevBits == newBits || prevHeight / DIFFICULTY_CHANGE_PERIOD < newHeight / DIFFICULTY_CHANGE_PERIOD
        } ?: true

    /**
     * Calculates the difficulty target based on 'compact form' representation
     *
     * https://developer.bitcoin.org/reference/block_chain.html#target-nbits
     */
    private fun readCompactForm(bits: Long): BigInteger {
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
