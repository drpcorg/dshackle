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
package io.emeraldpay.dshackle.upstream

import reactor.core.publisher.Flux

interface EgressSubscription {
    fun getAvailableTopics(): List<String>
    fun subscribe(topic: String, params: Any?, matcher: Selector.Matcher): Flux<out Any>
}

object EmptyEgressSubscription : EgressSubscription {
    override fun getAvailableTopics(): List<String> {
        return emptyList()
    }

    override fun subscribe(topic: String, params: Any?, matcher: Selector.Matcher): Flux<out Any> {
        return Flux.empty()
    }
}
