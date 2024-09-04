package io.emeraldpay.dshackle.upstream

import com.fasterxml.jackson.core.type.TypeReference
import io.emeraldpay.dshackle.Global
import io.emeraldpay.dshackle.upstream.rpcclient.CallParams
import io.emeraldpay.dshackle.upstream.rpcclient.ListParams
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono

typealias UpstreamRpcMethodsDetectorBuilder = (Upstream) -> UpstreamRpcMethodsDetector?

abstract class UpstreamRpcMethodsDetector(
    private val upstream: Upstream,
) {
    protected val log: Logger = LoggerFactory.getLogger(this::class.java)

    open fun detectRpcMethods(): Mono<Map<String, Boolean>> {
        return Flux.fromIterable(rpcMethods())
            .flatMap { (method, params) ->
                upstream.getIngressReader()
                    .read(ChainRequest(method, params))
                    .flatMap(ChainResponse::requireResult)
                    .map { mapOf(method to true) }
                    .onErrorResume {
                        log.warn(
                            "Can't detect rpc method $method of upstream ${upstream.getId()}, reason - {}",
                            it.message,
                        )
                        mapOf(method to false).toMono()
                    }
            }.toMono()
    }

    protected abstract fun rpcMethods(): Set<Pair<String, CallParams>>
}

class BasicEthUpstreamRpcMethodsDetector(
    upstream: Upstream,
) : UpstreamRpcMethodsDetector(upstream) {
    override fun rpcMethods(): Set<Pair<String, CallParams>> {
        return setOf("eth_getBlockReceipts" to ListParams())
    }
}
