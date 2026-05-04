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
 * Failure handling:
 *  - On RPC error / unparseable response we re-emit the cached LowerBoundData
 *    unchanged (same instance / same timestamp) so `updateBound`'s
 *    `newBound.timestamp != lastBound.timestamp` guard skips the regression
 *    update and the cached bound stays put.
 *  - If there is no cached value yet, we emit nothing. We do **not** synthesize
 *    `STATE=1`: that would falsely advertise full archive history to the
 *    router. The next refresh tick will retry.
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
            .flatMap { data -> parseOldestHistoric(data) }
            .onErrorResume { err -> retainCachedOrSkip(err.message) }
            .flux()
    }

    private fun parseOldestHistoric(data: ByteArray): Mono<LowerBoundData> {
        val raw = Global.objectMapper.readTree(data)
        val node = raw.get("oldestHistoricBlockNumber")
        if (node != null && !node.isNull && node.isNumber) {
            return Mono.just(LowerBoundData(node.asLong().coerceAtLeast(1L), LowerBoundType.STATE))
        }
        return retainCachedOrSkip("missing oldestHistoricBlockNumber")
    }

    private fun retainCachedOrSkip(reason: String?): Mono<LowerBoundData> {
        val cached = lowerBounds.getLastBound(LowerBoundType.STATE)
        if (cached != null) {
            log.debug(
                "Aztec upstream {} world state sync status unavailable; retaining cached STATE={}: {}",
                upstream.getId(),
                cached.lowerBound,
                reason,
            )
            // Same instance (same timestamp) so updateBound becomes a no-op
            // and the linear-regression coefficients are preserved.
            return Mono.just(cached)
        }
        // No cache and a malformed first response: best we can do is emit a
        // synthetic archive bound. This is the only place STATE=1 is invented;
        // see the trade-off in the class KDoc.
        log.warn(
            "Aztec upstream {} returned no oldestHistoricBlockNumber and we have no cached STATE",
            upstream.getId(),
            reason,
        )
        return LowerBoundData(0, LowerBoundType.UNKNOWN)
    }
}
