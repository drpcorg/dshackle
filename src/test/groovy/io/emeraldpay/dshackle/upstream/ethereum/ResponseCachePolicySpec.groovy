package io.emeraldpay.dshackle.upstream.ethereum

import io.emeraldpay.dshackle.data.BlockId
import spock.lang.Specification

class ResponseCachePolicySpec extends Specification {

    static String hash(int n) { "0x" + String.format("%064x", n) }

    static String hex(long n) { "0x" + Long.toHexString(n) }

    // head 1000, recent blocks 940..1000 resolvable (block n has hash(n)), finalized 900
    ResponsePolicyFixture fixture = new ResponsePolicyFixture(1000, 900)
    ResponseCachePolicy policy = fixture.policy()

    def "A recent block by number is keyed by its canonical hash"() {
        when:
        def key = policy.keyFor("eth_getBlockByNumber", [hex(995), true])
        then:
        key.hash == BlockId.from(hash(995))
        key.height == 995
        !key.durable
        !key.allowEmpty   // not finalized: an empty answer isn't stored
    }

    def "latest is keyed by the head hash"() {
        when:
        def key = policy.keyFor("eth_getBlockReceipts", ["latest"])
        then:
        key.hash == BlockId.from(hash(1000))
        !key.allowEmpty
    }

    def "A reorged-out block gets a different key than the canonical one"() {
        when:
        def before = policy.keyFor("eth_getBlockByNumber", [hex(999), true])
        fixture.reorg(999, 77999)
        def after = policy.keyFor("eth_getBlockByNumber", [hex(999), true])
        then:
        before.key != after.key
        after.hash == BlockId.from(hash(77999))
    }

    def "A deep block is cached by number only when finalized"() {
        expect:
        policy.keyFor("eth_getBlockByNumber", [hex(800), true]).durable
        policy.keyFor("eth_getBlockByNumber", [hex(800), true]).hash == null
        policy.keyFor("eth_getBlockByNumber", [hex(920), true]) == null   // too deep for recent, not finalized
    }

    def "Tags and unknown blocks are never cached"() {
        expect:
        policy.keyFor("eth_getBlockByNumber", [tag, true]) == null
        where:
        tag << ["finalized", "safe", "pending", "earliest", hex(1001)]
    }

    def "Nothing is cached while the head is stale"() {
        when:
        fixture.stale = true
        then:
        policy.keyFor("eth_getBlockByNumber", [hex(995), true]) == null
        policy.keyFor("eth_getBlockReceipts", ["latest"]) == null
        // by hash it's still fine: a block hash names one block forever
        policy.keyFor("eth_getBlockByHash", [hash(12345), true]) != null
    }

    def "getBlockByHash: only full blocks (the light ones have their own cache)"() {
        expect:
        policy.keyFor("eth_getBlockByHash", [hash(995), true]) != null
        policy.keyFor("eth_getBlockByHash", [hash(995), false]) == null
    }

    def "getLogs: a single block, by blockHash or from == to"() {
        expect:
        policy.keyFor("eth_getLogs", [[blockHash: hash(995), address: "0xabc"]]) != null
        policy.keyFor("eth_getLogs", [[fromBlock: hex(995), toBlock: hex(995), topics: ["0x01"]]]) != null
        policy.keyFor("eth_getLogs", [[fromBlock: hex(990), toBlock: hex(995)]]) == null
        policy.keyFor("eth_getLogs", [[fromBlock: "latest", toBlock: "latest"]]) == null
        policy.keyFor("eth_getLogs", [[blockHash: hash(995), fromBlock: hex(995)]]) == null
    }

    def "getLogs by number and by blockHash of the same block share the key only through the hash"() {
        when:
        def byNumber = policy.keyFor("eth_getLogs", [[fromBlock: hex(995), toBlock: hex(995), address: "0xabc"]])
        def byHash = policy.keyFor("eth_getLogs", [[blockHash: hash(995), address: "0xabc"]])
        then:
        byNumber.key == byHash.key
    }

    def "State reads by number only when finalized; by hash always"() {
        expect:
        policy.keyFor("eth_call", [[to: "0x1", data: "0x2"], hex(900)]) != null       // finalized
        policy.keyFor("eth_call", [[to: "0x1", data: "0x2"], hex(901)]) == null       // deep, not finalized
        policy.keyFor("eth_call", [[to: "0x1", data: "0x2"], hex(998)]) == null       // recent: another fork could answer
        policy.keyFor("eth_call", [[to: "0x1", data: "0x2"], "latest"]) == null
        policy.keyFor("eth_call", [[to: "0x1", data: "0x2"], [blockHash: hash(1000)]]) != null
        policy.keyFor("eth_call", [[to: "0x1", data: "0x2"], [blockHash: hash(1000), requireCanonical: true]]) == null
        policy.keyFor("eth_getBalance", ["0xabc", hex(800)]) != null
        policy.keyFor("eth_getStorageAt", ["0xabc", "0x0", hex(800)]) != null
        policy.keyFor("eth_getTransactionCount", ["0xabc", "pending"]) == null
    }

    def "Different calls get different keys"() {
        expect:
        policy.keyFor("eth_call", [[to: "0x1", data: "0x2"], hex(800)]).key != policy.keyFor("eth_call", [[to: "0x1", data: "0x3"], hex(800)]).key
    }

    def "A stored block must be the expected one"() {
        setup:
        def key = policy.keyFor("eth_getBlockByNumber", [hex(995), true])

        expect:
        policy.accepts(key, """{"hash":"${hash(995)}","number":"${hex(995)}"}""".bytes)
        !policy.accepts(key, """{"hash":"${hash(77995)}","number":"${hex(995)}"}""".bytes)
        !policy.accepts(key, "null".bytes)
        !policy.accepts(key, "".bytes)
    }

    def "A finalized block by number must have that number"() {
        setup:
        def key = policy.keyFor("eth_getBlockByNumber", [hex(800), false])

        expect:
        policy.accepts(key, """{"hash":"${hash(800)}","number":"${hex(800)}"}""".bytes)
        !policy.accepts(key, """{"hash":"${hash(801)}","number":"${hex(801)}"}""".bytes)
    }

    def "Logs must all belong to the block; an empty list only for a finalized block"() {
        setup:
        def recent = policy.keyFor("eth_getLogs", [[blockHash: hash(990)]])
        def head = policy.keyFor("eth_getLogs", [[fromBlock: hex(1000), toBlock: hex(1000)]])
        def finalized = policy.keyFor("eth_getLogs", [[fromBlock: hex(800), toBlock: hex(800)]])

        expect:
        policy.accepts(recent, """[{"blockHash":"${hash(990)}"}]""".bytes)
        !policy.accepts(recent, """[{"blockHash":"${hash(990)}"},{"blockHash":"${hash(991)}"}]""".bytes)
        !policy.accepts(recent, "[]".bytes)
        !policy.accepts(head, "[]".bytes)
        policy.accepts(head, """[{"blockHash":"${hash(1000)}"}]""".bytes)
        policy.accepts(finalized, "[]".bytes)
        policy.accepts(finalized, """[{"blockNumber":"${hex(800)}"}]""".bytes)
        !policy.accepts(finalized, """[{"blockNumber":"${hex(801)}"}]""".bytes)
    }

    def "A finalized height at the head isn't trusted: only 2 below a fresh head counts"() {
        setup:
        def fixture = new ResponsePolicyFixture(1000, 1000)   // an upstream reports finalized = latest
        def policy = fixture.policy()

        expect:
        policy.keyFor("eth_call", [[to: "0x1"], hex(999)]) == null
        policy.keyFor("eth_call", [[to: "0x1"], hex(998)]) != null
        !policy.keyFor("eth_getBlockByNumber", [hex(999), true]).durable
        policy.keyFor("eth_getBlockByNumber", [hex(998), true]).durable
    }

    def "Other methods are never cached"() {
        expect:
        policy.keyFor(method, params) == null
        where:
        method                      | params
        "eth_sendRawTransaction"    | ["0x00"]
        "eth_getTransactionReceipt" | [hash(1)]
        "eth_blockNumber"           | []
    }
}

class ResponsePolicyFixture {
    long head
    long finalized
    boolean stale = false
    Map<Long, String> canonical = [:]

    ResponsePolicyFixture(long head, long finalized) {
        this.head = head
        this.finalized = finalized
        (head - 60..head).each { canonical[it] = ResponseCachePolicySpec.hash((int) it) }
    }

    void reorg(long height, int newId) {
        canonical[height] = ResponseCachePolicySpec.hash(newId)
    }

    ResponseCachePolicy policy() {
        new ResponseCachePolicy(
            { Long h ->
                if (stale) return null
                def at = h == null ? head : h
                canonical[at] ? BlockId.from(canonical[at]) : null
            },
            { BlockId id -> canonical.find { k, v -> BlockId.from(v) == id }?.key },
            { stale ? null : head },
            { finalized },
        )
    }
}
