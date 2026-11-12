package io.emeraldpay.dshackle.upstream.ethereum

import io.emeraldpay.dshackle.Chain
import io.emeraldpay.dshackle.cache.Caches
import io.emeraldpay.dshackle.cache.CachesBlockByNumberSpec
import io.emeraldpay.dshackle.config.CacheConfig
import io.emeraldpay.dshackle.data.BlockContainer
import io.emeraldpay.dshackle.data.BlockId
import io.emeraldpay.dshackle.reader.Reader
import io.emeraldpay.dshackle.test.ApiReaderMock
import io.emeraldpay.dshackle.test.TestingCommons
import io.emeraldpay.dshackle.upstream.ChainRequest
import io.emeraldpay.dshackle.upstream.ChainResponse
import io.emeraldpay.dshackle.upstream.calls.DefaultEthereumMethods
import io.emeraldpay.dshackle.upstream.ethereum.domain.BlockHash
import io.emeraldpay.dshackle.upstream.ethereum.domain.TransactionId
import io.emeraldpay.dshackle.upstream.ethereum.json.BlockJson
import io.emeraldpay.dshackle.upstream.ethereum.json.TransactionRefJson
import org.apache.commons.collections4.functors.ConstantFactory
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

import static io.emeraldpay.dshackle.cache.CachesBlockByNumberSpec.hash

class EthereumCachingReaderWarmUpSpec extends Specification {

    /**
     * @param txs null for a header received with newHeads, i.e. without transactions
     */
    static BlockJson blockJson(long height, int id, int parentId, Integer txs, Instant timestamp) {
        def json = new BlockJson()
        json.number = height
        json.hash = BlockHash.from(hash(id))
        json.parentHash = BlockHash.from(hash(parentId))
        json.totalDifficulty = BigInteger.ONE
        json.timestamp = timestamp
        if (txs != null) {
            json.transactions = (0..<txs).collect { new TransactionRefJson(TransactionId.from(hash(id * 1000 + it))) }
        }
        return json
    }

    static Caches caches(boolean warmUp = true) {
        def config = new CacheConfig()
        config.recentBlocks.enabled = true
        config.recentBlocks.warmUp = warmUp
        return Caches.newBuilder().setConfig(config).build()
    }

    // the upstream mock makes its own calls on start, so count only the requests for blocks
    AtomicInteger blockCalls = new AtomicInteger()

    EthereumCachingReader reader(ApiReaderMock api, Caches caches) {
        def counting = { ChainRequest request ->
            if (request.method == "eth_getBlockByHash") {
                blockCalls.incrementAndGet()
            }
            api.read(request)
        } as Reader<ChainRequest, ChainResponse>
        return new EthereumCachingReader(
                TestingCommons.multistream(counting), caches,
                ConstantFactory.constantFactory(new DefaultEthereumMethods(Chain.ETHEREUM__MAINNET))
        )
    }

    def "Fetches a new head and its parents which are not in the cache yet"() {
        setup:
        def now = Instant.now()
        def api = TestingCommons.api()
        api.answer("eth_getBlockByHash", [hash(100), false], blockJson(100, 100, 99, 2, now))
        api.answer("eth_getBlockByHash", [hash(99), false], blockJson(99, 99, 98, 0, now))
        // 98 is known already, so it stops there
        def caches = caches()
        def b98 = BlockContainer.from(blockJson(98, 98, 97, 1, now))
        caches.cache(Caches.Tag.REQUESTED, b98)
        def reader = reader(api, caches)
        def header = BlockContainer.from(blockJson(100, 100, 99, null, now))

        when:
        reader.noteBlockRequest()
        reader.warmUp(header)

        then:
        new PollingConditions(timeout: 3).eventually {
            assert caches.isBlockChecked(header.hash)
            assert caches.isBlockChecked(b98.hash)
            assert caches.isBlockChecked(BlockId.from(hash(99)))
        }
        blockCalls.get() == 2
    }

    def "Nothing is fetched if nobody asks for blocks by number"() {
        setup:
        def now = Instant.now()
        def api = TestingCommons.api()
        api.answer("eth_getBlockByHash", [hash(100), false], blockJson(100, 100, 99, 2, now))
        def reader = reader(api, caches())

        when:
        reader.warmUp(BlockContainer.from(blockJson(100, 100, 99, null, now)))
        Thread.sleep(100)

        then:
        blockCalls.get() == 0
    }

    def "Nothing is fetched for an old head or with warm-up disabled"() {
        setup:
        def api = TestingCommons.api()
        api.answer("eth_getBlockByHash", [hash(100), false], blockJson(100, 100, 99, 2, timestamp))
        def reader = reader(api, caches(warmUp))

        when:
        reader.noteBlockRequest()
        reader.warmUp(BlockContainer.from(blockJson(100, 100, 99, null, timestamp)))
        Thread.sleep(100)

        then:
        blockCalls.get() == 0

        where:
        warmUp | timestamp
        true   | Instant.now().minusSeconds(3600)
        false  | Instant.now()
    }

    def "Only heads with a recent timestamp are warmed up"() {
        setup:
        // the helper makes a block N with timestamp 1_700_000_000 + N
        def head = CachesBlockByNumberSpec.block(100, 100, 99, 1)
        def mined = Instant.ofEpochSecond(1_700_000_100L)

        expect:
        EthereumCachingReader.isRecent(head, mined)
        EthereumCachingReader.isRecent(head, mined.plusSeconds(60))
        !EthereumCachingReader.isRecent(head, mined.plusSeconds(61))
        // a node which is still syncing, the block is few hours old
        !EthereumCachingReader.isRecent(head, mined.plusSeconds(3 * 3600))
    }

    def "Warm-up runs only while there are requests for blocks by number"() {
        setup:
        def reader = reader(TestingCommons.api(), Caches.default())
        long now = 10_000_000L
        reader.clock = { now }

        expect:
        !reader.hasBlockDemand()

        when:
        reader.noteBlockRequest()
        then:
        reader.hasBlockDemand()

        when:
        now += 61_000
        then:
        !reader.hasBlockDemand()
    }
}
