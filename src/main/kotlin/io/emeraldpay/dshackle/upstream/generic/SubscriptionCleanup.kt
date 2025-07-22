package io.emeraldpay.dshackle.upstream.generic

/**
 * Interface for handling subscription cleanup operations
 */
interface SubscriptionCleanup {

    /**
     * Called when a subscription should be cleaned up (no more clients)
     * @param topic the subscription topic (e.g., "blockSubscribe")
     * @param params the subscription parameters
     * @param subscriptionId the upstream subscription ID to unsubscribe
     */
    fun cleanupSubscription(topic: String, params: Any?, subscriptionId: String?)
}
