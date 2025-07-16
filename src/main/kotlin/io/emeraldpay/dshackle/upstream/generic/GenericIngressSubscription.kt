package io.emeraldpay.dshackle.upstream.generic

import io.emeraldpay.dshackle.upstream.ChainRequest
import io.emeraldpay.dshackle.upstream.IngressSubscription
import io.emeraldpay.dshackle.upstream.SubscriptionConnect
import io.emeraldpay.dshackle.upstream.ethereum.WsSubscriptions
import io.emeraldpay.dshackle.upstream.generic.subscribe.GenericPersistentConnect
import io.emeraldpay.dshackle.upstream.rpcclient.ListParams
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.log

class GenericIngressSubscription(
    val conn: WsSubscriptions,
    val methods: List<String>,
) : IngressSubscription, SubscriptionCleanup {

    companion object {
        private val log = LoggerFactory.getLogger(GenericIngressSubscription::class.java)
    }

    override fun getAvailableTopics(): List<String> {
        return methods
    }

    private val holders = ConcurrentHashMap<Pair<String, Any?>, SubscriptionHolder<out Any>>()

    @Suppress("UNCHECKED_CAST")
    override fun <T> get(topic: String, params: Any?): SubscriptionConnect<T> {
        // Generate a unique client ID for this subscription request
        val clientId = generateClientId()
        return getForClient(topic, params, clientId)
    }

    /**
     * Get a subscription for a specific client
     */
    fun <T> getForClient(topic: String, params: Any?, clientId: String): SubscriptionConnect<T> {
        val key = topic to params
        val holder = holders.computeIfAbsent(key) {
            log.debug("Creating new subscription holder for {}:{}", topic, params)
            SubscriptionHolder(
                GenericSubscriptionConnect(conn, topic, params, this),
                topic,
                params,
            )
        } as SubscriptionHolder<T>

        // Create client with disconnect callback
        val client = DefaultSubscriptionClient(clientId) {
            releaseSubscription(topic, params, clientId)
        }

        return holder.addClient(client)
    }

    /**
     * Release a client's subscription. Called when a client disconnects.
     */
    fun releaseSubscription(topic: String, params: Any?, clientId: String) {
        val key = topic to params
        holders.computeIfPresent(key) { _, holder ->
            if (holder.removeClient(clientId)) {
                log.info("Last client removed for subscription {}:{}, cleaning up", topic, params)
                // Cleanup will be handled by the connection itself
                null // Remove from map
            } else {
                holder // Keep if still has clients
            }
        }
    }

    /**
     * Generate a unique client ID
     */
    private fun generateClientId(): String {
        return "client-${System.currentTimeMillis()}-${Thread.currentThread().id}-${(Math.random() * 10000).toInt()}"
    }

    override fun cleanupSubscription(topic: String, params: Any?, subscriptionId: String?) {
        log.info("Cleaning up subscription {}:{} with ID: {}", topic, params, subscriptionId)

        if (subscriptionId.isNullOrEmpty()) {
            log.warn("Cannot cleanup subscription {}:{} - no subscription ID", topic, params)
            return
        }

        // Determine unsubscribe method based on topic
        val unsubscribeMethod = getUnsubscribeMethod(topic)
        if (unsubscribeMethod != null) {
            val unsubscribeRequest = ChainRequest(unsubscribeMethod, ListParams(subscriptionId))
            conn.unsubscribe(unsubscribeRequest)
                .doOnSuccess {
                    log.info("Successfully unsubscribed from {}:{}", topic, params)
                }
                .doOnError { error ->
                    log.warn("Failed to unsubscribe from {}:{}: {}", topic, params, error.message)
                }
                .subscribe()
        } else {
            log.warn("No unsubscribe method found for topic: {}", topic)
        }
    }

    private fun getUnsubscribeMethod(subscribeMethod: String): String? {
        return when (subscribeMethod) {
            "accountSubscribe" -> "accountUnsubscribe"
            "blockSubscribe" -> "blockUnsubscribe"
            "logsSubscribe" -> "logsUnsubscribe"
            "programSubscribe" -> "programUnsubscribe"
            "signatureSubscribe" -> "signatureUnsubscribe"
            "slotSubscribe" -> "slotUnsubscribe"
            // Ethereum methods
            "eth_subscribe" -> "eth_unsubscribe"
            else -> null
        }
    }
}

class GenericSubscriptionConnect(
    val conn: WsSubscriptions,
    val topic: String,
    val params: Any?,
    val cleanup: SubscriptionCleanup,
) : GenericPersistentConnect() {

    companion object {
        private val log = LoggerFactory.getLogger(GenericSubscriptionConnect::class.java)
    }

    private var subscriptionId: String? = null

    @Suppress("UNCHECKED_CAST")
    override fun createConnection(): Flux<Any> {
        val subscribeData = conn.subscribe(ChainRequest(topic, ListParams(getParams(params) as List<Any>)))

        return subscribeData.data
            .doOnNext { _ ->
                // Store subscription ID when first message arrives (ID should be available by then)
                if (subscriptionId == null) {
                    subscriptionId = subscribeData.subId.get()
                    log.debug("Subscription ID set for {}:{} -> {}", topic, params, subscriptionId)
                }
            }
            .timeout(
                Duration.ofSeconds(85),
                Mono.empty<ByteArray?>().doOnEach {
                    log.warn("Timeout during subscription to $topic after 85 seconds")
                },
            )
            .onErrorResume {
                log.error("Error during subscription to $topic", it)
                Mono.empty()
            }
            .doFinally {
                // Trigger cleanup when connection ends
                log.debug("Connection ended for subscription {}:{}", topic, params)
                cleanup.cleanupSubscription(topic, params, subscriptionId)
            } as Flux<Any>
    }

    private fun getParams(params: Any?): List<Any?> {
        if (params == null) {
            return listOf()
        }
        return params as List<Any?>
    }
}
