package io.emeraldpay.dshackle.cache

import io.emeraldpay.dshackle.Chain
import io.lettuce.core.api.reactive.RedisReactiveCommands
import reactor.core.publisher.Mono
import spock.lang.Specification

import java.time.Duration

class ResponseCacheSpec extends Specification {

    def "Keeps and returns a value"() {
        setup:
        def cache = new ResponseCache(1024 * 1024, Chain.ETHEREUM__MAINNET)

        when:
        cache.put("k", "v".bytes, false)
        then:
        new String(cache.get("k", false).block(Duration.ofSeconds(1))) == "v"
        cache.get("other", false).block(Duration.ofSeconds(1)) == null
    }

    def "A value over the entry limit isn't kept"() {
        setup:
        def cache = new ResponseCache(64L * 1024 * 1024, Chain.ETHEREUM__MAINNET)

        when:
        cache.put("big", new byte[ResponseCache.MAX_ENTRY_BYTES + 1], false)
        then:
        cache.get("big", false).block(Duration.ofSeconds(1)) == null
    }

    def "Only durable entries go to and come from Redis"() {
        setup:
        def redis = Mock(RedisReactiveCommands)
        def cache = new ResponseCache(1024 * 1024, Chain.ETHEREUM__MAINNET)
        cache.redis = redis

        when:
        cache.put("recent", "a".bytes, false)
        cache.put("final", "b".bytes, true)
        then:
        1 * redis.setex("resp:ETH:final", ResponseCache.REDIS_TTL_SECONDS, _) >> Mono.just("OK")
        0 * redis.setex("resp:ETH:recent", _, _)

        when:
        def miss = cache.get("unknown-recent", false).block(Duration.ofSeconds(1))
        def hit = cache.get("unknown-final", true).block(Duration.ofSeconds(1))
        then:
        1 * redis.get("resp:ETH:unknown-final") >> Mono.just("c".bytes)
        0 * redis.get("resp:ETH:unknown-recent")
        miss == null
        new String(hit) == "c"
    }

    def "A Redis error is a miss"() {
        setup:
        def redis = Mock(RedisReactiveCommands) {
            _ * get(_) >> Mono.error(new RuntimeException("down"))
        }
        def cache = new ResponseCache(1024 * 1024, Chain.ETHEREUM__MAINNET)
        cache.redis = redis

        expect:
        cache.get("k", true).block(Duration.ofSeconds(1)) == null
    }
}
