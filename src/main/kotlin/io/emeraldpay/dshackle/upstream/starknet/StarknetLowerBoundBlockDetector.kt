package io.emeraldpay.dshackle.upstream.starknet

import io.emeraldpay.dshackle.upstream.LowerBoundBlockDetector
import reactor.core.publisher.Mono

class StarknetLowerBoundBlockDetector : LowerBoundBlockDetector() {

    // for starknet we assume that all nodes are archive
    override fun lowerBlockDetect(): Mono<LowerBlockData> {
        return Mono.just(LowerBlockData(1))
    }
}
