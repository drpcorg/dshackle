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

    open fun detectRpcMethods(): Mono<Map<String, Boolean>> = detectByMagicMethod().switchIfEmpty(detectByMethod())

    protected fun detectByMethod(): Mono<Map<String, Boolean>> =
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
                            .onErrorResume { err ->
                                if (err.message?.contains("does not exist/is not available") == true) {
                                    Mono.just(method to false)
                                } else {
                                    Mono.empty()
                                }
                            }
                    }
            },
        ) {
            it
                .map { p -> p as Pair<String, Boolean> }
                .associate { (method, enabled) -> method to enabled }
        }

    protected abstract fun detectByMagicMethod(): Mono<Map<String, Boolean>>

    protected abstract fun rpcMethods(): Set<Pair<String, CallParams>>
}
