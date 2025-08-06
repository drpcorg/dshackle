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

import io.emeraldpay.dshackle.Chain
import io.emeraldpay.dshackle.Defaults
import io.emeraldpay.dshackle.Global
import io.emeraldpay.dshackle.config.ChainsConfig.ChainConfig
import io.emeraldpay.dshackle.foundation.ChainOptions
import io.emeraldpay.dshackle.reader.ChainReader
import io.emeraldpay.dshackle.upstream.ChainRequest
import io.emeraldpay.dshackle.upstream.ChainResponse
import io.emeraldpay.dshackle.upstream.SingleValidator
import io.emeraldpay.dshackle.upstream.Upstream
import io.emeraldpay.dshackle.upstream.ValidateUpstreamSettingsResult
import io.emeraldpay.dshackle.upstream.ethereum.domain.Address
import io.emeraldpay.dshackle.upstream.ethereum.hex.HexData
import io.emeraldpay.dshackle.upstream.ethereum.json.TransactionCallJson
import io.emeraldpay.dshackle.upstream.rpcclient.ListParams
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono
import reactor.kotlin.extra.retry.retryRandomBackoff
import java.math.BigInteger
import java.time.Duration
import java.util.concurrent.TimeoutException
import java.util.function.Supplier

interface CallLimitValidator : SingleValidator<ValidateUpstreamSettingsResult> {
    fun isEnabled(): Boolean
}

abstract class AbstractCallLimitValidator(
    private val upstream: Upstream,
    private val options: ChainOptions.Options,
) : CallLimitValidator {

    companion object {
        @JvmStatic
        val log: Logger = LoggerFactory.getLogger(AbstractCallLimitValidator::class.java)
    }
    override fun validate(onError: ValidateUpstreamSettingsResult): Mono<ValidateUpstreamSettingsResult> {
        return upstream.getIngressReader()
            .read(createRequest())
            .flatMap(ChainResponse::requireResult)
            .map { ValidateUpstreamSettingsResult.UPSTREAM_VALID }
            .onErrorResume {
                if (isLimitError(it)) {
                    log.warn(
                        "Error: ${it.message}. Node ${upstream.getId()} is probably incorrectly configured. " +
                            "You need to set up your return limit to at least ${options.callLimitSize}. " +
                            "Erigon config example: https://github.com/ledgerwatch/erigon/blob/d014da4dc039ea97caf04ed29feb2af92b7b129d/cmd/utils/flags.go#L369",
                    )
                    Mono.just(ValidateUpstreamSettingsResult.UPSTREAM_FATAL_SETTINGS_ERROR)
                } else {
                    Mono.error(it)
                }
            }
            .timeout(
                Defaults.timeoutInternal,
                Mono.fromCallable { log.error("No response for eth_call limit check from ${upstream.getId()}") }
                    .then(Mono.error(TimeoutException("Validation timeout for call limit"))),
            )
            .retryRandomBackoff(3, Duration.ofMillis(100), Duration.ofMillis(500)) { ctx ->
                log.warn(
                    "error during validateCallLimit for ${upstream.getId()}, iteration ${ctx.iteration()}, " +
                        "message ${ctx.exception().message}",
                )
            }
            .onErrorReturn(ValidateUpstreamSettingsResult.UPSTREAM_SETTINGS_ERROR)
    }

    abstract fun createRequest(): ChainRequest

    abstract fun isLimitError(err: Throwable): Boolean
}

class EthCallLimitValidator(
    upstream: Upstream,
    private val options: ChainOptions.Options,
    private val config: ChainConfig,
) : AbstractCallLimitValidator(upstream, options) {
    override fun isEnabled() = options.validateCallLimit && config.callLimitContract != null

    override fun createRequest() = ChainRequest(
        "eth_call",
        ListParams(
            TransactionCallJson(
                Address.from(config.callLimitContract),
                // contract like https://github.com/drpcorg/dshackle/pull/246
                // meta + size in hex
                HexData.from("0xd8a26e3a" + options.callLimitSize.toString(16).padStart(64, '0')),
            ),
            "latest",
        ),
    )

    override fun isLimitError(err: Throwable): Boolean =
        err.message != null && err.message!!.contains("rpc.returndata.limit")
}

class ChainIdValidator(
    private val upstream: Upstream,
    private val chain: Chain,
    private val customReader: ChainReader? = null,
) : SingleValidator<ValidateUpstreamSettingsResult> {
    private val validatorReader: Supplier<ChainReader> = Supplier {
        customReader ?: upstream.getIngressReader()
    }

    companion object {
        @JvmStatic
        val log: Logger = LoggerFactory.getLogger(ChainIdValidator::class.java)
    }
    override fun validate(onError: ValidateUpstreamSettingsResult): Mono<ValidateUpstreamSettingsResult> {
        return Mono.zip(
            chainId(),
            netVersion(),
        )
            .map {
                var netver: BigInteger
                if (it.t2.lowercase().contains("x")) {
                    netver = BigInteger(it.t2.lowercase().substringAfter("x"), 16)
                } else {
                    netver = BigInteger(it.t2)
                }
                val isChainValid = chain.chainId.lowercase() == it.t1.lowercase() &&
                    chain.netVersion == netver

                if (!isChainValid) {
                    val actualChain = Global.chainByChainId(it.t1).chainName
                    log.warn(
                        "${chain.chainName} is specified for upstream ${upstream.getId()} (chainId ${chain.chainId.lowercase()}, net_version ${chain.netVersion}) " +
                            "but actually it is $actualChain with chainId ${it.t1} and net_version $netver",
                    )
                }

                if (isChainValid) {
                    ValidateUpstreamSettingsResult.UPSTREAM_VALID
                } else {
                    ValidateUpstreamSettingsResult.UPSTREAM_FATAL_SETTINGS_ERROR
                }
            }
            .onErrorResume {
                log.error("Error during chain validation of upstream {}, reason - {}", upstream.getId(), it.message)
                Mono.just(onError)
            }
    }

    private fun chainId(): Mono<String> {
        return validatorReader.get()
            .read(ChainRequest("eth_chainId", ListParams()))
            .retryRandomBackoff(3, Duration.ofMillis(100), Duration.ofMillis(500)) { ctx ->
                log.warn(
                    "error during chainId retrieving for {}, iteration {}, reason - {}",
                    upstream.getId(),
                    ctx.iteration(),
                    ctx.exception().message,
                )
            }
            .doOnError { log.error("Error during execution 'eth_chainId' - {} for {}", it.message, upstream.getId()) }
            .flatMap(ChainResponse::requireStringResult)
    }

    private fun netVersion(): Mono<String> {
        return validatorReader.get()
            .read(ChainRequest("net_version", ListParams()))
            .retryRandomBackoff(3, Duration.ofMillis(100), Duration.ofMillis(500)) { ctx ->
                log.warn(
                    "error during netVersion retrieving for {}, iteration {}, reason - {}",
                    upstream.getId(),
                    ctx.iteration(),
                    ctx.exception().message,
                )
            }
            .doOnError { log.error("Error during execution 'net_version' - {} for {}", it.message, upstream.getId()) }
            .flatMap(ChainResponse::requireStringResult)
    }
}

class GasPriceValidator(
    private val upstream: Upstream,
    private val config: ChainConfig,
) : SingleValidator<ValidateUpstreamSettingsResult> {

    companion object {
        @JvmStatic
        val log: Logger = LoggerFactory.getLogger(GasPriceValidator::class.java)
    }
    override fun validate(onError: ValidateUpstreamSettingsResult): Mono<ValidateUpstreamSettingsResult> {
        if (!upstream.getMethods().isCallable("eth_gasPrice")) {
            return Mono.just(ValidateUpstreamSettingsResult.UPSTREAM_VALID)
        }
        return upstream.getIngressReader()
            .read(ChainRequest("eth_gasPrice", ListParams()))
            .flatMap(ChainResponse::requireStringResult)
            .map { result ->
                val actualGasPrice = result.substring(2).toLong(16)
                if (!config.gasPriceCondition.check(actualGasPrice)) {
                    log.warn(
                        "Node ${upstream.getId()} has gasPrice $actualGasPrice, " +
                            "but it is not equal to the required ${config.gasPriceCondition.rules()}",
                    )
                    ValidateUpstreamSettingsResult.UPSTREAM_FATAL_SETTINGS_ERROR
                } else {
                    ValidateUpstreamSettingsResult.UPSTREAM_VALID
                }
            }
            .onErrorResume { err ->
                log.warn("Error during gasPrice validation", err)
                Mono.just(onError)
            }
    }
}

class ErigonBuggedValidator(
    private val upstream: Upstream,
) : SingleValidator<ValidateUpstreamSettingsResult> {

    companion object {
        @JvmStatic
        val log: Logger = LoggerFactory.getLogger(ErigonBuggedValidator::class.java)
    }

    private var callCount: Int = 0

    override fun validate(onError: ValidateUpstreamSettingsResult): Mono<ValidateUpstreamSettingsResult> {
        if (callCount % 10 != 0) {
            callCount++
            return Mono.just(ValidateUpstreamSettingsResult.UPSTREAM_VALID)
        }
        callCount++

        return isErigon().flatMap { isErigon ->
            if (!isErigon) {
                Mono.just(ValidateUpstreamSettingsResult.UPSTREAM_VALID)
            } else {
                performErigonBugCheck()
            }
        }.onErrorResume { ex ->
            log.error(
                "Irrecoverable error during Erigon bug validation for {}, reason - {}",
                upstream.getId(),
                ex.message,
            )
            Mono.just(onError)
        }
    }

    private fun isErigon(): Mono<Boolean> =
        upstream.getIngressReader()
            .read(ChainRequest("web3_clientVersion", ListParams()))
            .retryRandomBackoff(3, Duration.ofMillis(100), Duration.ofMillis(500)) { ctx ->
                log.warn(
                    "error during clientVersion retrieving for {}, iteration {}, reason - {}",
                    upstream.getId(),
                    ctx.iteration(),
                    ctx.exception().message,
                )
            }
            .flatMap(ChainResponse::requireStringResult)
            .map { it.lowercase().contains("erigon") }
            .doOnError { log.error("Error during execution 'web3_clientVersion' - {} for {}", it.message, upstream.getId()) }

    private fun performErigonBugCheck(): Mono<ValidateUpstreamSettingsResult> {
        return getLatestBlockNumber()
            .flatMap { latestBlock ->
                binarySearchForBug(BigInteger.ONE, latestBlock, 0)
            }
            .timeout(Duration.ofSeconds(10))
            .onErrorReturn(ValidateUpstreamSettingsResult.UPSTREAM_VALID)
    }

    private fun getLatestBlockNumber(): Mono<BigInteger> {
        return upstream.getIngressReader()
            .read(ChainRequest("eth_blockNumber", ListParams()))
            .flatMap(ChainResponse::requireStringResult)
            .map { BigInteger(it.removePrefix("0x"), 16) }
            .timeout(Duration.ofSeconds(1))
            .onErrorReturn(BigInteger.valueOf(1000000))
    }

    private fun binarySearchForBug(start: BigInteger, end: BigInteger, depth: Int): Mono<ValidateUpstreamSettingsResult> {
        if (start >= end || depth > 20) {
            return Mono.just(ValidateUpstreamSettingsResult.UPSTREAM_VALID)
        }

        val mid = start.add(end).divide(BigInteger.valueOf(2))

        return testBlockForBug(mid)
            .flatMap { result ->
                when (result) {
                    TestResult.BUG_DETECTED -> {
                        log.warn(
                            "Erigon balance-bug detected on upstream {} at block {}",
                            upstream.getId(),
                            mid,
                        )
                        Mono.just(ValidateUpstreamSettingsResult.UPSTREAM_FATAL_SETTINGS_ERROR)
                    }
                    TestResult.VALID_RESPONSE -> {
                        // Go left (earlier blocks)
                        binarySearchForBug(start, mid, depth + 1)
                    }
                    TestResult.ERROR -> {
                        // Go right (later blocks)
                        binarySearchForBug(mid.add(BigInteger.ONE), end, depth + 1)
                    }
                    null -> Mono.just(ValidateUpstreamSettingsResult.UPSTREAM_VALID)
                }
            }
    }

    private fun testBlockForBug(blockNumber: BigInteger): Mono<TestResult> {
        val blockTag = "0x${blockNumber.toString(16)}"
        val request = ChainRequest(
            "eth_call",
            ListParams(
                mapOf(
                    "to" to "0x1111111111111111111111111111111111111111",
                    "data" to "0x1eaf190c",
                ),
                blockTag,
                mapOf(
                    "0x1111111111111111111111111111111111111111" to mapOf(
                        "code" to "0x6080604052348015600e575f5ffd5b50600436106026575f3560e01c80631eaf190c14602a575b5f5ffd5b60306044565b604051603b91906078565b60405180910390f35b5f5f73ffffffffffffffffffffffffffffffffffffffff1631905090565b5f819050919050565b6072816062565b82525050565b5f60208201905060895f830184606b565b9291505056fea2646970667358221220251f5b4d2ed1abe77f66fde198a57ada08562dc3b0afbc6bac0261d1bf516b5d64736f6c634300081e0033",
                    ),
                ),
            ),
        )

        return upstream.getIngressReader()
            .read(request)
            .flatMap(ChainResponse::requireStringResult)
            .map { result ->
                when {
                    result == "0x" -> TestResult.BUG_DETECTED
                    result.startsWith("0x") && result.length > 2 -> TestResult.VALID_RESPONSE
                    else -> TestResult.ERROR
                }
            }
            .timeout(Duration.ofSeconds(1))
            .onErrorReturn(TestResult.ERROR)
    }

    private enum class TestResult {
        BUG_DETECTED,
        VALID_RESPONSE,
        ERROR,
    }
}
