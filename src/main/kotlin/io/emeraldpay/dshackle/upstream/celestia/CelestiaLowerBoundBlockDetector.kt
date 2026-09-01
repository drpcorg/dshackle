package io.emeraldpay.dshackle.upstream.celestia

import io.emeraldpay.dshackle.Defaults
import io.emeraldpay.dshackle.Global
import io.emeraldpay.dshackle.upstream.ChainRequest
import io.emeraldpay.dshackle.upstream.ChainResponse
import io.emeraldpay.dshackle.upstream.Upstream
import io.emeraldpay.dshackle.upstream.lowerbound.LowerBoundData
import io.emeraldpay.dshackle.upstream.lowerbound.LowerBoundDetector
import io.emeraldpay.dshackle.upstream.lowerbound.LowerBoundType
import io.emeraldpay.dshackle.upstream.rpcclient.ListParams
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * Detects the lowest height the upstream still serves.
 *
 * celestia-node exposes `header.Tail` - the lowest header the node stores (its
 * DA sampling / pruning window). One RPC per refresh, no binary search. The
 * bound slides up continuously on pruned nodes; archive bridge nodes report 1.
 *
 * Nodes older than celestia-node v0.28 lack the method: on error we re-emit
 * the cached bound unchanged, or UNKNOWN when there is nothing cached yet, so
 * the upstream is treated as archive rather than being assigned a fake bound.
 */
class CelestiaLowerBoundBlockDetector(
    private val upstream: Upstream,
) : LowerBoundDetector(upstream.getChain()) {

    companion object {
        private val log = LoggerFactory.getLogger(CelestiaLowerBoundBlockDetector::class.java)
    }

    override fun period(): Long = 5

    override fun types(): Set<LowerBoundType> = setOf(LowerBoundType.BLOCK)

    override fun internalDetectLowerBound(): Flux<LowerBoundData> {
        return upstream.getIngressReader()
            .read(ChainRequest("header.Tail", ListParams()))
            .timeout(Defaults.internalCallsTimeout)
            .flatMap(ChainResponse::requireResult)
            .flatMap { data -> parseTail(data) }
            .onErrorResume { err -> retainCachedOrSkip(err.message) }
            .flux()
    }

    private fun parseTail(data: ByteArray): Mono<LowerBoundData> {
        val height = CelestiaChainSpecific.parseHeight(Global.objectMapper.readTree(data))
        if (height != null && height > 0) {
            return Mono.just(LowerBoundData(height, LowerBoundType.BLOCK))
        }
        return retainCachedOrSkip("missing header.height in the Tail response")
    }

    private fun retainCachedOrSkip(reason: String?): Mono<LowerBoundData> {
        val cached = lowerBounds.getLastBound(LowerBoundType.BLOCK)
        if (cached != null) {
            // Same instance (same timestamp) so updateBound becomes a no-op.
            return Mono.just(cached)
        }
        log.warn(
            "Celestia upstream {} reported no tail and we have no cached BLOCK bound: {}",
            upstream.getId(),
            reason,
        )
        return Mono.just(LowerBoundData(0, LowerBoundType.UNKNOWN))
    }
}
