/**
 * Copyright (c) 2020 EmeraldPay, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.emeraldpay.dshackle.cache

import io.emeraldpay.dshackle.Global
import io.emeraldpay.dshackle.config.CacheConfig
import io.emeraldpay.dshackle.data.BlockContainer
import io.emeraldpay.dshackle.data.BlockId
import io.emeraldpay.dshackle.data.DefaultContainer
import io.emeraldpay.dshackle.data.TxContainer
import io.emeraldpay.dshackle.data.TxId
import io.emeraldpay.dshackle.reader.CompoundReader
import io.emeraldpay.dshackle.reader.Reader
import io.emeraldpay.dshackle.upstream.Head
import io.emeraldpay.dshackle.upstream.ethereum.json.BlockJson
import io.emeraldpay.dshackle.upstream.ethereum.json.TransactionJson
import io.emeraldpay.dshackle.upstream.ethereum.json.TransactionReceiptJson
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

open class Caches(
    private val memBlocksByHash: BlocksMemCache,
    private val blocksByHeight: HeightCache,
    private val memTxsByHash: TxMemCache,
    private val memReceipts: ReceiptMemCache,
    private val redisBlocksByHash: BlocksRedisCache?,
    private val redisTxsByHash: TxRedisCache?,
    private val redisReceipts: ReceiptRedisCache?,
    private val redisHeightByHashCache: HeightByHashRedisCache?,
    private val cacheEnabled: Boolean,
    private val config: CacheConfig = CacheConfig(),
) {

    companion object {
        private val log = LoggerFactory.getLogger(Caches::class.java)

        @JvmStatic
        fun newBuilder(): Builder {
            return Builder()
        }

        private const val RECENT_DEPTH = 64
        private const val RECENT_MAX = 192

        // the head counts as stale after this many average block intervals without a new block (at least FRESH_MIN_MS):
        // then nothing is served from the recent blocks and requests go to an upstream, as without this cache
        private const val FRESH_INTERVALS = 3
        private const val FRESH_MIN_MS = 3_000L
        private const val INTERVAL_MAX_MS = 60_000L

        @JvmStatic
        fun default(): Caches {
            return newBuilder().build()
        }
    }

    private val memHeightByHash: HeightByHashMemCache = HeightByHashMemCache()

    /**
     * The last [RECENT_DEPTH] heights of an EVM chain: hash -> (height, parent hash, body). A parent link is a fact about
     * a block, it can't be wrong after a reorg, so walking it down from the current head gives the canonical block at a
     * height. `body` is the node's raw `eth_getBlockBy*(..., false)` response (tx hashes, possibly an empty list); it's
     * null for a newHeads header, which has no "transactions" field.
     * Kept apart from the Caffeine caches on purpose: their size-based admission drops fresh blocks, and the 64-entry
     * block cache never stores empty blocks. This one is pruned by height only.
     */
    private class RecentBlock(
        val height: Long,
        val parent: BlockId?,
        val body: BlockContainer?,
        // set by the first client request for this block (served from memory or not), for the first-request metric
        val requested: AtomicBoolean = AtomicBoolean(false),
    )

    private val recent = ConcurrentHashMap<BlockId, RecentBlock>()

    /** when the head last moved to a higher block, and the average time between new blocks (EWMA), in ms */
    @Volatile
    private var lastHeadAt = 0L

    @Volatile
    private var lastHeadHeight = -1L

    @Volatile
    private var headIntervalMs = 0L

    /** for tests */
    var clock: () -> Long = System::currentTimeMillis

    private val blocksByHash: Reader<BlockId, BlockContainer>
    private val txsByHash: Reader<TxId, TxContainer>
    private val receiptByHash: Reader<TxId, ByteArray>

    @Volatile
    private var head: Head? = null

    init {
        blocksByHash = if (redisBlocksByHash == null) {
            memBlocksByHash
        } else {
            CompoundReader(memBlocksByHash, redisBlocksByHash)
        }
        txsByHash = if (redisTxsByHash == null) {
            memTxsByHash
        } else {
            CompoundReader(memTxsByHash, redisTxsByHash)
        }
        receiptByHash = if (redisReceipts == null) {
            memReceipts
        } else {
            CompoundReader(memReceipts, redisReceipts)
        }
    }

    fun setHead(head: Head) {
        this.head = head
        redisTxsByHash?.head = head
        redisReceipts?.head = head
    }

    open fun cacheReceipt(tag: Tag, data: DefaultContainer<TransactionReceiptJson>) {
        if (!cacheEnabled) {
            return
        }
        val currentHeight = head?.getCurrentHeight()
        if (currentHeight != null && data.height != null && memReceipts.acceptsRecentBlocks(currentHeight - data.height)) {
            memReceipts.add(data).subscribe()
        }
        // TODO move subscription to the caller
        redisReceipts?.add(data)?.subscribe()
    }

    fun cache(tag: Tag, tx: TxContainer) {
        if (!cacheEnabled) {
            return
        }
        // do not cache transactions that are not in a block yet
        if (tx.blockId == null) {
            return
        }
        memTxsByHash.add(tx)
        // TODO move subscription to the caller
        getBlocksByHash().read(tx.blockId).flatMap { block ->
            redisTxsByHash?.add(tx, block) ?: Mono.empty()
        }.subscribe()
    }

    fun cache(tag: Tag, block: BlockContainer) {
        if (!cacheEnabled) {
            return
        }
        val job = ArrayList<Mono<Void>>()

        redisHeightByHashCache?.add(block)?.let(job::add)
        recordLink(block)

        if (tag == Tag.LATEST) {
            trackHead(block)
            // for LATEST data cache it in memory, it may be short living so better to avoid Redis
            memoizeBlock(block, canonical = true)
        } else if (tag == Tag.REQUESTED) {
            val blockOnlyContainer: BlockContainer?
            var jsonValue: BlockJson<*>? = null
            if (block.full) {
                jsonValue = Global.objectMapper.readValue<BlockJson<*>>(block.json, BlockJson::class.java)
                // shouldn't cache block json with transactions, separate txes and blocks with refs
                val blockOnly = jsonValue.withoutTransactionDetails()
                blockOnlyContainer = BlockContainer.from(blockOnly)
            } else {
                blockOnlyContainer = block
            }
            // a block requested by a client can be an orphan or an uncle: it must not take over its height
            memoizeBlock(blockOnlyContainer, canonical = false)
            redisBlocksByHash?.add(blockOnlyContainer)?.let(job::add)

            // now cache only transactions
            jsonValue?.let { value ->
                val plainTransactions = value.transactions.filterIsInstance<TransactionJson>()
                if (plainTransactions.isNotEmpty()) {
                    val transactions = plainTransactions.map { tx ->
                        TxContainer.from(tx)
                    }
                    if (redisTxsByHash != null) {
                        job.add(
                            Flux.fromIterable(transactions)
                                .doOnNext { memTxsByHash.add(it) }
                                .flatMap { redisTxsByHash.add(it, block) }
                                .then(),
                        )
                    }
                }
            }
        }
        Flux.fromIterable(job).flatMap { it }.subscribe() // TODO move out to a caller
    }

    /**
     * Cache the block only in memory
     */
    fun memoizeBlock(block: BlockContainer, canonical: Boolean = true) {
        memBlocksByHash.add(block)
        memHeightByHash.add(block)
        if (!canonical) {
            return
        }
        // only head updates decide which block is at a height; evict the cached txs and receipts of the block it replaced
        blocksByHeight.add(block)?.let { evict(it) }
        reconcileParents(block)
    }

    /**
     * After a reorg deeper than one block the head only reports the new tip. Walk its parents through the recent blocks
     * and re-map every height whose block changed (evicting what the old block left in the caches), down to the first
     * height where the old and new chains agree. Usually that's the parent itself, so it's one map lookup.
     */
    private fun reconcileParents(block: BlockContainer) {
        var hash = block.parentHash ?: return
        var height = block.height - 1
        for (i in 0 until RECENT_DEPTH) {
            val known = blocksByHeight.get(height)
            if (known == null || known == hash) {
                return
            }
            blocksByHeight.put(height, hash)?.let { evict(it) }
            hash = recent[hash]?.parent ?: return
            height -= 1
        }
    }

    private fun recordLink(block: BlockContainer) {
        // non-EVM chains (NEAR, Starknet, beacon, ...) parse into other types; they never use this
        val parsed = block.parsed as? BlockJson<*> ?: return
        val headHeight = head?.getCurrentHeight()
        if (headHeight != null && block.height > headHeight + RECENT_DEPTH) {
            // not a block near the head (a client can request any block by hash): it would never be pruned
            return
        }
        // bodies are kept only when they're going to be served; the parent links are always kept (reorg handling)
        val body = if (recentBlocksEnabled() && !block.full && parsed.transactions != null && block.json != null) block else null
        recent.compute(block.hash) { _, known ->
            when {
                known == null -> RecentBlock(block.height, block.parentHash, body)
                body != null && known.body == null -> RecentBlock(block.height, block.parentHash, body, known.requested)
                else -> known
            }
        }
        if (recent.size > RECENT_MAX) {
            val top = headHeight ?: block.height
            recent.entries.removeIf { it.value.height < top - RECENT_DEPTH }
        }
    }

    fun isCacheEnabled(): Boolean = cacheEnabled

    /** recent blocks by number are served from memory (`cache.recent-blocks.enabled`) */
    open fun recentBlocksEnabled(): Boolean = cacheEnabled && config.recentBlocks.enabled

    open fun warmUpEnabled(): Boolean = recentBlocksEnabled() && config.recentBlocks.warmUp

    /**
     * true when the body (tx list) of a recent block is in memory, so there is nothing to fetch for it
     */
    open fun isBlockChecked(hash: BlockId): Boolean {
        return recent[hash]?.body != null
    }

    /**
     * The canonical block at [height] from memory, without tx details, or null.
     * Canonical = reachable from the current head through parent links, so a block replaced by a reorg is never returned.
     * [height] null means the current head.
     */
    private fun trackHead(block: BlockContainer) {
        if (block.height <= lastHeadHeight) {
            return
        }
        val now = clock()
        if (lastHeadAt > 0) {
            val interval = ((now - lastHeadAt) / (block.height - lastHeadHeight)).coerceIn(1, INTERVAL_MAX_MS)
            headIntervalMs = if (headIntervalMs == 0L) interval else (headIntervalMs * 7 + interval) / 8
        }
        lastHeadAt = now
        lastHeadHeight = block.height
    }

    /**
     * true while new heads keep arriving at the chain's usual pace. A stuck head (all head feeds stalled) would
     * otherwise serve an old block as "latest", and could hide a reorg from the canonical walk.
     */
    open fun isHeadFresh(): Boolean {
        val last = lastHeadAt
        if (last == 0L) {
            return false
        }
        val limit = maxOf(FRESH_MIN_MS, headIntervalMs * FRESH_INTERVALS)
        return clock() - last <= limit
    }

    open fun canonicalBlockFromMemory(height: Long?, maxDepth: Int): BlockContainer? {
        val hash = canonicalHash(height, maxDepth) ?: return null
        val block = recent[hash]?.body ?: return null
        return if (height == null || block.height == height) block else null
    }

    /**
     * The hash of the canonical block at [height] (null = the head), found by walking parent links down from the
     * current head; null when the head is stale, the height is out of range, or a link is missing.
     */
    open fun canonicalHash(height: Long?, maxDepth: Int): BlockId? {
        if (!isHeadFresh()) {
            return null
        }
        val current = head?.getCurrent() ?: return null
        val target = height ?: current.height
        if (target > current.height || current.height - target > maxDepth) {
            return null
        }
        var hash = current.hash
        var at = current.height
        while (at > target) {
            hash = recent[hash]?.parent
                ?: (if (hash == current.hash) current.parentHash else null)
                ?: return null
            at -= 1
        }
        return hash
    }

    /** why [canonicalBlockFromMemory] has nothing for [height], for metrics */
    open fun blockMissReason(height: Long?, maxDepth: Int): String {
        if (lastHeadAt == 0L) return "no_head"
        if (!isHeadFresh()) return "stale_head"
        val current = head?.getCurrentHeight() ?: return "no_head"
        val target = height ?: current
        return when {
            target > current -> "above_head"
            current - target > maxDepth -> "too_deep"
            canonicalHash(height, maxDepth) == null -> "no_link"
            else -> "not_cached"
        }
    }

    /**
     * Marks the canonical block at [height] as requested; true when this is the first request for it (so a hit or a
     * miss now says whether the block was in memory before anyone asked). Null when the block isn't known.
     */
    open fun firstRequest(height: Long?, maxDepth: Int): Boolean? {
        val hash = canonicalHash(height, maxDepth) ?: return null
        val known = recent[hash] ?: return null
        return known.requested.compareAndSet(false, true)
    }

    fun evict(blockId: BlockId) {
        var evicted = false
        // Redis commands are lazy, nothing is deleted without a subscription
        redisBlocksByHash?.evict(blockId)?.subscribe(null, ::logEvictError)
        // the 64 blocks memory cache may have dropped it already
        (memBlocksByHash.get(blockId) ?: recent[blockId]?.body)?.let { block ->
            memTxsByHash.evict(block)
            redisTxsByHash?.evict(block)?.subscribe(null, ::logEvictError)
            memReceipts.evict(block)
            redisReceipts?.evict(block)?.subscribe(null, ::logEvictError)
            evicted = true
        }
        if (!evicted) {
            memTxsByHash.evict(blockId)
        }
    }

    private fun logEvictError(t: Throwable) {
        log.warn("Failed to evict from Redis: ${t.message}")
    }

    fun getBlocksByHash(): Reader<BlockId, BlockContainer> {
        return blocksByHash
    }

    fun getBlockHashByHeight(): Reader<Long, BlockId> {
        return blocksByHeight
    }

    fun getBlocksByHeight(): Reader<Long, BlockContainer> {
        return BlockByHeight(blocksByHeight, blocksByHash)
    }

    fun getTxByHash(): Reader<TxId, TxContainer> {
        return txsByHash
    }

    fun getReceipts(): Reader<TxId, ByteArray> {
        return receiptByHash
    }

    open fun getLastHeightByHash(): Reader<BlockId, Long> {
        return memHeightByHash
    }

    fun getRedisHeightByHash(): HeightByHashCache? {
        return redisHeightByHashCache
    }

    enum class Tag {
        /**
         * Latest data produced by blockchain
         */
        LATEST,

        /**
         * Data requested by client
         */
        REQUESTED,
    }

    class Builder {
        private var blocksByHash: BlocksMemCache? = null
        private var blocksByHeight: HeightCache? = null
        private var txsByHash: TxMemCache? = null
        private var receipts: ReceiptMemCache? = null
        private var redisBlocksByHash: BlocksRedisCache? = null
        private var redisTxsByHash: TxRedisCache? = null
        private var redisReceiptCache: ReceiptRedisCache? = null
        private var redisHeightByHashCache: HeightByHashRedisCache? = null
        private var cacheEnabled: Boolean = true
        private var config: CacheConfig = CacheConfig()

        fun setConfig(config: CacheConfig): Builder {
            this.config = config
            return this
        }

        fun setBlockByHash(cache: BlocksMemCache): Builder {
            blocksByHash = cache
            return this
        }

        fun setBlockByHash(cache: BlocksRedisCache): Builder {
            redisBlocksByHash = cache
            return this
        }

        fun setBlockByHeight(cache: HeightCache): Builder {
            blocksByHeight = cache
            return this
        }

        fun setTxByHash(cache: TxMemCache): Builder {
            txsByHash = cache
            return this
        }

        fun setTxByHash(cache: TxRedisCache): Builder {
            redisTxsByHash = cache
            return this
        }

        fun setReceipts(cache: ReceiptRedisCache): Builder {
            redisReceiptCache = cache
            return this
        }

        fun setReceipts(cache: ReceiptMemCache): Builder {
            this.receipts = cache
            return this
        }

        fun setHeightByHash(cache: HeightByHashRedisCache): Builder {
            redisHeightByHashCache = cache
            return this
        }

        fun setCacheEnabled(cacheEnabled: Boolean): Builder {
            this.cacheEnabled = cacheEnabled
            return this
        }

        fun build(): Caches {
            if (blocksByHash == null) {
                blocksByHash = BlocksMemCache()
            }
            if (blocksByHeight == null) {
                blocksByHeight = HeightCache()
            }
            if (txsByHash == null) {
                txsByHash = TxMemCache()
            }
            if (receipts == null) {
                receipts = ReceiptMemCache()
            }
            return Caches(
                blocksByHash!!, blocksByHeight!!, txsByHash!!, receipts!!,
                redisBlocksByHash, redisTxsByHash, redisReceiptCache, redisHeightByHashCache, cacheEnabled, config,
            )
        }
    }
}
