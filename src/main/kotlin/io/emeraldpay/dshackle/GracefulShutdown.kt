/**
 * Copyright (c) 2025 EmeraldPay, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.emeraldpay.dshackle

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationListener
import org.springframework.context.event.ContextClosedEvent
import org.springframework.core.Ordered
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Coordinates graceful shutdown of the service.
 *
 * Industry-standard load-balancer-friendly sequence:
 *   1. Mark the instance unhealthy so the load balancer stops routing new traffic.
 *   2. Sleep for [healthGraceSeconds] to give the load balancer time to notice.
 *   3. Stop accepting new connections on inbound servers (gRPC, HTTP/WS proxy).
 *   4. Signal long-running streams (subscriptions) to terminate gracefully.
 *   5. Wait up to [drainTimeoutSeconds] for in-flight request/response calls to complete.
 *   6. Force-close any still-active inbound servers.
 *   7. Allow Spring's normal `@PreDestroy` lifecycle (upstream connections, log flushing)
 *      to run for the remaining beans.
 */
@Service
class GracefulShutdown(
    @Value("\${dshackle.shutdown.health-grace-seconds:5}")
    private val healthGraceSeconds: Long,
    @Value("\${dshackle.shutdown.drain-timeout-seconds:30}")
    private val drainTimeoutSeconds: Long,
    @Value("\${dshackle.shutdown.force-timeout-seconds:10}")
    private val forceTimeoutSeconds: Long,
) : ApplicationListener<ContextClosedEvent>, Ordered {

    companion object {
        private val log = LoggerFactory.getLogger(GracefulShutdown::class.java)
    }

    private val shuttingDown = AtomicBoolean(false)
    private val inFlight = AtomicInteger(0)

    // Replay sink so subscribers that are created after shutdown is signaled also receive the cancel.
    private val streamsCancel: Sinks.Many<Boolean> = Sinks.many().replay().all()

    private val stopAcceptingHooks: MutableList<ShutdownHook> = CopyOnWriteArrayList()
    private val forceCloseHooks: MutableList<ShutdownHook> = CopyOnWriteArrayList()

    fun isShuttingDown(): Boolean = shuttingDown.get()

    fun inFlightCount(): Int = inFlight.get()

    fun drainTimeoutSeconds(): Long = drainTimeoutSeconds

    fun forceTimeoutSeconds(): Long = forceTimeoutSeconds

    /**
     * Emits a value when the service is shutting down. Long-running streams
     * (e.g. gRPC subscriptions) should `takeUntilOther(streamsCancelSignal())`
     * to terminate cleanly when shutdown begins.
     */
    fun streamsCancelSignal(): Mono<Boolean> = streamsCancel.asFlux().next()

    /**
     * Register a callback invoked during phase 1: stop accepting new connections / requests.
     * Each hook is invoked after the load-balancer-grace period.
     */
    fun registerStopAccepting(name: String, hook: () -> Unit) {
        stopAcceptingHooks.add(ShutdownHook(name, hook))
    }

    /**
     * Register a callback invoked during phase 2: force-close servers after drain.
     */
    fun registerForceClose(name: String, hook: () -> Unit) {
        forceCloseHooks.add(ShutdownHook(name, hook))
    }

    fun beginRequest() {
        inFlight.incrementAndGet()
    }

    fun endRequest() {
        inFlight.decrementAndGet()
    }

    /** Wraps a Mono so the call is counted as in-flight while it executes. */
    fun <T : Any> trackMono(source: Mono<T>): Mono<T> {
        return source
            .doOnSubscribe { beginRequest() }
            .doFinally { endRequest() }
    }

    /** Wraps a Flux so the call is counted as in-flight while it executes. */
    fun <T : Any> trackFlux(source: Flux<T>): Flux<T> {
        return source
            .doOnSubscribe { beginRequest() }
            .doFinally { endRequest() }
    }

    private fun awaitDrain(timeoutMillis: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (inFlight.get() > 0 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(100)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return inFlight.get() == 0
    }

    override fun onApplicationEvent(event: ContextClosedEvent) {
        if (!shuttingDown.compareAndSet(false, true)) {
            return
        }
        log.info("Graceful shutdown initiated")

        // Phase 1a: mark unhealthy + wait for the load balancer to drain traffic.
        log.info(
            "[shutdown 1/4] Marking service unhealthy; waiting {}s for load balancer to drain inbound traffic",
            healthGraceSeconds,
        )
        sleepQuietly(healthGraceSeconds * 1000)

        // Phase 1b: stop accepting new requests on inbound servers.
        log.info("[shutdown 2/4] Stop accepting new connections on inbound servers")
        runHooks(stopAcceptingHooks)

        // Phase 1c: tell long-running streams to wrap up.
        log.info("[shutdown 3/4] Signaling long-running streams to complete (in-flight={})", inFlight.get())
        streamsCancel.tryEmitNext(true)

        // Phase 2: wait for short-lived calls to finish.
        val drained = awaitDrain(drainTimeoutSeconds * 1000)
        if (drained) {
            log.info("All in-flight requests completed within drain window")
        } else {
            log.warn(
                "Drain timeout reached after {}s; {} request(s) still in flight",
                drainTimeoutSeconds,
                inFlight.get(),
            )
        }

        // Phase 3: force-close inbound servers (cancels any still-pending streams).
        log.info("[shutdown 4/4] Force-closing inbound servers")
        runHooks(forceCloseHooks)

        log.info("Graceful shutdown coordinator finished; handing off to bean destruction")
    }

    private fun runHooks(hooks: List<ShutdownHook>) {
        hooks.forEach { hook ->
            try {
                hook.action()
            } catch (t: Throwable) {
                log.warn("Shutdown hook '${hook.name}' failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    private fun sleepQuietly(millis: Long) {
        if (millis <= 0) return
        try {
            Thread.sleep(millis)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    // Listener should run before standard bean-destruction lifecycle.
    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE

    private data class ShutdownHook(val name: String, val action: () -> Unit)
}
