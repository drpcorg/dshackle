package io.emeraldpay.dshackle.upstream.error

import io.emeraldpay.dshackle.upstream.ChainRequest
import io.emeraldpay.dshackle.upstream.Upstream
import io.emeraldpay.dshackle.upstream.lowerbound.LowerBoundType
import io.emeraldpay.dshackle.upstream.rpcclient.ListParams
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.of
import org.junit.jupiter.params.provider.MethodSource
import org.mockito.Mockito.mock
import org.mockito.kotlin.verify

class EthereumDebugTraceLowerBoundErrorHandlerTest {

    @ParameterizedTest
    @MethodSource("requests")
    fun `update lower bound`(request: ChainRequest, type: LowerBoundType) {
        val upstream = mock<Upstream>()
        val handler = EthereumDebugTraceLowerBoundErrorHandler

        handler.handle(upstream, request, "missing trie node d5648cc9aef48154159d53800f2f")

        verify(upstream).updateLowerBound(213229736, type)
    }

    @Test
    fun `update lower bound base on regexp`() {
        val upstream = mock<Upstream>()
        val handler = EthereumDebugTraceLowerBoundErrorHandler

        handler.handle(upstream, ChainRequest("trace_block", ListParams("0xCB5A0A8")), "block #1 not found")

        verify(upstream).updateLowerBound(213229736, LowerBoundType.TRACE)
    }

    companion object {
        @JvmStatic
        fun requests(): List<Arguments> =
            listOf(
                of(ChainRequest("trace_block", ListParams("0xCB5A0A8")), LowerBoundType.TRACE),
                of(ChainRequest("arbtrace_block", ListParams("0xCB5A0A8")), LowerBoundType.TRACE),
                of(ChainRequest("debug_traceBlockByNumber", ListParams("0xCB5A0A8", mapOf("tracer" to "tracer"))), LowerBoundType.DEBUG),
                of(ChainRequest("trace_callMany", ListParams(arrayOf(mapOf("val" to 1)), "0xCB5A0A8")), LowerBoundType.TRACE),
                of(ChainRequest("arbtrace_callMany", ListParams(arrayOf(mapOf("val" to 1)), "0xCB5A0A8")), LowerBoundType.TRACE),
                of(ChainRequest("debug_traceCall", ListParams(mapOf("val" to 1), "0xCB5A0A8", mapOf("val" to 1))), LowerBoundType.DEBUG),
                of(ChainRequest("trace_call", ListParams(mapOf("val" to 1), arrayOf(mapOf("val" to 1)), "0xCB5A0A8")), LowerBoundType.TRACE),
                of(ChainRequest("arbtrace_call", ListParams(mapOf("val" to 1), arrayOf(mapOf("val" to 1)), "0xCB5A0A8")), LowerBoundType.TRACE),
            )
    }
}
