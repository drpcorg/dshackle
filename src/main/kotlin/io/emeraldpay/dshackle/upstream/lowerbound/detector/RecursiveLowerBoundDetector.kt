package io.emeraldpay.dshackle.upstream.lowerbound.detector

import io.emeraldpay.dshackle.upstream.Upstream
import io.emeraldpay.dshackle.upstream.lowerbound.LowerBoundData
import io.emeraldpay.dshackle.upstream.lowerbound.LowerBoundDetector
import io.emeraldpay.dshackle.upstream.lowerbound.LowerBoundType
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toFlux
import reactor.util.retry.Retry
import reactor.util.retry.RetryBackoffSpec
import java.time.Duration

abstract class RecursiveLowerBoundDetector(
    private val upstream: Upstream,
) : LowerBoundDetector() {

    override fun period(): Long {
        return 5
    }

    override fun internalDetectLowerBound(): Flux<LowerBoundData> {
        return Mono.just(upstream.getHead())
            .flatMap {
                val currentHeight = it.getCurrentHeight()
                if (currentHeight == null) {
                    Mono.empty()
                } else {
                    Mono.just(LowerBoundBinarySearch(0, currentHeight))
                }
            }
            .expand { data ->
                if (data.found) {
                    Mono.empty()
                } else {
                    val middle = middleBlock(data)

                    if (data.left > data.right) {
                        val current = if (data.current == 0L) 1 else data.current
                        Mono.just(LowerBoundBinarySearch(current, true))
                    } else {
                        hasData(middle)
                            .map {
                                if (it) {
                                    LowerBoundBinarySearch(data.left, middle - 1, middle)
                                } else {
                                    LowerBoundBinarySearch(
                                        middle + 1,
                                        data.right,
                                        data.current,
                                    )
                                }
                            }
                    }
                }
            }
            .filter { it.found }
            .next()
            .map {
                LowerBoundData(it.current, type())
            }.toFlux()
    }

    private fun middleBlock(lowerBoundBinarySearch: LowerBoundBinarySearch): Long =
        lowerBoundBinarySearch.left + (lowerBoundBinarySearch.right - lowerBoundBinarySearch.left) / 2

    protected fun retrySpec(nonRetryableErrors: Set<String>): RetryBackoffSpec {
        return Retry.backoff(
            Long.MAX_VALUE,
            Duration.ofSeconds(1),
        )
            .maxBackoff(Duration.ofMinutes(3))
            .filter {
                !nonRetryableErrors.any { err -> it.message?.contains(err, true) ?: false }
            }
            .doAfterRetry {
                log.debug(
                    "Error in calculation of lower block of upstream {}, retry attempt - {}, message - {}",
                    upstream.getId(),
                    it.totalRetries(),
                    it.failure().message,
                )
            }
    }

    protected abstract fun hasData(block: Long): Mono<Boolean>

    protected abstract fun type(): LowerBoundType

    private data class LowerBoundBinarySearch(
        val left: Long,
        val right: Long,
        val current: Long,
        val found: Boolean,
    ) {
        constructor(left: Long, right: Long) : this(left, right, 0, false)

        constructor(left: Long, right: Long, current: Long) : this(left, right, current, false)

        constructor(current: Long, found: Boolean) : this(0, 0, current, found)
    }
}
