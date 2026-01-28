package io.emeraldpay.dshackle.upstream.ethereum

import io.emeraldpay.dshackle.Chain
import io.emeraldpay.dshackle.config.ChainsConfig
import io.emeraldpay.dshackle.reader.ChainReader
import io.emeraldpay.dshackle.upstream.ChainRequest
import io.emeraldpay.dshackle.upstream.ChainResponse
import io.emeraldpay.dshackle.upstream.Upstream
import io.emeraldpay.dshackle.upstream.lowerbound.GoldLowerBounds
import io.emeraldpay.dshackle.upstream.lowerbound.LowerBoundType
import io.emeraldpay.dshackle.upstream.lowerbound.NoopManualLowerBoundService
import io.emeraldpay.dshackle.upstream.rpcclient.ListParams
import org.junit.jupiter.api.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.time.Duration

class EthereumLowerBoundTxDetectorTest {

    @Test
    fun `archival bound if there is a gold bound`() {
        val reader = mock<ChainReader> {
            on { read(ChainRequest("eth_getTransactionByHash", ListParams("goldHash"))) } doReturn
                Mono.just(ChainResponse("result".toByteArray(), null))
        }
        val upstream = mock<Upstream> {
            on { getChain() } doReturn Chain.POLYGON__MAINNET
            on { getIngressReader() } doReturn reader
        }
        GoldLowerBounds.init(
            listOf(
                ChainsConfig.ChainConfig
                    .default()
                    .copy(
                        shortNames = listOf("polygon"),
                        goldLowerBounds = mapOf(ChainsConfig.LowerBoundType.TX to ChainsConfig.GoldLowerBoundWithHash(10L, "goldHash")),
                    ),
            ),
        )

        val txDetector = EthereumLowerBoundTxDetector(upstream)

        StepVerifier.withVirtualTime { txDetector.detectLowerBound(NoopManualLowerBoundService()) }
            .expectSubscription()
            .expectNoEvent(Duration.ofSeconds(15))
            .expectNextMatches { it.lowerBound == 1L && it.type == LowerBoundType.TX }
            .thenCancel()
            .verify(Duration.ofSeconds(1))

        verify(reader).read(ChainRequest("eth_getTransactionByHash", ListParams("goldHash")))
        verify(upstream).getIngressReader()
    }
}
