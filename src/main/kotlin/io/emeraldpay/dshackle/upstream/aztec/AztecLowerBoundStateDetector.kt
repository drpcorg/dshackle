package io.emeraldpay.dshackle.upstream.aztec

import io.emeraldpay.dshackle.Defaults
import io.emeraldpay.dshackle.upstream.ChainRequest
import io.emeraldpay.dshackle.upstream.Upstream
import io.emeraldpay.dshackle.upstream.lowerbound.LowerBoundData
import io.emeraldpay.dshackle.upstream.lowerbound.LowerBoundDetector
import io.emeraldpay.dshackle.upstream.lowerbound.LowerBoundType
import io.emeraldpay.dshackle.upstream.lowerbound.detector.RecursiveLowerBound
import io.emeraldpay.dshackle.upstream.rpcclient.ListParams
import reactor.core.publisher.Flux

/**
 * Detects the lowest L2 block for which the upstream still has state available.
 *
 * Aztec full nodes are typically archive nodes (lower bound = 1), but pruning-capable
 * builds can drop historical state. The detector probes node_getBlock(N) over a binary
 * search between [0, currentHeight] - the call returns JSON `null` for a block the node
 * does not have, which we treat as "no data" so the recursive detector keeps searching
 * upward. The first block that comes back as a real object is the upstream's lower bound.
 */
class AztecLowerBoundStateDetector(
    private val upstream: Upstream,
) : LowerBoundDetector(upstream.getChain()) {

    companion object {
        private const val NO_BLOCK = "no aztec block"

        // Error/result substrings that the recursive search must treat as a definitive
        // "this upstream does not have this block" signal (i.e. do not retry).
        private val nonRetryableErrors = setOf(
            NO_BLOCK,
            "block not found",
            "block is not available",
            "no historical state",
            "pruned",
        )
    }

    private val recursiveLowerBound = RecursiveLowerBound(
        upstream,
        LowerBoundType.STATE,
        nonRetryableErrors,
        lowerBounds,
    )

    override fun period(): Long {
        return 5
    }

    override fun internalDetectLowerBound(): Flux<LowerBoundData> {
        return recursiveLowerBound.recursiveDetectLowerBound { block ->
            upstream.getIngressReader()
                .read(ChainRequest("node_getBlock", ListParams(block)))
                .timeout(Defaults.internalCallsTimeout)
                .doOnNext { response ->
                    // Aztec returns JSON `null` (not an error) for blocks the node does
                    // not have. Convert to an exception so the recursive detector treats
                    // it the same as a real "not found" RPC error and walks the search
                    // window upward.
                    if (response.hasResult() && response.getResult().contentEquals("null".toByteArray())) {
                        throw IllegalStateException(NO_BLOCK)
                    }
                }
        }
    }

    override fun types(): Set<LowerBoundType> {
        return setOf(LowerBoundType.STATE)
    }
}
