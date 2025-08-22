package io.emeraldpay.dshackle.upstream.ethereum

import io.emeraldpay.dshackle.ThrottledLogger
import io.emeraldpay.dshackle.upstream.Head
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import reactor.core.scheduler.Scheduler
import java.time.Duration
import java.time.Instant

class HeadLivenessValidatorImpl(
    private val head: Head,
    private val expectedBlockTime: Duration,
    private val scheduler: Scheduler,
    private val upstreamId: String,
) : HeadLivenessValidator {
    companion object {
        const val CHECKED_BLOCKS_UNTIL_LIVE = 3
        const val COOLDOWN_MINUTES = 5L
        const val TIMEOUT_MULTIPLIER = 2L
        const val MAX_BLOCK_TIME_MINUTES = 10L
        private val log = LoggerFactory.getLogger(HeadLivenessValidatorImpl::class.java)
    }

    @Volatile
    private var lastNonConsecutiveTime: Instant? = null

    @Volatile
    private var measuredBlockTime: Duration = expectedBlockTime

    @Volatile
    private var lastBlockTimestamp: Instant? = null

    override fun getFlux(): Flux<HeadLivenessState> {
        val headLiveness = head.headLiveness()
        // first we have moving window of 2 blocks and check that they are consecutive ones
        val headFlux = head.getFlux().doOnNext { _ ->
            // Measure actual block time
            val now = Instant.now()
            val previous = lastBlockTimestamp
            lastBlockTimestamp = now

            if (previous != null) {
                val actualInterval = Duration.between(previous, now)
                // Always take the larger value to avoid timeouts
                if (actualInterval > measuredBlockTime) {
                    measuredBlockTime = actualInterval
                    log.info("Updated measured block time to {}ms for upstream {}", measuredBlockTime.toMillis(), upstreamId)
                }
            }
        }.map { it.height }.buffer(2, 1).map {
            // sometimes we can get there the same block, in this case it will be reorg
            it.last() - it.first() <= 1L
        }.scan(Pair(0, true)) { acc, value ->
            // then we accumulate consecutive true events, false resets counter
            if (value) {
                Pair(acc.first + 1, true)
            } else {
                if (log.isDebugEnabled) {
                    log.debug("non consecutive blocks in head for $upstreamId")
                } else {
                    ThrottledLogger.log(log, "non consecutive blocks in head for $upstreamId")
                }
                // Mark the time when we detected non-consecutive blocks
                lastNonConsecutiveTime = Instant.now()
                Pair(0, false)
            }
        }.flatMap { (count, value) ->
            // we emit when we have false or checked CHECKED_BLOCKS_UNTIL_LIVE blocks
            // CHECKED_BLOCKS_UNTIL_LIVE blocks == (CHECKED_BLOCKS_UNTIL_LIVE - 1) consecutive true
            when {
                !value -> {
                    Flux.just(HeadLivenessState.NON_CONSECUTIVE)
                }
                count >= (CHECKED_BLOCKS_UNTIL_LIVE - 1) -> {
                    // Check if we're still in the cooldown period
                    val lastNonConsec = lastNonConsecutiveTime
                    if (lastNonConsec != null && Duration.between(lastNonConsec, Instant.now()).toMinutes() < COOLDOWN_MINUTES) {
                        if (log.isDebugEnabled) {
                            log.debug("Still in cooldown period for $upstreamId after non-consecutive blocks")
                        }
                        Flux.just(HeadLivenessState.NON_CONSECUTIVE)
                    } else {
                        Flux.just(HeadLivenessState.OK)
                    }
                }
                else -> Flux.empty()
            }
        }

        // Use defer to dynamically evaluate timeout on each subscription
        val timeoutFlux = Flux.defer {
            headFlux.timeout(
                measuredBlockTime.multipliedBy(CHECKED_BLOCKS_UNTIL_LIVE.toLong() * 2),
                Flux.just(HeadLivenessState.NON_CONSECUTIVE).doOnNext {
                    // Increase measured block time when timeout occurs to avoid future timeouts
                    measuredBlockTime = measuredBlockTime.multipliedBy(TIMEOUT_MULTIPLIER).coerceAtMost(Duration.ofMinutes(MAX_BLOCK_TIME_MINUTES))
                    if (log.isDebugEnabled) {
                        log.debug(
                            "head liveness check broken with timeout in $upstreamId, increased measured block time to {}ms",
                            measuredBlockTime.toMillis(),
                        )
                    } else {
                        ThrottledLogger.log(log, "head liveness check broken with timeout in $upstreamId")
                    }
                },
            )
        }.repeat().subscribeOn(scheduler)

        return Flux.merge(timeoutFlux, headLiveness)
    }
}
