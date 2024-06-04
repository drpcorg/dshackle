package io.emeraldpay.dshackle.upstream.lowerbound.detector

import io.emeraldpay.dshackle.upstream.ChainResponse
import io.emeraldpay.dshackle.upstream.Upstream
import io.emeraldpay.dshackle.upstream.lowerbound.LowerBoundData
import io.emeraldpay.dshackle.upstream.lowerbound.LowerBoundType
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toFlux
import reactor.util.retry.Retry
import reactor.util.retry.RetryBackoffSpec
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

class RecursiveLowerBound(
    private val upstream: Upstream,
    private val type: LowerBoundType,
    private val nonRetryableErrors: Set<String>,
    private val lowerBounds: Map<LowerBoundType, LowerBoundData>,
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    fun recursiveDetectLowerBound(hasData: (Long) -> Mono<ChainResponse>): Flux<LowerBoundData> {
        return initialRange()
            .expand { data ->
                if (data.found) {
                    Mono.empty()
                } else {
                    val middle = middleBlock(data)

                    if (data.left > data.right) {
                        val current = if (data.current == 0L) 1 else data.current
                        Mono.just(LowerBoundBinarySearchData(current, true))
                    } else {
                        hasData(middle)
                            .retryWhen(retrySpec(middle, nonRetryableErrors))
                            .flatMap(ChainResponse::requireResult)
                            .map { LowerBoundBinarySearchData(data.left, middle - 1, middle) }
                            .onErrorReturn(
                                LowerBoundBinarySearchData(
                                    middle + 1,
                                    data.right,
                                    data.current,
                                ),
                            )
                    }
                }
            }
            .filter { it.found }
            .next()
            .map {
                LowerBoundData(it.current, type)
            }.toFlux()
    }

    fun recursiveDetectLowerBoundWithOffset(maxLimit: Int, hasData: (Long) -> Mono<ChainResponse>): Flux<LowerBoundData> {
        val visitedBlocks = HashSet<Long>()
        return Mono.justOrEmpty(lowerBounds[type]?.lowerBound)
            .flatMapMany {
                // at first, we try to check the current bound to prevent huge calculations
                hasData(it!!)
                    .retryWhen(retrySpec(it, nonRetryableErrors))
                    .flatMap(ChainResponse::requireResult)
                    .map { LowerBoundData(lowerBounds[type]!!.lowerBound, type) }
                    .onErrorResume { Mono.empty() }
            }.switchIfEmpty(
                initialRange()
                    .expand { data ->
                        if (data.found) {
                            Mono.empty()
                        } else {
                            val middle = middleBlock(data)

                            if (data.left > data.right) {
                                val current = if (data.current == 0L) 1 else data.current
                                Mono.just(LowerBoundBinarySearchData(current, true))
                            } else {
                                hasData(middle)
                                    .retryWhen(retrySpec(middle, nonRetryableErrors))
                                    .flatMap(ChainResponse::requireResult)
                                    .map { LowerBoundBinarySearchData(data.left, middle - 1, middle) }
                                    .onErrorResume {
                                        if (middle < 0) {
                                            Mono.just(LowerBoundBinarySearchData(middle + 1, data.right, data.current))
                                        } else {
                                            shiftLeftAndSearch(data, middle, visitedBlocks, maxLimit, hasData)
                                        }
                                    }
                            }
                        }
                    }
                    .filter { it.found }
                    .next()
                    .map {
                        LowerBoundData(it.current, type)
                    }.toFlux(),
            )
    }

    private fun shiftLeftAndSearch(
        currentData: LowerBoundBinarySearchData,
        currentMiddle: Long,
        visitedBlocks: HashSet<Long>,
        maxLimit: Int,
        hasData: (Long) -> Mono<ChainResponse>,
    ): Mono<LowerBoundBinarySearchData> {
        val count = AtomicInteger(0)
        return Mono.just(LowerBoundBinarySearchData(currentMiddle - 1, false))
            .expand { currentBlock ->
                if (currentBlock.found) {
                    // to avoid extra handling
                    Mono.empty()
                } else {
                    if (visitedBlocks.contains(currentBlock.current) || currentBlock.current < 0) {
                        // if this block has been already seen there is no need to check it again
                        Mono.just(LowerBoundBinarySearchData(currentMiddle + 1, currentData.right, currentData.current, true))
                    } else {
                        hasData(currentBlock.current)
                            .retryWhen(retrySpec(currentBlock.current, nonRetryableErrors))
                            .flatMap(ChainResponse::requireResult)
                            .map {
                                // we found data at once and return it
                                LowerBoundBinarySearchData(currentData.left, currentBlock.current - 1, currentBlock.current, true)
                            }
                            .onErrorResume {
                                // otherwise we go the left until we reach the specified limit
                                count.incrementAndGet()
                                if (count.get() in 1..maxLimit) {
                                    visitedBlocks.add(currentBlock.current)
                                    Mono.just(LowerBoundBinarySearchData(currentBlock.current - 1, false))
                                } else {
                                    Mono.just(LowerBoundBinarySearchData(currentMiddle + 1, currentData.right, currentData.current, true))
                                }
                            }
                    }
                }
            }
            .filter { it.found }
            .next()
            .map {
                // in terms of the whole calculation we haven't found the bound
                LowerBoundBinarySearchData(it.left, it.right, it.current)
            }
    }

    private fun initialRange(): Mono<LowerBoundBinarySearchData> {
        return Mono.just(upstream.getHead())
            .flatMap {
                val currentHeight = it.getCurrentHeight()
                if (currentHeight == null) {
                    Mono.empty()
                } else if (!lowerBounds.contains(type)) {
                    Mono.just(LowerBoundBinarySearchData(0, currentHeight))
                } else {
                    // next calculations will be carried out only within the last range
                    Mono.just(LowerBoundBinarySearchData(lowerBounds[type]!!.lowerBound, currentHeight))
                }
            }
    }

    private fun retrySpec(block: Long, nonRetryableErrors: Set<String>): RetryBackoffSpec {
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
                    "Error in calculation of lower block {} of upstream {}, type - {}, retry attempt - {}, message - {}",
                    block,
                    upstream.getId(),
                    type,
                    it.totalRetries(),
                    it.failure().message,
                )
            }
    }

    private fun middleBlock(lowerBoundBinarySearchData: LowerBoundBinarySearchData): Long =
        lowerBoundBinarySearchData.left + (lowerBoundBinarySearchData.right - lowerBoundBinarySearchData.left) / 2

    private data class LowerBoundBinarySearchData(
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
