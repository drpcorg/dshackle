/**
 * Copyright (c) 2020 EmeraldPay, Inc
 * Copyright (c) 2020 ETCDEV GmbH
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
package io.emeraldpay.dshackle.upstream.generic

import io.emeraldpay.api.proto.BlockchainOuterClass
import io.emeraldpay.dshackle.Chain
import io.emeraldpay.dshackle.cache.Caches
import io.emeraldpay.dshackle.config.UpstreamsConfig
import io.emeraldpay.dshackle.reader.JsonRpcReader
import io.emeraldpay.dshackle.upstream.CachingReader
import io.emeraldpay.dshackle.upstream.DistanceExtractor
import io.emeraldpay.dshackle.upstream.DynamicMergedHead
import io.emeraldpay.dshackle.upstream.EgressSubscription
import io.emeraldpay.dshackle.upstream.EmptyHead
import io.emeraldpay.dshackle.upstream.Head
import io.emeraldpay.dshackle.upstream.HeadLagObserver
import io.emeraldpay.dshackle.upstream.Lifecycle
import io.emeraldpay.dshackle.upstream.MergedHead
import io.emeraldpay.dshackle.upstream.Multistream
import io.emeraldpay.dshackle.upstream.Selector
import io.emeraldpay.dshackle.upstream.Selector.Matcher
import io.emeraldpay.dshackle.upstream.Upstream
import io.emeraldpay.dshackle.upstream.forkchoice.PriorityForkChoice
import io.emeraldpay.dshackle.upstream.grpc.GrpcUpstream
import org.springframework.util.ConcurrentReferenceHashMap
import org.springframework.util.ConcurrentReferenceHashMap.ReferenceType.WEAK
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler

open class GenericMultistream(
    chain: Chain,
    private val upstreams: MutableList<Upstream>,
    caches: Caches,
    private val headScheduler: Scheduler,
    cachingReaderBuilder: CachingReaderBuilder,
    private val localReaderBuilder: LocalReaderBuilder,
    private val subscriptionBuilder: SubscriptionBuilder,
) : Multistream(chain, caches) {

    private val cachingReader = cachingReaderBuilder(this, caches, getMethodsFactory())

    override fun getUpstreams(): MutableList<out Upstream> {
        return upstreams
    }

    override fun addUpstreamInternal(u: Upstream) {
        upstreams.add(u as GenericUpstream)
    }

    private var head: DynamicMergedHead = DynamicMergedHead(
        PriorityForkChoice(),
        "Multistream of ${chain.chainCode}",
        headScheduler,
    )

    init {
        this.init()
    }

    private var subscription: EgressSubscription = subscriptionBuilder(this)

    override fun init() {
        if (upstreams.size > 0) {
            upstreams.forEach { addHead(it) }
        }
        super.init()
    }

    private val filteredHeads: MutableMap<String, Head> =
        ConcurrentReferenceHashMap(16, WEAK)

    override fun start() {
        super.start()
        head.start()
        onHeadUpdated(head)
        cachingReader.start()
    }

    override fun stop() {
        super.stop()
        cachingReader.stop()
        filteredHeads.clear()
    }

    override fun addHead(upstream: Upstream) {
        val newHead = upstream.getHead()
        if (newHead is Lifecycle && !newHead.isRunning()) {
            newHead.start()
        }
        head.addHead(upstream)
    }

    override fun removeHead(upstreamId: String) {
        head.removeHead(upstreamId)
    }

    override fun isRunning(): Boolean {
        return super.isRunning() || cachingReader.isRunning()
    }

    override fun makeLagObserver(): HeadLagObserver =
        HeadLagObserver(head, upstreams, DistanceExtractor::extractPriorityDistance, headScheduler, 6).apply {
            start()
        }

    override fun getCachingReader(): CachingReader? {
        return cachingReader
    }

    override fun getHead(mather: Matcher): Head {
        if (mather == Selector.empty || mather == Selector.anyLabel) {
            return head
        } else {
            return filteredHeads.computeIfAbsent(mather.describeInternal().intern()) { _ ->
                upstreams.filter { mather.matches(it) }
                    .apply {
                        log.debug("Found $size upstreams matching [${mather.describeInternal()}]")
                    }
                    .let {
                        val selected = it.map { it.getHead() }
                        when (it.size) {
                            0 -> EmptyHead()
                            1 -> selected.first()
                            else -> MergedHead(
                                selected,
                                PriorityForkChoice(),
                                headScheduler,
                                "Head for ${it.map { it.getId() }}",
                            ).apply {
                                start()
                            }
                        }
                    }
            }
        }
    }

    override fun getHead(): Head {
        return head
    }

    override fun getEnrichedHead(mather: Matcher): Head {
        return getHead()
    }

    override fun getLabels(): Collection<UpstreamsConfig.Labels> {
        return upstreams.flatMap { it.getLabels() }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Upstream> cast(selfType: Class<T>): T {
        if (!selfType.isAssignableFrom(this.javaClass)) {
            throw ClassCastException("Cannot cast ${this.javaClass} to $selfType")
        }
        return this as T
    }

    override fun getLocalReader(): Mono<JsonRpcReader> {
        return localReaderBuilder(cachingReader, getMethods(), getHead())
    }

    override fun getEgressSubscription(): EgressSubscription {
        return subscription
    }

    override fun onUpstreamsUpdated() {
        super.onUpstreamsUpdated()
        subscription = subscriptionBuilder(this)
    }

    override fun tryProxySubscribe(
        matcher: Matcher,
        request: BlockchainOuterClass.NativeSubscribeRequest,
    ): Flux<out Any>? =
        upstreams.filter {
            matcher.matches(it)
        }.takeIf { ups ->
            ups.size == 1 && ups.all { it.isGrpc() }
        }?.map {
            it as GrpcUpstream
        }?.map {
            it.proxySubscribe(request)
        }?.let {
            Flux.merge(it)
        }
}
