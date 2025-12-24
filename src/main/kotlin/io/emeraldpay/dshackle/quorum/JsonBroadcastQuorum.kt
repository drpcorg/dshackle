package io.emeraldpay.dshackle.quorum

import com.fasterxml.jackson.databind.JsonNode
import io.emeraldpay.dshackle.upstream.ChainResponse
import io.emeraldpay.dshackle.upstream.Upstream
import io.emeraldpay.dshackle.upstream.signature.ResponseSigner

open class JsonBroadcastQuorum() : CallQuorum, ValueAwareQuorum<JsonNode>(JsonNode::class.java) {

    private var result: ChainResponse? = null
    private var txResponse: JsonNode? = null
    private var sig: ResponseSigner.Signature? = null

    override fun isResolved(): Boolean {
        return result != null
    }

    override fun isFailed(): Boolean {
        return result == null
    }

    override fun getResponse(): ChainResponse? {
        return result
    }

    override fun getSignature(): ResponseSigner.Signature? {
        return sig
    }

    override fun recordValue(
        response: ChainResponse,
        responseValue: JsonNode?,
        signature: ResponseSigner.Signature?,
        upstream: Upstream,
    ) {
        if (txResponse == null && responseValue != null) {
            txResponse = responseValue
            sig = signature
            result = response
        }
    }

    override fun recordError(
        errorMessage: String?,
        signature: ResponseSigner.Signature?,
        upstream: Upstream,
    ) {
        resolvers.add(upstream)
    }

    override fun toString(): String {
        return "Quorum: Json Broadcast to upstreams"
    }
}
