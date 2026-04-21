package io.emeraldpay.dshackle.upstream.signature

import io.emeraldpay.dshackle.upstream.ethereum.rpc.RpcException
import spock.lang.Specification

class DisabledSignerSpec extends Specification {

    def "sign throws RpcException with CODE_INTERNAL_ERROR"() {
        setup:
        def signer = new DisabledSigner()

        when:
        signer.sign(1L, "data".bytes, "upstreamId")

        then:
        def ex = thrown(RpcException)
        ex.code == -32603
        ex.rpcMessage.contains("signing key is not configured")
    }

    def "Signer is not enabled"() {
        setup:
        def signer = new DisabledSigner()

        expect:
        !signer.enabled
    }
}
