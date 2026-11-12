package io.emeraldpay.dshackle.cache

import com.github.benmanes.caffeine.cache.Caffeine
import io.emeraldpay.dshackle.Chain
import io.lettuce.core.api.reactive.RedisReactiveCommands
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono
import java.security.MessageDigest
import java.time.Duration

/**
 * Raw JSON-RPC results of requests whose answer can't change, keyed by a digest of
 * method + block (by hash, or by number at or below the finalized block) + the other params.
 * What may be stored is decided by [io.emeraldpay.dshackle.upstream.ethereum.EthereumCachingReader]; this is just
 * the storage: memory bounded by bytes, and optionally Redis for entries at or below the finalized block (durable),
 * which survive a restart.
 */
open class ResponseCache(
    maxBytes: Long,
    private val chain: Chain,
) {
    companion object {
        private val log = LoggerFactory.getLogger(ResponseCache::class.java)

        // a single response bigger than this isn't kept (a full block of a busy chain is ~0.5-2 MB)
        const val MAX_ENTRY_BYTES = 4 * 1024 * 1024

        const val REDIS_TTL_SECONDS = 3600L

        // entries of blocks that got reorged out are unreachable (their key has the block hash), this drops them
        val MEMORY_TTL: Duration = Duration.ofMinutes(10)

        fun digest(key: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }

    private val memory = Caffeine.newBuilder()
        .maximumWeight(maxBytes)
        .expireAfterWrite(MEMORY_TTL)
        .weigher<String, ByteArray> { k, v -> k.length + v.size }
        .build<String, ByteArray>()

    @Volatile
    var redis: RedisReactiveCommands<String, ByteArray>? = null

    private fun redisKey(key: String) = "resp:${chain.chainCode}:$key"

    open fun get(key: String, durable: Boolean): Mono<ByteArray> {
        memory.getIfPresent(key)?.let { return Mono.just(it) }
        val r = redis
        if (!durable || r == null) {
            return Mono.empty()
        }
        return r.get(redisKey(key))
            .doOnNext { memory.put(key, it) }
            .onErrorResume {
                log.debug("Redis read failed: {}", it.message)
                Mono.empty()
            }
    }

    open fun put(key: String, value: ByteArray, durable: Boolean) {
        if (value.size > MAX_ENTRY_BYTES) {
            return
        }
        memory.put(key, value)
        if (durable) {
            redis?.setex(redisKey(key), REDIS_TTL_SECONDS, value)
                ?.subscribe(null) { log.debug("Redis write failed: {}", it.message) }
        }
    }

    fun memoryEntries(): Long = memory.estimatedSize()
}
