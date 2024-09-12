package io.emeraldpay.dshackle.upstream.error

import io.emeraldpay.dshackle.upstream.ChainRequest
import io.emeraldpay.dshackle.upstream.Upstream
import io.emeraldpay.dshackle.upstream.ethereum.EthereumLowerBoundStateDetector.Companion.stateErrors
import io.emeraldpay.dshackle.upstream.lowerbound.LowerBoundType

object EthereumStateLowerBoundErrorHandler : EthereumLowerBoundErrorHandler() {
    private val firstTagIndexMethods = setOf(
        "eth_call",
        "debug_traceCall",
        "eth_getBalance",
        "eth_estimateGas",
        "eth_getCode",
        "eth_getTransactionCount",
    )
    private val secondTagIndexMethods = setOf(
        "eth_getProof",
        "eth_getStorageAt",
    )

    private val applicableMethods = firstTagIndexMethods + secondTagIndexMethods

    override fun handle(upstream: Upstream, request: ChainRequest, errorMessage: String?) {
        try {
            if (canHandle(request, errorMessage)) {
                parseTagParam(request, tagIndex(request.method))?.let {
                    upstream.updateLowerBound(it, LowerBoundType.STATE)
                }
            }
        } catch (e: RuntimeException) {
            log.warn("Couldn't update the {} lower bound of {}, reason - {}", LowerBoundType.STATE, upstream.getId(), e.message)
        }
    }

    override fun canHandle(request: ChainRequest, errorMessage: String?): Boolean {
        return stateErrors.any { errorMessage?.contains(it) ?: false } && applicableMethods.contains(request.method)
    }

    override fun tagIndex(method: String): Int {
        return if (firstTagIndexMethods.contains(method)) {
            1
        } else if (secondTagIndexMethods.contains(method)) {
            2
        } else {
            -1
        }
    }
}
