package io.emeraldpay.dshackle.upstream.error

import io.emeraldpay.dshackle.upstream.ChainRequest
import io.emeraldpay.dshackle.upstream.Head
import io.emeraldpay.dshackle.upstream.Upstream
import io.emeraldpay.dshackle.upstream.lowerbound.LowerBoundType
import io.emeraldpay.dshackle.upstream.rpcclient.ListParams
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class UpstreamErrorHandlerTest {

    @Test
    fun `use lower bound error handler`() {
        val head = mock<Head>()
        whenever(head.getCurrentHeight()).thenReturn(300_000_000L)
        val upstream = mock<Upstream>()
        whenever(upstream.getHead()).thenReturn(head)
        val request = ChainRequest("eth_getCode", ListParams("0x343", "0xCB5A0A8"))
        val handler = UpstreamErrorHandler

        handler.handle(upstream, request, "missing trie node d5648cc9aef48154159d53800f2f")

        Thread.sleep(100)

        verify(upstream).updateLowerBound(213229736, LowerBoundType.STATE)
    }
}
