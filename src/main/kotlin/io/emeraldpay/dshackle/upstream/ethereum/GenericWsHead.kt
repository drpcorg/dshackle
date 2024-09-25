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

import io.emeraldpay.dshackle.data.BlockContainer
import io.emeraldpay.dshackle.reader.ChainReader
import io.emeraldpay.dshackle.upstream.BlockValidator
import io.emeraldpay.dshackle.upstream.DefaultUpstream
import io.emeraldpay.dshackle.upstream.Lifecycle
import io.emeraldpay.dshackle.upstream.SingleValidator
import io.emeraldpay.dshackle.upstream.ValidateUpstreamSettingsResult
import io.emeraldpay.dshackle.upstream.ValidateUpstreamSettingsResult.UPSTREAM_FATAL_SETTINGS_ERROR
import io.emeraldpay.dshackle.upstream.ValidateUpstreamSettingsResult.UPSTREAM_SETTINGS_ERROR
import io.emeraldpay.dshackle.upstream.ValidateUpstreamSettingsResult.UPSTREAM_VALID
import io.emeraldpay.dshackle.upstream.forkchoice.ForkChoice
import io.emeraldpay.dshackle.upstream.generic.ChainSpecific
import io.emeraldpay.dshackle.upstream.generic.GenericHead
import io.emeraldpay.dshackle.upstream.rpcclient.JsonRpcWsClient
import reactor.core.Disposable
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.core.scheduler.Scheduler
import reactor.kotlin.core.publisher.switchIfEmpty
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class GenericWsHead(
    forkChoice: ForkChoice,
    blockValidator: BlockValidator,
    private val api: ChainReader,
    private val wsSubscriptions: WsSubscriptions,
    private val wsConnectionResubscribeScheduler: Scheduler,
    headScheduler: Scheduler,
    upstream: DefaultUpstream,
    private val chainSpecific: ChainSpecific,
    jsonRpcWsClient: JsonRpcWsClient,
    timeout: Duration,
) : GenericHead(upstream.getId(), forkChoice, blockValidator, headScheduler, chainSpecific), Lifecycle {
    private val wsHeadTimeout = run {
        val defaultTimeout = Duration.ofMinutes(1)
        if (timeout >= defaultTimeout) {
            timeout.plus(defaultTimeout)
        } else {
            defaultTimeout
        }
    }.also {
        log.info("WS head timeout for ${upstream.getId()} is $it")
    }
    private val chainIdValidator = chainSpecific.chainSettingsValidator(upstream.getChain(), upstream, jsonRpcWsClient)

    private val connectionId = AtomicReference<String?>(null)
    private val subscribed = AtomicBoolean(false)
    private val connected = AtomicBoolean(false)
    private val isSyncing = AtomicBoolean(false)

    private val subscription = AtomicReference<Disposable?>()
    private val headResubSubscription = AtomicReference<Disposable?>()
    private val noHeadUpdatesSink = Sinks.many().multicast().directBestEffort<Boolean>()

    private val subscriptionId = AtomicReference("")

    override fun isRunning(): Boolean {
        return subscription.get() != null
    }

    override fun start() {
        super.start()
        this.subscription.get()?.dispose()
        this.subscribed.set(true)
        val heads = Flux.merge(
            // get the current block, not just wait for the next update
            getLatestBlock(api),
            listenNewHeads(),
        )
        this.subscription.set(super.follow(heads))

        if (headResubSubscription.get() == null) {
            headResubSubscription.set(registerHeadResubscribeFlux())
        }
    }

    override fun onNoHeadUpdates() {
        noHeadUpdatesSink.tryEmitNext(true)
    }

    override fun onSyncingNode(isSyncing: Boolean) {
        if (isSyncing && !this.isSyncing.get()) {
            cancelSub()
        }
        this.isSyncing.set(isSyncing)
    }

    private fun listenNewHeads(): Flux<BlockContainer> {
        return Mono.justOrEmpty(chainIdValidator)
            .flatMap {
                it!!.validate(UPSTREAM_SETTINGS_ERROR)
            }
            .switchIfEmpty {
                Mono.just(UPSTREAM_VALID)
            }
            .flatMapMany {
                when (it) {
                    UPSTREAM_VALID -> {
                        subscribe()
                            .flatMap { data ->
                                chainSpecific.getFromHeader(data, "unknown", api)
                            }
                            .timeout(wsHeadTimeout, Mono.error(RuntimeException("No response from subscribe to newHeads")))
                            .onErrorResume { err ->
                                log.error("Error getting heads for {}, message {}", upstreamId, err.message)
                                unsubscribe()
                            }
                    }
                    UPSTREAM_SETTINGS_ERROR -> {
                        log.warn("Couldn't check chain settings via ws connection for {}, ws sub will be recreated", upstreamId)
                        subscribed.set(false)
                        Mono.empty()
                    }
                    UPSTREAM_FATAL_SETTINGS_ERROR -> {
                        log.error("Chain settings check hasn't been passed via ws connection, upstream {} will be removed", upstreamId)
                        headLivenessSink.emitNext(HeadLivenessState.FATAL_ERROR) { _, res -> res == Sinks.EmitResult.FAIL_NON_SERIALIZED }
                        Mono.empty()
                    }
                }
            }
    }

    override fun stop() {
        super.stop()
        cancelSub()
        headResubSubscription.getAndSet(null)?.dispose()
    }

    override fun chainIdValidator(): SingleValidator<ValidateUpstreamSettingsResult>? {
        return chainIdValidator
    }

    private fun unsubscribe(): Mono<BlockContainer> {
        subscribed.set(false)
        return wsSubscriptions.unsubscribe(chainSpecific.unsubscribeNewHeadsRequest(subscriptionId.get()).copy(id = ids.getAndIncrement()))
            .flatMap { it.requireResult() }
            .doOnNext { log.warn("{} has just unsubscribed from newHeads", upstreamId) }
            .onErrorResume {
                log.error("{} couldn't unsubscribe from newHeads", upstreamId, it)
                Mono.empty()
            }
            .then(Mono.empty())
    }

    private val ids = AtomicInteger(1)

    private fun subscribe(): Flux<ByteArray> {
        return try {
            wsSubscriptions.subscribe(chainSpecific.listenNewHeadsRequest().copy(id = ids.getAndIncrement()))
                .also {
                    connectionId.set(it.connectionId)
                    subscriptionId.set(it.subId.get())
                    if (!connected.get()) {
                        connected.set(true)
                    }
                }.data
        } catch (e: Exception) {
            Flux.error(e)
        }
    }

    private fun registerHeadResubscribeFlux(): Disposable {
        val connectionStates = wsSubscriptions.connectionInfoFlux()
            .map {
                if (it.connectionId == connectionId.get() && it.connectionState == WsConnection.ConnectionState.DISCONNECTED) {
                    headLivenessSink.emitNext(HeadLivenessState.DISCONNECTED) { _, res -> res == Sinks.EmitResult.FAIL_NON_SERIALIZED }
                    subscribed.set(false)
                    connected.set(false)
                    connectionId.set(null)
                } else if (it.connectionState == WsConnection.ConnectionState.CONNECTED) {
                    connected.set(true)
                    return@map true
                }
                return@map false
            }

        return Flux.merge(
            noHeadUpdatesSink.asFlux(),
            connectionStates,
        ).publishOn(wsConnectionResubscribeScheduler)
            .filter { it && !subscribed.get() && connected.get() && !isSyncing.get() }
            .subscribe {
                log.warn("Restart ws head, upstreamId: $upstreamId")
                start()
            }
    }

    private fun cancelSub() {
        subscription.getAndSet(null)?.dispose()
        subscribed.set(false)
    }
}
