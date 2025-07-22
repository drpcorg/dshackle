/**
 * Copyright (c) 2022 EmeraldPay, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.emeraldpay.dshackle.upstream.ethereum.subscribe

import io.emeraldpay.dshackle.upstream.ChainRequest
import io.emeraldpay.dshackle.upstream.IngressSubscription
import io.emeraldpay.dshackle.upstream.SubscriptionConnect
import io.emeraldpay.dshackle.upstream.ethereum.EthereumEgressSubscription
import io.emeraldpay.dshackle.upstream.ethereum.EthereumIngressSubscription
import io.emeraldpay.dshackle.upstream.ethereum.WsSubscriptions
import io.emeraldpay.dshackle.upstream.generic.DefaultSubscriptionClient
import io.emeraldpay.dshackle.upstream.generic.SubscriptionCleanup
import io.emeraldpay.dshackle.upstream.generic.SubscriptionHolder
import io.emeraldpay.dshackle.upstream.rpcclient.ListParams
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

class EthereumWsIngressSubscription(
    private val conn: WsSubscriptions,
) : IngressSubscription, EthereumIngressSubscription, SubscriptionCleanup {

    companion object {
        private val log = LoggerFactory.getLogger(EthereumWsIngressSubscription::class.java)
    }

    private val pendingTxes = WebsocketPendingTxes(conn)
    private val holders = ConcurrentHashMap<Pair<String, Any?>, SubscriptionHolder<*>>()

    override fun getAvailableTopics(): List<String> {
        return listOf(EthereumEgressSubscription.METHOD_PENDING_TXES)
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> get(topic: String, params: Any?): SubscriptionConnect<T>? {
        if (topic == EthereumEgressSubscription.METHOD_PENDING_TXES) {
            val clientId = generateClientId()
            return getForClient(topic, params, clientId)
        }
        return null
    }

    /**
     * Get a subscription for a specific client with tracking
     */
    fun <T> getForClient(topic: String, params: Any?, clientId: String): SubscriptionConnect<T>? {
        if (topic != EthereumEgressSubscription.METHOD_PENDING_TXES) {
            return null
        }

        val key = topic to params

        @Suppress("UNCHECKED_CAST")
        val holder = holders.computeIfAbsent(key) {
            log.debug("Creating new subscription holder for {}:{}", topic, params)
            SubscriptionHolder(
                pendingTxes,
                topic,
                params,
            )
        } as SubscriptionHolder<T>

        log.info("[AUTO-UNSUBSCRIBE] Creating new client {} for Ethereum subscription {}:{}", clientId, topic, params)
        val client = DefaultSubscriptionClient(clientId) {
            releaseSubscription(topic, params, clientId)
        }

        return holder.addClient(client)
    }

    /**
     * Release a client's subscription. Called when a client disconnects.
     */
    fun releaseSubscription(topic: String, params: Any?, clientId: String) {
        log.info("[AUTO-UNSUBSCRIBE] Releasing Ethereum subscription for client {} on topic {}:{}", clientId, topic, params)
        val key = topic to params
        holders.computeIfPresent(key) { _, holder ->
            if (holder.removeClient(clientId)) {
                log.info("[AUTO-UNSUBSCRIBE] Last client removed for Ethereum subscription {}:{}, triggering cleanup", topic, params)
                null
            } else {
                log.info("[AUTO-UNSUBSCRIBE] Client {} removed, but {} clients remain for {}:{}", clientId, holder.getClientCount(), topic, params)
                holder
            }
        }
    }

    /**
     * Generate a unique client ID
     */
    private fun generateClientId(): String {
        return "eth-client-${System.currentTimeMillis()}-${Thread.currentThread().id}-${(Math.random() * 10000).toInt()}"
    }

    override fun cleanupSubscription(topic: String, params: Any?, subscriptionId: String?) {
        log.info("[AUTO-UNSUBSCRIBE] Cleaning up Ethereum subscription {}:{} with ID: {}", topic, params, subscriptionId)

        if (subscriptionId.isNullOrEmpty()) {
            log.warn("Cannot cleanup Ethereum subscription {}:{} - no subscription ID", topic, params)
            return
        }

        val unsubscribeRequest = ChainRequest("eth_unsubscribe", ListParams(subscriptionId))
        log.info("[AUTO-UNSUBSCRIBE] Sending eth_unsubscribe request to upstream for {}:{}", topic, params)
        conn.unsubscribe(unsubscribeRequest)
            .doOnSuccess {
                log.info("[AUTO-UNSUBSCRIBE] Successfully unsubscribed from upstream {}:{}", topic, params)
            }
            .doOnError { error ->
                log.warn("[AUTO-UNSUBSCRIBE] Failed to unsubscribe from upstream {}:{}: {}", topic, params, error.message)
            }
            .subscribe()
    }

    override fun getPendingTxes(): PendingTxesSource {
        return pendingTxes
    }

    /**
     * Get subscription statistics for monitoring
     */
    fun getSubscriptionStats(): Map<String, Any> {
        val stats = mutableMapOf<String, Any>()
        stats["totalSubscriptions"] = holders.size
        stats["subscriptions"] = holders.map { (key, holder) ->
            mapOf(
                "topic" to key.first,
                "params" to (key.second?.toString() ?: "null"),
                "clientCount" to holder.getClientCount(),
                "clients" to holder.getClientIds(),
            )
        }
        return stats
    }

    /**
     * Force cleanup of a specific subscription (for testing/admin purposes)
     */
    fun forceCleanupSubscription(topic: String, params: Any?) {
        val key = topic to params
        holders.remove(key)?.let { holder ->
            log.info("Force cleaned up Ethereum subscription {}:{} with {} clients", topic, params, holder.getClientCount())
        }
    }
}
