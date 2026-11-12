package io.emeraldpay.dshackle.upstream.ethereum

import io.emeraldpay.dshackle.Chain
import io.emeraldpay.dshackle.cache.Caches
import io.emeraldpay.dshackle.cache.CachesBlockByNumberSpec
import io.emeraldpay.dshackle.test.TestingCommons
import io.emeraldpay.dshackle.upstream.ChainRequest
import io.emeraldpay.dshackle.upstream.calls.DefaultEthereumMethods
import io.emeraldpay.dshackle.upstream.rpcclient.ListParams
import org.apache.commons.collections4.functors.ConstantFactory
import reactor.core.publisher.Mono
import spock.lang.Specification

import java.time.Duration

class EthereumLocalReaderSpec extends Specification {

    def "eth_getBlockByNumber is served from the memory cache when enabled"() {
        setup:
        def block = CachesBlockByNumberSpec.block(100, 100, 99, 2)
        EthereumCachingReader reader = Mock() {
            _ * chainCode() >> "ETHEREUM"
            _ * blockMissReason(_) >> "not_cached"
            _ * recentBlocksEnabled() >> true
        }
        def router = new EthereumLocalReader(reader, new DefaultEthereumMethods(Chain.ETHEREUM__MAINNET))

        when:
        def act = router.read(new ChainRequest("eth_getBlockByNumber", new ListParams(["0x64", false]))).block(Duration.ofSeconds(1))
        then:
        1 * reader.canonicalBlockFromMemory(100L) >> block
        act.getResult() == block.json

        when:
        act = router.read(new ChainRequest("eth_getBlockByNumber", new ListParams(["latest", false]))).block(Duration.ofSeconds(1))
        then:
        1 * reader.canonicalBlockFromMemory(null) >> block
        act.getResult() == block.json

        when: "a miss goes to an upstream"
        act = router.read(new ChainRequest("eth_getBlockByNumber", new ListParams(["0x65", false]))).block(Duration.ofSeconds(1))
        then:
        1 * reader.canonicalBlockFromMemory(101L) >> null
        act == null
    }

    def "eth_getBlockByNumber with full txs, other tags or bad params is never served from the cache"() {
        setup:
        EthereumCachingReader reader = Mock() {
            _ * chainCode() >> "ETHEREUM"
            _ * blockMissReason(_) >> "not_cached"
            _ * recentBlocksEnabled() >> true
        }
        def router = new EthereumLocalReader(reader, new DefaultEthereumMethods(Chain.ETHEREUM__MAINNET))

        when:
        def act = router.read(new ChainRequest("eth_getBlockByNumber", new ListParams(params))).block(Duration.ofSeconds(1))
        then:
        0 * reader.canonicalBlockFromMemory(_)
        act == null

        where:
        params << [["0x64", true], ["finalized", false], ["safe", false], ["pending", false], ["earliest", false], ["0x64"], ["0xzz", false], [100, false]]
    }

    def "eth_getBlockByNumber goes to an upstream when the cache is disabled"() {
        setup:
        EthereumCachingReader reader = Mock()
        def router = new EthereumLocalReader(reader, new DefaultEthereumMethods(Chain.ETHEREUM__MAINNET))

        when:
        def act = router.read(new ChainRequest("eth_getBlockByNumber", new ListParams(["0x64", false]))).block(Duration.ofSeconds(1))
        then:
        0 * reader.canonicalBlockFromMemory(_)
        act == null
    }

    def "A response is served from the responses cache"() {
        setup:
        def key = new ResponseCachePolicy.Key("key", null, 100L, true, true, ResponseCachePolicy.Check.VALUE)
        EthereumCachingReader reader = Mock() {
            _ * chainCode() >> "ETHEREUM"
        }
        def router = new EthereumLocalReader(reader, new DefaultEthereumMethods(Chain.ETHEREUM__MAINNET))
        def request = new ChainRequest("eth_getBalance", new ListParams(["0xe0aadb0a012dbcdc529c4c743d3e0385a0b54d3d", "0x64"]))

        when:
        def act = router.read(request).block(Duration.ofSeconds(1))
        then:
        1 * reader.responseKey("eth_getBalance", _) >> key
        1 * reader.cachedResponse(key) >> Mono.just('"0x1"'.bytes)
        new String(act.getResult()) == '"0x1"'

        when: "it's not in the cache, the request goes to an upstream"
        act = router.read(request).block(Duration.ofSeconds(1))
        then:
        1 * reader.responseKey("eth_getBalance", _) >> key
        1 * reader.cachedResponse(key) >> Mono.empty()
        act == null
    }

    def "Calls hardcoded"() {
        setup:
        def methods = new DefaultEthereumMethods(Chain.ETHEREUM__MAINNET)
        def router = new EthereumLocalReader(
                new EthereumCachingReader(
                        TestingCommons.multistream(TestingCommons.api()),
                        Caches.default(),
                        ConstantFactory.constantFactory(new DefaultEthereumMethods(Chain.ETHEREUM__MAINNET)),
                ),
                methods
        )
        when:
        def act = router.read(new ChainRequest("eth_coinbase", new ListParams())).block(Duration.ofSeconds(1))
        then:
        act.resultAsProcessedString == "0x0000000000000000000000000000000000000000"
    }

    def "Serves non-hardcoded call when nonce is set"() {
        setup:
        def methods = new DefaultEthereumMethods(Chain.ETHEREUM__MAINNET)
        def api = TestingCommons.api()
        api.answer("eth_getTransactionByHash",
                ["0x0000000000000000000000000000000000000000000000000000000000000001"], null)
        def router = new EthereumLocalReader(
                new EthereumCachingReader(
                        TestingCommons.multistream(api),
                        Caches.default(),
                        ConstantFactory.constantFactory(new DefaultEthereumMethods(Chain.ETHEREUM__MAINNET)),
                ),
                methods
        )
        when:
        def act = router.read(new ChainRequest("eth_getTransactionByHash",
                        new ListParams(["0x0000000000000000000000000000000000000000000000000000000000000001"]),
                        10))
                .block(Duration.ofSeconds(1))
        then:
        act != null
    }
}
