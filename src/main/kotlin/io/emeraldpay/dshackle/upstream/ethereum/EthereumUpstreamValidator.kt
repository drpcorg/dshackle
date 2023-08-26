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

import com.fasterxml.jackson.databind.ObjectMapper
import io.emeraldpay.dshackle.Chain
import io.emeraldpay.dshackle.Defaults
import io.emeraldpay.dshackle.Global
import io.emeraldpay.dshackle.config.UpstreamsConfig
import io.emeraldpay.dshackle.upstream.Upstream
import io.emeraldpay.dshackle.upstream.UpstreamAvailability
import io.emeraldpay.dshackle.upstream.calls.DefaultEthereumMethods
import io.emeraldpay.dshackle.upstream.calls.DefaultEthereumMethods.Companion.CHAIN_DATA
import io.emeraldpay.dshackle.upstream.calls.DefaultEthereumMethods.Companion.getChainByData
import io.emeraldpay.dshackle.upstream.rpcclient.JsonRpcRequest
import io.emeraldpay.dshackle.upstream.rpcclient.JsonRpcResponse
import io.emeraldpay.etherjar.domain.Address
import io.emeraldpay.etherjar.hex.HexData
import io.emeraldpay.etherjar.rpc.json.SyncingJson
import io.emeraldpay.etherjar.rpc.json.TransactionCallJson
import org.slf4j.LoggerFactory
import org.springframework.scheduling.concurrent.CustomizableThreadFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import reactor.kotlin.extra.retry.retryRandomBackoff
import reactor.util.function.Tuple2
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeoutException

open class EthereumUpstreamValidator @JvmOverloads constructor(
    private val chain: Chain,
    private val upstream: Upstream,
    private val options: UpstreamsConfig.Options,
    private val callLimitContract: String? = null
) {
    companion object {
        private val log = LoggerFactory.getLogger(EthereumUpstreamValidator::class.java)
        val scheduler =
            Schedulers.fromExecutor(Executors.newCachedThreadPool(CustomizableThreadFactory("ethereum-validator")))
    }

    private val objectMapper: ObjectMapper = Global.objectMapper

    open fun validate(): Mono<UpstreamAvailability> {
        return Mono.zip(
            validateSyncing(),
            validatePeers()
        )
            .map(::resolve)
            .defaultIfEmpty(UpstreamAvailability.UNAVAILABLE)
            .onErrorReturn(UpstreamAvailability.UNAVAILABLE)
    }

    fun resolve(results: Tuple2<UpstreamAvailability, UpstreamAvailability>): UpstreamAvailability {
        val cp = Comparator { avail1: UpstreamAvailability, avail2: UpstreamAvailability -> if (avail1.isBetterTo(avail2)) -1 else 1 }
        return listOf(results.t1, results.t2).sortedWith(cp).last()
    }

    fun validateSyncing(): Mono<UpstreamAvailability> {
        if (!options.validateSyncing) {
            return Mono.just(UpstreamAvailability.OK)
        }
        return upstream.getIngressReader()
            .read(JsonRpcRequest("eth_syncing", listOf()))
            .flatMap(JsonRpcResponse::requireResult)
            .map { objectMapper.readValue(it, SyncingJson::class.java) }
            .timeout(
                Defaults.timeoutInternal,
                Mono.fromCallable { log.warn("No response for eth_syncing from ${upstream.getId()}") }
                    .then(Mono.error(TimeoutException("Validation timeout for Syncing")))
            )
            .map {
                val isSyncing = it.isSyncing
                upstream.getHead().onSyncingNode(isSyncing)
                if (isSyncing) {
                    UpstreamAvailability.SYNCING
                } else {
                    UpstreamAvailability.OK
                }
            }
            .doOnError { err -> log.error("Error during syncing validation for ${upstream.getId()}", err) }
            .onErrorReturn(UpstreamAvailability.UNAVAILABLE)
    }

    fun validatePeers(): Mono<UpstreamAvailability> {
        if (!options.validatePeers || options.minPeers == 0) {
            return Mono.just(UpstreamAvailability.OK)
        }
        return upstream
            .getIngressReader()
            .read(JsonRpcRequest("net_peerCount", listOf()))
            .flatMap(JsonRpcResponse::requireStringResult)
            .map(Integer::decode)
            .timeout(
                Defaults.timeoutInternal,
                Mono.fromCallable { log.warn("No response for net_peerCount from ${upstream.getId()}") }
                    .then(Mono.error(TimeoutException("Validation timeout for Peers")))
            )
            .map { count ->
                val minPeers = options.minPeers
                if (count < minPeers) {
                    UpstreamAvailability.IMMATURE
                } else {
                    UpstreamAvailability.OK
                }
            }
            .doOnError { err -> log.error("Error during peer count validation for ${upstream.getId()}", err) }
            .onErrorReturn(UpstreamAvailability.UNAVAILABLE)
    }

    fun start(): Flux<UpstreamAvailability> {
        return Flux.interval(
            Duration.ZERO,
            Duration.ofSeconds(options.validationInterval.toLong()),
        ).subscribeOn(scheduler)
            .flatMap {
                validate()
            }
    }

    fun validateUpstreamSettings(): Boolean {
        return Mono.zip(
            validateChain(),
            validateCallLimit(),
            validateOldBlocks()
        ).map {
            it.t1 && it.t2 && it.t3
        }.block() ?: false
    }

    private fun validateChain(): Mono<Boolean> {
        if (!options.validateChain) {
            return Mono.just(true)
        }
        return Mono.zip(
            chainId(),
            netVersion()
        )
            .map {
                val chainData = CHAIN_DATA[chain] ?: return@map false
                val isChainValid = chainData.chainId == it.t1 && chainData.netVersion == it.t2

                if (!isChainValid) {
                    val actualChain = getChainByData(
                        DefaultEthereumMethods.HardcodedData.createHardcodedData(it.t2, it.t1)
                    )?.chainName
                    log.warn(
                        "${chain.chainName} is specified for upstream ${upstream.getId()} " +
                            "but actually it is $actualChain with chainId ${it.t1} and net_version ${it.t2}"
                    )
                }

                isChainValid
            }
            .onErrorResume {
                log.error("Error during chain validation", it)
                Mono.just(false)
            }
    }

    private fun validateCallLimit(): Mono<Boolean> {
        if (!options.validateCallLimit || callLimitContract == null) {
            return Mono.just(true)
        }
        return upstream.getIngressReader()
            .read(
                JsonRpcRequest(
                    "eth_call",
                    listOf(
                        TransactionCallJson(
                            Address.from(callLimitContract),
                            // calling contract with param 200_000, meaning it will generate 200k symbols or response
                            // f4240 + metadata — ~1 million
                            HexData.from("0xd8a26e3a00000000000000000000000000000000000000000000000000000000000f4240")
                        ),
                        "latest"
                    )
                )
            )
            .flatMap(JsonRpcResponse::requireResult)
            .map { true }
            .onErrorResume {
                if (it.message != null && it.message!!.contains("rpc.returndata.limit")) {
                    log.warn(
                        "Error: ${it.message}. Node ${upstream.getId()} is probably incorrectly configured. " +
                            "You need to set up your return limit to at least 1_100_000. " +
                            "Erigon config example: https://github.com/ledgerwatch/erigon/blob/d014da4dc039ea97caf04ed29feb2af92b7b129d/cmd/utils/flags.go#L369"
                    )
                    Mono.just(false)
                } else {
                    Mono.error(it)
                }
            }
            .timeout(
                Defaults.timeoutInternal,
                Mono.fromCallable { log.error("No response for eth_call limit check from ${upstream.getId()}") }
                    .then(Mono.error(TimeoutException("Validation timeout for call limit")))
            )
            .retryRandomBackoff(3, Duration.ofMillis(100), Duration.ofMillis(500)) { ctx ->
                log.warn(
                    "error during validateCallLimit for ${upstream.getId()}, iteration ${ctx.iteration()}, " +
                        "message ${ctx.exception().message}"
                )
            }
            .onErrorReturn(false)
    }

    private fun validateOldBlocks(): Mono<Boolean> {
        return EthereumArchiveBlockNumberReader(upstream.getIngressReader())
            .readArchiveBlock()
            .flatMap {
                upstream.getIngressReader()
                    .read(JsonRpcRequest("eth_getBlockByNumber", listOf(it, false)))
                    .flatMap(JsonRpcResponse::requireResult)
            }
            .retryRandomBackoff(3, Duration.ofMillis(100), Duration.ofMillis(500)) { ctx ->
                log.warn(
                    "error during old block retrieving for ${upstream.getId()}, iteration ${ctx.iteration()}, " +
                        "message ${ctx.exception().message}"
                )
            }
            .map { result ->
                val receivedResult = result.isNotEmpty() && !Global.nullValue.contentEquals(result)
                if (!receivedResult) {
                    log.warn(
                        "Node ${upstream.getId()} probably is synced incorrectly, it is not possible to get old blocks"
                    )
                }
                true
            }
            .onErrorResume {
                log.warn("Error during old blocks validation", it)
                Mono.just(true)
            }
    }

    private fun chainId(): Mono<String> {
        return upstream.getIngressReader()
            .read(JsonRpcRequest("eth_chainId", emptyList()))
            .retryRandomBackoff(3, Duration.ofMillis(100), Duration.ofMillis(500)) { ctx ->
                log.warn(
                    "error during chainId retrieving for ${upstream.getId()}, iteration ${ctx.iteration()}, " +
                        "message ${ctx.exception().message}"
                )
            }
            .doOnError { log.error("Error during execution 'eth_chainId' - ${it.message} for ${upstream.getId()}") }
            .flatMap(JsonRpcResponse::requireResult)
            .map { String(it) }
    }

    private fun netVersion(): Mono<String> {
        return upstream.getIngressReader()
            .read(JsonRpcRequest("net_version", emptyList()))
            .retryRandomBackoff(3, Duration.ofMillis(100), Duration.ofMillis(500)) { ctx ->
                log.warn(
                    "error during netVersion retrieving for ${upstream.getId()}, iteration ${ctx.iteration()}, " +
                        "message ${ctx.exception().message}"
                )
            }
            .doOnError { log.error("Error during execution 'net_version' - ${it.message} for ${upstream.getId()}") }
            .flatMap(JsonRpcResponse::requireResult)
            .map { String(it) }
    }
}
