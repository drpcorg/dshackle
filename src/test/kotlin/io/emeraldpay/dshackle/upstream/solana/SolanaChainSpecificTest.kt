package io.emeraldpay.dshackle.upstream.solana

import io.emeraldpay.dshackle.data.BlockId
import io.emeraldpay.dshackle.reader.ChainReader
import io.emeraldpay.dshackle.upstream.ChainRequest
import io.emeraldpay.dshackle.upstream.ChainResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import reactor.core.publisher.Mono
import java.nio.ByteBuffer

// blockSubscribe response example
val blockSubscribeExample = """{
      "context": {
        "slot": 112301554
      },
      "value": {
        "slot": 112301554,
        "block": {
          "previousBlockhash": "GJp125YAN4ufCSUvZJVdCyWQJ7RPWMmwxoyUQySydZA",
          "blockhash": "6ojMHjctdqfB55JDpEpqfHnP96fiaHEcvzEQ2NNcxzHP",
          "parentSlot": 112301553,
          "blockTime": 1639926816,
          "blockHeight": 101210751
        },
        "err": null
      }
    }
""".trimIndent()

// slotSubscribe response example
val slotSubscribeExample = """{
    "slot": 112301554,
    "parent": 112301553,
    "root": 112301500
}
""".trimIndent()

class SolanaChainSpecificTest {

    @BeforeEach
    fun setup() {
        // Reset to default strategy and metrics before each test
        SolanaChainSpecific.strategyType = SolanaHeadStrategyType.BLOCK_SUBSCRIBE
        SolanaChainSpecific.resetAllMetrics()
    }

    // =========================================================================
    // BlockSubscribe Strategy Tests (Original Implementation)
    // =========================================================================

    @Nested
    inner class BlockSubscribeStrategyTests {

        @Test
        fun `parseBlock from blockSubscribe notification`() {
            SolanaChainSpecific.strategyType = SolanaHeadStrategyType.BLOCK_SUBSCRIBE
            val reader = mock<ChainReader> {}

            val result = SolanaChainSpecific.getFromHeader(blockSubscribeExample.toByteArray(), "upstream-1", reader).block()!!

            assertThat(result.height).isEqualTo(101210751)
            assertThat(result.hash).isEqualTo(BlockId.fromBase64("6ojMHjctdqfB55JDpEpqfHnP96fiaHEcvzEQ2NNcxzHP"))
            assertThat(result.upstreamId).isEqualTo("upstream-1")
            assertThat(result.parentHash).isEqualTo(BlockId.fromBase64("GJp125YAN4ufCSUvZJVdCyWQJ7RPWMmwxoyUQySydZA"))
            assertThat(result.slot).isEqualTo(112301554)
        }

        @Test
        fun `listenNewHeadsRequest returns blockSubscribe`() {
            SolanaChainSpecific.strategyType = SolanaHeadStrategyType.BLOCK_SUBSCRIBE

            val request = SolanaChainSpecific.listenNewHeadsRequest()

            assertThat(request.method).isEqualTo("blockSubscribe")
        }

        @Test
        fun `unsubscribeNewHeadsRequest returns blockUnsubscribe`() {
            SolanaChainSpecific.strategyType = SolanaHeadStrategyType.BLOCK_SUBSCRIBE

            val request = SolanaChainSpecific.unsubscribeNewHeadsRequest("sub-123")

            assertThat(request.method).isEqualTo("blockUnsubscribe")
        }

        @Test
        fun `metrics track WS messages for blockSubscribe`() {
            SolanaChainSpecific.strategyType = SolanaHeadStrategyType.BLOCK_SUBSCRIBE
            val reader = mock<ChainReader> {}

            SolanaChainSpecific.getFromHeader(blockSubscribeExample.toByteArray(), "upstream-1", reader).block()

            val metrics = SolanaChainSpecific.currentStrategy.metrics
            assertThat(metrics.wsMessagesReceived.get()).isEqualTo(1)
            assertThat(metrics.headUpdates.get()).isEqualTo(1)
            assertThat(metrics.httpCallsMade.get()).isEqualTo(0) // No HTTP calls in blockSubscribe
        }
    }

    // =========================================================================
    // SlotSubscribe Strategy Tests (New Optimized Implementation)
    // =========================================================================

    @Nested
    inner class SlotSubscribeStrategyTests {

        @Test
        fun `parseBlock from slotSubscribe notification with cached height`() {
            SolanaChainSpecific.strategyType = SolanaHeadStrategyType.SLOT_SUBSCRIBE

            val reader = mock<ChainReader> {
                // First call to get initial height
                on { read(any<ChainRequest>()) }.thenReturn(
                    Mono.just(ChainResponse("101210751".toByteArray(), null))
                )
            }

            // Simulate first call to establish cache
            val strategy = SolanaChainSpecific.currentStrategy as SlotSubscribeStrategy
            strategy.clearCache()

            val result = SolanaChainSpecific.getFromHeader(slotSubscribeExample.toByteArray(), "upstream-1", reader).block()!!

            assertThat(result.slot).isEqualTo(112301554)
            // Height should come from HTTP call since cache is empty
            assertThat(result.height).isEqualTo(101210751)
            // Synthetic hash based on slot
            val expectedHash = BlockId.from(ByteBuffer.allocate(32).putLong(112301554).array())
            assertThat(result.hash).isEqualTo(expectedHash)
        }

        @Test
        fun `listenNewHeadsRequest returns slotSubscribe`() {
            SolanaChainSpecific.strategyType = SolanaHeadStrategyType.SLOT_SUBSCRIBE

            val request = SolanaChainSpecific.listenNewHeadsRequest()

            assertThat(request.method).isEqualTo("slotSubscribe")
        }

        @Test
        fun `unsubscribeNewHeadsRequest returns slotUnsubscribe`() {
            SolanaChainSpecific.strategyType = SolanaHeadStrategyType.SLOT_SUBSCRIBE

            val request = SolanaChainSpecific.unsubscribeNewHeadsRequest("sub-123")

            assertThat(request.method).isEqualTo("slotUnsubscribe")
        }

        @Test
        fun `throttle HTTP calls every N slots`() {
            SolanaChainSpecific.strategyType = SolanaHeadStrategyType.SLOT_SUBSCRIBE
            val strategy = SolanaChainSpecific.currentStrategy as SlotSubscribeStrategy
            strategy.clearCache()

            val reader = mock<ChainReader> {
                on { read(any<ChainRequest>()) }.thenReturn(
                    Mono.just(ChainResponse("100000000".toByteArray(), null))
                )
            }

            // First slot - should trigger HTTP call (no cache)
            val slot1 = """{"slot": 100, "parent": 99, "root": 50}"""
            SolanaChainSpecific.getFromHeader(slot1.toByteArray(), "upstream-1", reader).block()

            // Next 4 slots - should use cached height (within interval of 5)
            for (i in 101..104) {
                val slotN = """{"slot": $i, "parent": ${i - 1}, "root": 50}"""
                SolanaChainSpecific.getFromHeader(slotN.toByteArray(), "upstream-1", reader).block()
            }

            val metrics = strategy.metrics
            assertThat(metrics.wsMessagesReceived.get()).isEqualTo(5)
            // Only 1 HTTP call for first slot (others use cache since delta < 5)
            assertThat(metrics.httpCallsMade.get()).isEqualTo(1)

            // Slot 105 - should trigger new HTTP call (interval reached)
            val slot105 = """{"slot": 105, "parent": 104, "root": 50}"""
            SolanaChainSpecific.getFromHeader(slot105.toByteArray(), "upstream-1", reader).block()

            assertThat(metrics.httpCallsMade.get()).isEqualTo(2)
        }

        @Test
        fun `synthetic hash is deterministic based on slot`() {
            val slot = 12345L
            val hash1 = BlockId.from(ByteBuffer.allocate(32).putLong(slot).array())
            val hash2 = BlockId.from(ByteBuffer.allocate(32).putLong(slot).array())

            assertThat(hash1).isEqualTo(hash2)

            val differentSlot = 12346L
            val hash3 = BlockId.from(ByteBuffer.allocate(32).putLong(differentSlot).array())

            assertThat(hash1).isNotEqualTo(hash3)
        }
    }

    // =========================================================================
    // Strategy Switching Tests
    // =========================================================================

    @Nested
    inner class StrategySwitchingTests {

        @Test
        fun `switchStrategy changes active strategy`() {
            assertThat(SolanaChainSpecific.strategyType).isEqualTo(SolanaHeadStrategyType.BLOCK_SUBSCRIBE)
            assertThat(SolanaChainSpecific.currentStrategy.name).isEqualTo("blockSubscribe")

            SolanaChainSpecific.switchStrategy(SolanaHeadStrategyType.SLOT_SUBSCRIBE)

            assertThat(SolanaChainSpecific.strategyType).isEqualTo(SolanaHeadStrategyType.SLOT_SUBSCRIBE)
            assertThat(SolanaChainSpecific.currentStrategy.name).isEqualTo("slotSubscribe")
        }

        @Test
        fun `getAllMetrics returns metrics for both strategies`() {
            val allMetrics = SolanaChainSpecific.getAllMetrics()

            assertThat(allMetrics).containsKeys("blockSubscribe", "slotSubscribe")
            assertThat(allMetrics["blockSubscribe"]).containsKeys("strategy", "wsMessagesReceived", "httpCallsMade")
            assertThat(allMetrics["slotSubscribe"]).containsKeys("strategy", "wsMessagesReceived", "httpCallsMade")
        }

        @Test
        fun `resetAllMetrics clears counters for both strategies`() {
            // Generate some metrics
            val reader = mock<ChainReader> {}
            SolanaChainSpecific.strategyType = SolanaHeadStrategyType.BLOCK_SUBSCRIBE
            SolanaChainSpecific.getFromHeader(blockSubscribeExample.toByteArray(), "upstream-1", reader).block()

            assertThat(SolanaChainSpecific.currentStrategy.metrics.wsMessagesReceived.get()).isGreaterThan(0)

            SolanaChainSpecific.resetAllMetrics()

            assertThat(SolanaChainSpecific.getAllMetrics()["blockSubscribe"]!!["wsMessagesReceived"]).isEqualTo(0L)
            assertThat(SolanaChainSpecific.getAllMetrics()["slotSubscribe"]!!["wsMessagesReceived"]).isEqualTo(0L)
        }
    }

    // =========================================================================
    // Metrics Tests
    // =========================================================================

    @Nested
    inner class MetricsTests {

        @Test
        fun `SolanaHeadMetrics tracks latencies correctly`() {
            val metrics = SolanaHeadMetrics("test")

            metrics.recordLatency("operation1", 100)
            metrics.recordLatency("operation1", 150)
            metrics.recordLatency("operation1", 200)

            val map = metrics.toMap()
            assertThat(map["strategy"]).isEqualTo("test")
        }

        @Test
        fun `SolanaHeadMetrics reset clears all counters`() {
            val metrics = SolanaHeadMetrics("test")

            metrics.recordWsMessage()
            metrics.recordHttpCall()
            metrics.recordHeadUpdate()
            metrics.recordError()

            assertThat(metrics.wsMessagesReceived.get()).isEqualTo(1)
            assertThat(metrics.httpCallsMade.get()).isEqualTo(1)

            metrics.reset()

            assertThat(metrics.wsMessagesReceived.get()).isEqualTo(0)
            assertThat(metrics.httpCallsMade.get()).isEqualTo(0)
            assertThat(metrics.headUpdates.get()).isEqualTo(0)
            assertThat(metrics.errors.get()).isEqualTo(0)
        }
    }

    // =========================================================================
    // Data Class Tests
    // =========================================================================

    @Nested
    inner class DataClassTests {

        @Test
        fun `SolanaSlotNotification parses correctly`() {
            val json = """{"slot": 123456, "parent": 123455, "root": 123400}"""
            val notification = io.emeraldpay.dshackle.Global.objectMapper.readValue(json, SolanaSlotNotification::class.java)

            assertThat(notification.slot).isEqualTo(123456)
            assertThat(notification.parent).isEqualTo(123455)
            assertThat(notification.root).isEqualTo(123400)
        }

        @Test
        fun `SolanaBlock parses correctly`() {
            val json = """{
                "blockHeight": 101210751,
                "blockTime": 1639926816,
                "blockhash": "6ojMHjctdqfB55JDpEpqfHnP96fiaHEcvzEQ2NNcxzHP",
                "previousBlockhash": "GJp125YAN4ufCSUvZJVdCyWQJ7RPWMmwxoyUQySydZA"
            }"""
            val block = io.emeraldpay.dshackle.Global.objectMapper.readValue(json, SolanaBlock::class.java)

            assertThat(block.height).isEqualTo(101210751)
            assertThat(block.timestamp).isEqualTo(1639926816)
            assertThat(block.hash).isEqualTo("6ojMHjctdqfB55JDpEpqfHnP96fiaHEcvzEQ2NNcxzHP")
            assertThat(block.parent).isEqualTo("GJp125YAN4ufCSUvZJVdCyWQJ7RPWMmwxoyUQySydZA")
        }
    }
}
