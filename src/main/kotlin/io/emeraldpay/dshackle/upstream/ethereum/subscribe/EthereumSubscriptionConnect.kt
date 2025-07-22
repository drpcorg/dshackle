/**
 * Copyright (c) 2024 EmeraldPay, Inc
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

import io.emeraldpay.dshackle.upstream.ChainRequest
import io.emeraldpay.dshackle.upstream.ethereum.WsSubscriptions
import io.emeraldpay.dshackle.upstream.generic.SubscriptionCleanup
import io.emeraldpay.dshackle.upstream.generic.subscribe.GenericPersistentConnect
import io.emeraldpay.dshackle.upstream.rpcclient.ListParams
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration

/**
 * Ethereum-specific subscription connection that integrates with client tracking and cleanup
 */
class EthereumSubscriptionConnect(
    private val conn: WsSubscriptions,
    private val topic: String,
    private val params: Any?,
    private val cleanup: SubscriptionCleanup,
) : GenericPersistentConnect() {

    companion object {
        private val log = LoggerFactory.getLogger(EthereumSubscriptionConnect::class.java)
    }

    private var subscriptionId: String? = null

    @Suppress("UNCHECKED_CAST")
    override fun createConnection(): Flux<Any> {
        val ethParams = getParams(params)
        val subscribeData = if (ethParams != null) {
            conn.subscribe(ChainRequest("eth_subscribe", ListParams(topic, ethParams)))
        } else {
            conn.subscribe(ChainRequest("eth_subscribe", ListParams(topic)))
        }

        return subscribeData.data
            .doOnNext { _ ->
                // Store subscription ID when first message arrives (ID should be available by then)
                if (subscriptionId == null) {
                    subscriptionId = subscribeData.subId.get()
                    log.debug("[AUTO-UNSUBSCRIBE] Ethereum subscription ID set for {}:{} -> {}", topic, params, subscriptionId)
                }
            }
            .timeout(
                Duration.ofSeconds(85),
                Mono.empty<ByteArray?>().doOnEach {
                    log.warn("[AUTO-UNSUBSCRIBE] Timeout during Ethereum subscription to $topic after 85 seconds")
                },
            )
            .onErrorResume {
                log.error("[AUTO-UNSUBSCRIBE] Error during Ethereum subscription to $topic", it)
                Mono.empty()
            }
            .doFinally {
                // Trigger cleanup when connection ends
                log.debug("[AUTO-UNSUBSCRIBE] Ethereum connection ended for subscription {}:{}", topic, params)
                cleanup.cleanupSubscription(topic, params, subscriptionId)
            } as Flux<Any>
    }

    private fun getParams(params: Any?): Any? {
        return when (topic) {
            "newHeads" -> null // newHeads doesn't require parameters
            "logs" -> params // logs uses the filter parameters
            "newPendingTransactions" -> null // pending txs doesn't require parameters
            else -> params
        }
    }
}
