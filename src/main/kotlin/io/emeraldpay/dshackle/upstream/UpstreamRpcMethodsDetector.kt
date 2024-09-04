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

    open fun detectRpcMethods(): Mono<Map<String, Boolean>> =
        detectByMagicMethod()
            .map { it.associateWith { true } }
            .switchIfEmpty(detectByMethod())

    private fun detectByMethod(): Mono<Map<String, Boolean>> =
        Flux
            .fromIterable(rpcMethods())
            .flatMap { (method, params) ->
                upstream
                    .getIngressReader()
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

    protected abstract fun detectByMagicMethod(): Mono<List<String>>

    protected abstract fun rpcMethods(): Set<Pair<String, CallParams>>
}

// Should be Eth network only?
class BasicEthUpstreamRpcMethodsDetector(
    upstream: Upstream,
) : UpstreamRpcMethodsDetector(upstream) {
    override fun detectByMagicMethod(): Mono<List<String>> = Mono.empty()

    override fun rpcMethods(): Set<Pair<String, CallParams>> = setOf("eth_getBlockReceipts" to ListParams("latest"))
}

class BasicPolkadotUpstreamRpcMethodsDetector(
    private val upstream: Upstream,
) : UpstreamRpcMethodsDetector(upstream) {
    override fun detectByMagicMethod(): Mono<List<String>> =
        upstream
            .getIngressReader()
            .read(ChainRequest("rpc_methods", ListParams()))
            .flatMap(ChainResponse::requireResult)
            .map {
                Global.objectMapper
                    .readValue(it, object : TypeReference<HashMap<String, List<String>>>() {})
                    .getOrDefault("methods", emptyList())
            }.onErrorResume {
                log.warn(
                    "Can't detect rpc method rpc_methods of upstream ${upstream.getId()}, reason - {}",
                    it.message,
                )
                Mono.empty()
            }

    override fun rpcMethods(): Set<Pair<String, CallParams>> = emptySet()
}
