package io.emeraldpay.dshackle.upstream.error

import io.emeraldpay.dshackle.upstream.ChainRequest
import io.emeraldpay.dshackle.upstream.Upstream
import java.util.concurrent.CompletableFuture

interface ErrorHandler {
    fun handle(upstream: Upstream, request: ChainRequest, errorMessage: String?)

    fun canHandle(request: ChainRequest, errorMessage: String?): Boolean
}

object UpstreamErrorHandler {
    private val errorHandlers = listOf(
        EthereumStateLowerBoundErrorHandler,
    )

    fun handle(upstream: Upstream, request: ChainRequest, errorMessage: String?) {
        CompletableFuture.runAsync {
            errorHandlers
                .filter { it.canHandle(request, errorMessage) }
                .forEach { it.handle(upstream, request, errorMessage) }
        }
    }
}
