package io.emeraldpay.dshackle.upstream.starknet

import io.emeraldpay.dshackle.upstream.RecursiveLowerBoundBlockDetector
import io.emeraldpay.dshackle.upstream.Upstream
import io.emeraldpay.dshackle.upstream.rpcclient.JsonRpcRequest
import io.emeraldpay.dshackle.upstream.rpcclient.JsonRpcResponse
import reactor.core.publisher.Mono

class StarknetLowerBoundBlockDetector(
    private val upstream: Upstream,
) : RecursiveLowerBoundBlockDetector(upstream) {

    override fun hasState(blockNumber: Long): Mono<Boolean> {
        return upstream.getIngressReader().read(
            JsonRpcRequest(
                "starknet_getStateUpdate", // in case of starknet we don't know exactly if they purge old blocks or not, but we assume that starknet_getStateUpdate helps us to determine the lower block
                listOf(
                    mapOf("block_number" to blockNumber),
                ),
            ),
        )
            .flatMap(JsonRpcResponse::requireResult)
            .map { true }
            .onErrorReturn(false)
    }
}
