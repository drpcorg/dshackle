package io.emeraldpay.dshackle.upstream.generic

import io.emeraldpay.dshackle.Chain
import io.emeraldpay.dshackle.upstream.ChainRequest
import io.emeraldpay.dshackle.upstream.ethereum.WsSubscriptions
import io.emeraldpay.dshackle.upstream.rpcclient.ListParams
import org.junit.jupiter.api.Test
import org.mockito.Mockito.verify
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import reactor.util.function.Tuples
import java.time.Duration
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
}
