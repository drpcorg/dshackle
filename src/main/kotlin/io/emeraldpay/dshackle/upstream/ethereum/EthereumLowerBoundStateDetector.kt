package io.emeraldpay.dshackle.upstream.ethereum

import io.emeraldpay.dshackle.Defaults
import io.emeraldpay.dshackle.Global
import io.emeraldpay.dshackle.upstream.ChainRequest
import io.emeraldpay.dshackle.upstream.ChainResponse
import io.emeraldpay.dshackle.upstream.Upstream
import io.emeraldpay.dshackle.upstream.lowerbound.LowerBoundData
import io.emeraldpay.dshackle.upstream.lowerbound.LowerBoundType
import io.emeraldpay.dshackle.upstream.lowerbound.detector.RecursiveLowerBound
import io.emeraldpay.dshackle.upstream.lowerbound.toHex
import io.emeraldpay.dshackle.upstream.rpcclient.ListParams
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

class EthereumLowerBoundStateDetector(
    private val upstream: Upstream,
) : EthereumLowerBoundDetectorBase(upstream.getChain()) {
    private val recursiveLowerBound = RecursiveLowerBound(upstream, LowerBoundType.STATE, stateErrors, lowerBounds, commonErrorPatterns)

    @Volatile
    private var supportsStateOverride: Boolean? = null

    companion object {
        private const val STATE_CHECKER_ADDRESS = "0x1111111111111111111111111111111111111111"
        private const val STATE_CHECKER_CALL_DATA = "0x1eaf190c"
        private const val STATE_CHECKER_BYTECODE = "0x6080604052348015600e575f5ffd5b50600436106026575f3560e01c80631eaf190c14602a575b5f5ffd5b60306044565b604051603b91906078565b60405180910390f35b5f5f73ffffffffffffffffffffffffffffffffffffffff1631905090565b5f819050919050565b6072816062565b82525050565b5f60208201905060895f830184606b565b9291505056fea2646970667358221220251f5b4d2ed1abe77f66fde198a57ada08562dc3b0afbc6bac0261d1bf516b5d64736f6c634300081e0033"

        val stateErrors = setOf(
            "No state available for block", // nethermind
            "missing trie node", // geth
            "header not found", // optimism, bsc, avalanche
            "Node state is pruned", // kava
            "is not available, lowest height is", // kava, cronos
            "State already discarded for", // moonriver, moonbeam
            "your node is running with state pruning", // fuse
            "failed to compute tipset state", // filecoin-calibration
            "bad tipset height", // filecoin-calibration
            "body not found for block",
            "request beyond head block",
            "block not found",
            "could not find block",
            "unknown block",
            "header for hash not found",
            "after last accepted block",
            "Version has either been pruned, or is for a future block", // cronos
            "no historical RPC is available for this historical", // optimism
            "historical backend error", // optimism
            "load state tree: failed to load state tree", // filecoin
            "purged for block", // erigon
            "No state data", // our own error if there is "null" in response
            "state is not available", // bsc also can return this error along with "header not found"
            "Block with such an ID is pruned", // zksync
            "state at block", // berachain, eth
            "unsupported block number", // arb
            "unexpected state root", // fantom
            "evm module does not exist on height", // sei
            "failed to load state at height", // 0g
            "no state found for block", // optimism
            "old data not available due", // eth
            "State not found for block", // rootstock
            "state does not maintain archive data", // fantom
            "Access to archival, debug, or trace data is not included in your current plan", // chainstack
            "empty reader set", // strange bsc geth error
            "Request might be querying historical state that is not available", // monad
            "No receipts data",
            "No tx data",
            "No block data",
        )
    }

    override fun period(): Long {
        return 3
    }

    private fun testStateOverrideSupport(): Mono<Boolean> {
        log.debug("Testing state override support for upstream {}", upstream.getId())
        return upstream.getIngressReader().read(
            ChainRequest(
                "eth_call",
                ListParams(
                    mapOf(
                        "to" to STATE_CHECKER_ADDRESS,
                        "data" to STATE_CHECKER_CALL_DATA,
                    ),
                    "latest",
                    mapOf(
                        STATE_CHECKER_ADDRESS to mapOf(
                            "code" to STATE_CHECKER_BYTECODE,
                        ),
                    ),
                ),
            ),
        ).map { response ->
            val supported = if (response.hasResult()) {
                val result = String(response.getResult())
                result != "\"0x\"" && result != "\"0x0\"" && !response.getResult().contentEquals(Global.nullValue)
            } else {
                false
            }
            log.debug("State override support test for upstream {}: {}", upstream.getId(), supported)
            supported
        }.onErrorResume { error ->
            log.debug("State override support test failed for upstream {}: {}", upstream.getId(), error.message)
            Mono.just(false)
        }.timeout(Defaults.internalCallsTimeout)
    }

    private fun stateDetectionWithOverride(block: Long): Mono<ChainResponse> {
        log.debug("Testing state with override for upstream {} at block {}", upstream.getId(), block)
        return upstream.getIngressReader().read(
            ChainRequest(
                "eth_call",
                ListParams(
                    mapOf(
                        "to" to STATE_CHECKER_ADDRESS,
                        "data" to STATE_CHECKER_CALL_DATA,
                    ),
                    block.toHex(),
                    mapOf(
                        STATE_CHECKER_ADDRESS to mapOf(
                            "code" to STATE_CHECKER_BYTECODE,
                        ),
                    ),
                ),
            ),
        ).doOnNext { response ->
            if (response.hasResult() && !response.getResult().contentEquals(Global.nullValue)) {
                val result = String(response.getResult())
                log.debug("State override successful for upstream {} at block {}: state is available (result: {})", upstream.getId(), block, result)
            } else {
                log.debug("State override failed for upstream {} at block {}: no state data", upstream.getId(), block)
                throw IllegalStateException("No state data")
            }
        }.doOnError { error ->
            log.debug("State override error for upstream {} at block {}: {}", upstream.getId(), block, error.message)
        }.timeout(Defaults.internalCallsTimeout)
    }

    private fun fallbackStateDetection(block: Long): Mono<ChainResponse> {
        log.debug("Testing state with fallback (eth_getBalance) for upstream {} at block {}", upstream.getId(), block)
        return upstream.getIngressReader().read(
            ChainRequest(
                "eth_getBalance",
                ListParams(ZERO_ADDRESS, block.toHex()),
            ),
        ).doOnNext { response ->
            if (response.hasResult() && response.getResult().contentEquals(Global.nullValue)) {
                log.debug("Fallback state detection failed for upstream {} at block {}: null result", upstream.getId(), block)
                throw IllegalStateException("No state data")
            } else {
                log.debug("Fallback state detection successful for upstream {} at block {}", upstream.getId(), block)
            }
        }.doOnError { error ->
            log.debug("Fallback state detection error for upstream {} at block {}: {}", upstream.getId(), block, error.message)
        }.timeout(Defaults.internalCallsTimeout)
    }

    override fun internalDetectLowerBound(): Flux<LowerBoundData> {
        return recursiveLowerBound.recursiveDetectLowerBound { block ->
            if (block == 0L) {
                log.debug("Testing block 0 for upstream {} (genesis block)", upstream.getId())
                Mono.just(ChainResponse(ByteArray(0), null))
            } else {
                detectStateForBlock(block)
            }
        }.doOnNext { lowerBoundData ->
            val detectionMethod = when (supportsStateOverride) {
                true -> "state override (eth_call)"
                false -> "fallback (eth_getBalance)"
                null -> "unknown method"
            }
            log.info("Found state lower bound for upstream {} using {}: block {}", upstream.getId(), detectionMethod, lowerBoundData.lowerBound)
        }.flatMap {
            Flux.just(it, lowerBoundFrom(it, LowerBoundType.TRACE))
        }
    }

    private fun detectStateForBlock(block: Long): Mono<ChainResponse> {
        val currentSupportsStateOverride = supportsStateOverride

        return when (currentSupportsStateOverride) {
            true -> {
                log.debug("Using state override for upstream {} at block {} (cached: supported)", upstream.getId(), block)
                stateDetectionWithOverride(block).onErrorResume { error ->
                    log.debug(
                        "State override failed for upstream {} at block {}, falling back to eth_getBalance: {}",
                        upstream.getId(),
                        block,
                        error.message,
                    )
                    fallbackStateDetection(block)
                }
            }
            false -> {
                log.debug("Using fallback method for upstream {} at block {} (cached: not supported)", upstream.getId(), block)
                fallbackStateDetection(block)
            }
            null -> {
                log.debug("Testing state override support for upstream {} (first time)", upstream.getId())
                testStateOverrideSupport()
                    .doOnNext { supported ->
                        supportsStateOverride = supported
                        log.info("State override support for upstream {}: {}", upstream.getId(), supported)
                    }
                    .flatMap { supported ->
                        if (supported) {
                            log.debug("Using state override for upstream {} at block {} (newly detected)", upstream.getId(), block)
                            stateDetectionWithOverride(block).onErrorResume { error ->
                                log.debug(
                                    "State override failed for upstream {} at block {}, falling back to eth_getBalance: {}",
                                    upstream.getId(),
                                    block,
                                    error.message,
                                )
                                fallbackStateDetection(block)
                            }
                        } else {
                            log.debug("Using fallback method for upstream {} at block {} (newly detected)", upstream.getId(), block)
                            fallbackStateDetection(block)
                        }
                    }
                    .onErrorResume { error ->
                        log.warn(
                            "State override support test failed for upstream {}, falling back to eth_getBalance: {}",
                            upstream.getId(),
                            error.message,
                        )
                        supportsStateOverride = false
                        fallbackStateDetection(block)
                    }
            }
        }
    }

    override fun types(): Set<LowerBoundType> {
        return setOf(LowerBoundType.STATE, LowerBoundType.TRACE)
    }
}
