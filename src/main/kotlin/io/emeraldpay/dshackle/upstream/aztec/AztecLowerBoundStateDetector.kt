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
 * builds can drop historical state. The detector probes node_getBlockHeader(N) over a
 * binary search between [0, currentHeight] - the call returns JSON `null` for a block
 * the node does not have, which we treat as "no data" so the recursive detector keeps
 * searching upward. The first block that comes back as a real header is the upstream's
 * lower bound.
 *
 * node_getBlockHeader is preferred over node_getBlock here because it returns just the
 * block header (~100 bytes) instead of the full block with transactions (often KBs);
 * the binary search performs ~log2(currentHeight) probes per refresh cycle.
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

        internal fun isNullResult(result: ByteArray): Boolean {
            // Aztec nodes report a missing block as JSON `null`. Be tolerant of trailing
            // whitespace/newlines and case variations so a stray `\n` does not flip the
            // search into thinking the block is present.
            val text = String(result).trim()
            return text.equals("null", ignoreCase = true)
        }
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
                .read(ChainRequest("node_getBlockHeader", ListParams(block)))
                .timeout(Defaults.internalCallsTimeout)
                .doOnNext { response ->
                    // Aztec returns JSON `null` (not an error) for blocks the node does
                    // not have. Convert to an exception so the recursive detector treats
                    // it the same as a real "not found" RPC error and walks the search
                    // window upward.
                    if (response.hasResult() && isNullResult(response.getResult())) {
                        throw IllegalStateException(NO_BLOCK)
                    }
                }
        }
    }

    override fun types(): Set<LowerBoundType> {
        return setOf(LowerBoundType.STATE)
    }
}
