package io.emeraldpay.dshackle.upstream.grpc.auth

import io.emeraldpay.dshackle.auth.processor.SESSION_ID
import io.grpc.Metadata
import java.util.concurrent.ConcurrentHashMap

class GrpcAuthContext {

    companion object {
        private val sessions = ConcurrentHashMap<String, String>()

        fun putTokenInContext(upstreamId: String, sessionId: String) {
            sessions[upstreamId] = sessionId
        }

        fun removeToken(upstreamId: String) {
            sessions.remove(upstreamId)
        }

        fun containsToken(upstreamId: String) = sessions.containsKey(upstreamId)

        fun getToken(upstreamId: String) = sessions[upstreamId]

        val AUTHORIZATION_HEADER: Metadata.Key<String> = Metadata.Key.of(SESSION_ID, Metadata.ASCII_STRING_MARSHALLER)
    }
}
