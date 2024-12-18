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

import io.emeraldpay.dshackle.cache.Caches
import io.emeraldpay.dshackle.cache.CurrentBlockCache
import io.emeraldpay.dshackle.commons.CACHE_BLOCK_BY_HASH_READER
import io.emeraldpay.dshackle.commons.CACHE_BLOCK_BY_HEIGHT_READER
import io.emeraldpay.dshackle.commons.CACHE_RECEIPTS_READER
import io.emeraldpay.dshackle.commons.CACHE_TX_BY_HASH_READER
import io.emeraldpay.dshackle.commons.DIRECT_QUORUM_RPC_READER
import io.emeraldpay.dshackle.data.BlockContainer
import io.emeraldpay.dshackle.data.BlockId
import io.emeraldpay.dshackle.data.TxContainer
import io.emeraldpay.dshackle.data.TxId
import io.emeraldpay.dshackle.reader.CompoundReader
import io.emeraldpay.dshackle.reader.Reader
import io.emeraldpay.dshackle.reader.RekeyingReader
import io.emeraldpay.dshackle.reader.SpannedReader
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
import org.apache.commons.collections4.Factory
import org.springframework.cloud.sleuth.Tracer
import reactor.core.publisher.Mono
import java.util.function.Function

/**
 * Reader for the common operations, that use the cache when data is available or a native call with quorum verification
 */
open class EthereumCachingReader(
    private val up: Multistream,
    private val caches: Caches,
    callMethodsFactory: Factory<CallMethods>,
    private val tracer: Tracer,
) : CachingReader {

    private val balanceCache = CurrentBlockCache<Address, Wei>()
    private val directReader = EthereumDirectReader(up, caches, balanceCache, callMethodsFactory, tracer)

    open fun blockByFinalization(): Reader<FinalizationType, Result<BlockContainer>> {
        return SpannedReader(directReader.blockByFinalizationReader, tracer, DIRECT_QUORUM_RPC_READER)
    }

    open fun blocksByIdAsCont(upstreamFilter: Selector.UpstreamFilter): Reader<BlockId, Result<BlockContainer>> {
        val idToBlockHash = Function<BlockId, Request<BlockHash>> { id -> Request(BlockHash.from(id.value), upstreamFilter) }
        return CompoundReader(
            SpannedReader(CacheWithUpstreamIdReader(caches.getBlocksByHash()), tracer, CACHE_BLOCK_BY_HASH_READER),
            SpannedReader(RekeyingReader(idToBlockHash, directReader.blockReader), tracer, DIRECT_QUORUM_RPC_READER),
        )
    }

    open fun blocksByHeightAsCont(upstreamFilter: Selector.UpstreamFilter): Reader<Long, Result<BlockContainer>> {
        val numToRequest = Function<Long, Request<Long>> { num -> Request(num, upstreamFilter) }
        return CompoundReader(
            SpannedReader(CacheWithUpstreamIdReader(caches.getBlocksByHeight()), tracer, CACHE_BLOCK_BY_HEIGHT_READER),
            SpannedReader(RekeyingReader(numToRequest, directReader.blockByHeightReader), tracer, DIRECT_QUORUM_RPC_READER),
        )
    }

    open fun logsByHash(): Reader<BlockId, Result<List<TransactionLogJson>>> {
        return directReader.logsByHashReader
    }

    open fun txByHashAsCont(upstreamFilter: Selector.UpstreamFilter): Reader<TxId, Result<TxContainer>> {
        val idToTxHash = Function<TxId, Request<TransactionId>> { id -> Request(TransactionId.from(id.value), upstreamFilter) }
        return CompoundReader(
            CacheWithUpstreamIdReader(SpannedReader(caches.getTxByHash(), tracer, CACHE_TX_BY_HASH_READER)),
            SpannedReader(RekeyingReader(idToTxHash, directReader.txReader), tracer, DIRECT_QUORUM_RPC_READER),
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
            CacheWithUpstreamIdReader(SpannedReader(caches.getReceipts(), tracer, CACHE_RECEIPTS_READER)),
            SpannedReader(requested, tracer, DIRECT_QUORUM_RPC_READER),
        )
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

    private class CacheWithUpstreamIdReader<K, D>(
        private val reader: Reader<K, D>,
    ) : Reader<K, Result<D>> {
        override fun read(key: K): Mono<Result<D>> {
            return reader.read(key)
                .map { Result(it, emptyList()) }
        }
    }
}
