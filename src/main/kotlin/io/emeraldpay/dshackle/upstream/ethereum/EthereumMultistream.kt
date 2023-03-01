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
package io.emeraldpay.dshackle.upstream.ethereum

import io.emeraldpay.api.proto.BlockchainOuterClass
import io.emeraldpay.dshackle.Chain
import io.emeraldpay.dshackle.cache.Caches
import io.emeraldpay.dshackle.config.UpstreamsConfig
import io.emeraldpay.dshackle.reader.JsonRpcReader
import io.emeraldpay.dshackle.upstream.ChainFees
import io.emeraldpay.dshackle.upstream.DynamicMergedHead
import io.emeraldpay.dshackle.upstream.EgressSubscription
import io.emeraldpay.dshackle.upstream.EmptyHead
import io.emeraldpay.dshackle.upstream.Head
import io.emeraldpay.dshackle.upstream.HeadLagObserver
import io.emeraldpay.dshackle.upstream.Lifecycle
import io.emeraldpay.dshackle.upstream.MergedHead
import io.emeraldpay.dshackle.upstream.Multistream
import io.emeraldpay.dshackle.upstream.Selector
import io.emeraldpay.dshackle.upstream.Upstream
import io.emeraldpay.dshackle.upstream.ethereum.subscribe.AggregatedPendingTxes
import io.emeraldpay.dshackle.upstream.ethereum.subscribe.NoPendingTxes
import io.emeraldpay.dshackle.upstream.ethereum.subscribe.PendingTxesSource
import io.emeraldpay.dshackle.upstream.forkchoice.MostWorkForkChoice
import io.emeraldpay.dshackle.upstream.forkchoice.PriorityForkChoice
import io.emeraldpay.dshackle.upstream.grpc.GrpcUpstream
import org.slf4j.LoggerFactory
import org.springframework.util.ConcurrentReferenceHashMap
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler

@Suppress("UNCHECKED_CAST")
open class EthereumMultistream(
    chain: Chain,
    val upstreams: MutableList<EthereumUpstream>,
    caches: Caches,
    headScheduler: Scheduler
) : Multistream(chain, upstreams as MutableList<Upstream>, caches), EthereumLikeMultistream {

    companion object {
        private val log = LoggerFactory.getLogger(EthereumMultistream::class.java)
    }

    private var head: DynamicMergedHead = DynamicMergedHead(
        PriorityForkChoice(),
        "ETH Multistream of ${chain.chainCode}",
        headScheduler
    )

    private val filteredHeads: MutableMap<String, Head> =
        ConcurrentReferenceHashMap(16, ConcurrentReferenceHashMap.ReferenceType.WEAK)

    private val reader: EthereumCachingReader = EthereumCachingReader(this, this.caches, getMethodsFactory())
    private var subscribe = EthereumEgressSubscription(this, NoPendingTxes())

    private val supportsEIP1559 = when (chain) {
        Chain.ETHEREUM, Chain.TESTNET_ROPSTEN,
        Chain.TESTNET_GOERLI, Chain.TESTNET_RINKEBY,
        Chain.TESTNET_SEPOLIA, Chain.ARBITRUM,
        Chain.OPTIMISM, Chain.TESTNET_ARBITRUM,
        Chain.TESTNET_OPTIMISM -> true
        else -> false
    }
    private val feeEstimation = if (supportsEIP1559) EthereumPriorityFees(this, reader, 256)
    else EthereumLegacyFees(this, reader, 256)

    init {
        this.init()
    }

    override fun init() {
        if (upstreams.size > 0) {
            upstreams.forEach { addHead(it) }
        }
        super.init()
    }

    override fun onUpstreamsUpdated() {
        super.onUpstreamsUpdated()

        val pendingTxes: PendingTxesSource = upstreams
            .mapNotNull {
                it.getIngressSubscription().getPendingTxes()
            }.let {
                if (it.isEmpty()) {
                    NoPendingTxes()
                } else if (it.size == 1) {
                    it.first()
                } else {
                    AggregatedPendingTxes(it)
                }
            }
        subscribe = EthereumEgressSubscription(this, pendingTxes)
    }

    override fun start() {
        super.start()
        head.start()
        onHeadUpdated(head)
        reader.start()
    }

    override fun stop() {
        super.stop()
        reader.stop()
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

    override fun makeLagObserver(): HeadLagObserver {
        return EthereumHeadLagObserver(head, upstreams as Collection<Upstream>)
    }

    override fun isRunning(): Boolean {
        return super.isRunning() || reader.isRunning()
    }

    override fun getReader(): EthereumCachingReader {
        return reader
    }

    override fun getHead(): Head {
        return head
    }

    override fun tryProxy(
        matcher: Selector.Matcher,
        request: BlockchainOuterClass.NativeSubscribeRequest
    ): Flux<out Any>? =
        upstreams.filter {
            matcher.matches(it)
        }.takeIf { ups ->
            ups.size == 1 && ups.all { it.isGrpc() }
        }?.map {
            it as GrpcUpstream
        }?.map {
            it.getBlockchainApi().nativeSubscribe(request)
        }?.let {
            Flux.merge(it)
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

    override fun getEgressSubscription(): EgressSubscription {
        return subscribe
    }

    override fun getLocalReader(localEnabled: Boolean): Mono<JsonRpcReader> {
        return Mono.just(EthereumLocalReader(reader, getMethods(), getHead(), localEnabled))
    }

    override fun getHead(mather: Selector.Matcher): Head =
        filteredHeads.computeIfAbsent(mather.describeInternal().intern()) { _ ->
            upstreams.filter { mather.matches(it) }
                .apply {
                    log.debug("Found $size upstreams matching [${mather.describeInternal()}]")
                }.let {
                    val selected = it.map { it.getHead() }
                    when (it.size) {
                        0 -> EmptyHead()
                        1 -> selected.first()
                        else -> MergedHead(selected, MostWorkForkChoice(), "Eth head ${it.map { it.getId() }}").apply {
                            start()
                        }
                    }
                }
        }

    override fun getFeeEstimation(): ChainFees {
        return feeEstimation
    }
}
