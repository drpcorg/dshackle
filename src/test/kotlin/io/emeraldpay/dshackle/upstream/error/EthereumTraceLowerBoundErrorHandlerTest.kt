package io.emeraldpay.dshackle.upstream.error

import io.emeraldpay.dshackle.upstream.ChainRequest
import io.emeraldpay.dshackle.upstream.Head
import io.emeraldpay.dshackle.upstream.Upstream
import io.emeraldpay.dshackle.upstream.lowerbound.LowerBoundType
import io.emeraldpay.dshackle.upstream.rpcclient.ListParams
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.of
import org.junit.jupiter.params.provider.MethodSource
import org.mockito.Mockito.anyLong
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class EthereumTraceLowerBoundErrorHandlerTest {

    @ParameterizedTest
    @MethodSource("requests")
    fun `update lower bound`(request: ChainRequest) {
        val upstream = mockUpstreamWithHead(300_000_000L)
        val handler = EthereumTraceLowerBoundErrorHandler

        handler.handle(upstream, request, "missing trie node d5648cc9aef48154159d53800f2f")

        verify(upstream).updateLowerBound(213229736, LowerBoundType.TRACE)
    }

    @Test
    fun `update lower bound base on regexp`() {
        val upstream = mockUpstreamWithHead(300_000_000L)
        val handler = EthereumTraceLowerBoundErrorHandler

        handler.handle(upstream, ChainRequest("trace_block", ListParams("0xCB5A0A8")), "block #1 not found")

        verify(upstream).updateLowerBound(213229736, LowerBoundType.TRACE)
    }

    @Test
    fun `no update lower bound if parsed tag exceeds head`() {
        val upstream = mockUpstreamWithHead(100_000_000L)

        EthereumTraceLowerBoundErrorHandler.handle(
            upstream,
            ChainRequest("trace_block", ListParams("0xCB5A0A8")),
            "missing trie node d5648cc9aef48154159d53800f2f",
        )

        verify(upstream, never()).updateLowerBound(anyLong(), any())
    }

    @Test
    fun `no update lower bound if head height is null`() {
        val upstream = mockUpstreamWithHead(null)

        EthereumTraceLowerBoundErrorHandler.handle(
            upstream,
            ChainRequest("trace_block", ListParams("0xCB5A0A8")),
            "missing trie node d5648cc9aef48154159d53800f2f",
        )

        verify(upstream, never()).updateLowerBound(anyLong(), any())
    }

    private fun mockUpstreamWithHead(height: Long?): Upstream {
        val head = mock<Head>()
        whenever(head.getCurrentHeight()).thenReturn(height)
        val upstream = mock<Upstream>()
        whenever(upstream.getHead()).thenReturn(head)
        return upstream
    }

    companion object {
        @JvmStatic
        fun requests(): List<Arguments> =
            listOf(
                of(ChainRequest("trace_block", ListParams("0xCB5A0A8"))),
                of(ChainRequest("arbtrace_block", ListParams("0xCB5A0A8"))),
                of(ChainRequest("debug_traceBlockByNumber", ListParams("0xCB5A0A8", mapOf("tracer" to "tracer")))),
                of(ChainRequest("trace_callMany", ListParams(arrayOf(mapOf("val" to 1)), "0xCB5A0A8"))),
                of(ChainRequest("arbtrace_callMany", ListParams(arrayOf(mapOf("val" to 1)), "0xCB5A0A8"))),
                of(ChainRequest("debug_traceCall", ListParams(mapOf("val" to 1), "0xCB5A0A8", mapOf("val" to 1)))),
                of(ChainRequest("trace_call", ListParams(mapOf("val" to 1), arrayOf(mapOf("val" to 1)), "0xCB5A0A8"))),
                of(ChainRequest("arbtrace_call", ListParams(mapOf("val" to 1), arrayOf(mapOf("val" to 1)), "0xCB5A0A8"))),
            )
    }
}
