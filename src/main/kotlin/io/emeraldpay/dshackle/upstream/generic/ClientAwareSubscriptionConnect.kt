package io.emeraldpay.dshackle.upstream.generic

import io.emeraldpay.dshackle.upstream.Selector
import io.emeraldpay.dshackle.upstream.SubscriptionConnect
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wrapper around SubscriptionConnect that tracks client lifecycle
 */
class ClientAwareSubscriptionConnect<T>(
    private val delegate: SubscriptionConnect<T>,
    private val client: SubscriptionClient,
) : SubscriptionConnect<T> {

    companion object {
        private val log = LoggerFactory.getLogger(ClientAwareSubscriptionConnect::class.java)
    }

    private val disconnected = AtomicBoolean(false)

    override fun connect(matcher: Selector.Matcher): Flux<T> {
        return delegate.connect(matcher)
            .doOnSubscribe {
                log.debug("Client {} connected to subscription", client.clientId)
            }
            .doOnCancel {
                handleDisconnect("cancelled")
            }
            .doOnComplete {
                handleDisconnect("completed")
            }
            .doOnError { error ->
                log.debug("Client {} subscription error: {}", client.clientId, error.message)
                handleDisconnect("error")
            }
            .doFinally {
                handleDisconnect("finally")
            }
    }

    private fun handleDisconnect(reason: String) {
        if (disconnected.compareAndSet(false, true)) {
            log.debug("Client {} disconnected ({}), triggering cleanup", client.clientId, reason)
            try {
                client.onDisconnect()
            } catch (e: Exception) {
                log.warn("Error during client {} disconnect callback: {}", client.clientId, e.message)
            }
        }
    }

    /**
     * Manually disconnect the client
     */
    fun disconnect() {
        handleDisconnect("manual")
    }
}