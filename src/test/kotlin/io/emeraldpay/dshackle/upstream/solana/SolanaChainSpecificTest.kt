package io.emeraldpay.dshackle.upstream.solana

import io.emeraldpay.dshackle.Global
import io.emeraldpay.dshackle.data.BlockId
import io.emeraldpay.dshackle.reader.ChainReader
import io.emeraldpay.dshackle.upstream.ChainRequest
import io.emeraldpay.dshackle.upstream.ChainResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import reactor.core.publisher.Mono
import java.nio.ByteBuffer
import java.time.Instant
import java.time.temporal.ChronoUnit

class SolanaChainSpecificTest {

    @BeforeEach
    fun setup() {
        // Clear cache before each test
        SolanaChainSpecific.clearCache()
    }

    @Test
    fun `parseBlock from slotSubscribe notification`() {
        val reader = mock<ChainReader> {
            on { read(any<ChainRequest>()) }.thenReturn(
                Mono.just(ChainResponse("101210751".toByteArray(), null)),
            )
        }

        val beforeCall = Instant.now()
        val json = """{"slot": 112301554, "parent": 112301553, "root": 112301500}"""
        val result = SolanaChainSpecific.getFromHeader(json.toByteArray(), "upstream-1", reader).block()!!
        val afterCall = Instant.now()

        assertThat(result.slot).isEqualTo(112301554)
        assertThat(result.height).isEqualTo(101210751)
        assertThat(result.upstreamId).isEqualTo("upstream-1")

        // Synthetic hash based on slot
        val expectedHash = BlockId.from(ByteBuffer.allocate(32).putLong(112301554).array())
        assertThat(result.hash).isEqualTo(expectedHash)

        // Synthetic parent hash based on parent slot
        val expectedParentHash = BlockId.from(ByteBuffer.allocate(32).putLong(112301553).array())
        assertThat(result.parentHash).isEqualTo(expectedParentHash)

        // Timestamp is synthetic (Instant.now() at call time)
        assertThat(result.timestamp).isBetween(beforeCall, afterCall.plus(1, ChronoUnit.SECONDS))
    }

    @Test
    fun `listenNewHeadsRequest returns slotSubscribe`() {
        val request = SolanaChainSpecific.listenNewHeadsRequest()

        assertThat(request.method).isEqualTo("slotSubscribe")
    }

    @Test
    fun `unsubscribeNewHeadsRequest returns slotUnsubscribe`() {
        val request = SolanaChainSpecific.unsubscribeNewHeadsRequest("sub-123")

        assertThat(request.method).isEqualTo("slotUnsubscribe")
    }

    @Test
    fun `throttle HTTP calls every 5 slots`() {
        val reader = mock<ChainReader> {
            on { read(any<ChainRequest>()) }.thenReturn(
                Mono.just(ChainResponse("100000000".toByteArray(), null)),
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

        // Only 1 HTTP call so far
        verify(reader, times(1)).read(any<ChainRequest>())

        // Slot 105 - should trigger new HTTP call (interval reached)
        val slot105 = """{"slot": 105, "parent": 104, "root": 50}"""
        SolanaChainSpecific.getFromHeader(slot105.toByteArray(), "upstream-1", reader).block()

        // Now 2 HTTP calls
        verify(reader, times(2)).read(any<ChainRequest>())
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

    @Test
    fun `SolanaSlotNotification parses correctly`() {
        val json = """{"slot": 123456, "parent": 123455, "root": 123400}"""
        val notification = Global.objectMapper.readValue(json, SolanaSlotNotification::class.java)

        assertThat(notification.slot).isEqualTo(123456)
        assertThat(notification.parent).isEqualTo(123455)
        assertThat(notification.root).isEqualTo(123400)
    }

    @Test
    fun `uses slot as height when cache is empty and no HTTP call`() {
        val reader = mock<ChainReader> {
            on { read(any<ChainRequest>()) }.thenReturn(Mono.error(RuntimeException("Network error")))
        }

        // First slot with HTTP error - should fallback to slot as height
        val json = """{"slot": 112301554, "parent": 112301553, "root": 112301500}"""
        val result = SolanaChainSpecific.getFromHeader(json.toByteArray(), "upstream-1", reader).block()!!

        assertThat(result.slot).isEqualTo(112301554)
        // When cache empty and HTTP fails, uses slot as height
        assertThat(result.height).isEqualTo(112301554)
    }

    @Test
    fun `uses cached height between throttle intervals`() {
        val reader = mock<ChainReader> {
            on { read(any<ChainRequest>()) }.thenReturn(
                Mono.just(ChainResponse("100000000".toByteArray(), null)),
            )
        }

        // First call sets cache
        val slot1 = """{"slot": 100, "parent": 99, "root": 50}"""
        val result1 = SolanaChainSpecific.getFromHeader(slot1.toByteArray(), "upstream-1", reader).block()!!
        assertThat(result1.height).isEqualTo(100000000)

        // Second call uses cached height
        val slot2 = """{"slot": 101, "parent": 100, "root": 50}"""
        val result2 = SolanaChainSpecific.getFromHeader(slot2.toByteArray(), "upstream-1", reader).block()!!

        assertThat(result2.slot).isEqualTo(101)
        assertThat(result2.height).isEqualTo(100000000) // cached height
    }
}
