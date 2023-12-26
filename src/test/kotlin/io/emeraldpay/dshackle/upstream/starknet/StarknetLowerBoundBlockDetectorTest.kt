package io.emeraldpay.dshackle.upstream.starknet

import io.emeraldpay.dshackle.upstream.LowerBoundBlockDetector
import org.junit.jupiter.api.Test
import reactor.test.StepVerifier
import java.time.Duration

class StarknetLowerBoundBlockDetectorTest {

    @Test
    fun `starknet lower block is 1`() {
        val detector = StarknetLowerBoundBlockDetector()

        StepVerifier.withVirtualTime { detector.lowerBlock() }
            .expectSubscription()
            .expectNoEvent(Duration.ofSeconds(15))
            .expectNext(LowerBoundBlockDetector.LowerBlockData(1))
            .thenCancel()
            .verify(Duration.ofSeconds(3))
    }
}
