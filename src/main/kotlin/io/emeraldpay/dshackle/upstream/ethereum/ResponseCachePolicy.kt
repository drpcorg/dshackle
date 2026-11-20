package io.emeraldpay.dshackle.upstream.ethereum

import com.fasterxml.jackson.databind.JsonNode
import io.emeraldpay.dshackle.Global
import io.emeraldpay.dshackle.cache.ResponseCache
import io.emeraldpay.dshackle.data.BlockId

/**
 * Decides which EVM responses can be cached, under which key, and checks a response before it's stored.
 *
 * Reorg safety:
 * - a block given by hash can't change: keyed by that hash;
 * - a block given by number is resolved to the canonical hash by walking parent links down from a fresh head
 *   (recent blocks), or used as is when it's at or below the finalized block;
 * - anything else (latest for state reads, safe, finalized, pending, earliest, too deep and not finalized) isn't cached;
 * - an empty result (no logs, no receipts) is stored only for a finalized block: a node may not have indexed a newer
 *   block yet, or may be on another fork;
 * - state reads (eth_call, balances...) carry no block in their answer, so it can't be checked: by number only for a
 *   finalized block, by hash always (the node itself resolves the hash);
 * - the finalized height reported by the upstreams is trusted only up to [SETTLED_DEPTH] below a fresh head;
 * - a stored block/receipts/logs response must name the expected block (hash or number), or it isn't stored.
 */
class ResponseCachePolicy(
    /** canonical hash at a height (null = head) within the recent window, or null */
    private val canonicalHash: (Long?) -> BlockId?,
    /** height of a recent block by hash, or null */
    private val recentHeight: (BlockId) -> Long?,
    /** current head height while the head is fresh, or null */
    private val headHeight: () -> Long?,
    /** finalized height, or null */
    private val finalizedHeight: () -> Long?,
) {
    companion object {
        const val SETTLED_DEPTH = 2

        private val BLOCK_METHODS = setOf("eth_getBlockByNumber", "eth_getBlockByHash", "eth_getBlockReceipts")
        private val STATE_METHODS = mapOf(
            // method to the index of its block param
            "eth_call" to 1,
            "eth_getBalance" to 1,
            "eth_getCode" to 1,
            "eth_getTransactionCount" to 1,
            "eth_getStorageAt" to 2,
        )
        val METHODS = BLOCK_METHODS + STATE_METHODS.keys + "eth_getLogs"
    }

    enum class Check { BLOCK, LIST, VALUE }

    /**
     * A cacheable request: [key] (a digest), [durable] = at or below finalized (may go to Redis),
     * [allowEmpty] = an empty list may be stored; a stored response must name [hash] or [height] when [check] says so.
     */
    data class Key(
        val key: String,
        val hash: BlockId?,
        val height: Long?,
        val durable: Boolean,
        val allowEmpty: Boolean,
        val check: Check,
    )

    private data class Ref(val hash: BlockId?, val height: Long?, val byHash: Boolean)

    fun keyFor(method: String, params: List<Any?>): Key? {
        return try {
            when (method) {
                "eth_getBlockByNumber" -> {
                    if (params.size != 2) return null
                    val ref = resolve(params[0], allowLatest = true) ?: return null
                    block(method, ref, params.drop(1), Check.BLOCK)
                }
                "eth_getBlockByHash" -> {
                    // (hash, false) is already served by the block cache of EthereumLocalReader
                    if (params.size != 2 || params[1] != true) return null
                    val ref = resolve(mapOf("blockHash" to params[0]), allowLatest = false) ?: return null
                    block(method, ref, params.drop(1), Check.BLOCK)
                }
                "eth_getBlockReceipts" -> {
                    if (params.size != 1) return null
                    val ref = resolve(params[0], allowLatest = true) ?: return null
                    block(method, ref, emptyList(), Check.LIST)
                }
                "eth_getLogs" -> logs(params)
                in STATE_METHODS -> state(method, params, STATE_METHODS.getValue(method))
                else -> null
            }
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun block(method: String, ref: Ref, rest: List<Any?>, check: Check): Key {
        return Key(key(method, ref, rest), ref.hash, ref.height, isFinalized(ref.height), isFinalized(ref.height), check)
    }

    private fun logs(params: List<Any?>): Key? {
        val filter = params.singleOrNull() as? Map<*, *> ?: return null
        val ref = if (filter["blockHash"] != null) {
            if (filter.containsKey("fromBlock") || filter.containsKey("toBlock")) return null
            resolve(mapOf("blockHash" to filter["blockHash"]), allowLatest = false) ?: return null
        } else {
            val from = filter["fromBlock"] as? String ?: return null
            val to = filter["toBlock"] as? String ?: return null
            // a single block only, by number (a range or a tag isn't cached)
            if (from != to || !from.startsWith("0x")) return null
            resolve(from, allowLatest = false) ?: return null
        }
        val rest = filter.filterKeys { it != "blockHash" && it != "fromBlock" && it != "toBlock" }.toSortedMap(compareBy { it.toString() })
        return Key(key("eth_getLogs", ref, listOf(rest)), ref.hash, ref.height, isFinalized(ref.height), isFinalized(ref.height), Check.LIST)
    }

    private fun state(method: String, params: List<Any?>, blockIndex: Int): Key? {
        if (params.size != blockIndex + 1 && !(method == "eth_call" && params.size == blockIndex + 2)) return null
        val ref = resolve(params[blockIndex], allowLatest = false) ?: return null
        // the answer names no block: by number it's only safe where every upstream has the same block
        if (!ref.byHash && !isFinalized(ref.height)) return null
        val rest = params.filterIndexed { i, _ -> i != blockIndex }
        return Key(key(method, ref, rest), ref.hash, ref.height, isFinalized(ref.height), true, Check.VALUE)
    }

    /**
     * A block reference: hex number, "latest" (block methods only), {blockHash}, {blockNumber}; null if not cacheable
     */
    private fun resolve(ref: Any?, allowLatest: Boolean): Ref? {
        return when (ref) {
            is String -> when {
                ref == "latest" && allowLatest -> canonicalHash(null)?.let { Ref(it, headHeight(), false) }
                ref.startsWith("0x") && ref.length in 3..18 -> byNumber(ref.substring(2).toLong(16))
                else -> null
            }
            is Map<*, *> -> {
                val hash = ref["blockHash"] as? String
                val number = ref["blockNumber"] as? String
                when {
                    hash != null && ref["requireCanonical"] != true -> {
                        val id = BlockId.from(hash)
                        Ref(id, recentHeight(id), true)
                    }
                    number != null && number.startsWith("0x") -> byNumber(number.substring(2).toLong(16))
                    else -> null
                }
            }
            else -> null
        }
    }

    private fun byNumber(height: Long): Ref? {
        canonicalHash(height)?.let { return Ref(it, height, false) }
        // too deep for the recent blocks: only a finalized block is the same on every upstream
        return if (isFinalized(height)) Ref(null, height, false) else null
    }

    /**
     * At or below the finalized block, which must itself be below a fresh head: an upstream that answers
     * "finalized" with its latest block must not make head data permanent.
     */
    private fun isFinalized(height: Long?): Boolean {
        if (height == null) return false
        val finalized = finalizedHeight() ?: return false
        val head = headHeight() ?: return false
        return height <= minOf(finalized, head - SETTLED_DEPTH)
    }

    private fun key(method: String, ref: Ref, rest: List<Any?>): String {
        val block = ref.hash?.toHex() ?: "n${ref.height}"
        return ResponseCache.digest("$method|$block|${Global.objectMapper.writeValueAsString(rest)}")
    }

    /**
     * true when [result] (the raw JSON-RPC result) may be stored under [key]
     */
    fun accepts(key: Key, result: ByteArray): Boolean {
        if (result.isEmpty()) return false
        val json: JsonNode = try {
            Global.objectMapper.readTree(result)
        } catch (e: Exception) {
            return false
        }
        if (json.isNull || json.isMissingNode) return false
        return when (key.check) {
            Check.VALUE -> true
            Check.BLOCK -> json.isObject && names(json, key, "hash", "number")
            Check.LIST -> json.isArray && (if (json.isEmpty) key.allowEmpty else json.all { names(it, key, "blockHash", "blockNumber") })
        }
    }

    private fun names(node: JsonNode, key: Key, hashField: String, numberField: String): Boolean {
        key.hash?.let { expected ->
            val hash = node.get(hashField)?.asText() ?: return false
            return hash.equals(expected.toHexWithPrefix(), ignoreCase = true)
        }
        val height = key.height ?: return false
        val number = node.get(numberField)?.asText() ?: return false
        return number.startsWith("0x") && number.substring(2).toLongOrNull(16) == height
    }
}
