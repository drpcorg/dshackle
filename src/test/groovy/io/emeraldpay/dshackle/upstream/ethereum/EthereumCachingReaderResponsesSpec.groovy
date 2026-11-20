package io.emeraldpay.dshackle.upstream.ethereum

import io.emeraldpay.dshackle.Chain
import io.emeraldpay.dshackle.cache.Caches
import io.emeraldpay.dshackle.config.CacheConfig
import io.emeraldpay.dshackle.test.TestingCommons
import io.emeraldpay.dshackle.upstream.calls.DefaultEthereumMethods
import io.emeraldpay.dshackle.upstream.rpcclient.ListParams
import org.apache.commons.collections4.functors.ConstantFactory
import spock.lang.Specification

import java.time.Duration

import static io.emeraldpay.dshackle.cache.CachesBlockByNumberSpec.hash

class EthereumCachingReaderResponsesSpec extends Specification {

    static EthereumCachingReader reader(boolean enabled) {
        def config = new CacheConfig()
        config.responses.enabled = enabled
        return new EthereumCachingReader(
                TestingCommons.multistream(TestingCommons.api()),
                Caches.newBuilder().setConfig(config).build(),
                ConstantFactory.constantFactory(new DefaultEthereumMethods(Chain.ETHEREUM__MAINNET))
        )
    }

    def "A response from an upstream is stored and returned for the same request"() {
        setup:
        def reader = reader(true)
        def params = [hash(100), true]
        def json = """{"hash":"${hash(100)}","number":"0x64","transactions":[]}""".bytes
        def key = reader.responseKey("eth_getBlockByHash", params)

        expect:
        key != null
        reader.cachedResponse(key).block(Duration.ofSeconds(1)) == null

        when:
        reader.onUpstreamResponse("eth_getBlockByHash", new ListParams(params), json)

        then:
        reader.cachedResponse(key).block(Duration.ofSeconds(1)) == json
        // same block without transactions is another request
        reader.responseKey("eth_getBlockByHash", [hash(100), false]) == null
    }

    def "A response for another block is not stored"() {
        setup:
        def reader = reader(true)
        def params = [hash(101), true]
        def json = """{"hash":"${hash(100)}","number":"0x64","transactions":[]}""".bytes

        when:
        def stored = reader.storeResponse("eth_getBlockByHash", params, json)

        then:
        !stored
        reader.cachedResponse(reader.responseKey("eth_getBlockByHash", params)).block(Duration.ofSeconds(1)) == null
    }

    def "A block by number is not stored without a known head"() {
        setup:
        def reader = reader(true)
        def json = """{"hash":"${hash(100)}","number":"0x64","transactions":[]}""".bytes

        expect:
        reader.responseKey("eth_getBlockByNumber", [tag, true]) == null
        !reader.storeResponse("eth_getBlockByNumber", [tag, true], json)

        where:
        tag << ["latest", "0x64", "finalized"]
    }

    def "Nothing is stored if the cache is not enabled"() {
        setup:
        def reader = reader(false)
        def params = [hash(100), true]
        def json = """{"hash":"${hash(100)}","number":"0x64","transactions":[]}""".bytes

        expect:
        reader.responseKey("eth_getBlockByHash", params) == null
        !reader.storeResponse("eth_getBlockByHash", params, json)
    }
}
