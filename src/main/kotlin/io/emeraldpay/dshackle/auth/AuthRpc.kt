package io.emeraldpay.dshackle.auth

import io.emeraldpay.api.proto.AuthGrpc.AuthImplBase
import io.emeraldpay.api.proto.AuthOuterClass
import io.emeraldpay.dshackle.auth.service.AuthService
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.stub.StreamObserver
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class AuthRpc(
    private val authService: AuthService
) : AuthImplBase() {

    companion object {
        private val log = LoggerFactory.getLogger(AuthRpc::class.java)
    }

    override fun authenticate(
        request: AuthOuterClass.AuthRequest,
        responseObserver: StreamObserver<AuthOuterClass.AuthResponse>
    ) {
        try {
            val providerToken = authService.authenticate(request.token)
            responseObserver.onNext(
                AuthOuterClass.AuthResponse.newBuilder()
                    .setProviderToken(providerToken)
                    .build()
            )
            responseObserver.onCompleted()
        } catch (e: StatusException) {
            log.error(e.message)
            responseObserver.onError(e)
        } catch (e: Exception) {
            val message = "Internal error: ${e.message}"
            log.error(message, e)
            responseObserver.onError(
                Status.INTERNAL
                    .withDescription(message)
                    .asException()
            )
        }
    }
}
