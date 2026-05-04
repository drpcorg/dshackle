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
 * On transient errors (the public Aztec endpoint occasionally returns code 19
 * on this method) we re-emit the previously cached STATE bound, so a flaky
 * tick can't clobber a real prune boundary with a fabricated default. If no
 * value has been read yet, the tick is skipped entirely - the router will
 * pick up the bound the next time the upstream answers successfully.
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
                    // Re-emit the cached value. The base detector's filter
                    // (lowerBound >= last) will pass it through and updateBound
                    // becomes a no-op. The cache stays at its last good value
                    // until the next successful refresh.
                    log.debug(
                        "Aztec upstream {} world state sync status unavailable; retaining cached STATE={}: {}",
                        upstream.getId(),
                        cached.lowerBound,
                        err.message,
                    )
                    Mono.just(LowerBoundData(cached.lowerBound, LowerBoundType.STATE))
                } else {
                    // First tick failed - we have no idea what the bound is.
                    // Skip the tick entirely (don't fake STATE=1). The base
                    // detector's switchIfEmpty will publish a default UNKNOWN/0
                    // for routing telemetry, but the STATE slot stays empty
                    // until we successfully read a real value.
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
        val oldest = if (node != null && !node.isNull && node.isNumber) {
            node.asLong().coerceAtLeast(1L)
        } else {
            // Response parsed but the field is missing/non-numeric. Re-emit cache
            // (or nothing) instead of guessing STATE=1.
            lowerBounds.getLastBound(LowerBoundType.STATE)?.lowerBound ?: 1L
        }
        return LowerBoundData(oldest, LowerBoundType.STATE)
    }
}
