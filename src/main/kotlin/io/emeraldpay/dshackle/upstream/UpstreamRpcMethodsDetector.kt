package io.emeraldpay.dshackle.upstream

import io.emeraldpay.dshackle.upstream.rpcclient.CallParams
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono

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
        Mono.zip(
            rpcMethods().map {
                Mono
                    .just(it)
                    .flatMap { (method, param) ->
                        upstream
                            .getIngressReader()
                            .read(ChainRequest(method, param))
                            .flatMap(ChainResponse::requireResult)
                            .map { method to true }
                            .onErrorReturn(
                                method to false,
                            )
                    }
            },
        ) {
            it
                .map { p -> p as Pair<String, Boolean> }
                .associate { (method, enabled) -> method to enabled }
        }

    protected abstract fun detectByMagicMethod(): Mono<List<String>>

    protected abstract fun rpcMethods(): Set<Pair<String, CallParams>>
}
