package io.emeraldpay.dshackle.quorum

import io.emeraldpay.dshackle.upstream.Upstream
import io.emeraldpay.dshackle.upstream.ChainException
import io.emeraldpay.dshackle.upstream.ChainResponse
import io.emeraldpay.dshackle.upstream.signature.ResponseSigner
import spock.lang.Specification

class NotNullQuorumSpec extends Specification {

    def "Resolves if attempts are exhausted and response is null"() {
        setup:
        def up = Mock(Upstream) {
            2 * getId() >> "id"
        }
        def up1 = Mock(Upstream) {
            1 * getId() >> "id1"
        }
        def up2 = Mock(Upstream) {
            1 * getId() >> "id2"
        }
        def value = "null".getBytes()
        def response = new ChainResponse(value, null)
        def quorum = new NotNullQuorum()

        when:
        def res = quorum.record(response, new ResponseSigner.Signature("sig1".bytes, "test", 100), up)
        def res1 = quorum.record(response, new ResponseSigner.Signature("sig1".bytes, "test", 100), up1)
        def res2 = quorum.record(response, new ResponseSigner.Signature("sig1".bytes, "test", 100), up2)
        def res3 = quorum.record(response, new ResponseSigner.Signature("sig1".bytes, "test", 100), up)
        then:
        !res
        !res1
        !res2
        res3
        quorum.response.result == value
        !quorum.isFailed()
        quorum.isResolved()
        quorum.signature == new ResponseSigner.Signature("sig1".bytes, "test", 100)
    }

    def "Failed if all upstreams respond with error"() {
        setup:
        def up = Mock(Upstream) {
            2 * getId() >> "id"
        }
        def up1 = Mock(Upstream) {
            1 * getId() >> "id1"
        }
        def up2 = Mock(Upstream) {
            1 * getId() >> "id2"
        }
        def quorum = new NotNullQuorum()

        when:
        quorum.record(new ChainException(10, "error"), null, up)
        quorum.record(new ChainException(10, "error"), null, up1)
        quorum.record(new ChainException(10, "error"), null, up2)
        quorum.record(new ChainException(10, "error"), null, up)

        then:
        quorum.isFailed()
        !quorum.isResolved()
        quorum.error == new ChainException(10, "error").error
    }

    def "Resolve if one of upstream responds with value"() {
        setup:
        def up = Mock(Upstream) {
            2 * getId() >> "id"
        }
        def up1 = Mock(Upstream) {
            1 * getId() >> "id1"
        }
        def up2 = Mock(Upstream) {
            1 * getId() >> "id2"
        }
        def value = "null".getBytes()
        def quorum = new NotNullQuorum()

        when:
        def res = quorum.record(new ChainResponse(value, null), new ResponseSigner.Signature("sig1".bytes, "test", 100), up)
        quorum.record(new ChainException(10, "error"), new ResponseSigner.Signature("sig1".bytes, "test", 100), up1)
        quorum.record(new ChainException(10, "error"), new ResponseSigner.Signature("sig1".bytes, "test", 100), up2)
        quorum.record(new ChainException(10, "error"), new ResponseSigner.Signature("sig1".bytes, "test", 100), up)

        then:
        !res
        quorum.isResolved()
        !quorum.isFailed()
        quorum.response.result == value
        quorum.signature == new ResponseSigner.Signature("sig1".bytes, "test", 100)
    }

    def "A null is accepted after the configured number of different upstreams"() {
        setup:
        def up = Mock(Upstream) { _ * getId() >> "id" }
        def up1 = Mock(Upstream) { _ * getId() >> "id1" }
        def value = "null".getBytes()
        def quorum = new NotNullQuorum(2)

        when:
        def res = quorum.record(new ChainResponse(value, null), null, up)
        def res1 = quorum.record(new ChainResponse(value, null), null, up1)
        then:
        !res
        res1
        quorum.isResolved()
        quorum.response.result == value
    }

    def "A value after the first null still wins"() {
        setup:
        def up = Mock(Upstream) { _ * getId() >> "id" }
        def up1 = Mock(Upstream) { _ * getId() >> "id1" }
        def quorum = new NotNullQuorum(2)

        when:
        def res = quorum.record(new ChainResponse("null".bytes, null), null, up)
        def res1 = quorum.record(new ChainResponse("{\"a\":1}".bytes, null), null, up1)
        then:
        !res
        res1
        new String(quorum.response.result) == '{"a":1}'
    }

    def "A value after errors still wins"() {
        setup:
        def up = Mock(Upstream) { _ * getId() >> "id" }
        def up1 = Mock(Upstream) { _ * getId() >> "id1" }
        def up2 = Mock(Upstream) { _ * getId() >> "id2" }
        def quorum = new NotNullQuorum(2)

        when:
        quorum.record(new ChainException(10, "error"), null, up)
        quorum.record(new ChainException(10, "error"), null, up1)
        def res = quorum.record(new ChainResponse("{\"a\":1}".bytes, null), null, up2)
        then:
        res
        quorum.isResolved()
    }
}
