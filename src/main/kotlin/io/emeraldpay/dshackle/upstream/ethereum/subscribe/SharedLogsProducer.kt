/**
 * Copyright (c) 2021 EmeraldPay, Inc
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
package io.emeraldpay.dshackle.upstream.ethereum.subscribe

import io.emeraldpay.dshackle.upstream.Multistream
import io.emeraldpay.dshackle.upstream.Selector
import io.emeraldpay.dshackle.upstream.ethereum.domain.Address
import io.emeraldpay.dshackle.upstream.ethereum.hex.Hex32
import io.emeraldpay.dshackle.upstream.ethereum.subscribe.json.LogMessage
import org.slf4j.LoggerFactory
import reactor.core.Disposable
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks
import reactor.core.scheduler.Scheduler
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class SharedLogsProducer(
    upstream: Multistream,
    scheduler: Scheduler,
) {
    companion object {
        private val log = LoggerFactory.getLogger(SharedLogsProducer::class.java)
    }

    private val produceLogs = ProduceLogs(upstream)
    private val connectBlockUpdates = ConnectBlockUpdates(upstream, scheduler)

    private val subscriptions = ConcurrentHashMap<String, LogsSubscription>()
    private val subscriptionCounter = AtomicInteger(0)

    @Volatile
    private var sharedStream: Disposable? = null

    @Volatile
    private var logsSink: Sinks.Many<LogMessage>? = null

    fun subscribe(
        addresses: List<Address>,
        topics: List<List<Hex32>?>,
        matcher: Selector.Matcher,
    ): Flux<LogMessage> {
        val subscriptionId = "logs-sub-${subscriptionCounter.incrementAndGet()}"
        val subscription = LogsSubscription(subscriptionId, addresses, topics, matcher)

        subscriptions[subscriptionId] = subscription

        // Start shared stream if this is the first subscription
        if (subscriptions.size == 1) {
            startSharedStream(matcher)
        }

        val logsFlux = logsSink?.asFlux() ?: Flux.empty()

        return logsFlux
            .filter { logMessage -> subscription.matches(logMessage) }
            .doFinally { // Remove subscription when stream ends
                subscriptions.remove(subscriptionId)
                // Stop shared stream if no more subscriptions
                if (subscriptions.isEmpty()) {
                    stopSharedStream()
                }
            }
    }

    private fun startSharedStream(matcher: Selector.Matcher) {
        log.debug("Starting shared logs stream")
        logsSink = Sinks.many().multicast().onBackpressureBuffer()

        sharedStream = produceLogs.produce(connectBlockUpdates.connect(matcher))
            .subscribe(
                { logMessage ->
                    logsSink?.tryEmitNext(logMessage)
                },
                { error ->
                    log.error("Error in shared logs stream", error)
                    logsSink?.tryEmitError(error)
                },
                {
                    log.debug("Shared logs stream completed")
                    logsSink?.tryEmitComplete()
                },
            )
    }

    private fun stopSharedStream() {
        log.debug("Stopping shared logs stream")
        sharedStream?.dispose()
        sharedStream = null
        logsSink?.tryEmitComplete()
        logsSink = null
    }

    private data class LogsSubscription(
        val id: String,
        val addresses: List<Address>,
        val topics: List<List<Hex32>?>,
        val matcher: Selector.Matcher,
    ) {
        fun matches(logMessage: LogMessage): Boolean {
            val addressMatch = addresses.isEmpty() || addresses.contains(logMessage.address)

            val topicsMatch = if (topics.isEmpty()) {
                true
            } else if (logMessage.topics.size < topics.size) {
                false
            } else {
                topics.zip(logMessage.topics).all { (wantedTopics, logTopic) ->
                    wantedTopics == null || logTopic in wantedTopics
                }
            }

            return addressMatch && topicsMatch
        }
    }
}
