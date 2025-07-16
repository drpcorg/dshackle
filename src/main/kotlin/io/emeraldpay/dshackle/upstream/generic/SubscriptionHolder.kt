package io.emeraldpay.dshackle.upstream.generic

import io.emeraldpay.dshackle.upstream.SubscriptionConnect
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Holds a subscription connection with reference counting for proper cleanup
 */
class SubscriptionHolder<T>(
    val connection: SubscriptionConnect<T>,
    val topic: String,
    val params: Any?,
) {
    companion object {
        private val log = LoggerFactory.getLogger(SubscriptionHolder::class.java)
    }

    private val refCount = AtomicInteger(0)

    /**
     * Add a reference to this subscription
     * @return the subscription connection
     */
    fun addRef(): SubscriptionConnect<T> {
        val count = refCount.incrementAndGet()
        log.debug("Added reference to subscription {}:{}, ref count: {}", topic, params, count)
        return connection
    }

    /**
     * Remove a reference from this subscription
     * @return true if this was the last reference and cleanup should occur
     */
    fun removeRef(): Boolean {
        val count = refCount.decrementAndGet()
        log.debug("Removed reference from subscription {}:{}, ref count: {}", topic, params, count)

        if (count < 0) {
            log.warn("Reference count went negative for subscription {}:{}", topic, params)
            return false
        }

        return count == 0
    }

    /**
     * Get current reference count (for monitoring/debugging)
     */
    fun getRefCount(): Int = refCount.get()
}
