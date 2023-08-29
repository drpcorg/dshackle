package io.emeraldpay.dshackle.upstream.grpc.auth

import io.emeraldpay.dshackle.upstream.grpc.auth.GrpcAuthContext.Companion.AUTHORIZATION_HEADER
import io.emeraldpay.dshackle.upstream.grpc.auth.GrpcAuthContext.Companion.PROVIDER_ID_HEADER
import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
import io.grpc.ForwardingClientCall
import io.grpc.Metadata
import io.grpc.MethodDescriptor

class ClientAuthenticationInterceptor : ClientInterceptor {

    override fun <ReqT, RespT> interceptCall(
        method: MethodDescriptor<ReqT, RespT>,
        callOptions: CallOptions,
        next: Channel
    ): ClientCall<ReqT, RespT> =
        object : ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {
            override fun start(responseListener: Listener<RespT>, headers: Metadata) {
                val providerId = headers[PROVIDER_ID_HEADER]
                if (providerId != null) {
                    GrpcAuthContext.sessions[providerId]?.let {
                        headers.put(AUTHORIZATION_HEADER, it)
                    }
                }
                super.start(responseListener, headers)
            }
        }
}
