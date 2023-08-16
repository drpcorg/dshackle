package io.emeraldpay.dshackle.config

import spock.lang.Specification

class AuthorizationConfigReaderSpec extends Specification {
    def reader = new AuthorizationConfigReader()

    def "if no auth option then default settings"() {
        setup:
        def yamlIs = this.class.getClassLoader().getResourceAsStream("configs/upstreams-basic.yaml")
        when:
        def act = reader.read(yamlIs)
        then:
        act == AuthorizationConfig.default()
    }

    def "if auth is disabled then defaults settings"() {
        setup:
        def yamlIs = this.class.getClassLoader().getResourceAsStream("configs/auth-disabled.yaml")
        when:
        def act = reader.read(yamlIs)
        then:
        act == AuthorizationConfig.default()
    }

    def "check default settings"() {
        when:
        def act = AuthorizationConfig.default()
        then:
        !act.enabled
        act.drpcPublicKeyPath == ""
        act.providerPrivateKeyPath == ""
    }

    def "exceptions if no settings"() {
        setup:
        def yamlIs = this.class.getClassLoader().getResourceAsStream(filePath)
        when:
        reader.read(yamlIs)
        then:
        def t = thrown(IllegalStateException)
        t.message == message
        where:
        filePath                                | message
        "configs/auth-without-public-key.yaml"  | "Public key in not specified"
        "configs/auth-without-private-key.yaml" | "Private key in not specified"
        "configs/auth-without-key-pair.yaml"    | "Auth key-pair is not specified"
    }
}
