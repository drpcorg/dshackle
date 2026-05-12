package io.emeraldpay.dshackle.upstream.generic

import io.emeraldpay.dshackle.Chain
import io.emeraldpay.dshackle.upstream.ChainRequest
import io.emeraldpay.dshackle.upstream.ethereum.WsSubscriptions
import io.emeraldpay.dshackle.upstream.rpcclient.ListParams
import org.junit.jupiter.api.Test
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import reactor.util.function.Tuples
import java.time.Duration
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

class GenericSubscriptionConnectTest {

    @Test
    fun `test request param is flat list`() {
        val param: List<Any> = listOf("all")
        val topic = "topic"
        val response = "hello".toByteArray()
        val ws = mock<WsSubscriptions> {
            on { subscribe(ChainRequest(topic, ListParams(param))) } doReturn
                WsSubscriptions.SubscribeData(Mono.just(Tuples.of("", Flux.just(response))), "", AtomicReference(""))
        }

        val genericSubscriptionConnect = GenericSubscriptionConnect(Chain.ETHEREUM__MAINNET, ws, topic, param, "")

        StepVerifier.create(genericSubscriptionConnect.createConnection())
            .expectNext(response)
            .expectComplete()
            .verify(Duration.ofSeconds(1))

        verify(ws).subscribe(ChainRequest(topic, ListParams(param)))
    }

    @Test
    fun `emits TimeoutException when upstream goes silent past idle timeout`() {
        // Simulates the Solana-like failure: WS subscription is established, but upstream stops
        // delivering events without closing the connection. The Flux must surface a TimeoutException
        // so the surrounding DurableFlux re-subscribes.
        val param: List<Any> = listOf("all")
        val topic = "slotSubscribe"
        val ws = mock<WsSubscriptions> {
            on { subscribe(ChainRequest(topic, ListParams(param))) } doReturn
                WsSubscriptions.SubscribeData(
                    Mono.just(Tuples.of("sub-id-1", Flux.never<ByteArray>())),
                    "conn-1",
                    AtomicReference("sub-id-1"),
                )
        }

        val genericSubscriptionConnect = GenericSubscriptionConnect(Chain.SOLANA__MAINNET, ws, topic, param, "")

        StepVerifier.withVirtualTime { genericSubscriptionConnect.createConnection() }
            .expectSubscription()
            .expectNoEvent(Duration.ofSeconds(84))
            .thenAwait(Duration.ofSeconds(2))
            .expectError(TimeoutException::class.java)
            .verify(Duration.ofSeconds(5))
    }

    @Test
    fun `idle timeout triggers resubscribe via DurableFlux retry`() {
        // End-to-end on the GenericSubscriptionConnect: when the first subscription stalls,
        // DurableFlux must call createConnection() again, which issues a fresh `subscribe` RPC.
        val param: List<Any> = listOf("all")
        val topic = "slotSubscribe"

        @Suppress("UNCHECKED_CAST")
        val secondAttempt = WsSubscriptions.SubscribeData(
            Mono.just(Tuples.of("sub-id-2", Flux.just("recovered".toByteArray()))),
            "conn-1",
            AtomicReference("sub-id-2"),
        )
        @Suppress("UNCHECKED_CAST")
        val firstAttempt = WsSubscriptions.SubscribeData(
            Mono.just(Tuples.of("sub-id-1", Flux.never<ByteArray>())),
            "conn-1",
            AtomicReference("sub-id-1"),
        )

        val ws = mock<WsSubscriptions> {
            on { subscribe(ChainRequest(topic, ListParams(param))) }
                .doReturn(firstAttempt, secondAttempt)
            on { unsubscribe(any()) } doReturn Mono.empty()
        }

        val genericSubscriptionConnect = GenericSubscriptionConnect(Chain.SOLANA__MAINNET, ws, topic, param, "slotUnsubscribe")

        // GenericPersistentConnect wraps createConnection() with DurableFlux + SharedFluxHolder.
        // The first subscription stalls (Flux.never) → timeout (85s) → TimeoutException →
        // DurableFlux schedules retry → second subscription delivers "recovered".
        StepVerifier.withVirtualTime { genericSubscriptionConnect.connect(io.emeraldpay.dshackle.upstream.Selector.empty) }
            .expectSubscription()
            .thenAwait(Duration.ofSeconds(90))
            .expectNextMatches { it is ByteArray && String(it) == "recovered" }
            .thenCancel()
            .verify(Duration.ofSeconds(5))

        // Verifies we re-subscribed (= sent slotSubscribe again on the same WS).
        verify(ws, times(2)).subscribe(ChainRequest(topic, ListParams(param)))
    }
}
