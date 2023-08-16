package io.emeraldpay.dshackle.auth.service

import com.auth0.jwt.JWT
import io.emeraldpay.dshackle.auth.processor.AuthProcessorFactory
import io.emeraldpay.dshackle.config.AuthorizationConfig
import io.emeraldpay.dshackle.config.MainConfig
import io.grpc.Status
import org.springframework.stereotype.Service
import java.util.concurrent.locks.ReentrantLock

@Service
class AuthService(
    private val mainConfig: MainConfig,
    private val rsaKeyReader: KeyReader,
    private val authProcessorFactory: AuthProcessorFactory
) {

    private val lock = ReentrantLock()

    fun authenticate(token: String): String {
        val authConfig = mainConfig.authorization
        if (!authConfig.enabled) {
            throw Status.UNIMPLEMENTED
                .withDescription("Authentication process is not enabled")
                .asException()
        }

        if (lock.tryLock()) {
            return auth(authConfig, token)
        } else {
            throw Status.FAILED_PRECONDITION
                .withDescription("Authentication process is in progress")
                .asException()
        }
    }

    private fun auth(authConfig: AuthorizationConfig, token: String): String {
        try {
            val keyPair = rsaKeyReader.getKeyPair(authConfig.providerPrivateKeyPath, authConfig.drpcPublicKeyPath)
            val decodedJwt = JWT.decode(token)

            return authProcessorFactory
                .getAuthProcessor(decodedJwt)
                .process(keyPair, token)
        } finally {
            lock.unlock()
        }
    }
}
