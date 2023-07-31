/**
 * Copyright (c) 2020 EmeraldPay, Inc
 * Copyright (c) 2019 ETCDEV GmbH
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

import com.google.common.cache.CacheBuilder
import io.emeraldpay.dshackle.data.BlockContainer
import io.emeraldpay.dshackle.data.BlockId
import io.emeraldpay.dshackle.reader.JsonRpcReader
import io.emeraldpay.dshackle.upstream.Head
import io.emeraldpay.dshackle.upstream.Lifecycle
import reactor.core.Disposable
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.core.scheduler.Scheduler
import java.time.Duration

class EnrichedMergedHead constructor(
    private val sources: Iterable<Head>,
    private val referenceHead: Head,
    private val headScheduler: Scheduler,
    private val api: JsonRpcReader
) : Head, Lifecycle {

    private val enrichedBlocks = CacheBuilder.newBuilder()
        .maximumSize(10)
        .build<BlockId, BlockContainer>()
    private val enrichedPromises = CacheBuilder.newBuilder()
        .maximumSize(10)
        .build<BlockId, Sinks.One<BlockContainer>>()
    private var cacheSub: Disposable? = null

    private fun getEnrichBlockMono(id: BlockId): Mono<BlockContainer> {
        val block = enrichedBlocks.getIfPresent(id)
        return if (block != null) {
            Mono.just(block)
        } else {
            enrichedPromises.get(id) {
                Sinks.one()
            }.asMono()
        }
    }

    override fun getFlux(): Flux<BlockContainer> {
        return referenceHead.getFlux().concatMap { block ->
            if (block.enriched) {
                Mono.just(block)
            } else {
                Mono.firstWithValue(
                    getEnrichBlockMono(block.hash),
                    Mono.just(block)
                        .delayElement(Duration.ofSeconds(1))
                        .flatMap { EthereumBlockEnricher.enrich(it.hash.toHex(), api, headScheduler, it.upstreamId) }
                )
            }
        }
    }

    override fun onBeforeBlock(handler: Runnable) {}

    override fun getCurrentHeight(): Long? {
        return referenceHead.getCurrentHeight()
    }

    override fun isRunning(): Boolean {
        return cacheSub != null
    }

    override fun start() {
        cacheSub?.dispose()
        sources.forEach { head ->
            if (head is Lifecycle && !head.isRunning()) {
                head.start()
            }
        }
        if (referenceHead is Lifecycle && !referenceHead.isRunning()) {
            referenceHead.start()
        }
        cacheSub = Flux.merge(sources.map { it.getFlux() }).subscribe { block ->
            if (block.enriched) {
                enrichedBlocks.put(block.hash, block)
                enrichedPromises.get(block.hash) { Sinks.one() }.tryEmitValue(block)
            }
        }
    }

    override fun stop() {
        cacheSub?.dispose()
        cacheSub = null
    }

    override fun onSyncingNode(isSyncing: Boolean) {}
}
