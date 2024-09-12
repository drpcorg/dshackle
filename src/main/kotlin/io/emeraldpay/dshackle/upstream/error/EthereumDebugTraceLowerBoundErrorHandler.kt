package io.emeraldpay.dshackle.upstream.error

import io.emeraldpay.dshackle.upstream.ChainRequest
import io.emeraldpay.dshackle.upstream.Upstream
import io.emeraldpay.dshackle.upstream.ethereum.EthereumLowerBoundStateDetector.Companion.stateErrors
import io.emeraldpay.dshackle.upstream.lowerbound.LowerBoundType

object EthereumDebugTraceLowerBoundErrorHandler : EthereumLowerBoundErrorHandler() {
    private val zeroTagIndexMethods = setOf(
        "trace_block",
        "arbtrace_block",
        "debug_traceBlockByNumber",
    )
    private val firstTagIndexMethods = setOf(
        "trace_callMany",
        "arbtrace_callMany",
        "debug_traceCall",
    )
    private val secondTagIndexMethods = setOf(
        "trace_call",
        "arbtrace_call",
    )

    private val applicableMethods = zeroTagIndexMethods + firstTagIndexMethods + secondTagIndexMethods

    private val errors = stateErrors
        .plus(
            setOf(
                "historical state not available",
            ),
        )
    private val errorRegexp = Regex("block .* not found")

    override fun handle(upstream: Upstream, request: ChainRequest, errorMessage: String?) {
        val type = if (request.method.startsWith("debug")) LowerBoundType.DEBUG else LowerBoundType.TRACE
        try {
            if (canHandle(request, errorMessage)) {
                parseTagParam(request, tagIndex(request.method))?.let {
                    upstream.updateLowerBound(it, type)
                }
            }
        } catch (e: RuntimeException) {
            log.warn("Couldn't update the {} lower bound of {}, reason - {}", type, upstream.getId(), e.message)
        }
    }

    override fun canHandle(request: ChainRequest, errorMessage: String?): Boolean {
        return (errors.any { errorMessage?.contains(it) ?: false } || (errorMessage?.matches(errorRegexp) ?: false)) &&
            applicableMethods.contains(request.method)
    }

    override fun tagIndex(method: String): Int {
        return if (firstTagIndexMethods.contains(method)) {
            1
        } else if (secondTagIndexMethods.contains(method)) {
            2
        } else if (zeroTagIndexMethods.contains(method)) {
            0
        } else {
            -1
        }
    }
}
