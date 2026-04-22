package io.emeraldpay.dshackle.upstream.avm

import io.emeraldpay.dshackle.Chain
import io.emeraldpay.dshackle.upstream.Upstream
import io.emeraldpay.dshackle.upstream.lowerbound.LowerBoundDetector
import io.emeraldpay.dshackle.upstream.lowerbound.LowerBoundService

class AvmLowerBoundService(
    private val chain: Chain,
    upstream: Upstream,
) : LowerBoundService(chain, upstream) {
    override fun detectors(): List<LowerBoundDetector> {
        return listOf(AvmLowerBoundStateDetector(chain))
    }
}
