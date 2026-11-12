package io.emeraldpay.dshackle.cache

import io.emeraldpay.dshackle.config.CacheConfig
import io.emeraldpay.dshackle.data.BlockContainer
import io.emeraldpay.dshackle.data.BlockId
import io.emeraldpay.dshackle.upstream.Head
import io.emeraldpay.dshackle.upstream.ethereum.domain.BlockHash
import io.emeraldpay.dshackle.upstream.ethereum.domain.TransactionId
import io.emeraldpay.dshackle.upstream.ethereum.json.BlockJson
import io.emeraldpay.dshackle.upstream.ethereum.json.TransactionRefJson
import spock.lang.Specification

import java.time.Instant

class CachesBlockByNumberSpec extends Specification {

    static String hash(int n) {
        return "0x" + String.format("%064x", n)
    }

    /**
     * @param txs null = a newHeads header (no "transactions" field), 0 = an empty block, n = n tx hashes
     */
    static BlockContainer block(long height, int id, int parentId, Integer txs) {
        def json = new BlockJson()
        json.number = height
        json.hash = BlockHash.from(hash(id))
        json.parentHash = BlockHash.from(hash(parentId))
        json.totalDifficulty = BigInteger.ONE
        json.timestamp = Instant.ofEpochSecond(1_700_000_000L + height)
        if (txs != null) {
            json.transactions = (0..<txs).collect { new TransactionRefJson(TransactionId.from(hash(id * 1000 + it))) }
        }
        return BlockContainer.from(json)
    }

    static Caches enabledCaches() {
        def config = new CacheConfig()
        config.recentBlocks.enabled = true
        return Caches.newBuilder().setConfig(config).build()
    }

    Caches cachesWithHead(BlockContainer current) {
        def caches = enabledCaches()
        Head head = Mock() {
            _ * getCurrent() >> current
            _ * getCurrentHeight() >> current.height
        }
        caches.setHead(head)
        // a head update, so the head counts as fresh
        caches.cache(Caches.Tag.LATEST, current)
        return caches
    }

    def "Returns the head and its cached parents"() {
        setup:
        def b100 = block(100, 100, 99, 2)
        def b101 = block(101, 101, 100, 1)
        def b102 = block(102, 102, 101, 3)
        def caches = cachesWithHead(b102)
        [b100, b101, b102].each { caches.cache(Caches.Tag.LATEST, it) }

        expect:
        caches.canonicalBlockFromMemory(null, 32)?.hash == b102.hash
        caches.canonicalBlockFromMemory(102, 32)?.hash == b102.hash
        caches.canonicalBlockFromMemory(101, 32)?.hash == b101.hash
        caches.canonicalBlockFromMemory(100, 32)?.hash == b100.hash
        caches.canonicalBlockFromMemory(100, 32).json == b100.json
    }

    def "Misses above the head, below the depth, and below a gap"() {
        setup:
        def b100 = block(100, 100, 99, 2)
        def b102 = block(102, 102, 101, 3) // 101 was never seen
        def caches = cachesWithHead(b102)
        [b100, b102].each { caches.cache(Caches.Tag.LATEST, it) }

        expect:
        caches.canonicalBlockFromMemory(103, 32) == null
        caches.canonicalBlockFromMemory(100, 1) == null
        caches.canonicalBlockFromMemory(100, 32) == null
        caches.canonicalBlockFromMemory(101, 32) == null
    }

    def "Serves empty blocks too"() {
        setup:
        def b100 = block(100, 100, 99, 2)
        def b101 = block(101, 101, 100, 0)
        def b102 = block(102, 102, 101, 1)
        def caches = cachesWithHead(b102)
        [b100, b101, b102].each { caches.cache(Caches.Tag.REQUESTED, it) }

        expect:
        caches.canonicalBlockFromMemory(101, 32)?.hash == b101.hash
        caches.canonicalBlockFromMemory(101, 32).json == b101.json
        caches.canonicalBlockFromMemory(100, 32)?.hash == b100.hash
        caches.isBlockChecked(b101.hash)
    }

    def "Keeps every recent block and prunes old heights"() {
        setup:
        def blocks = (1..300).collect { block(it, it, it - 1, it % 3) }
        def caches = cachesWithHead(blocks.last())
        blocks.each { caches.cache(Caches.Tag.LATEST, it) }

        expect: "all of the last 32 heights, whatever the admission policy of the other caches"
        (268..300).every { caches.canonicalBlockFromMemory(it, 32)?.hash == blocks[it - 1].hash }
        !caches.isBlockChecked(blocks[99].hash)
    }

    def "Nothing is kept when the cache is disabled"() {
        setup:
        def b100 = block(100, 100, 99, 2)
        def caches = Caches.newBuilder().setCacheEnabled(false).build()
        Head head = Mock() {
            _ * getCurrent() >> b100
            _ * getCurrentHeight() >> 100L
        }
        caches.setHead(head)
        caches.cache(Caches.Tag.LATEST, b100)

        expect:
        !caches.isCacheEnabled()
        caches.canonicalBlockFromMemory(100, 32) == null
    }

    def "A block replaced by a reorg is not returned"() {
        setup:
        def b100 = block(100, 100, 99, 1)
        def b101old = block(101, 1101, 100, 1)
        def b101 = block(101, 101, 100, 1)
        def b102 = block(102, 102, 101, 1)
        def caches = cachesWithHead(b102)
        // the orphan was cached last, e.g. requested by hash
        [b100, b101, b102, b101old].each { caches.cache(Caches.Tag.REQUESTED, it) }

        expect:
        caches.canonicalBlockFromMemory(101, 32)?.hash == b101.hash
    }

    def "Non-EVM head blocks are cached without errors"() {
        setup:
        def nonEvm = new BlockContainer(
                10L, io.emeraldpay.dshackle.data.BlockId.from(hash(10)), BigInteger.ONE, Instant.now(), false,
                "{}".bytes, new Object(), io.emeraldpay.dshackle.data.BlockId.from(hash(9)),
        )
        def caches = cachesWithHead(nonEvm)

        when:
        caches.cache(Caches.Tag.LATEST, nonEvm)
        then:
        noExceptionThrown()
        caches.canonicalBlockFromMemory(null, 32) == null
    }

    def "A newHeads header is linked but not served"() {
        setup:
        def b100 = block(100, 100, 99, 1)
        def header101 = block(101, 101, 100, null)
        def caches = cachesWithHead(header101)
        caches.cache(Caches.Tag.REQUESTED, b100)
        caches.cache(Caches.Tag.LATEST, header101)

        expect:
        caches.canonicalBlockFromMemory(null, 32) == null
        caches.canonicalBlockFromMemory(100, 32)?.hash == b100.hash
        !caches.isBlockChecked(header101.hash)

        when: "the body of the head is fetched"
        caches.cache(Caches.Tag.REQUESTED, block(101, 101, 100, 2))
        then:
        caches.isBlockChecked(header101.hash)
        caches.canonicalBlockFromMemory(101, 32)?.hash == header101.hash
    }

    def "Nothing is served while the head is stale, and serving resumes with the next head"() {
        setup:
        long now = 1_000_000L
        def b100 = block(100, 100, 99, 1)
        def b101 = block(101, 101, 100, 1)
        def b102 = block(102, 102, 101, 1)
        def current = b101
        def caches = enabledCaches()
        caches.clock = { now }
        Head head = Mock() {
            _ * getCurrent() >> { current }
            _ * getCurrentHeight() >> { current.height }
        }
        caches.setHead(head)
        caches.cache(Caches.Tag.LATEST, b100)
        now += 1000 // 1 s blocks
        caches.cache(Caches.Tag.LATEST, b101)

        expect: "fresh right after a head"
        caches.isHeadFresh()
        caches.canonicalBlockFromMemory(null, 32)?.hash == b101.hash

        when: "no new head for longer than 3 s (3 intervals, at least 3 s)"
        now += 3_500
        then:
        !caches.isHeadFresh()
        caches.canonicalBlockFromMemory(null, 32) == null
        caches.canonicalBlockFromMemory(100, 32) == null

        when: "the next head arrives"
        current = b102
        caches.cache(Caches.Tag.LATEST, b102)
        then:
        caches.isHeadFresh()
        caches.canonicalBlockFromMemory(null, 32)?.hash == b102.hash
    }

    def "A chain with slow blocks gets a longer window"() {
        setup:
        long now = 1_000_000L
        def caches = enabledCaches()
        caches.clock = { now }
        Head head = Mock() { _ * getCurrent() >> block(102, 102, 101, 1) }
        caches.setHead(head)
        [100, 101, 102].each { h ->
            caches.cache(Caches.Tag.LATEST, block(h, h, h - 1, 1))
            now += 12_000 // 12 s blocks
        }
        now -= 12_000

        expect:
        (now += 30_000) && caches.isHeadFresh()   // 30 s < 3 x 12 s
        (now += 10_000) && !caches.isHeadFresh()  // 40 s > 36 s
    }

    def "No head yet means nothing is served"() {
        setup:
        def caches = enabledCaches()
        Head head = Mock() { _ * getCurrent() >> block(100, 100, 99, 1) }
        caches.setHead(head)

        expect:
        !caches.isHeadFresh()
        caches.canonicalBlockFromMemory(null, 32) == null
    }

    def "The first request for a recent block is reported once, whether it was in memory or not"() {
        setup:
        def b100 = block(100, 100, 99, 1)
        def header101 = block(101, 101, 100, null)   // a newHeads header: linked, no body
        def caches = cachesWithHead(header101)
        caches.cache(Caches.Tag.REQUESTED, b100)

        expect:
        caches.firstRequest(100, 32) == true      // first request for 100 (it has a body: a hit)
        caches.firstRequest(100, 32) == false     // later requests aren't first
        caches.firstRequest(101, 32) == true      // first request for 101 (no body yet: a miss)

        when: "the body of 101 arrives later"
        caches.cache(Caches.Tag.REQUESTED, block(101, 101, 100, 2))
        then: "it isn't reported as first again"
        caches.firstRequest(101, 32) == false
        caches.canonicalBlockFromMemory(101, 32) != null
        caches.firstRequest(200, 32) == null      // unknown height
    }

    def "Bodies aren't kept when recent blocks are off, the links still are"() {
        setup:
        def b100 = block(100, 100, 99, 1)
        def b101 = block(101, 101, 100, 1)
        def caches = Caches.default()
        Head head = Mock() {
            _ * getCurrent() >> b101
            _ * getCurrentHeight() >> 101L
        }
        caches.setHead(head)
        [b100, b101].each { caches.cache(Caches.Tag.LATEST, it) }

        expect:
        !caches.recentBlocksEnabled()
        caches.canonicalBlockFromMemory(100, 32) == null
        caches.canonicalHash(100, 32) == b100.hash
    }

    def "A block far above the head isn't recorded"() {
        setup:
        def b100 = block(100, 100, 99, 1)
        def caches = cachesWithHead(b100)

        when: "a client asked for some block by hash that claims a huge height"
        caches.cache(Caches.Tag.REQUESTED, block(1_000_000, 555, 554, 1))
        then:
        !caches.isBlockChecked(BlockId.from(hash(555)))
        caches.canonicalBlockFromMemory(100, 32)?.hash == b100.hash
    }
}
