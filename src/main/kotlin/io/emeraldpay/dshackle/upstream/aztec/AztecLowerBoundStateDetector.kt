package io.emeraldpay.dshackle.upstream.aztec

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
 * Detects the lowest L2 block for which the upstream still has state available.
 *
 * Aztec exposes `node_getWorldStateSyncStatus`, whose response contains
 * `oldestHistoricBlockNumber` - the prune boundary kept by the world-state
 * synchronizer. One RPC call per refresh, no binary search needed.
 *
 * The bound is a sliding window: it monotonically increases as the node prunes
 * older blocks (configured by `historyToKeep`). The base detector + LowerBounds
 * already model this correctly via linear regression over the most recent
 * three samples, so we only need to feed it real readings.
 *
 * On transient errors (the public Aztec endpoint occasionally returns code 19
 * on this method) we **re-emit the cached LowerBoundData unchanged**, which
 * makes `updateBound`'s `newBound.timestamp != lastBound.timestamp` guard skip
 * the regression update. This is safer than:
 *  - emitting STATE=1 (would clobber a real prune boundary because the base
 *    filter accepts `lowerBound == 1L` unconditionally),
 *  - emitting a freshly-timestamped copy (would feed a false "no progress"
 *    sample into the regression and bias `k` toward zero).
 * On the very first tick with no cached value, we emit nothing - the router
 * sees no STATE bound for this upstream until it answers successfully.
 */
class AztecLowerBoundStateDetector(
    private val upstream: Upstream,
) : LowerBoundDetector(upstream.getChain()) {

    companion object {
        private val log = LoggerFactory.getLogger(AztecLowerBoundStateDetector::class.java)
    }

    override fun period(): Long = 5

    override fun types(): Set<LowerBoundType> = setOf(LowerBoundType.STATE)

    override fun internalDetectLowerBound(): Flux<LowerBoundData> {
        return upstream.getIngressReader()
            .read(ChainRequest("node_getWorldStateSyncStatus", ListParams()))
            .timeout(Defaults.internalCallsTimeout)
            .flatMap(ChainResponse::requireResult)
            .map(::parseOldestHistoric)
            .onErrorResume { err ->
                val cached = lowerBounds.getLastBound(LowerBoundType.STATE)
                if (cached != null) {
                    log.debug(
                        "Aztec upstream {} world state sync status unavailable; retaining cached STATE={}: {}",
                        upstream.getId(),
                        cached.lowerBound,
                        err.message,
                    )
                    // Same instance (same timestamp) so updateBound becomes a no-op
                    // and the linear-regression coefficients are preserved.
                    Mono.just(cached)
                } else {
                    log.debug(
                        "Aztec upstream {} world state sync status unavailable on initial tick: {}",
                        upstream.getId(),
                        err.message,
                    )
                    Mono.empty()
                }
            }
            .flux()
    }

    private fun parseOldestHistoric(data: ByteArray): LowerBoundData {
        val raw = Global.objectMapper.readTree(data)
        val node = raw.get("oldestHistoricBlockNumber")
        if (node != null && !node.isNull && node.isNumber) {
            return LowerBoundData(node.asLong().coerceAtLeast(1L), LowerBoundType.STATE)
        }
        // Response parsed but the field is missing/non-numeric. Fall back to the
        // cached value (or nothing) - never invent a STATE=1 here, that would
        // overwrite a real prune boundary on a malformed payload.
        val cached = lowerBounds.getLastBound(LowerBoundType.STATE)
        if (cached != null) {
            log.debug(
                "Aztec upstream {} returned no oldestHistoricBlockNumber; retaining cached STATE={}",
                upstream.getId(),
                cached.lowerBound,
            )
            return cached
        }
        // No cache and a malformed first response: best we can do is emit a
        // synthetic archive bound. This is the only place STATE=1 is invented;
        // see the trade-off in the class KDoc.
        log.warn(
            "Aztec upstream {} returned no oldestHistoricBlockNumber and we have no cached STATE; assuming STATE=1",
            upstream.getId(),
        )
        return LowerBoundData(1, LowerBoundType.STATE)
    }
}
