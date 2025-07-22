package io.emeraldpay.dshackle.upstream.generic

import io.emeraldpay.dshackle.upstream.ChainResponse
import io.emeraldpay.dshackle.upstream.ethereum.WsSubscriptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.concurrent.atomic.AtomicReference

class GenericIngressSubscriptionTest {

    @Test
    fun `should create subscription holder for new topic`() {
        // Given
        val mockWsSubscriptions = mock<WsSubscriptions>()
        val subscription = GenericIngressSubscription(mockWsSubscriptions, listOf("blockSubscribe"))

        // When
        val stats = subscription.getSubscriptionStats()

        // Then
        assertEquals(0, stats["totalSubscriptions"])
        assertTrue((stats["subscriptions"] as List<*>).isEmpty())
    }

    @Test
    fun `should track multiple clients for same subscription`() {
        // Given
        val mockWsSubscriptions = mock<WsSubscriptions> {
            on { subscribe(any()) } doReturn WsSubscriptions.SubscribeData(
                Flux.empty(),
                "conn-1",
                AtomicReference("sub-1"),
            )
        }
        val subscription = GenericIngressSubscription(mockWsSubscriptions, listOf("blockSubscribe"))

        // When
        val client1 = subscription.getForClient<Any>("blockSubscribe", listOf("all"), "client-1")
        val client2 = subscription.getForClient<Any>("blockSubscribe", listOf("all"), "client-2")

        // Then
        val stats = subscription.getSubscriptionStats()
        assertEquals(1, stats["totalSubscriptions"])

        val subscriptions = stats["subscriptions"] as List<Map<String, Any>>
        assertEquals(1, subscriptions.size)
        assertEquals("blockSubscribe", subscriptions[0]["topic"])
        assertEquals(2, subscriptions[0]["clientCount"])

        val clients = subscriptions[0]["clients"] as Set<String>
        assertTrue(clients.contains("client-1"))
        assertTrue(clients.contains("client-2"))
    }

    @Test
    fun `should cleanup subscription when last client disconnects`() {
        // Given
        val mockWsSubscriptions = mock<WsSubscriptions> {
            on { subscribe(any()) } doReturn WsSubscriptions.SubscribeData(
                Flux.empty(),
                "conn-1",
                AtomicReference("sub-1"),
            )
            on { unsubscribe(any()) } doReturn Mono.just(ChainResponse.ok("true"))
        }
        val subscription = GenericIngressSubscription(mockWsSubscriptions, listOf("blockSubscribe"))

        // When
        subscription.getForClient<Any>("blockSubscribe", listOf("all"), "client-1")
        subscription.releaseSubscription("blockSubscribe", listOf("all"), "client-1")

        // Then
        val stats = subscription.getSubscriptionStats()
        assertEquals(0, stats["totalSubscriptions"])
    }

    @Test
    fun `should keep subscription when some clients remain`() {
        // Given
        val mockWsSubscriptions = mock<WsSubscriptions> {
            on { subscribe(any()) } doReturn WsSubscriptions.SubscribeData(
                Flux.empty(),
                "conn-1",
                AtomicReference("sub-1"),
            )
        }
        val subscription = GenericIngressSubscription(mockWsSubscriptions, listOf("blockSubscribe"))

        // When
        subscription.getForClient<Any>("blockSubscribe", listOf("all"), "client-1")
        subscription.getForClient<Any>("blockSubscribe", listOf("all"), "client-2")
        subscription.releaseSubscription("blockSubscribe", listOf("all"), "client-1")

        // Then
        val stats = subscription.getSubscriptionStats()
        assertEquals(1, stats["totalSubscriptions"])

        val subscriptions = stats["subscriptions"] as List<Map<String, Any>>
        assertEquals(1, subscriptions[0]["clientCount"])

        val clients = subscriptions[0]["clients"] as Set<String>
        assertFalse(clients.contains("client-1"))
        assertTrue(clients.contains("client-2"))
    }

    @Test
    fun `should handle different subscription topics separately`() {
        // Given
        val mockWsSubscriptions = mock<WsSubscriptions> {
            on { subscribe(any()) } doReturn WsSubscriptions.SubscribeData(
                Flux.empty(),
                "conn-1",
                AtomicReference("sub-1"),
            )
        }
        val subscription = GenericIngressSubscription(
            mockWsSubscriptions,
            listOf("blockSubscribe", "accountSubscribe"),
        )

        // When
        subscription.getForClient<Any>("blockSubscribe", listOf("all"), "client-1")
        subscription.getForClient<Any>("accountSubscribe", listOf("account1"), "client-2")

        // Then
        val stats = subscription.getSubscriptionStats()
        assertEquals(2, stats["totalSubscriptions"])

        val subscriptions = stats["subscriptions"] as List<Map<String, Any>>
        assertEquals(2, subscriptions.size)

        val topics = subscriptions.map { it["topic"] }.toSet()
        assertTrue(topics.contains("blockSubscribe"))
        assertTrue(topics.contains("accountSubscribe"))
    }

    @Test
    fun `should generate correct unsubscribe method for Solana topics`() {
        // Given
        val mockWsSubscriptions = mock<WsSubscriptions> {
            on { unsubscribe(any()) } doReturn Mono.just(ChainResponse.ok("true"))
        }
        val subscription = GenericIngressSubscription(mockWsSubscriptions, listOf("blockSubscribe"))

        // When
        subscription.cleanupSubscription("blockSubscribe", listOf("all"), "sub-123")

        // Then
        verify(mockWsSubscriptions).unsubscribe(
            argThat { request ->
                request.method == "blockUnsubscribe" &&
                    request.params.toString().contains("sub-123")
            },
        )
    }

    @Test
    fun `should force cleanup subscription`() {
        // Given
        val mockWsSubscriptions = mock<WsSubscriptions> {
            on { subscribe(any()) } doReturn WsSubscriptions.SubscribeData(
                Flux.empty(),
                "conn-1",
                AtomicReference("sub-1"),
            )
        }
        val subscription = GenericIngressSubscription(mockWsSubscriptions, listOf("blockSubscribe"))

        // When
        subscription.getForClient<Any>("blockSubscribe", listOf("all"), "client-1")
        subscription.forceCleanupSubscription("blockSubscribe", listOf("all"))

        // Then
        val stats = subscription.getSubscriptionStats()
        assertEquals(0, stats["totalSubscriptions"])
    }
}
