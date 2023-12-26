package io.emeraldpay.dshackle.upstream.polkadot

import io.emeraldpay.dshackle.upstream.RecursiveLowerBoundBlockDetector
import io.emeraldpay.dshackle.upstream.Upstream
import io.emeraldpay.dshackle.upstream.rpcclient.JsonRpcRequest
import io.emeraldpay.dshackle.upstream.rpcclient.JsonRpcResponse
import io.emeraldpay.dshackle.upstream.toHex
import reactor.core.publisher.Mono

class PolkadotLowerBoundBlockDetector(
    private val upstream: Upstream,
) : RecursiveLowerBoundBlockDetector(upstream) {

    override fun hasState(blockNumber: Long): Mono<Boolean> {
        return upstream.getIngressReader().read(
            JsonRpcRequest(
                "chain_getBlockHash",
                listOf(blockNumber.toHex()), // in polkadot state methods work only with hash
            ),
        )
            .flatMap(JsonRpcResponse::requireResult)
            .map {
                String(it, 1, it.size - 2)
            }
            .flatMap {
                upstream.getIngressReader().read(
                    JsonRpcRequest(
                        "state_getMetadata",
                        listOf(it),
                    ),
                )
            }
            .flatMap(JsonRpcResponse::requireResult)
            .map { true }
            .onErrorReturn(false)
    }
}
