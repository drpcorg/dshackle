package io.emeraldpay.dshackle.auth.processor

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.RegisteredClaims
import com.auth0.jwt.algorithms.Algorithm
import io.emeraldpay.dshackle.auth.AuthContext
import io.emeraldpay.dshackle.auth.service.KeyReader
import io.grpc.Status
import org.springframework.stereotype.Component
import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

const val SESSION_ID = "sessionId"
const val VERSION = "version"

enum class AuthVersion {
    V1;

    companion object {
        fun getVersion(version: String) = values().find { it.name == version }
            ?: throw Status.INVALID_ARGUMENT
                .withDescription("Unsupported auth version $version")
                .asException()
    }
}

abstract class AuthProcessor {

    open fun process(keyPair: KeyReader.KeyPair, token: String): String {
        try {
            val verifier: JWTVerifier = JWT.require(verifyingAlgorithm(keyPair.drpcPublicKey))
                .withIssuer("drpc")
                .withClaim(RegisteredClaims.ISSUED_AT) { _, _ ->
                    val tokenWrapper = AuthContext.tokenWrapper ?: return@withClaim true
                    return@withClaim Instant.now().isAfter(tokenWrapper.lastAuthAt.plus(1, ChronoUnit.HOURS))
                }
                .build()
            verifier.verify(token)
        } catch (e: Exception) {
            throw Status.INVALID_ARGUMENT
                .withDescription("Invalid token: ${e.message}")
                .asException()
        }

        return processInternal(keyPair.providerPrivateKey)
            .also {
                AuthContext.putTokenInContext(it)
            }
            .token
    }

    protected abstract fun processInternal(privateKey: PrivateKey): AuthContext.TokenWrapper

    protected abstract fun verifyingAlgorithm(publicKey: PublicKey): Algorithm
}

@Component
open class AuthProcessorV1 : AuthProcessor() {

    override fun processInternal(privateKey: PrivateKey): AuthContext.TokenWrapper {
        val issAt = Instant.now()
        val token = JWT.create()
            .withIssuedAt(issAt)
            .withClaim(SESSION_ID, UUID.randomUUID().toString())
            .withClaim(VERSION, AuthVersion.V1.toString())
            .sign(Algorithm.RSA256(privateKey as RSAPrivateKey))

        return AuthContext.TokenWrapper(token, issAt)
    }

    override fun verifyingAlgorithm(publicKey: PublicKey): Algorithm =
        Algorithm.RSA256(publicKey as RSAPublicKey, null)
}
