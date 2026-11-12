package io.emeraldpay.dshackle.rpc

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Metrics
import reactor.core.publisher.Mono
import java.util.concurrent.ConcurrentHashMap

/**
 * Coalesces identical in-flight requests: the first caller of a key (the leader) runs the request, callers that arrive
 * while it is still running (followers) get the same result, or error, instead of calling an upstream again.
 * Nothing is kept once the request has finished: this is not a cache.
 *
 * The shared Mono is cached, so a leader whose client goes away doesn't cancel the request for the followers.
 */
class SingleFlight<T : Any> {

    private val inflight = ConcurrentHashMap<String, Mono<T>>()

    /**
     * @return the shared result, and true when this caller is the leader (it started the request)
     */
    fun run(key: String, supplier: () -> Mono<T>): Pair<Mono<T>, Boolean> {
        inflight[key]?.let { return it to false }
        lateinit var shared: Mono<T>
        shared = Mono.defer(supplier)
            .doFinally { inflight.remove(key, shared) }
            .cache()
        val existing = inflight.putIfAbsent(key, shared)
        return if (existing != null) existing to false else shared to true
    }

    fun size(): Int = inflight.size

    companion object {
        private val counters = ConcurrentHashMap<String, Counter>()

        /** a request of a coalescible method: "eligible", or why it can't be shared (stream, sort, params, pending) */
        fun countChecked(chain: String, result: String) {
            counters.computeIfAbsent("checked|$chain|$result") {
                Counter.builder("request.coalesce.checked")
                    .description("Requests of coalescible methods, which are eligible to share an in-flight request or not, with a reason")
                    .tag("chain", chain)
                    .tag("result", result)
                    .register(Metrics.globalRegistry)
            }.increment()
        }

        fun countCoalesced(chain: String, method: String) {
            counters.computeIfAbsent("$chain|$method") {
                Counter.builder("request.coalesced")
                    .description("Requests answered with the result of an identical request that was already in flight")
                    .tag("chain", chain)
                    .tag("method", method)
                    .register(Metrics.globalRegistry)
            }.increment()
        }
    }
}
