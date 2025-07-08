package io.emeraldpay.dshackle.upstream.ethereum

import io.emeraldpay.dshackle.Chain
import io.emeraldpay.dshackle.cache.Caches
import io.emeraldpay.dshackle.test.TestingCommons
import io.emeraldpay.dshackle.upstream.ChainRequest
import io.emeraldpay.dshackle.upstream.calls.DefaultEthereumMethods
import io.emeraldpay.dshackle.upstream.rpcclient.ListParams
import org.apache.commons.collections4.functors.ConstantFactory
import spock.lang.Specification

import java.time.Duration

class EthereumLocalReaderSpec extends Specification {

    def "Calls hardcoded"() {
        setup:
        def methods = new DefaultEthereumMethods(Chain.ETHEREUM__MAINNET)
        def router = new EthereumLocalReader(
                new EthereumCachingReader(
                        TestingCommons.multistream(TestingCommons.api()),
                        Caches.default(),
                        ConstantFactory.constantFactory(new DefaultEthereumMethods(Chain.ETHEREUM__MAINNET)),
                        TestingCommons.tracerMock()
                ),
                methods
        )
        when:
        def act = router.read(new ChainRequest("eth_coinbase", new ListParams())).block(Duration.ofSeconds(1))
        then:
        act.resultAsProcessedString == "0x0000000000000000000000000000000000000000"
    }

    def "Returns empty if nonce set"() {
        setup:
        def methods = new DefaultEthereumMethods(Chain.ETHEREUM__MAINNET)
        def router = new EthereumLocalReader(
                new EthereumCachingReader(
                        TestingCommons.multistream(TestingCommons.api()),
                        Caches.default(),
                        ConstantFactory.constantFactory(new DefaultEthereumMethods(Chain.ETHEREUM__MAINNET)),
                        TestingCommons.tracerMock()
                ),
                methods
        )
        when:
        def act = router.read(new ChainRequest("eth_getTransactionByHash", new ListParams(["test"]), 10))
                .block(Duration.ofSeconds(1))
        then:
        act == null
    }
}
