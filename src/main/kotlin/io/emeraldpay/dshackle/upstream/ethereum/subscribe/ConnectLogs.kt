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
import io.emeraldpay.dshackle.upstream.SubscriptionConnect
import io.emeraldpay.dshackle.upstream.ethereum.domain.Address
import io.emeraldpay.dshackle.upstream.ethereum.hex.Hex32
import io.emeraldpay.dshackle.upstream.ethereum.hex.HexDataComparator
import io.emeraldpay.dshackle.upstream.ethereum.subscribe.json.LogMessage
import reactor.core.publisher.Flux
import reactor.core.scheduler.Scheduler
import java.util.function.Function

open class ConnectLogs(
    upstream: Multistream,
    private val connectBlockUpdates: ConnectBlockUpdates,
) {
    companion object {
        private val ADDR_COMPARATOR = HexDataComparator()
        private val TOPIC_COMPARATOR = HexDataComparator()
    }

    constructor(upstream: Multistream, scheduler: Scheduler) : this(upstream, ConnectBlockUpdates(upstream, scheduler))

    private val produceLogs = ProduceLogs(upstream)

    fun start(matcher: Selector.Matcher): Flux<LogMessage> {
        return produceLogs.produce(connectBlockUpdates.connect(matcher))
    }

    open fun create(addresses: List<Address>, topics: List<List<Hex32>?>): SubscriptionConnect<LogMessage> {
        return object : SubscriptionConnect<LogMessage> {
            override fun connect(matcher: Selector.Matcher): Flux<LogMessage> {
                if (addresses.isEmpty() && topics.isEmpty()) {
                    return start(matcher)
                }
                return start(matcher)
                    .transform(filtered(addresses, topics))
            }
        }
    }

    fun filtered(addresses: List<Address>, selectedTopics: List<List<Hex32>?>): Function<Flux<LogMessage>, Flux<LogMessage>> {
        val sortedAddresses: List<Address> = addresses.sortedWith(ADDR_COMPARATOR)
        val topicSets: List<Set<Hex32>?> = selectedTopics.map { topicsOrNull ->
            topicsOrNull?.toSet()
        }

        return Function { logs ->
            logs.filter { log ->
                val goodAddress = sortedAddresses.isEmpty() ||
                    sortedAddresses.binarySearch(log.address, ADDR_COMPARATOR) >= 0

                val goodTopics = if (topicSets.isEmpty()) {
                    true
                } else if (log.topics.size < topicSets.size) {
                    false
                } else {
                    topicSets.zip(log.topics).all { (wantedTopics, logTopic) ->
                        wantedTopics == null || logTopic in wantedTopics
                    }
                }

                goodAddress && goodTopics
            }
        }
    }
}
