package io.emeraldpay.dshackle.upstream.ethereum

import io.emeraldpay.dshackle.Defaults
import io.emeraldpay.dshackle.data.BlockContainer
import io.emeraldpay.dshackle.upstream.ChainRequest
import io.emeraldpay.dshackle.upstream.ChainResponse
import io.emeraldpay.dshackle.upstream.Upstream
import io.emeraldpay.dshackle.upstream.lowerbound.LowerBoundData
import io.emeraldpay.dshackle.upstream.lowerbound.LowerBoundDetector
import io.emeraldpay.dshackle.upstream.lowerbound.LowerBoundType
import io.emeraldpay.dshackle.upstream.lowerbound.detector.RecursiveLowerBound
import io.emeraldpay.dshackle.upstream.lowerbound.toHex
import io.emeraldpay.dshackle.upstream.rpcclient.ListParams
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

class EthereumLowerBoundProofDetector(
    private val upstream: Upstream,
) : LowerBoundDetector(upstream.getChain()) {
    companion object {
        const val MAX_OFFSET = 30
        private const val NO_PROOF_DATA = "distance to target block exceeds maximum proof window"

        // todo: use it for set lowerbound to 1
        val SHORTCUT_ERRORS = setOf(
            "proofs are available only for the 'latest' block",
        )
         val NO_PROOF_ERRORS = setOf(
            NO_PROOF_DATA,
            "requested block is too old",
            "block not found",
        ).plus(SHORTCUT_ERRORS)
    }
    //         "Can't route your request to suitable provider, if you specified certain providers revise the list"
    // requested block is too old, block must be within 100000 blocks of the head block number (currently 22237523)

    private val recursiveLowerBound = RecursiveLowerBound(upstream, LowerBoundType.PROOF, NO_PROOF_ERRORS, lowerBounds)

    override fun period(): Long {
        return 3
    }
    override fun internalDetectLowerBound(): Flux<LowerBoundData> {
        return recursiveLowerBound.recursiveDetectLowerBoundWithOffset(MAX_OFFSET) { block ->
            if (block == 0L) {
                Mono.just(ChainResponse(ByteArray(0), null))
            } else {
                val request = ChainRequest(
                    "eth_getProof",
                    ListParams("0x0000000000000000000000000000000000000000", listOf<Any>(), block.toHex())
                )
                upstream.getIngressReader()
                    .read(request)
                    .timeout(Defaults.internalCallsTimeout.multipliedBy(10))
                    .doOnNext {
                        if (it.hasResult() && it.getResult().contentEquals("null".toByteArray())) {
                            throw IllegalStateException(NO_PROOF_DATA)
                        }
                    }
                    .doOnError { error ->
                        log.error("------------Error in ChainRequest for block $block :", error)
                    }
            }
        }.flatMap {
            Flux.just(it, lowerBoundFrom(it, LowerBoundType.PROOF))
        }
    }

    override fun types(): Set<LowerBoundType> {
        return setOf(LowerBoundType.PROOF)
    }
}
