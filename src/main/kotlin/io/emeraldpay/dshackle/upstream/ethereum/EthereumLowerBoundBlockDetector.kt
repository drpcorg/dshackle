package io.emeraldpay.dshackle.upstream.ethereum

import io.emeraldpay.dshackle.Defaults
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

class EthereumLowerBoundBlockDetector(
    private val upstream: Upstream,
) : EthereumLowerBoundDetectorBase(upstream.getChain()) {

    companion object {
        private const val NO_BLOCK_DATA = "No block data"

        val NO_BLOCK_ERRORS = setOf(
            NO_BLOCK_DATA,
            "error loading messages for tipset",
            "bad tipset height",
            "Block with such an ID is pruned", // zksync
            "height is not available", // sei
            "method handler crashed", // sei
            "Unexpected error", // hyperliquid
        )
    }

    private val recursiveLowerBound = RecursiveLowerBound(upstream, LowerBoundType.BLOCK, NO_BLOCK_ERRORS, lowerBounds, commonErrorPatterns)

    override fun period(): Long {
        return 3
    }

    override fun internalDetectLowerBound(): Flux<LowerBoundData> {
        return recursiveLowerBound.recursiveDetectLowerBound { block ->
            if (block == 0L) {
                Mono.just(ChainResponse(ByteArray(0), null))
            } else {
                upstream.getIngressReader()
                    .read(
                        ChainRequest(
                            "eth_getBlockByNumber",
                            ListParams(block.toHex(), false),
                        ),
                    )
                    .timeout(Defaults.internalCallsTimeout)
                    .doOnNext {
                        if (it.hasResult() && it.getResult().contentEquals("null".toByteArray())) {
                            throw IllegalStateException(NO_BLOCK_DATA)
                        }
                    }
            }
        }.flatMap {
            Flux.just(it, lowerBoundFrom(it, LowerBoundType.LOGS))
        }
    }

    override fun types(): Set<LowerBoundType> {
        return setOf(LowerBoundType.BLOCK, LowerBoundType.LOGS)
    }
}
