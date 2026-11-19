package io.emeraldpay.dshackle.config

import spock.lang.Specification

class RequestsConfigReaderSpec extends Specification {

    RequestsConfigReader reader = new RequestsConfigReader()

    def "Disabled by default"() {
        setup:
        def config = this.class.getClassLoader().getResourceAsStream("configs/dshackle-full.yaml")
        when:
        def act = reader.read(config)

        then:
        !act.unstreamBounded
        !act.coalescing
    }

    def "Read unstream-bounded"() {
        setup:
        def config = this.class.getClassLoader().getResourceAsStream("configs/requests-unstream.yaml")
        when:
        def act = reader.read(config)

        then:
        act.unstreamBounded
    }

    def "Read coalescing"() {
        setup:
        def config = this.class.getClassLoader().getResourceAsStream("configs/requests-coalescing.yaml")
        when:
        def act = reader.read(config)

        then:
        act.coalescing
        !act.unstreamBounded
    }
}
