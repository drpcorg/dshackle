package io.emeraldpay.dshackle.upstream.kadena

import io.emeraldpay.dshackle.Defaults
import io.emeraldpay.dshackle.Global
import io.emeraldpay.dshackle.upstream.ChainRequest
import io.emeraldpay.dshackle.upstream.Upstream
import io.emeraldpay.dshackle.upstream.lowerbound.LowerBoundData
import io.emeraldpay.dshackle.upstream.lowerbound.LowerBoundDetector
import io.emeraldpay.dshackle.upstream.lowerbound.LowerBoundType
import io.emeraldpay.dshackle.upstream.rpcclient.ListParams
import reactor.core.publisher.Flux

class KadenaLowerBoundStateDetector(
    private val upstream: Upstream,
) : LowerBoundDetector(upstream.getChain()) {

    override fun period(): Long {
        return 3
    }

    override fun internalDetectLowerBound(): Flux<LowerBoundData> {
        return upstream.getIngressReader()
            .read(ChainRequest("/cut", ListParams()))
            .timeout(Defaults.internalCallsTimeout)
            .map {
                val resp = Global.objectMapper.readValue(it.getResult(), KadenaHeader::class.java)
                resp.id
            }.flatMapMany {
                Flux.fromIterable(
                    listOf(
//                        LowerBoundData(it, LowerBoundType.STATE),
//                        LowerBoundData(it, LowerBoundType.BLOCK),
                    ),
                )
            }
    }

    override fun types(): Set<LowerBoundType> {
        return setOf(LowerBoundType.STATE, LowerBoundType.BLOCK)
    }
}
