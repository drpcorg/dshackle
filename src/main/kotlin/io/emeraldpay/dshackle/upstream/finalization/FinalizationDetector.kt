package io.emeraldpay.dshackle.upstream.finalization

import io.emeraldpay.dshackle.upstream.Upstream
import reactor.core.publisher.Flux
import java.time.Duration

interface FinalizationDetector {
    fun detectFinalization(
        upstream: Upstream,
        blockTime: Duration,
    ): Flux<FinalizationData>

    fun getFinalizations(): Collection<FinalizationData>
}

class NoopFinalizationDetector : FinalizationDetector {
    override fun detectFinalization(
        upstream: Upstream,
        blockTime: Duration,
    ): Flux<FinalizationData> {
        return Flux.empty()
    }

    override fun getFinalizations(): Collection<FinalizationData> {
        return emptyList()
    }
}
