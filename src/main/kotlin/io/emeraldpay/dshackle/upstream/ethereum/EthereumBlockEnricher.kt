package io.emeraldpay.dshackle.upstream.ethereum

import io.emeraldpay.dshackle.Defaults
import io.emeraldpay.dshackle.SilentException
import io.emeraldpay.dshackle.data.BlockContainer
import io.emeraldpay.dshackle.reader.JsonRpcReader
import io.emeraldpay.dshackle.upstream.rpcclient.JsonRpcRequest
import io.emeraldpay.dshackle.upstream.rpcclient.JsonRpcResponse
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler
import reactor.retry.Repeat
import java.time.Duration

class EthereumBlockEnricher {
    companion object {
        fun enrich(blockHash: String, api: JsonRpcReader, scheduler: Scheduler, upstreamId: String): Mono<BlockContainer> {
            return Mono.just(blockHash)
                .flatMap { hash ->
                    api.read(JsonRpcRequest("eth_getBlockByHash", listOf(hash, false)))
                        .flatMap { resp ->
                            if (resp.isNull()) {
                                Mono.error(SilentException("Received null for block $hash"))
                            } else {
                                Mono.just(resp)
                            }
                        }
                        .flatMap(JsonRpcResponse::requireResult)
                        .map { BlockContainer.fromEthereumJson(it, upstreamId) }
                        .subscribeOn(scheduler)
                        .timeout(Defaults.timeoutInternal, Mono.empty())
                }.repeatWhenEmpty { n ->
                    Repeat.times<Any>(5)
                        .exponentialBackoff(Duration.ofMillis(50), Duration.ofMillis(500))
                        .apply(n)
                }
                .timeout(Defaults.timeout, Mono.empty())
                .onErrorResume { Mono.empty() }
        }
    }
}
