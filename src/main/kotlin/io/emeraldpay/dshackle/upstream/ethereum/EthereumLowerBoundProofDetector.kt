package io.emeraldpay.dshackle.upstream.ethereum

import io.emeraldpay.dshackle.Defaults
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
        private const val NO_PROOF_DATA = "distance to target block exceeds maximum proof window"
        private const val TOO_OLD_BLOCK = "requested block is too old"

        // todo: use it for set lower bound to latest block
        val SHORTCUT_ERRORS = setOf(
            "proofs are available only for the 'latest' block",
        )
        val NO_PROOF_ERRORS = setOf(
            NO_PROOF_DATA,
            TOO_OLD_BLOCK,
            "block not found",
        )
    }

    private val recursiveLowerBound = RecursiveLowerBound(upstream, LowerBoundType.PROOF, NO_PROOF_ERRORS, lowerBounds)

    override fun period(): Long {
        return 3
    }
    override fun internalDetectLowerBound(): Flux<LowerBoundData> {
        return recursiveLowerBound.recursiveDetectLowerBound { block ->
            if (block == 0L) {
                Mono.just(ChainResponse(ByteArray(0), null))
            } else {
                val request = ChainRequest(
                    "eth_getProof",
                    ListParams("0x0000000000000000000000000000000000000000", listOf<Any>(), block.toHex()),
                )
                upstream.getIngressReader()
                    .read(request)
                    .timeout(Defaults.internalCallsTimeout)
                    .doOnNext {
                        if (it.hasResult() && it.getResult().contentEquals("null".toByteArray())) {
                            throw IllegalStateException(NO_PROOF_DATA)
                        }
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
