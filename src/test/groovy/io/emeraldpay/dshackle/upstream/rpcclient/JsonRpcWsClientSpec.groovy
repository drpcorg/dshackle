package io.emeraldpay.dshackle.upstream.rpcclient

import io.emeraldpay.dshackle.upstream.ChainException
import io.emeraldpay.dshackle.upstream.ChainRequest
import io.emeraldpay.dshackle.upstream.ethereum.WsConnection
import io.emeraldpay.dshackle.upstream.ethereum.WsConnectionPool
import reactor.core.Exceptions
import spock.lang.Specification

import java.time.Duration

class JsonRpcWsClientSpec extends Specification {

    def "Produce error if WS is not connected"() {
        setup:
        def ws = Mock(WsConnection)
        def pool = Mock(WsConnectionPool) {
            getConnection() >> ws
        }
        def client = new JsonRpcWsClient(pool)
        when:
        client.read(new ChainRequest("foo_bar", new ListParams([]), 1))
                .block(Duration.ofSeconds(1))
        then:
        def t = thrown(Exceptions.ReactiveException)
        t.cause instanceof ChainException
        1 * ws.isConnected() >> false
    }
}
