/**
 * Copyright (c) 2022 EmeraldPay, Inc
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
package io.emeraldpay.dshackle.upstream.ethereum

import io.emeraldpay.dshackle.Global
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.Metrics
import io.micrometer.core.instrument.Tag
import org.slf4j.LoggerFactory
import org.springframework.util.backoff.BackOffExecution
import org.springframework.util.backoff.ExponentialBackOff
import reactor.core.Disposable
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks
import java.time.Duration
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * A Websocket connection pool which keeps up to `target` connection providing them in a round-robin fashion.
 *
 * It doesn't make all the connections immediately but rather grows them one by one ensuring existing are ok.
 * By default, it adds a new connection every 5 second until it reaches the target number.
 */
class WsConnectionMultiPool(
    private val wsConnectionFactory: WsConnectionFactory,
    private val connections: Int,
    private val upstreamId: String,
) : WsConnectionPool {

    companion object {
        private val log = LoggerFactory.getLogger(WsConnectionMultiPool::class.java)
        private const val SCHEDULE_FULL = 60L
        private const val SCHEDULE_GROW = 5L
        private const val SCHEDULE_BROKEN = 15L

        // Adaptive threshold: more generous for smaller pools
        private fun getLeakMultiplier(targetSize: Int): Double {
            return when {
                targetSize <= 5 -> 3.0 // Small pools: 3x threshold
                targetSize <= 10 -> 2.5 // Medium pools: 2.5x threshold
                else -> 2.0 // Large pools: 2x threshold
            }
        }
    }

    private val current = ArrayList<WsConnection>()
    private var adjustLock = ReentrantReadWriteLock()
    private val index = AtomicInteger(0)
    private var connIndex = 0
    private val connectionInfo = Sinks.many().multicast().directBestEffort<WsConnection.ConnectionInfo>()
    private val connectionSubscriptionMap = mutableMapOf<String, Disposable>()

    var scheduler: ScheduledExecutorService = Global.control

    // Metrics
    private val metricsTags = listOf(
        Tag.of("upstream", upstreamId),
    )

    private val connectionsCreatedCounter = Counter.builder("upstream.ws.pool.created")
        .description("Total number of WebSocket connections created")
        .tags(metricsTags)
        .register(Metrics.globalRegistry)

    private val connectionsRemovedCounter = Counter.builder("upstream.ws.pool.removed")
        .description("Total number of WebSocket connections removed")
        .tags(metricsTags)
        .register(Metrics.globalRegistry)

    private val leakDetectedCounter = Counter.builder("upstream.ws.pool.leak.detected")
        .description("Number of times connection leak was detected")
        .tags(metricsTags)
        .register(Metrics.globalRegistry)

    init {
        Gauge.builder("upstream.ws.pool.size", this) {
            adjustLock.read { current.size.toDouble() }
        }
            .description("Current WebSocket pool size")
            .tags(metricsTags)
            .register(Metrics.globalRegistry)

        Gauge.builder("upstream.ws.pool.active", this) {
            adjustLock.read { current.count { conn -> conn.isConnected }.toDouble() }
        }
            .description("Number of active WebSocket connections")
            .tags(metricsTags)
            .register(Metrics.globalRegistry)

        Gauge.builder("upstream.ws.pool.target", this) { connections.toDouble() }
            .description("Target WebSocket pool size")
            .tags(metricsTags)
            .register(Metrics.globalRegistry)
    }

    override fun connect() {
        adjust()
    }

    override fun getConnection(): WsConnection {
        val tries = ExponentialBackOff(50, 1.25).also {
            it.maxElapsedTime = Duration.ofMinutes(1).toMillis()
        }.start()
        var next = next()
        while (next == null) {
            val sleep = tries.nextBackOff()
            if (sleep == BackOffExecution.STOP) {
                throw IllegalStateException("No available WS connection")
            }
            Thread.sleep(sleep)
            next = next()
        }
        return next
    }

    override fun connectionInfoFlux(): Flux<WsConnection.ConnectionInfo> =
        connectionInfo.asFlux()

    override fun close() {
        adjustLock.write {
            connectionSubscriptionMap.values.forEach { it.dispose() }
            connectionSubscriptionMap.clear()
            current.forEach { it.close() }
            current.clear()
        }
    }

    private fun next(): WsConnection? {
        adjustLock.read {
            if (current.isEmpty()) {
                return null
            }
            return current[index.getAndIncrement() % current.size]
        }
    }

    private fun adjust() {
        adjustLock.write {
            // Check for potential leak - only log and increment metric, no intervention
            val leakThreshold = getLeakMultiplier(connections)
            if (current.size > connections * leakThreshold) {
                log.warn(
                    "Possible WebSocket leak detected for upstream {}: {} connections when max is {}",
                    upstreamId,
                    current.size,
                    connections,
                )
                leakDetectedCounter.increment()
            }

            // Log current state
            val connectedCount = current.count { it.isConnected }
            log.debug(
                "WebSocket pool state for {}: size={}, connected={}, target={}",
                upstreamId,
                current.size,
                connectedCount,
                connections,
            )

            // add a new connection only if all existing are active or there are no connections at all
            val allOk = current.all { it.isConnected }
            val schedule: Long
            if (allOk) {
                schedule = if (current.size >= connections) {
                    // recheck the state in a minute and adjust if any connection went bad
                    SCHEDULE_FULL
                } else {
                    log.info(
                        "Creating WebSocket connection {} for upstream {}, pool size: {}",
                        connIndex,
                        upstreamId,
                        current.size + 1,
                    )
                    current.add(
                        wsConnectionFactory.createWsConnection(connIndex++)
                            .also {
                                it.connect()
                                connectionSubscriptionMap[it.connectionId()] = it.connectionInfoFlux()
                                    .subscribe { info ->
                                        connectionInfo.emitNext(info) { _, res -> res == Sinks.EmitResult.FAIL_NON_SERIALIZED }
                                    }
                                connectionsCreatedCounter.increment()
                            },
                    )
                    SCHEDULE_GROW
                }
            } else {
                // technically there is no reason to disconnect because it supposed to reconnect,
                // but to ensure clean start (or other internal state issues) lets completely close all broken and create new
                val removedCount = current.count { !it.isConnected }
                if (removedCount > 0) {
                    log.info(
                        "Removing {} broken WebSocket connection(s) for upstream {}, remaining: {}",
                        removedCount,
                        upstreamId,
                        current.size - removedCount,
                    )
                }
                current.removeIf {
                    if (!it.isConnected) {
                        // DO NOT FORGET to close the connection, otherwise it would keep reconnecting but unused
                        connectionSubscriptionMap.remove(it.connectionId())?.dispose()
                        it.close()
                        connectionsRemovedCounter.increment()
                        true
                    } else {
                        false
                    }
                }
                schedule = SCHEDULE_BROKEN
            }

            // Log info when exceeding target but not yet at leak threshold
            if (current.size > connections && current.size < connections * leakThreshold) {
                log.info(
                    "WebSocket pool size {} exceeds target {} for upstream {}",
                    current.size,
                    connections,
                    upstreamId,
                )
            }

            scheduler.schedule({ adjust() }, schedule, TimeUnit.SECONDS)
        }
    }

    private fun isUnavailable() = adjustLock.read { current.count { it.isConnected } == 0 }
}
