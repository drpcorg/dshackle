package io.emeraldpay.dshackle.upstream.aztec

import io.emeraldpay.dshackle.Defaults
import io.emeraldpay.dshackle.upstream.ChainException
import io.emeraldpay.dshackle.upstream.ChainRequest
import io.emeraldpay.dshackle.upstream.ChainResponse
import io.emeraldpay.dshackle.upstream.SingleValidator
import io.emeraldpay.dshackle.upstream.Upstream
import io.emeraldpay.dshackle.upstream.UpstreamAvailability
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono
import reactor.util.retry.Retry
import java.time.Duration

/**
 * Validator for Aztec health checks that retries transient HTTP 5xx errors before
 * giving up.
 *
 * Aztec public testnet/mainnet sequencer endpoints sit behind load balancers that
 * occasionally answer 502/503/504 with an HTML body during deploys, sequencer
 * failovers or transient overload. dshackle's HTTP layer maps that into a
 * `ChainCallUpstreamException("HTTP Code: 5xx")` and the default validator turns
 * it into UNAVAILABLE on the very first hit, which causes the upstream to flap
 * in/out of the multistream every few minutes even though the next probe always
 * succeeds.
 *
 * This validator does the same thing as GenericSingleCallValidator but retries
 * up to [maxRetries] times on transient HTTP errors. Non-retryable errors
 * (timeouts, JSON-RPC errors, application 4xx, etc.) propagate immediately to
 * the [onError] fallback, just as before.
 */
class AztecRetryingValidator(
    private val request: ChainRequest,
    private val upstream: Upstream,
    private val check: (ByteArray) -> UpstreamAvailability,
    private val maxRetries: Long = DEFAULT_MAX_RETRIES,
    private val backoff: Duration = DEFAULT_BACKOFF,
) : SingleValidator<UpstreamAvailability> {

    companion object {
        private val log = LoggerFactory.getLogger(AztecRetryingValidator::class.java)

        const val DEFAULT_MAX_RETRIES: Long = 2
        val DEFAULT_BACKOFF: Duration = Duration.ofMillis(500)

        // Sequencer/proxy LB occasionally answers with HTML 5xx during deploys or
        // failovers; treat these as transient and retry rather than ejecting the
        // upstream from the rotation.
        private val TRANSIENT_HTTP_CODES = setOf(502, 503, 504)

        internal fun isTransientHttpError(throwable: Throwable): Boolean {
            if (throwable !is ChainException) return false
            val message = throwable.error.message
            return TRANSIENT_HTTP_CODES.any { code -> message.contains("HTTP Code: $code") }
        }
    }

    override fun validate(onError: UpstreamAvailability): Mono<UpstreamAvailability> {
        return upstream.getIngressReader()
            .read(request)
            .flatMap(ChainResponse::requireResult)
            .retryWhen(
                Retry.backoff(maxRetries, backoff)
                    .filter(::isTransientHttpError)
                    .doBeforeRetry { signal ->
                        log.warn(
                            "Transient HTTP error from {} on Aztec validator {}: {}; retry {}/{}",
                            upstream.getId(),
                            request.method,
                            signal.failure().message,
                            signal.totalRetries() + 1,
                            maxRetries,
                        )
                    }
                    .onRetryExhaustedThrow { _, signal -> signal.failure() },
            )
            .map(check)
            .timeout(Defaults.timeoutInternal)
            .doOnError { err ->
                log.error("Error during {} validation for {}: {}", request.method, upstream.getId(), err.message)
            }
            .onErrorReturn(onError)
    }
}
