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
package io.emeraldpay.dshackle.upstream.ethereum

import io.emeraldpay.dshackle.Defaults
import io.emeraldpay.dshackle.Global
import io.emeraldpay.dshackle.cache.Caches
import io.emeraldpay.dshackle.cache.CurrentBlockCache
import io.emeraldpay.dshackle.data.BlockContainer
import io.emeraldpay.dshackle.data.BlockId
import io.emeraldpay.dshackle.data.TxContainer
import io.emeraldpay.dshackle.data.TxId
import io.emeraldpay.dshackle.reader.CompoundReader
import io.emeraldpay.dshackle.reader.Reader
import io.emeraldpay.dshackle.reader.RekeyingReader
import io.emeraldpay.dshackle.upstream.CachingReader
import io.emeraldpay.dshackle.upstream.Multistream
import io.emeraldpay.dshackle.upstream.Selector
import io.emeraldpay.dshackle.upstream.calls.CallMethods
import io.emeraldpay.dshackle.upstream.ethereum.EthereumDirectReader.Request
import io.emeraldpay.dshackle.upstream.ethereum.EthereumDirectReader.Result
import io.emeraldpay.dshackle.upstream.ethereum.domain.Address
import io.emeraldpay.dshackle.upstream.ethereum.domain.BlockHash
import io.emeraldpay.dshackle.upstream.ethereum.domain.TransactionId
import io.emeraldpay.dshackle.upstream.ethereum.domain.Wei
import io.emeraldpay.dshackle.upstream.ethereum.json.TransactionLogJson
import io.emeraldpay.dshackle.upstream.finalization.FinalizationType
import io.emeraldpay.dshackle.upstream.rpcclient.CallParams
import io.emeraldpay.dshackle.upstream.rpcclient.ListParams
import org.apache.commons.collections4.Factory
import reactor.core.publisher.Mono
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Function

/**
 * Reader for the common operations, that use the cache when data is available or a native call with quorum verification
 */
open class EthereumCachingReader(
    private val up: Multistream,
    private val caches: Caches,
    callMethodsFactory: Factory<CallMethods>,
) : CachingReader {

    private val balanceCache = CurrentBlockCache<Address, Wei>()
    private val directReader = EthereumDirectReader(up, caches, balanceCache, callMethodsFactory)

    // at most one warm-up per chain at a time: heads that arrive meanwhile are covered as parents by the next one
    private val warming = AtomicBoolean(false)

    // when a client last asked this chain for a block by number: warm-up runs only while that is recent
    @Volatile
    private var lastBlockRequestAt = 0L

    /** for tests */
    var clock: () -> Long = System::currentTimeMillis

    open fun blockByFinalization(): Reader<FinalizationType, Result<BlockContainer>> {
        return directReader.blockByFinalizationReader
    }

    open fun blocksByIdAsCont(upstreamFilter: Selector.UpstreamFilter): Reader<BlockId, Result<BlockContainer>> {
        val idToBlockHash = Function<BlockId, Request<BlockHash>> { id -> Request(BlockHash.from(id.value), upstreamFilter) }
        return CompoundReader(
            CacheWithUpstreamIdReader(caches.getBlocksByHash()),
            RekeyingReader(idToBlockHash, directReader.blockReader),
        )
    }

    open fun blocksByHeightAsCont(upstreamFilter: Selector.UpstreamFilter): Reader<Long, Result<BlockContainer>> {
        val numToRequest = Function<Long, Request<Long>> { num -> Request(num, upstreamFilter) }
        return CompoundReader(
            CacheWithUpstreamIdReader(caches.getBlocksByHeight()),
            RekeyingReader(numToRequest, directReader.blockByHeightReader),
        )
    }

    open fun logsByHash(): Reader<BlockId, Result<List<TransactionLogJson>>> {
        return directReader.logsByHashReader
    }

    open fun txByHashAsCont(upstreamFilter: Selector.UpstreamFilter): Reader<TxId, Result<TxContainer>> {
        val idToTxHash = Function<TxId, Request<TransactionId>> { id -> Request(TransactionId.from(id.value), upstreamFilter) }
        return CompoundReader(
            CacheWithUpstreamIdReader(caches.getTxByHash()),
            RekeyingReader(idToTxHash, directReader.txReader),
        )
    }

    fun balance(): Reader<Address, Result<Wei>> {
        // TODO include height as part of cache?
        return CompoundReader(
            CacheWithUpstreamIdReader(balanceCache),
            directReader.balanceReader,
        )
    }

    fun receipts(upstreamFilter: Selector.UpstreamFilter): Reader<TxId, Result<ByteArray>> {
        val requested = RekeyingReader(
            { txid: TxId -> Request(TransactionId.from(txid.value), upstreamFilter) },
            directReader.receiptReader,
        )
        return CompoundReader(
            CacheWithUpstreamIdReader(caches.getReceipts()),
            requested,
        )
    }

    open fun chainCode(): String = up.getChain().chainCode

    override fun onUpstreamResponse(method: String, params: CallParams, result: ByteArray) {
        val list = (params as? ListParams)?.list ?: return
        // a block the recent blocks missed: keep it for the next requests
        fillFromResponse(method, list, result)
    }

    open fun recentBlocksEnabled(): Boolean = caches.recentBlocksEnabled()

    /** true while a client asked this chain for a block by number within [WARMUP_DEMAND_WINDOW] */
    open fun hasBlockDemand(): Boolean = lastBlockRequestAt > 0 && clock() - lastBlockRequestAt <= WARMUP_DEMAND_WINDOW.toMillis()

    /** called for every client eth_getBlockByNumber: keeps the warm-up running for this chain */
    open fun noteBlockRequest() {
        lastBlockRequestAt = clock()
    }

    /** see [Caches.firstRequest] */
    open fun firstRequest(height: Long?): Boolean? = caches.firstRequest(height, Defaults.cacheBlocksByNumberDepth)

    /**
     * An upstream's answer to eth_getBlockByNumber(<n|latest>, false) that the recent blocks didn't have: keep its body,
     * so the next requests for that block are served from memory. It's served only once the parent walk from a fresh
     * head reaches it, like any other recent block.
     */
    open fun fillFromResponse(method: String, params: List<Any?>, result: ByteArray) {
        if (!caches.recentBlocksEnabled() || method != "eth_getBlockByNumber" || params.size != 2 || params[1] != false) {
            return
        }
        if (result.isEmpty() || Global.nullValue.contentEquals(result)) {
            return
        }
        val block = try {
            BlockContainer.fromEthereumJson(result, "response")
        } catch (e: Exception) {
            return
        }
        if (block.full) {
            return
        }
        caches.cache(Caches.Tag.REQUESTED, block)
    }

    /** why a recent block by number isn't in memory, for the miss metric */
    open fun blockMissReason(height: Long?): String = caches.blockMissReason(height, Defaults.cacheBlocksByNumberDepth)

    /**
     * The canonical block at [height] (null = current head) from memory, without tx details; null on a miss.
     * See [Caches.canonicalBlockFromMemory].
     */
    open fun canonicalBlockFromMemory(height: Long?): BlockContainer? {
        return caches.canonicalBlockFromMemory(height, Defaults.cacheBlocksByNumberDepth)
    }

    /**
     * Puts the body of a new head block, and of its parents that the cache doesn't know yet, into the cache, so
     * eth_getBlockByNumber for recent blocks can be answered locally. A head received over WebSocket is only a header;
     * a polled head can skip heights on fast chains. Errors are ignored: a block that isn't fetched now is retried as a
     * parent of the next head, and a miss simply goes to an upstream.
     */
    open fun warmUp(head: BlockContainer) {
        if (!caches.warmUpEnabled()) {
            // also when the cache is off for the chain: nothing would be kept, so every head would fetch the whole depth again
            return
        }
        if (!hasBlockDemand()) {
            // nobody asked this chain for blocks by number lately: fetching every new block would be wasted.
            // The first requests after a quiet period go to an upstream (and fill the cache), then warm-up resumes.
            return
        }
        if (!isRecent(head, Instant.now())) {
            // a node catching up emits a head for every block it imports (hundreds per second): old blocks nobody
            // asks for by number, so don't fetch them
            return
        }
        if (!warming.compareAndSet(false, true)) {
            return
        }
        val start: Mono<BlockId> = if (caches.isBlockChecked(head.hash)) {
            Mono.justOrEmpty(head.parentHash)
        } else {
            fetchBlock(head.hash).mapNotNull { it.parentHash }
        }
        start.flatMap { warmParents(it, Defaults.cacheWarmUpDepth) }
            .onErrorResume { Mono.empty() }
            .doFinally { warming.set(false) }
            .subscribe()
    }

    private fun warmParents(hash: BlockId, depth: Int): Mono<Void> {
        if (depth <= 0 || caches.isBlockChecked(hash)) {
            return Mono.empty()
        }
        return fetchBlock(hash)
            .flatMap { block -> block.parentHash?.let { warmParents(it, depth - 1) } ?: Mono.empty() }
    }

    private fun fetchBlock(hash: BlockId): Mono<BlockContainer> {
        // the direct reader puts the result into the caches (Tag.REQUESTED)
        return directReader.blockReader.read(Request(BlockHash.from(hash.value), Selector.UpstreamFilter.default))
            .map { it.data }
            .onErrorResume { Mono.empty() }
    }

    companion object {
        /** warm-up runs while the chain got a block-by-number request within this window */
        val WARMUP_DEMAND_WINDOW: Duration = Duration.ofSeconds(60)

        /** heads older than this aren't warmed up: the node that sent them is still syncing */
        val WARMUP_MAX_AGE: Duration = Duration.ofSeconds(60)

        @JvmStatic
        fun isRecent(head: BlockContainer, now: Instant): Boolean {
            return Duration.between(head.timestamp, now) <= WARMUP_MAX_AGE
        }
    }

    override fun isRunning(): Boolean {
        // TODO should be always running?
        return true // up.isRunning
    }

    override fun start() {
        val evictCaches: Runnable = Runnable {
            balanceCache.evict()
        }
        up.getHead().onBeforeBlock(evictCaches)
    }

    override fun stop() {
    }

    private class CacheWithUpstreamIdReader<K, D : Any>(
        private val reader: Reader<K, D>,
    ) : Reader<K, Result<D>> {
        override fun read(key: K): Mono<Result<D>> {
            return reader.read(key)
                .map { Result(it, emptyList()) }
        }
    }
}
