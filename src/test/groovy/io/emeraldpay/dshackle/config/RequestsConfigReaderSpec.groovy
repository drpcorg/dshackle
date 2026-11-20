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
    }

    def "Read unstream-bounded"() {
        setup:
        def config = this.class.getClassLoader().getResourceAsStream("configs/requests-unstream.yaml")
        when:
        def act = reader.read(config)

        then:
        act.unstreamBounded
    }
}
