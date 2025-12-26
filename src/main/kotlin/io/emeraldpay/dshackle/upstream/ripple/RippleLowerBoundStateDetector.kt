package io.emeraldpay.dshackle.upstream.ripple

import io.emeraldpay.dshackle.Defaults
import io.emeraldpay.dshackle.Global
import io.emeraldpay.dshackle.upstream.ChainRequest
import io.emeraldpay.dshackle.upstream.Upstream
import io.emeraldpay.dshackle.upstream.lowerbound.LowerBoundData
import io.emeraldpay.dshackle.upstream.lowerbound.LowerBoundDetector
import io.emeraldpay.dshackle.upstream.lowerbound.LowerBoundType
import io.emeraldpay.dshackle.upstream.rpcclient.ListParams
import reactor.core.publisher.Flux

class RippleLowerBoundStateDetector(
    private val upstream: Upstream,
) : LowerBoundDetector(upstream.getChain()) {

    override fun period(): Long {
        return 120
    }

    override fun internalDetectLowerBound(): Flux<LowerBoundData> {
        return upstream.getIngressReader()
            .read(ChainRequest("server_state", ListParams()))
            .timeout(Defaults.internalCallsTimeout)
            .map {
                val resp = Global.objectMapper.readValue(it.getResult(), RippleState::class.java)
                parseCompleteLedgersLowerBound(resp.state.completeLedgers)
            }
            .filter { it != null }
            .map { it!! }
            .flatMapMany { lowerBound ->
                Flux.fromIterable(listOf(LowerBoundData(lowerBound, LowerBoundType.STATE)))
            }
    }

    override fun types(): Set<LowerBoundType> {
        return setOf(LowerBoundType.STATE)
    }

    private fun parseCompleteLedgersLowerBound(completeLedgers: String?): Long? {
        if (completeLedgers.isNullOrBlank()) {
            return null
        }
        if (completeLedgers == "empty") {
            return null
        }

        return completeLedgers.split(",")
            .mapNotNull { part ->
                val token = part.trim()
                if (token.isEmpty()) {
                    null
                } else {
                    token.substringBefore("-").toLongOrNull()
                }
            }
            .minOrNull()
    }
}
