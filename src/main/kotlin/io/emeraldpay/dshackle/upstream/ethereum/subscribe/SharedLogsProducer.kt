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

    // Map of matcher hash to shared streams
    private val sharedStreams = ConcurrentHashMap<String, Disposable>()
    private val logsSinks = ConcurrentHashMap<String, Sinks.Many<LogMessage>>()

    fun subscribe(
        addresses: List<Address>,
        topics: List<List<Hex32>?>,
        matcher: Selector.Matcher,
    ): Flux<LogMessage> {
        val subscriptionId = "logs-sub-${subscriptionCounter.incrementAndGet()}"
        val subscription = LogsSubscription(subscriptionId, addresses, topics, matcher)
        val matcherKey = matcher.describeInternal()

        subscriptions[subscriptionId] = subscription

        // Start shared stream if this is the first subscription for this matcher
        val subscriptionsForMatcher = subscriptions.values.filter { it.matcher.describeInternal() == matcherKey }
        if (subscriptionsForMatcher.size == 1) {
            startSharedStream(matcher)
        }

        val logsFlux = logsSinks[matcherKey]?.asFlux() ?: Flux.empty()

        return logsFlux
            .filter { logMessage -> subscription.matches(logMessage) }
            .doFinally { // Remove subscription when stream ends
                subscriptions.remove(subscriptionId)
                // Stop shared stream if no more subscriptions for this matcher
                val remainingSubscriptionsForMatcher = subscriptions.values.filter { it.matcher.describeInternal() == matcherKey }
                if (remainingSubscriptionsForMatcher.isEmpty()) {
                    stopSharedStream(matcherKey)
                }
            }
    }

    private fun startSharedStream(matcher: Selector.Matcher) {
        val matcherKey = matcher.describeInternal()

        val logsSink = Sinks.many().multicast().onBackpressureBuffer<LogMessage>()
        logsSinks[matcherKey] = logsSink

        val sharedStream = produceLogs.produce(connectBlockUpdates.connect(matcher))
            .subscribe(
                { logMessage ->
                    logsSink.emitNext(logMessage) { _, res -> res == Sinks.EmitResult.FAIL_NON_SERIALIZED }
                },
                { error ->
                    log.error("Error in shared logs stream for matcher: $matcherKey", error)
                    logsSink.emitError(error) { _, res -> res == Sinks.EmitResult.FAIL_NON_SERIALIZED }
                },
                {
                    logsSink.emitComplete { _, res -> res == Sinks.EmitResult.FAIL_NON_SERIALIZED }
                },
            )
        sharedStreams[matcherKey] = sharedStream
    }

    private fun stopSharedStream(matcherKey: String) {
        sharedStreams[matcherKey]?.dispose()
        sharedStreams.remove(matcherKey)
        logsSinks[matcherKey]?.tryEmitComplete()
        logsSinks.remove(matcherKey)
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
