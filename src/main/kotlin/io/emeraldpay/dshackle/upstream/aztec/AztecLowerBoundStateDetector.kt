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
 * On transient errors (the public Aztec endpoint occasionally returns code 19 on
 * this method) we fall back to STATE=1 since Aztec full nodes are archive by
 * default. Subsequent refresh ticks will retry and pick up the real prune
 * boundary if the node is actually pruning.
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
                log.debug(
                    "Aztec upstream {} did not report oldestHistoricBlockNumber, defaulting to STATE=1: {}",
                    upstream.getId(),
                    err.message,
                )
                Mono.just(LowerBoundData(1, LowerBoundType.STATE))
            }
            .flux()
    }

    private fun parseOldestHistoric(data: ByteArray): LowerBoundData {
        val raw = Global.objectMapper.readTree(data)
        val node = raw.get("oldestHistoricBlockNumber")
        val oldest = if (node != null && !node.isNull && node.isNumber) {
            node.asLong().coerceAtLeast(1L)
        } else {
            1L
        }
        return LowerBoundData(oldest, LowerBoundType.STATE)
    }
}
