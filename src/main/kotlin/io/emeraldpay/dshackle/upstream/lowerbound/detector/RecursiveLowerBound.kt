package io.emeraldpay.dshackle.upstream.lowerbound.detector

import io.emeraldpay.dshackle.upstream.ChainResponse
import io.emeraldpay.dshackle.upstream.Upstream
import io.emeraldpay.dshackle.upstream.lowerbound.LowerBoundData
import io.emeraldpay.dshackle.upstream.lowerbound.LowerBoundType
import io.emeraldpay.dshackle.upstream.lowerbound.LowerBounds
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toFlux
import reactor.util.retry.Retry
import reactor.util.retry.RetryBackoffSpec
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

open class RecursiveLowerBound(
    protected val upstream: Upstream,
    protected val type: LowerBoundType,
    protected val nonRetryableErrors: Set<String>,
    protected val lowerBounds: LowerBounds,
    protected val nonRetryableErrorPatters: Set<Regex> = setOf(),
) {
    protected val log = LoggerFactory.getLogger(this::class.java)

    fun recursiveDetectLowerBound(hasData: (Long) -> Mono<ChainResponse>): Flux<LowerBoundData> {
        return initialRange()
            .expand { data ->
                if (data.found) {
                    Mono.empty()
                } else {
                    val middle = middleBlock(data)

                    if (data.left > data.right) {
                        val current = if (data.current == 0L) 1 else data.current
                        hasData(current)
                            .retryWhen(retrySpec(middle, nonRetryableErrors))
                            .flatMap(ChainResponse::requireResult)
                            .map { LowerBoundBinarySearchData(current, true) }
                            .onErrorResume {
                                if (current == 1L && data.right > 10) {
                                    Mono.empty() // Couldn't detect bound: data.right is chain height here and current wasn't set because we got errors all the time
                                } else {
                                    Mono.just(LowerBoundBinarySearchData(current, true)) // if we approached left bound(1) or node have just pruned data.current due to long bound calculation, return data.current as is
                                }
                            }
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
        return Mono.justOrEmpty(lowerBounds.getLastBound(type)?.lowerBound)
            .flatMapMany { bound ->
                // at first, we try to check the current bound to prevent huge calculations
                hasData(bound!!)
                    .retryWhen(retrySpec(bound, nonRetryableErrors))
                    .flatMap(ChainResponse::requireResult)
                    .map { LowerBoundData(bound, type) }
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
                                hasData(current)
                                    .retryWhen(retrySpec(middle, nonRetryableErrors))
                                    .flatMap(ChainResponse::requireResult)
                                    .map { LowerBoundBinarySearchData(current, true) }
                                    .onErrorResume {
                                        if (current == 1L && data.right > 10) {
                                            Mono.empty() // Couldn't detect bound: data.right is chain height here and current wasn't set because we got errors all the time
                                        } else {
                                            Mono.just(LowerBoundBinarySearchData(current, true)) // if we approached left bound(1) or node have just pruned data.current due to long bound calculation, return data.current as is
                                        }
                                    }
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

    /**
     * The start of the contiguous data range that ends at the head: the lowest block b such
     * that every probe in [b, head] found data. It gallops down from the head (steps 1024, 2048,
     * ...) until a probe misses, then bisects between the miss and the last hit.
     *
     * Unlike [recursiveDetectLowerBoundWithOffset] it never trusts a single old block: a node that
     * keeps early history plus a recent window (reth with pruned receipts) has a gap in between,
     * and the binary search from genesis or the check of the previous bound lands in the early
     * part and reports 1. Here the gap stops the gallop, so the bound is the window's start.
     *
     * A probe that fails with [emptyBlockError] says nothing about the data (the block has no
     * transactions to check), so it moves up to [maxLimit] blocks left; a run of only empty
     * blocks counts as a hit.
     */
    fun detectContiguousLowerBound(
        maxLimit: Int,
        emptyBlockError: String,
        hasData: (Long) -> Mono<ChainResponse>,
    ): Flux<LowerBoundData> {
        val head = upstream.getHead().getCurrentHeight() ?: return Flux.empty()

        // the block at or just left of [block] where data was found; empty when there is none
        fun probe(block: Long): Mono<Long> {
            fun at(current: Long, left: Int): Mono<Long> {
                if (current < 1) {
                    return Mono.just(block)
                }
                return hasData(current)
                    .retryWhen(retrySpec(current, nonRetryableErrors))
                    .flatMap(ChainResponse::requireResult)
                    .map { current }
                    .onErrorResume { err ->
                        if (err.message?.contains(emptyBlockError) == true) {
                            if (left > 0) at(current - 1, left - 1) else Mono.just(block)
                        } else {
                            Mono.empty()
                        }
                    }
            }
            return at(block, maxLimit)
        }

        // invariant: no data at [missing], data at [good]
        fun bisect(missing: Long, good: Long): Mono<Long> {
            if (good - missing <= 1) {
                return Mono.just(good)
            }
            val middle = missing + (good - missing) / 2
            return probe(middle)
                .filter { it > missing }
                .flatMap { bisect(missing, it) }
                .switchIfEmpty(Mono.defer { bisect(middle, good) })
        }

        fun gallop(good: Long, step: Long): Mono<Long> {
            if (good <= 1) {
                return Mono.just(1)
            }
            val next = maxOf(1, good - step)
            return probe(next)
                .flatMap { gallop(it, step * 2) }
                .switchIfEmpty(Mono.defer { bisect(next, good) })
        }

        return gallop(head, INITIAL_STEP)
            .map { LowerBoundData(it, type) }
            .toFlux()
    }

    protected fun shiftLeftAndSearch(
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

    protected open fun initialRange(): Mono<LowerBoundBinarySearchData> {
        return Mono.just(upstream.getHead())
            .flatMap {
                val currentHeight = it.getCurrentHeight()
                if (currentHeight == null) {
                    Mono.empty()
                } else if (lowerBounds.getLastBound(type) == null) {
                    Mono.just(LowerBoundBinarySearchData(0, currentHeight))
                } else {
                    // next calculations will be carried out only within the last range
                    Mono.just(LowerBoundBinarySearchData(lowerBounds.getLastBound(type)!!.lowerBound, currentHeight))
                }
            }
    }

    protected fun retrySpec(block: Long, nonRetryableErrors: Set<String>): RetryBackoffSpec {
        return Retry.backoff(
            MAX_RETRIES,
            Duration.ofSeconds(1),
        )
            .maxBackoff(Duration.ofMinutes(3))
            .filter {
                !nonRetryableErrors.any { err -> it.message?.contains(err, true) ?: false } &&
                    !nonRetryableErrorPatters.any { err -> it.message?.matches(err) ?: false }
            }
            .doAfterRetry {
                if (it.totalRetries() == MAX_RETRIES - 1) {
                    log.warn(
                        "There are too much retries to calculate {} lower bound of upstream {}, block {} " +
                            "probably this error with message `{}` is not retryable, please report it to dshackle devs",
                        type,
                        upstream.getId(),
                        block,
                        it.failure().message,
                    )
                } else {
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
    }

    protected fun middleBlock(lowerBoundBinarySearchData: LowerBoundBinarySearchData): Long =
        lowerBoundBinarySearchData.left + (lowerBoundBinarySearchData.right - lowerBoundBinarySearchData.left) / 2

    protected data class LowerBoundBinarySearchData(
        val left: Long,
        val right: Long,
        val current: Long,
        val found: Boolean,
    ) {
        constructor(left: Long, right: Long) : this(left, right, 0, false)

        constructor(left: Long, right: Long, current: Long) : this(left, right, current, false)

        constructor(current: Long, found: Boolean) : this(0, 0, current, found)
    }

    companion object {
        // ponytail: ~70 min per block at max backoff, then the block counts as "no data"
        private const val MAX_RETRIES = 30L

        // first gallop step below the head, doubled after every hit
        private const val INITIAL_STEP = 1024L
    }
}
