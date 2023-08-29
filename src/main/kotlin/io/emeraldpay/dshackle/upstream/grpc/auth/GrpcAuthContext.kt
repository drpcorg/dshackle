package io.emeraldpay.dshackle.upstream.grpc.auth

import io.emeraldpay.dshackle.auth.processor.SESSION_ID
import io.grpc.Metadata
import java.util.concurrent.ConcurrentHashMap

const val PROVIDER_ID = "providerId"

class GrpcAuthContext {

    companion object {
        val sessions = ConcurrentHashMap<String, String>()

        fun putTokenInContext(providerId: String, sessionId: String) {
            sessions[providerId] = sessionId
        }

        fun removeToken(providerId: String) {
            sessions.remove(providerId)
        }

        val AUTHORIZATION_HEADER: Metadata.Key<String> = Metadata.Key.of(SESSION_ID, Metadata.ASCII_STRING_MARSHALLER)
        val PROVIDER_ID_HEADER: Metadata.Key<String> = Metadata.Key.of(PROVIDER_ID, Metadata.ASCII_STRING_MARSHALLER)
    }
}
