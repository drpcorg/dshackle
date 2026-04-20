package io.emeraldpay.dshackle.upstream.signature

import io.emeraldpay.dshackle.config.AuthorizationConfig
import org.apache.commons.codec.binary.Hex
import org.bouncycastle.openssl.PEMParser
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Paths
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec

@Configuration
open class SignatureBeans {
    @Bean
    open fun signer(factory: ResponseSignerFactory): ResponseSigner =
        factory.createSigner()
}

@Component
open class ResponseSignerFactory(
    private val authorizationConfig: AuthorizationConfig,
) {

    companion object {
        private val log = LoggerFactory.getLogger(ResponseSignerFactory::class.java)
    }

    fun createSigner(): ResponseSigner {
        if (!authorizationConfig.enabled) {
            log.info("Response signing disabled: auth is not enabled")
            return DisabledSigner()
        }
        val path = authorizationConfig.serverConfig.providerPrivateKeyPath
        if (path.isBlank()) {
            log.warn("Response signing disabled: auth.server.provider-private-key is not set")
            return DisabledSigner()
        }

        val (privateKey, keyId) = readRsaKey(path)
        return RsaSigner(privateKey, keyId)
    }

    internal fun readRsaKey(path: String): Pair<RSAPrivateKey, Long> {
        val pemContent = StringReader(Files.readString(Paths.get(path)))
        val pemObject = PEMParser(pemContent).readPemObject()
            ?: throw IllegalStateException("Cannot parse PEM key at $path")

        val keyFactory = KeyFactory.getInstance("RSA")
        val privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(pemObject.content))

        if (privateKey !is RSAPrivateKey) {
            throw IllegalStateException("Only RSA keys are supported for response signing")
        }

        val publicKey = extractPublicKey(keyFactory, privateKey)
        val keyId = getPublicKeyId(publicKey)
        return Pair(privateKey, keyId)
    }

    private fun extractPublicKey(keyFactory: KeyFactory, privateKey: RSAPrivateKey): PublicKey {
        val crt = privateKey as? RSAPrivateCrtKey
            ?: throw IllegalStateException("RSA private key does not expose public exponent; use a PKCS#8 key that contains CRT parameters")
        val spec = RSAPublicKeySpec(crt.modulus, crt.publicExponent)
        return keyFactory.generatePublic(spec)
    }

    private fun getPublicKeyId(publicKey: PublicKey): Long {
        val digest = MessageDigest.getInstance("SHA-256")
        val fullId = digest.digest(publicKey.encoded)
        log.info("Using key to sign responses: ${Hex.encodeHexString(fullId).substring(0..15)}")
        return ByteBuffer.wrap(fullId).asLongBuffer().get()
    }
}
