package io.emeraldpay.dshackle.upstream.kadena

import io.emeraldpay.dshackle.Chain
import io.emeraldpay.dshackle.upstream.Upstream
import io.emeraldpay.dshackle.upstream.lowerbound.LowerBoundDetector
import io.emeraldpay.dshackle.upstream.lowerbound.LowerBoundService

class KadenaLowerBoundService(
    chain: Chain,
    private val upstream: Upstream,
) : LowerBoundService(chain, upstream) {
    override fun detectors(): List<LowerBoundDetector> {
        return listOf(KadenaLowerBoundStateDetector(upstream))
    }
}
