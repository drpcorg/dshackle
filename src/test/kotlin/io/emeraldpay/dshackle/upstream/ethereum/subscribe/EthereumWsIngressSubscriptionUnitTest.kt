package io.emeraldpay.dshackle.upstream.ethereum.subscribe

import io.emeraldpay.dshackle.upstream.ChainResponse
import io.emeraldpay.dshackle.upstream.ethereum.EthereumEgressSubscription
import io.emeraldpay.dshackle.upstream.ethereum.WsSubscriptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.concurrent.atomic.AtomicReference

class EthereumWsIngressSubscriptionUnitTest {

    @Test
    fun `should support all ethereum subscription topics`() {
        // Given
        val mockWsSubscriptions = mock<WsSubscriptions>()
        val subscription = EthereumWsIngressSubscription(mockWsSubscriptions)

        // When
        val availableTopics = subscription.getAvailableTopics()

        // Then
        assertTrue(availableTopics.contains(EthereumEgressSubscription.METHOD_PENDING_TXES))
        assertTrue(availableTopics.contains(EthereumEgressSubscription.METHOD_NEW_HEADS))
        assertTrue(availableTopics.contains(EthereumEgressSubscription.METHOD_LOGS))
        assertEquals(3, availableTopics.size)
    }

    @Test
    fun `should return null for unsupported topics`() {
        // Given
        val mockWsSubscriptions = mock<WsSubscriptions>()
        val subscription = EthereumWsIngressSubscription(mockWsSubscriptions)

        // When
        val connection = subscription.get<Any>("unsupportedTopic", null)

        // Then
        assertNull(connection)
    }

    @Test
    fun `should track multiple clients for same ethereum subscription`() {
        // Given
        val mockWsSubscriptions = mock<WsSubscriptions>()
        val subscription = EthereumWsIngressSubscription(mockWsSubscriptions)

        // When
        val client1 = subscription.getForClient<Any>(
            EthereumEgressSubscription.METHOD_NEW_HEADS,
            null,
            "eth-client-1",
        )
        val client2 = subscription.getForClient<Any>(
            EthereumEgressSubscription.METHOD_NEW_HEADS,
            null,
            "eth-client-2",
        )

        // Then
        assertNotNull(client1)
        assertNotNull(client2)

        val stats = subscription.getSubscriptionStats()
        assertEquals(1, stats["totalSubscriptions"])

        @Suppress("UNCHECKED_CAST")
        val subscriptions = stats["subscriptions"] as List<Map<String, Any>>
        assertEquals(1, subscriptions.size)
        assertEquals(EthereumEgressSubscription.METHOD_NEW_HEADS, subscriptions[0]["topic"])
        assertEquals(2, subscriptions[0]["clientCount"])

        @Suppress("UNCHECKED_CAST")
        val clients = subscriptions[0]["clients"] as Set<String>
        assertTrue(clients.contains("eth-client-1"))
        assertTrue(clients.contains("eth-client-2"))
    }

    @Test
    fun `should cleanup ethereum subscription when last client disconnects`() {
        // Given
        val mockWsSubscriptions = mock<WsSubscriptions>()
        val subscription = EthereumWsIngressSubscription(mockWsSubscriptions)

        // When
        subscription.getForClient<Any>(
            EthereumEgressSubscription.METHOD_NEW_HEADS,
            null,
            "eth-client-1",
        )
        subscription.releaseSubscription(
            EthereumEgressSubscription.METHOD_NEW_HEADS,
            null,
            "eth-client-1",
        )

        // Then
        val stats = subscription.getSubscriptionStats()
        assertEquals(0, stats["totalSubscriptions"])
    }

    @Test
    fun `should keep ethereum subscription when some clients remain`() {
        // Given
        val mockWsSubscriptions = mock<WsSubscriptions>()
        val subscription = EthereumWsIngressSubscription(mockWsSubscriptions)

        // When
        subscription.getForClient<Any>(
            EthereumEgressSubscription.METHOD_NEW_HEADS,
            null,
            "eth-client-1",
        )
        subscription.getForClient<Any>(
            EthereumEgressSubscription.METHOD_NEW_HEADS,
            null,
            "eth-client-2",
        )
        subscription.releaseSubscription(
            EthereumEgressSubscription.METHOD_NEW_HEADS,
            null,
            "eth-client-1",
        )

        // Then
        val stats = subscription.getSubscriptionStats()
        assertEquals(1, stats["totalSubscriptions"])

        @Suppress("UNCHECKED_CAST")
        val subscriptions = stats["subscriptions"] as List<Map<String, Any>>
        assertEquals(1, subscriptions[0]["clientCount"])

        @Suppress("UNCHECKED_CAST")
        val clients = subscriptions[0]["clients"] as Set<String>
        assertFalse(clients.contains("eth-client-1"))
        assertTrue(clients.contains("eth-client-2"))
    }

    @Test
    fun `should handle different ethereum subscription topics separately`() {
        // Given
        val mockWsSubscriptions = mock<WsSubscriptions>()
        val subscription = EthereumWsIngressSubscription(mockWsSubscriptions)

        // When
        subscription.getForClient<Any>(
            EthereumEgressSubscription.METHOD_NEW_HEADS,
            null,
            "eth-client-1",
        )
        subscription.getForClient<Any>(
            EthereumEgressSubscription.METHOD_PENDING_TXES,
            null,
            "eth-client-2",
        )
        val logsParams = mapOf("address" to "0x123")
        subscription.getForClient<Any>(
            EthereumEgressSubscription.METHOD_LOGS,
            logsParams,
            "eth-client-3",
        )

        // Then
        val stats = subscription.getSubscriptionStats()
        assertEquals(3, stats["totalSubscriptions"])

        @Suppress("UNCHECKED_CAST")
        val subscriptions = stats["subscriptions"] as List<Map<String, Any>>
        assertEquals(3, subscriptions.size)

        val topics = subscriptions.map { it["topic"] }.toSet()
        assertTrue(topics.contains(EthereumEgressSubscription.METHOD_NEW_HEADS))
        assertTrue(topics.contains(EthereumEgressSubscription.METHOD_PENDING_TXES))
        assertTrue(topics.contains(EthereumEgressSubscription.METHOD_LOGS))
    }

    @Test
    fun `should send eth_unsubscribe on cleanup`() {
        // Given
        val mockWsSubscriptions = mock<WsSubscriptions> {
            on { unsubscribe(any()) } doReturn Mono.just(ChainResponse.ok("true"))
        }
        val subscription = EthereumWsIngressSubscription(mockWsSubscriptions)

        // When
        subscription.cleanupSubscription(
            EthereumEgressSubscription.METHOD_NEW_HEADS,
            null,
            "eth-sub-123",
        )

        // Then
        verify(mockWsSubscriptions).unsubscribe(
            argThat { request ->
                request.method == "eth_unsubscribe" &&
                    request.params.toString().contains("eth-sub-123")
            },
        )
    }

    @Test
    fun `should not cleanup when subscription ID is null or empty`() {
        // Given
        val mockWsSubscriptions = mock<WsSubscriptions>()
        val subscription = EthereumWsIngressSubscription(mockWsSubscriptions)

        // When
        subscription.cleanupSubscription(
            EthereumEgressSubscription.METHOD_NEW_HEADS,
            null,
            null,
        )
        subscription.cleanupSubscription(
            EthereumEgressSubscription.METHOD_NEW_HEADS,
            null,
            "",
        )

        // Then
        verify(mockWsSubscriptions, times(0)).unsubscribe(any())
    }

    @Test
    fun `should generate unique ethereum client IDs`() {
        // Given
        val mockWsSubscriptions = mock<WsSubscriptions>()
        val subscription = EthereumWsIngressSubscription(mockWsSubscriptions)

        // When
        val connection1 = subscription.get<Any>(EthereumEgressSubscription.METHOD_NEW_HEADS, null)
        val connection2 = subscription.get<Any>(EthereumEgressSubscription.METHOD_NEW_HEADS, null)

        // Then
        val stats = subscription.getSubscriptionStats()
        assertEquals(1, stats["totalSubscriptions"]) // Same subscription

        @Suppress("UNCHECKED_CAST")
        val subscriptions = stats["subscriptions"] as List<Map<String, Any>>
        assertEquals(2, subscriptions[0]["clientCount"]) // But 2 different clients

        @Suppress("UNCHECKED_CAST")
        val clients = subscriptions[0]["clients"] as Set<String>
        assertEquals(2, clients.size)
        assertTrue(clients.all { it.startsWith("eth-client-") })
    }
}