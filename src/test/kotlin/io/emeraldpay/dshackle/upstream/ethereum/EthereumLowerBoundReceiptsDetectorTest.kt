package io.emeraldpay.dshackle.upstream.ethereum

import com.fasterxml.jackson.databind.node.ObjectNode
import io.emeraldpay.dshackle.Chain
import io.emeraldpay.dshackle.Global
import io.emeraldpay.dshackle.config.ChainsConfig
import io.emeraldpay.dshackle.reader.ChainReader
import io.emeraldpay.dshackle.upstream.ChainRequest
import io.emeraldpay.dshackle.upstream.ChainResponse
import io.emeraldpay.dshackle.upstream.Head
import io.emeraldpay.dshackle.upstream.Upstream
import io.emeraldpay.dshackle.upstream.lowerbound.GoldLowerBounds
import io.emeraldpay.dshackle.upstream.lowerbound.LowerBoundType
import io.emeraldpay.dshackle.upstream.lowerbound.NoopManualLowerBoundService
import io.emeraldpay.dshackle.upstream.rpcclient.ListParams
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.io.File
import java.time.Duration

class EthereumLowerBoundReceiptsDetectorTest {

    @Test
    fun `archival bound if there is a gold bound`() {
        val reader = mock<ChainReader> {
            on { read(ChainRequest("eth_getTransactionReceipt", ListParams("goldHash"))) } doReturn
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
                        goldLowerBounds = mapOf(ChainsConfig.LowerBoundType.RECEIPTS to ChainsConfig.GoldLowerBoundWithHash(10L, "goldHash")),
                    ),
            ),
        )

        val txDetector = EthereumLowerBoundReceiptsDetector(upstream)

        StepVerifier.withVirtualTime { txDetector.detectLowerBound(NoopManualLowerBoundService()) }
            .expectSubscription()
            .expectNoEvent(Duration.ofSeconds(15))
            .expectNextMatches { it.lowerBound == 1L && it.type == LowerBoundType.RECEIPTS }
            .thenCancel()
            .verify(Duration.ofSeconds(1))

        verify(reader).read(ChainRequest("eth_getTransactionReceipt", ListParams("goldHash")))
        verify(upstream).getIngressReader()
    }

    @Test
    fun `bound is the start of the recent window when receipts have a gap`() {
        // reth with pruned receipts: early history plus the last 10064 blocks
        val head = 1_000_000L
        val bound = head - 10_064
        val detector = EthereumLowerBoundReceiptsDetector(upstreamWithReceipts(head) { it <= 1_000 || it >= bound })

        StepVerifier.withVirtualTime { detector.detectLowerBound(NoopManualLowerBoundService()) }
            .expectSubscription()
            .expectNoEvent(Duration.ofSeconds(15))
            .expectNextMatches { it.lowerBound == bound && it.type == LowerBoundType.RECEIPTS }
            .expectNextMatches { it.lowerBound == bound && it.type == LowerBoundType.LOGS }
            .thenCancel()
            .verify(Duration.ofSeconds(3))
    }

    @Test
    fun `archive node with empty early blocks keeps bound 1`() {
        val head = 1_000_000L
        val detector = EthereumLowerBoundReceiptsDetector(
            upstreamWithReceipts(head, emptyBlocks = { it <= 30 }) { true },
        )

        StepVerifier.withVirtualTime { detector.detectLowerBound(NoopManualLowerBoundService()) }
            .expectSubscription()
            .expectNoEvent(Duration.ofSeconds(15))
            .expectNextMatches { it.lowerBound == 1L && it.type == LowerBoundType.RECEIPTS }
            .expectNextMatches { it.lowerBound == 1L && it.type == LowerBoundType.LOGS }
            .thenCancel()
            .verify(Duration.ofSeconds(3))
    }

    private fun upstreamWithReceipts(
        head: Long,
        emptyBlocks: (Long) -> Boolean = { false },
        hasReceipts: (Long) -> Boolean,
    ): Upstream {
        GoldLowerBounds.init(emptyList())
        val template = Global.objectMapper.readTree(
            File(this::class.java.getResource("/responses/get-by-number-response.json")!!.toURI()),
        ) as ObjectNode

        // one transaction per block, its hash encodes the block number
        fun txHash(block: Long) = "0x" + block.toString(16).padStart(64, '0')
        val reader = mock<ChainReader> {
            on { read(any()) } doAnswer { inv ->
                val request = inv.getArgument<ChainRequest>(0)
                val param = (request.params as ListParams).list.first() as String
                when (request.method) {
                    "eth_getBlockByNumber" -> {
                        val block = param.removePrefix("0x").toLong(16)
                        val json = template.deepCopy()
                        val txs = json.putArray("transactions")
                        if (!emptyBlocks(block)) txs.add(txHash(block))
                        Mono.just(ChainResponse(Global.objectMapper.writeValueAsBytes(json), null))
                    }
                    "eth_getTransactionReceipt" -> {
                        val block = param.removePrefix("0x").toLong(16)
                        val result = if (hasReceipts(block)) "{}" else "null"
                        Mono.just(ChainResponse(result.toByteArray(), null))
                    }
                    else -> Mono.error(IllegalStateException("unexpected ${request.method}"))
                }
            }
        }
        val headMock = mock<Head> {
            on { getCurrentHeight() } doReturn head
        }
        return mock {
            on { getId() } doReturn "id"
            on { getChain() } doReturn Chain.UNSPECIFIED
            on { getHead() } doReturn headMock
            on { getIngressReader() } doReturn reader
        }
    }
}
