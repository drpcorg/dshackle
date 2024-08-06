package io.emeraldpay.dshackle.commons

import reactor.core.publisher.Flux
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

object FluxIntervalWrapper {

    // the ordinary Flux.interval emits values even if the inner flatMap is not completed
    // this method is a wrapper over the Flux.interval
    // which prevents performing the inner flatMap if it is not finished in the previous step
    fun <T> interval(
        period: Duration,
        mapper: Flux<T>,
        transformer: (Flux<Long>) -> Flux<Long>,
    ): Flux<T> {
        val isProcessing = AtomicBoolean(false)

        return Flux.interval(period)
            .transform(transformer)
            .filter { !isProcessing.get() }
            .flatMap {
                isProcessing.set(true)
                mapper
                    .doFinally { isProcessing.set(false) }
            }
    }
}
