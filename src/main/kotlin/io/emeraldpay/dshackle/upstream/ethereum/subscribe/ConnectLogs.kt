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
import io.emeraldpay.dshackle.upstream.ethereum.subscribe.json.LogMessage
import reactor.core.publisher.Flux
import reactor.core.scheduler.Scheduler

open class ConnectLogs(
    upstream: Multistream,
    scheduler: Scheduler,
) {
    private val sharedLogsProducer = SharedLogsProducer(upstream, scheduler)

    open fun create(addresses: List<Address>, topics: List<List<Hex32>?>): SubscriptionConnect<LogMessage> {
        return object : SubscriptionConnect<LogMessage> {
            override fun connect(matcher: Selector.Matcher): Flux<LogMessage> {
                return sharedLogsProducer.subscribe(addresses, topics, matcher)
            }
        }
    }
}
