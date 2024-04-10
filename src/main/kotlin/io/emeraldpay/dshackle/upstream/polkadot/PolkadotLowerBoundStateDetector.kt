package io.emeraldpay.dshackle.upstream.polkadot

import io.emeraldpay.dshackle.upstream.ChainRequest
import io.emeraldpay.dshackle.upstream.ChainResponse
import io.emeraldpay.dshackle.upstream.Upstream
import io.emeraldpay.dshackle.upstream.lowerbound.LowerBoundType
import io.emeraldpay.dshackle.upstream.lowerbound.detector.RecursiveLowerBoundDetector
import io.emeraldpay.dshackle.upstream.lowerbound.toHex
import io.emeraldpay.dshackle.upstream.rpcclient.ListParams
import reactor.core.publisher.Mono

class PolkadotLowerBoundStateDetector(
    private val upstream: Upstream,
) : RecursiveLowerBoundDetector(upstream) {

    companion object {
        private val nonRetryableErrors = setOf(
            "State already discarded for",
        )
    }

    override fun hasData(block: Long): Mono<Boolean> {
        return upstream.getIngressReader().read(
            ChainRequest(
                "chain_getBlockHash",
                ListParams(block.toHex()), // in polkadot state methods work only with hash
            ),
        )
            .flatMap(ChainResponse::requireResult)
            .map {
                String(it, 1, it.size - 2)
            }
            .flatMap {
                upstream.getIngressReader().read(
                    ChainRequest(
                        "state_getMetadata",
                        ListParams(it),
                    ),
                )
            }
            .retryWhen(retrySpec(nonRetryableErrors))
            .flatMap(ChainResponse::requireResult)
            .map { true }
            .onErrorReturn(false)
    }

    override fun type(): LowerBoundType {
        return LowerBoundType.STATE
    }
}
