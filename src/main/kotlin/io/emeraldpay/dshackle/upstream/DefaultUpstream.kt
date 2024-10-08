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
package io.emeraldpay.dshackle.upstream

import io.emeraldpay.api.proto.BlockchainOuterClass
import io.emeraldpay.dshackle.Chain
import io.emeraldpay.dshackle.config.ChainsConfig
import io.emeraldpay.dshackle.config.UpstreamsConfig
import io.emeraldpay.dshackle.config.UpstreamsConfig.Labels.Companion.fromMap
import io.emeraldpay.dshackle.foundation.ChainOptions
import io.emeraldpay.dshackle.startup.QuorumForLabels
import io.emeraldpay.dshackle.startup.UpstreamChangeEvent
import io.emeraldpay.dshackle.upstream.calls.CallMethods
import io.emeraldpay.dshackle.upstream.lowerbound.LowerBoundType
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks
import java.util.concurrent.atomic.AtomicReference

abstract class DefaultUpstream(
    private val id: String,
    private val hash: Short,
    defaultLag: Long?,
    defaultAvail: UpstreamAvailability,
    private val options: ChainOptions.Options,
    private val role: UpstreamsConfig.UpstreamRole,
    private var targets: CallMethods?,
    private val node: QuorumForLabels.QuorumItem?,
    private val chainConfig: ChainsConfig.ChainConfig,
    private val chain: Chain,
) : Upstream {

    constructor(
        id: String,
        hash: Short,
        options: ChainOptions.Options,
        role: UpstreamsConfig.UpstreamRole,
        targets: CallMethods?,
        node: QuorumForLabels.QuorumItem?,
        chainConfig: ChainsConfig.ChainConfig,
        chain: Chain,
    ) :
        this(id, hash, null, UpstreamAvailability.UNAVAILABLE, options, role, targets, node, chainConfig, chain)

    protected val log = LoggerFactory.getLogger(this::class.java)

    private val status = AtomicReference(Status(defaultLag, defaultAvail, statusByLag(defaultLag, defaultAvail)))
    private val statusStream = Sinks.many()
        .multicast()
        .directBestEffort<UpstreamAvailability>()
    protected val stateEventStream: Sinks.Many<UpstreamChangeEvent> = Sinks.many()
        .multicast()
        .directBestEffort()

    init {
        if (id.length < 3 || !id.matches(Regex("[a-zA-Z][a-zA-Z0-9_-]+[a-zA-Z0-9]"))) {
            throw IllegalArgumentException("Invalid upstream id: $id")
        }
    }

    override fun isAvailable(): Boolean {
        return getStatus() == UpstreamAvailability.OK || getStatus() == UpstreamAvailability.LAGGING
    }

    fun onStatus(value: BlockchainOuterClass.ChainStatus) {
        val available = value.availability
        setStatus(
            if (available != null) UpstreamAvailability.fromGrpc(available.number) else UpstreamAvailability.UNAVAILABLE,
        )
    }

    override fun getStatus(): UpstreamAvailability {
        return status.get().status
    }

    open fun setStatus(avail: UpstreamAvailability) {
        status.updateAndGet { curr ->
            Status(curr.lag, avail, statusByLag(curr.lag, avail))
        }.also {
            statusStream.emitNext(
                it.status,
            ) { _, res -> res == Sinks.EmitResult.FAIL_NON_SERIALIZED }
            log.trace("Status of upstream [$id] changed to [$it], requested change status to [$avail]")
        }
    }

    private fun statusByLag(lag: Long?, proposed: UpstreamAvailability): UpstreamAvailability {
        return if (proposed == UpstreamAvailability.OK) {
            when {
                lag == null -> proposed
                lag > chainConfig.syncingLagSize -> UpstreamAvailability.SYNCING
                lag > chainConfig.laggingLagSize -> UpstreamAvailability.LAGGING
                else -> proposed
            }
        } else {
            proposed
        }
    }

    override fun observeStatus(): Flux<UpstreamAvailability> {
        return statusStream.asFlux().distinctUntilChanged()
    }

    override fun observeState(): Flux<UpstreamChangeEvent> {
        return stateEventStream.asFlux()
    }

    override fun setLag(lag: Long) {
        lag.coerceAtLeast(0).let { nLag ->
            status.updateAndGet { curr ->
                Status(nLag, curr.avail, statusByLag(nLag, curr.avail))
            }.also {
                statusStream.emitNext(
                    it.status,
                ) { _, res -> res == Sinks.EmitResult.FAIL_NON_SERIALIZED }
                log.trace("Status of upstream [$id] changed to [$it], requested change lag to [$lag]")
            }
        }
    }

    override fun getLag(): Long? {
        return this.status.get().lag
    }

    override fun getOptions(): ChainOptions.Options {
        return options
    }

    override fun getRole(): UpstreamsConfig.UpstreamRole {
        return role
    }

    override fun getMethods(): CallMethods {
        return targets ?: throw IllegalStateException("Methods are not set")
    }

    override fun updateMethods(m: CallMethods) {
        targets = m
        sendUpstreamStateEvent(UpstreamChangeEvent.ChangeType.UPDATED)
    }

    override fun nodeId(): Short = hash

    open fun getQuorumByLabel(): QuorumForLabels {
        return node?.let { QuorumForLabels(it.copy(labels = fromMap(it.labels))) }
            ?: QuorumForLabels(QuorumForLabels.QuorumItem.empty())
    }

    override fun getId(): String {
        return id
    }

    override fun updateLowerBound(lowerBound: Long, type: LowerBoundType) {
        // NOOP
    }

    override fun predictLowerBound(type: LowerBoundType): Long {
        return 0
    }

    protected fun sendUpstreamStateEvent(eventType: UpstreamChangeEvent.ChangeType) {
        stateEventStream.emitNext(
            UpstreamChangeEvent(chain, this, eventType),
        ) { _, res -> res == Sinks.EmitResult.FAIL_NON_SERIALIZED }
    }

    data class Status(val lag: Long?, val avail: UpstreamAvailability, val status: UpstreamAvailability)

    override fun getChain(): Chain {
        return chain
    }
}
