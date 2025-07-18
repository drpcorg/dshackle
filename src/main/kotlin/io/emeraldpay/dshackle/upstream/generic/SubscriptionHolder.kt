package io.emeraldpay.dshackle.upstream.generic

import io.emeraldpay.dshackle.upstream.SubscriptionConnect
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Holds a subscription connection with client tracking for proper cleanup
 */
class SubscriptionHolder<T>(
    val connection: SubscriptionConnect<T>,
    val topic: String,
    val params: Any?,
) {
    companion object {
        private val log = LoggerFactory.getLogger(SubscriptionHolder::class.java)
    }

    private val clients = ConcurrentHashMap<String, SubscriptionClient>()

    /**
     * Add a client to this subscription
     * @param client the client to add
     * @return the subscription connection wrapped with client tracking
     */
    fun addClient(client: SubscriptionClient): SubscriptionConnect<T> {
        clients[client.clientId] = client
        log.info("[AUTO-UNSUBSCRIBE] Added client {} to subscription {}:{}, total clients: {}", client.clientId, topic, params, clients.size)
        return ClientAwareSubscriptionConnect(connection, client)
    }

    /**
     * Remove a client from this subscription
     * @param clientId the client ID to remove
     * @return true if this was the last client and cleanup should occur
     */
    fun removeClient(clientId: String): Boolean {
        val removed = clients.remove(clientId)
        if (removed != null) {
            log.info("[AUTO-UNSUBSCRIBE] Removed client {} from subscription {}:{}, remaining clients: {}", clientId, topic, params, clients.size)
        } else {
            log.warn("[AUTO-UNSUBSCRIBE] Attempted to remove non-existent client {} from subscription {}:{}", clientId, topic, params)
        }

        return clients.isEmpty()
    }

    /**
     * Get current client count (for monitoring/debugging)
     */
    fun getClientCount(): Int = clients.size

    /**
     * Get all client IDs (for monitoring/debugging)
     */
    fun getClientIds(): Set<String> = clients.keys.toSet()

    /**
     * Check if a specific client is subscribed
     */
    fun hasClient(clientId: String): Boolean = clients.containsKey(clientId)
}
