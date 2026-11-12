package io.emeraldpay.dshackle.cache

import io.emeraldpay.dshackle.data.BlockId
import io.emeraldpay.dshackle.upstream.Head
import spock.lang.Specification

import static io.emeraldpay.dshackle.cache.CachesBlockByNumberSpec.block
import static io.emeraldpay.dshackle.cache.CachesBlockByNumberSpec.hash

class CachesReorgSpec extends Specification {

    def "A block requested by a client doesn't take over its height"() {
        setup:
        def heights = new HeightCache()
        def caches = Caches.newBuilder().setBlockByHeight(heights).build()
        def canonical = block(100, 100, 99, 1)
        def uncle = block(100, 1100, 99, 1)

        when:
        caches.cache(Caches.Tag.LATEST, canonical)
        caches.cache(Caches.Tag.REQUESTED, uncle)

        then:
        heights.get(100) == canonical.hash
    }

    def "A deep reorg re-maps every height down to the common ancestor"() {
        setup:
        def heights = new HeightCache()
        def caches = Caches.newBuilder().setBlockByHeight(heights).build()
        Head head = Mock() { _ * getCurrentHeight() >> 103L }
        caches.setHead(head)
        // old chain 100 <- 101 <- 102, new chain 100 <- 1101 <- 1102 <- 1103
        [block(100, 100, 99, 1), block(101, 101, 100, 1), block(102, 102, 101, 1)].each { caches.cache(Caches.Tag.LATEST, it) }
        // the new side blocks were seen (e.g. fetched) before the new tip arrives
        [block(101, 1101, 100, 1), block(102, 1102, 1101, 1)].each { caches.cache(Caches.Tag.REQUESTED, it) }

        when:
        caches.cache(Caches.Tag.LATEST, block(103, 1103, 1102, 1))

        then:
        heights.get(103) == BlockId.from(hash(1103))
        heights.get(102) == BlockId.from(hash(1102))
        heights.get(101) == BlockId.from(hash(1101))
        heights.get(100) == BlockId.from(hash(100))
    }
}
