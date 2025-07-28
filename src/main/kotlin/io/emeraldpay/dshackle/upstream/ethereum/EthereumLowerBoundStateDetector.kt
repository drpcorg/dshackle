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
        private const val STATE_CHECKER_ADDRESS = "0x0000000000000000000000000000000000000001"
        private const val STATE_CHECKER_CALL_DATA = "0x26121ff0"
        private const val STATE_CHECKER_BYTECODE = "0x608060405234801561001057600080fd5b506004361061002b5760003560e01c806326121ff014610030575b600080fd5b610038610048565b6040516100459190610073565b60405180910390f35b6000602a905090565b6000819050919050565b61006d81610052565b82525050565b60006020820190506100886000830184610064565b9291505056fea2646970667358221220c7b6d4bc2c1b1d5b5c2e8e2a5b5c5d5e5f5a5b5c5d5e5f5a5b5c5d5e5f5a5b5c64736f6c63430008130033"

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
            if (response.hasResult()) {
                val result = String(response.getResult())
                result != "\"0x\"" && result != "\"0x0\"" && !response.getResult().contentEquals(Global.nullValue)
            } else {
                false
            }
        }.onErrorReturn(false)
            .timeout(Defaults.internalCallsTimeout)
    }

    private fun stateDetectionWithOverride(block: Long): Mono<ChainResponse> {
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
            if (response.hasResult()) {
                val result = String(response.getResult())
                if (result == "\"0x\"" || result == "\"0x0\"" || response.getResult().contentEquals(Global.nullValue)) {
                    throw IllegalStateException("No state data")
                }
            } else {
                throw IllegalStateException("No state data")
            }
        }.timeout(Defaults.internalCallsTimeout)
    }

    private fun fallbackStateDetection(block: Long): Mono<ChainResponse> {
        return upstream.getIngressReader().read(
            ChainRequest(
                "eth_getBalance",
                ListParams(ZERO_ADDRESS, block.toHex()),
            ),
        ).doOnNext { response ->
            if (response.hasResult() && response.getResult().contentEquals(Global.nullValue)) {
                throw IllegalStateException("No state data")
            }
        }.timeout(Defaults.internalCallsTimeout)
    }

    override fun internalDetectLowerBound(): Flux<LowerBoundData> {
        return recursiveLowerBound.recursiveDetectLowerBound { block ->
            if (block == 0L) {
                Mono.just(ChainResponse(ByteArray(0), null))
            } else {
                detectStateForBlock(block)
            }
        }.flatMap {
            Flux.just(it, lowerBoundFrom(it, LowerBoundType.TRACE))
        }
    }

    private fun detectStateForBlock(block: Long): Mono<ChainResponse> {
        val currentSupportsStateOverride = supportsStateOverride

        return if (currentSupportsStateOverride == true) {
            stateDetectionWithOverride(block).onErrorResume {
                fallbackStateDetection(block)
            }
        } else if (currentSupportsStateOverride == false) {
            fallbackStateDetection(block)
        } else {
            testStateOverrideSupport()
                .doOnNext { supported -> supportsStateOverride = supported }
                .flatMap { supported ->
                    if (supported) {
                        stateDetectionWithOverride(block).onErrorResume {
                            fallbackStateDetection(block)
                        }
                    } else {
                        fallbackStateDetection(block)
                    }
                }
                .onErrorResume {
                    supportsStateOverride = false
                    fallbackStateDetection(block)
                }
        }
    }

    override fun types(): Set<LowerBoundType> {
        return setOf(LowerBoundType.STATE, LowerBoundType.TRACE)
    }
}
