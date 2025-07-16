package io.emeraldpay.dshackle.upstream.generic

/**
 * Represents a client that has subscribed to a topic
 */
interface SubscriptionClient {
    /**
     * Unique identifier for this client
     */
    val clientId: String

    /**
     * Called when the client disconnects
     */
    fun onDisconnect()
}

/**
 * Default implementation of SubscriptionClient
 */
class DefaultSubscriptionClient(
    override val clientId: String,
    private val onDisconnectCallback: () -> Unit,
) : SubscriptionClient {

    override fun onDisconnect() {
        onDisconnectCallback()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SubscriptionClient) return false
        return clientId == other.clientId
    }

    override fun hashCode(): Int {
        return clientId.hashCode()
    }

    override fun toString(): String {
        return "SubscriptionClient(clientId='$clientId')"
    }
}
