package io.emeraldpay.dshackle.upstream.ethereum

import io.emeraldpay.dshackle.reader.ChainReader
import io.emeraldpay.dshackle.upstream.ChainCallError
import io.emeraldpay.dshackle.upstream.ChainRequest
import io.emeraldpay.dshackle.upstream.ChainResponse
import io.emeraldpay.dshackle.upstream.rpcclient.ListParams
import org.junit.jupiter.api.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.time.Duration

class PendingTransactionValidatorTest {

    @Test
    fun `noop validator always emits true`() {
        val validator = NoopPendingTransactionValidator()

        StepVerifier.create(validator.pendingTxExists())
            .expectNext(true)
            .expectComplete()
            .verify(Duration.ofSeconds(3))
    }

    @Test
    fun `emits true when pending plus queued exceeds limit`() {
        val reader = mockReader(
            response = txpoolContent(pendingAddresses = 6, queuedAddresses = 0),
        )
        val validator = PendingTransactionValidatorImpl(
            upstreamId = "test-upstream",
            directReader = reader,
            interval = Duration.ofSeconds(30),
            txLimit = 5,
        )

        StepVerifier.withVirtualTime { validator.pendingTxExists() }
            .expectSubscription()
            .expectNoEvent(Duration.ofSeconds(15))
            .expectNext(true)
            .thenCancel()
            .verify(Duration.ofSeconds(3))
    }

    @Test
    fun `emits true when pending plus queued is at limit`() {
        val reader = mockReader(
            response = txpoolContent(pendingAddresses = 3, queuedAddresses = 2),
        )
        val validator = PendingTransactionValidatorImpl(
            upstreamId = "test-upstream",
            directReader = reader,
            interval = Duration.ofSeconds(30),
            txLimit = 5,
        )

        StepVerifier.withVirtualTime { validator.pendingTxExists() }
            .expectSubscription()
            .expectNoEvent(Duration.ofSeconds(15))
            .expectNext(true)
            .thenCancel()
            .verify(Duration.ofSeconds(3))
    }

    @Test
    fun `counts both pending and queued buckets`() {
        // 3 pending + 4 queued = 7 > limit of 5
        val reader = mockReader(
            response = txpoolContent(pendingAddresses = 3, queuedAddresses = 4),
        )
        val validator = PendingTransactionValidatorImpl(
            upstreamId = "test-upstream",
            directReader = reader,
            interval = Duration.ofSeconds(30),
            txLimit = 5,
        )

        StepVerifier.withVirtualTime { validator.pendingTxExists() }
            .expectSubscription()
            .expectNoEvent(Duration.ofSeconds(15))
            .expectNext(true)
            .thenCancel()
            .verify(Duration.ofSeconds(3))
    }

    @Test
    fun `handles missing pending field`() {
        val reader = mockReader(response = """{"queued": {"0xaa": {}, "0xbb": {}}}""")
        val validator = PendingTransactionValidatorImpl(
            upstreamId = "test-upstream",
            directReader = reader,
            interval = Duration.ofSeconds(30),
            txLimit = 5,
        )

        // Only 2 queued (no pending node) -> below limit -> false
        StepVerifier.withVirtualTime { validator.pendingTxExists() }
            .expectSubscription()
            .expectNoEvent(Duration.ofSeconds(15))
            .expectNext(false)
            .thenCancel()
            .verify(Duration.ofSeconds(3))
    }

    @Test
    fun `handles missing queued field`() {
        val reader = mockReader(response = """{"pending": {"0xaa": {}, "0xbb": {}, "0xcc": {}}}""")
        val validator = PendingTransactionValidatorImpl(
            upstreamId = "test-upstream",
            directReader = reader,
            interval = Duration.ofSeconds(30),
            txLimit = 2,
        )

        // 3 pending (no queued node) > limit 2 -> true
        StepVerifier.withVirtualTime { validator.pendingTxExists() }
            .expectSubscription()
            .expectNoEvent(Duration.ofSeconds(15))
            .expectNext(true)
            .thenCancel()
            .verify(Duration.ofSeconds(3))
    }

    @Test
    fun `emits false on rpc error`() {
        val reader = mock<ChainReader> {
            on { read(ChainRequest("txpool_content", ListParams())) } doReturn
                Mono.just(ChainResponse(null, ChainCallError(-32000, "method not supported")))
        }
        val validator = PendingTransactionValidatorImpl(
            upstreamId = "test-upstream",
            directReader = reader,
            interval = Duration.ofSeconds(30),
            txLimit = 5,
        )

        StepVerifier.withVirtualTime { validator.pendingTxExists() }
            .expectSubscription()
            .expectNoEvent(Duration.ofSeconds(15))
            .expectNext(false)
            .thenCancel()
            .verify(Duration.ofSeconds(3))
    }

    @Test
    fun `continues polling after an error response`() {
        val errorResponse = Mono.just(ChainResponse(null, ChainCallError(-32000, "method not supported")))
        val okResponse = Mono.just(
            ChainResponse(
                txpoolContent(pendingAddresses = 10, queuedAddresses = 0).toByteArray(),
                null,
            ),
        )
        val reader = mock<ChainReader> {
            on { read(ChainRequest("txpool_content", ListParams())) } doReturn errorResponse doReturn okResponse
        }
        val validator = PendingTransactionValidatorImpl(
            upstreamId = "test-upstream",
            directReader = reader,
            interval = Duration.ofSeconds(30),
            txLimit = 5,
        )

        StepVerifier.withVirtualTime { validator.pendingTxExists() }
            .expectSubscription()
            .expectNoEvent(Duration.ofSeconds(15))
            .expectNext(false) // first poll: error -> false
            .expectNoEvent(Duration.ofSeconds(30))
            .expectNext(true) // second poll: success
            .thenCancel()
            .verify(Duration.ofSeconds(3))
    }

    @Test
    fun `polls at configured interval`() {
        val reader = mockReader(
            response = txpoolContent(pendingAddresses = 10, queuedAddresses = 0),
        )
        val validator = PendingTransactionValidatorImpl(
            upstreamId = "test-upstream",
            directReader = reader,
            interval = Duration.ofSeconds(30),
            txLimit = 5,
        )

        StepVerifier.withVirtualTime { validator.pendingTxExists() }
            .expectSubscription()
            .expectNoEvent(Duration.ofSeconds(15))
            .expectNext(true)
            .expectNoEvent(Duration.ofSeconds(30))
            .expectNext(true)
            .expectNoEvent(Duration.ofSeconds(30))
            .expectNext(true)
            .thenCancel()
            .verify(Duration.ofSeconds(3))
    }

    private fun mockReader(response: String): ChainReader =
        mock<ChainReader> {
            on { read(ChainRequest("txpool_content", ListParams())) } doReturn
                Mono.just(ChainResponse(response.toByteArray(), null))
        }

    private fun txpoolContent(pendingAddresses: Int, queuedAddresses: Int): String {
        val pending = (0 until pendingAddresses).joinToString(",") { """"0xp$it": {}""" }
        val queued = (0 until queuedAddresses).joinToString(",") { """"0xq$it": {}""" }
        return """{"pending": {$pending}, "queued": {$queued}}"""
    }
}
