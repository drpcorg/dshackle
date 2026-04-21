package io.emeraldpay.dshackle.upstream.signature

import io.emeraldpay.dshackle.config.AuthorizationConfig
import io.emeraldpay.dshackle.upstream.ethereum.rpc.RpcException
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.springframework.util.ResourceUtils
import spock.lang.Specification

import java.security.Security

class ResponseSignerFactorySpec extends Specification {

    def setupSpec() {
        Security.addProvider(new BouncyCastleProvider())
    }

    def "DisabledSigner when auth disabled"() {
        setup:
        def auth = AuthorizationConfig.default()

        when:
        def signer = new ResponseSignerFactory(auth).createSigner()

        then:
        signer instanceof DisabledSigner
    }

    def "DisabledSigner when provider-private-key path is blank"() {
        setup:
        def auth = new AuthorizationConfig(
                true,
                "owner",
                new AuthorizationConfig.ServerConfig("", "classpath:keys/public.pem"),
                AuthorizationConfig.ClientConfig.default(),
        )

        when:
        def signer = new ResponseSignerFactory(auth).createSigner()

        then:
        signer instanceof DisabledSigner
    }

    def "RsaSigner built from valid RSA key"() {
        setup:
        def privPath = ResourceUtils.getFile("classpath:keys/priv.p8.key").absolutePath
        def pubPath = ResourceUtils.getFile("classpath:keys/public.pem").absolutePath
        def auth = new AuthorizationConfig(
                true,
                "owner",
                new AuthorizationConfig.ServerConfig(privPath, pubPath),
                AuthorizationConfig.ClientConfig.default(),
        )

        when:
        def signer = new ResponseSignerFactory(auth).createSigner()

        then:
        signer instanceof RsaSigner
        (signer as RsaSigner).keyId != 0L
    }

    def "Fails on missing key file"() {
        setup:
        def auth = new AuthorizationConfig(
                true,
                "owner",
                new AuthorizationConfig.ServerConfig("/no/such/file.pem", "classpath:keys/public.pem"),
                AuthorizationConfig.ClientConfig.default(),
        )

        when:
        new ResponseSignerFactory(auth).createSigner()

        then:
        thrown(Exception)
    }

    def "DisabledSigner.sign throws RpcException"() {
        setup:
        def signer = new ResponseSignerFactory(AuthorizationConfig.default()).createSigner()

        when:
        signer.sign(1L, "data".bytes, "up")

        then:
        def ex = thrown(RpcException)
        ex.code == -32603
    }
}
