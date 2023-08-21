package io.emeraldpay.dshackle.auth.service

import com.auth0.jwt.JWT
import io.emeraldpay.dshackle.auth.processor.AuthProcessorFactory
import io.emeraldpay.dshackle.config.MainConfig
import io.grpc.Status
import org.springframework.stereotype.Service

@Service
class AuthService(
    private val mainConfig: MainConfig,
    private val rsaKeyReader: KeyReader,
    private val authProcessorFactory: AuthProcessorFactory
) {

    fun authenticate(token: String): String {
        val authConfig = mainConfig.authorization
        if (!authConfig.enabled) {
            throw Status.UNIMPLEMENTED
                .withDescription("Authentication process is not enabled")
                .asException()
        }

        val keyPair = rsaKeyReader.getKeyPair(authConfig.providerPrivateKeyPath, authConfig.drpcPublicKeyPath)
        val decodedJwt = JWT.decode(token)

        return authProcessorFactory
            .getAuthProcessor(decodedJwt)
            .process(keyPair, token)
    }
}
