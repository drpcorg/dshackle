package io.emeraldpay.dshackle.upstream.ethereum

import io.emeraldpay.dshackle.upstream.Head
import org.springframework.scheduling.concurrent.CustomizableThreadFactory
import reactor.core.publisher.Flux
import reactor.core.scheduler.Schedulers
import java.time.Duration
import java.util.concurrent.Executors

class HeadLivenessValidator(
    val head: Head,
    val expectedBlockTime: Duration
) {

    val scheduler =
        Schedulers.fromExecutor(Executors.newCachedThreadPool(CustomizableThreadFactory("head-liveness-validator")))
    companion object {
        const val CHECKED_BLOCKS_UNTIL_LIVE = 3
    }

    fun getFlux(): Flux<Boolean> {
        // first we have moving window of 2 blocks and check that they are consecutive ones
        return head.getFlux().buffer(2, 1).map {
            it.last().height - it.first().height == 1L
        }.scan(Pair(0, true)) { acc, value ->
            // then we accumulate consecutive true events, false resets counter
            if (value) {
                Pair(acc.first + 1, true)
            } else {
                Pair(0, false)
            }
        }.flatMap { (count, value) ->
            // we emit when we have false or checked CHECKED_BLOCKS_UNTIL_LIVE blocks
            // CHECKED_BLOCKS_UNTIL_LIVE blocks == (CHECKED_BLOCKS_UNTIL_LIVE - 1) consecutive true
            when {
                count == (CHECKED_BLOCKS_UNTIL_LIVE - 1) -> Flux.just(true)
                !value -> Flux.just(false)
                else -> Flux.empty()
            }
            // finally, we timeout after we waited for double the time we needed to emit those blocks
        }.timeout(expectedBlockTime.multipliedBy(CHECKED_BLOCKS_UNTIL_LIVE.toLong() * 2), Flux.just(false))
            .distinctUntilChanged().subscribeOn(scheduler)
    }
}
