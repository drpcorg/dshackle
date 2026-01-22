package io.emeraldpay.dshackle.upstream.lowerbound

import io.emeraldpay.dshackle.Chain
import org.junit.jupiter.api.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import reactor.core.publisher.Flux
import reactor.test.StepVerifier
import java.time.Duration

class LowerBoundDetectorTest {

    @Test
    fun `receive only manual bounds`() {
        val detector = TestLowerBoundDetector()
        val manualBoundService = mock<ManualLowerBoundService> {
            on { manualBoundTypes() } doReturn setOf(LowerBoundType.STATE, LowerBoundType.BLOCK)
            on { hasManualBound(LowerBoundType.STATE) } doReturn true
            on { hasManualBound(LowerBoundType.BLOCK) } doReturn true
            on { manualLowerBound(LowerBoundType.STATE) } doReturn LowerBoundData(50L, 1000, LowerBoundType.STATE)
            on { manualLowerBound(LowerBoundType.BLOCK) } doReturn LowerBoundData(80L, 1000, LowerBoundType.BLOCK)
        }

        StepVerifier.withVirtualTime { detector.detectLowerBound(manualBoundService) }
            .expectSubscription()
            .expectNoEvent(Duration.ofSeconds(15))
            .expectNextSequence(
                setOf(
                    LowerBoundData(50L, 1000, LowerBoundType.STATE),
                    LowerBoundData(80L, 1000, LowerBoundType.BLOCK),
                )
            )
            .thenCancel()
            .verify(Duration.ofSeconds(3))
    }

    @Test
    fun `receive one manual and one calculated bounds`() {
        val detector = TestLowerBoundDetector()
        val manualBoundService = mock<ManualLowerBoundService> {
            on { manualBoundTypes() } doReturn setOf(LowerBoundType.STATE)
            on { hasManualBound(LowerBoundType.STATE) } doReturn true
            on { hasManualBound(LowerBoundType.BLOCK) } doReturn false
            on { manualLowerBound(LowerBoundType.STATE) } doReturn LowerBoundData(50L, 1000, LowerBoundType.STATE)
            on { manualLowerBound(LowerBoundType.BLOCK) } doReturn null
        }

        StepVerifier.withVirtualTime { detector.detectLowerBound(manualBoundService) }
            .expectSubscription()
            .expectNoEvent(Duration.ofSeconds(15))
            .expectNextSequence(
                setOf(
                    LowerBoundData(5000L, 1000, LowerBoundType.BLOCK),
                    LowerBoundData(50L, 1000, LowerBoundType.STATE),
                )
            )
            .thenCancel()
            .verify(Duration.ofSeconds(3))
    }

    @Test
    fun `receive only calculated bounds`() {
        val detector = TestLowerBoundDetector()
        val manualBoundService = BaseManualLowerBoundService(mock(), emptyMap())

        StepVerifier.withVirtualTime { detector.detectLowerBound(manualBoundService) }
            .expectSubscription()
            .expectNoEvent(Duration.ofSeconds(15))
            .expectNextSequence(
                setOf(
                    LowerBoundData(1000L, 1000, LowerBoundType.STATE),
                    LowerBoundData(5000L, 1000, LowerBoundType.BLOCK),
                )
            )
            .thenCancel()
            .verify(Duration.ofSeconds(3))
    }
}

private class TestLowerBoundDetector : LowerBoundDetector(Chain.CORE__MAINNET) {
    override fun period(): Long {
        return 1
    }

    override fun internalDetectLowerBound(): Flux<LowerBoundData> {
        return Flux.just(
            LowerBoundData(1000L, 1000, LowerBoundType.STATE),
            LowerBoundData(5000L, 1000, LowerBoundType.BLOCK),
        )
    }

    override fun types(): Set<LowerBoundType> {
        return setOf(LowerBoundType.STATE, LowerBoundType.BLOCK)
    }
}