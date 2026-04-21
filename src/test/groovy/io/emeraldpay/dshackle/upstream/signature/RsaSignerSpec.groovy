package io.emeraldpay.dshackle.upstream.signature

import org.apache.commons.codec.binary.Hex
import org.bouncycastle.jce.provider.BouncyCastleProvider
import spock.lang.Specification

import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Security
import java.security.Signature
import java.security.interfaces.RSAPrivateKey

class RsaSignerSpec extends Specification {

    def setupSpec() {
        Security.addProvider(new BouncyCastleProvider())
    }

    def "Wrap message"() {
        setup:
        def signer = new RsaSigner(Stub(RSAPrivateKey), 100L)

        when:
        def act = signer.wrapMessage(10, "test".bytes, "infura")

        then:
        act == "DSHACKLESIG/10/infura/9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
    }

    def "Signed message is valid"() {
        setup:
        def result = "test".bytes
        def keyGen = KeyPairGenerator.getInstance("RSA")
        keyGen.initialize(2048)
        def pair = keyGen.generateKeyPair()

        def sha256 = MessageDigest.getInstance("SHA-256")
        def verifier = Signature.getInstance("SHA256withRSA", "BC")
        verifier.initVerify(pair.getPublic())
        verifier.update("DSHACKLESIG/10/infura/${Hex.encodeHexString(sha256.digest(result))}".getBytes())

        def signer = new RsaSigner((pair.getPrivate() as RSAPrivateKey), 100L)

        when:
        def sig = signer.sign(10, result, "infura")

        then:
        verifier.verify(sig.value)
        sig.upstreamId == "infura"
        sig.keyId == 100L
    }

    def "Signer is enabled"() {
        setup:
        def signer = new RsaSigner(Stub(RSAPrivateKey), 1L)

        expect:
        signer.enabled
    }

    def "Different nonce produces different signature"() {
        setup:
        def keyGen = KeyPairGenerator.getInstance("RSA")
        keyGen.initialize(2048)
        def pair = keyGen.generateKeyPair()
        def signer = new RsaSigner((pair.getPrivate() as RSAPrivateKey), 1L)

        when:
        def sig1 = signer.sign(1, "test".bytes, "up")
        def sig2 = signer.sign(2, "test".bytes, "up")

        then:
        !Arrays.equals(sig1.value, sig2.value)
    }
}
