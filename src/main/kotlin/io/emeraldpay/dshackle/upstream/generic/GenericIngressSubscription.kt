package io.emeraldpay.dshackle.upstream.generic

import io.emeraldpay.dshackle.Chain
import io.emeraldpay.dshackle.Global
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
import java.util.concurrent.TimeoutException

class GenericIngressSubscription(
    val chain: Chain,
    val conn: WsSubscriptions,
    val methods: List<String>,
) : IngressSubscription {
    override fun getAvailableTopics(): List<String> {
        return methods
    }

    private val holders = ConcurrentHashMap<Pair<String, Any?>, SubscriptionConnect<out Any>>()

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> get(topic: String, params: Any?, unsubscribeMethod: String): SubscriptionConnect<T> {
        return holders.computeIfAbsent(topic to params) { key ->
            GenericSubscriptionConnect(
                chain,
                conn,
                key.first,
                key.second,
                unsubscribeMethod,
            )
        } as SubscriptionConnect<T>
    }
}

class GenericSubscriptionConnect(
    val chain: Chain,
    val conn: WsSubscriptions,
    val topic: String,
    val params: Any?,
    val unsubscribeMethod: String,
) : GenericPersistentConnect() {

    companion object {
        private val log = LoggerFactory.getLogger(GenericSubscriptionConnect::class.java)
        private val IDLE_TIMEOUT: Duration = Duration.ofSeconds(85)
    }

    @Suppress("UNCHECKED_CAST")
    override fun createConnection(): Flux<Any> {
        val sub = conn.subscribe(ChainRequest(topic, ListParams(getParams(params) as List<Any>)))
        return sub.data
            .flatMapMany { it.t2 }
            // Some upstreams (notably Solana RPCs) silently stop delivering events on a subscription
            // while keeping the WebSocket connection alive. Emit a TimeoutException so the surrounding
            // DurableFlux re-invokes createConnection() and re-issues `subscribe` with a fresh subId.
            .timeout(
                IDLE_TIMEOUT,
                Mono.error(
                    TimeoutException("No events from subscription to $topic in $IDLE_TIMEOUT, forcing resubscribe"),
                ),
            )
            .doOnError { log.warn("Error during subscription to $topic: {}", it.message) }
            .doFinally {
                if (unsubscribeMethod != "") {
                    conn.unsubscribe(
                        ChainRequest(
                            unsubscribeMethod,
                            ListParams(Global.getSubId(sub.subId.get(), chain)),
                        ),
                    )
                        .subscribe {
                            log.info("unsubscribed from ${sub.subId.get()}")
                        }
                }
            } as Flux<Any>
    }

    private fun getParams(params: Any?): List<Any?> {
        if (params == null) {
            return listOf()
        }
        return params as List<Any?>
    }
}
