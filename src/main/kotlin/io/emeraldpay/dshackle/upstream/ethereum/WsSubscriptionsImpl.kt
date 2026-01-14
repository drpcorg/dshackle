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

import io.emeraldpay.dshackle.upstream.ChainException
import io.emeraldpay.dshackle.upstream.ChainRequest
import io.emeraldpay.dshackle.upstream.ChainResponse
import io.emeraldpay.dshackle.upstream.rpcclient.ListParams
import io.emeraldpay.dshackle.upstream.rpcclient.ObjectParams
import io.emeraldpay.dshackle.upstream.rpcclient.RippleCommandParams
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.util.function.Tuples
import java.util.concurrent.atomic.AtomicReference

class WsSubscriptionsImpl(
    val wsPool: WsConnectionPool,
) : WsSubscriptions {

    companion object {
        private val log = LoggerFactory.getLogger(WsSubscriptionsImpl::class.java)
    }

    override fun subscribe(request: ChainRequest): WsSubscriptions.SubscribeData {
        val subscriptionId = AtomicReference("")
        val conn = wsPool.getConnection()
        val messages = conn.getSubscribeResponses()
            .filter { it.subscriptionId == subscriptionId.get() }
            .filter { it.result != null } // should never happen
            .map { it.result!! }

        val message = conn.callRpc(request)
            .flatMap {
                if (it.hasError()) {
                    log.warn("Failed to establish subscription: ${it.error?.message}")
                    Mono.error(ChainException(it.id, it.error!!))
                } else {
                    val rawResult = it.getResultAsRawString()
                    val id = if (rawResult.startsWith("{") || rawResult == "{}") {
                        // Ripple returns object result; use stream type as subscription ID
                        extractRippleStreamType(request) ?: request.id.toString()
                    } else {
                        it.getResultAsProcessedString()
                    }
                    subscriptionId.set(id)
                    Mono.just(Tuples.of(subscriptionId.get(), messages))
                }
            }

        return WsSubscriptions.SubscribeData(message, conn.connectionId(), subscriptionId)
    }

    /**
     * Extract expected event type from Ripple subscribe request.
     * For `subscribe` with `streams: ["ledger"]`, returns "ledgerClosed" as this is the event type
     * that will be received in subscription messages.
     */
    private fun extractRippleStreamType(request: ChainRequest): String? {
        if (request.method == "subscribe") {
            val params = request.params
            val streams: List<*>? = when (params) {
                is RippleCommandParams -> params.params["streams"] as? List<*>
                is ObjectParams -> params.obj["streams"] as? List<*>
                else -> null
            }
            if (streams?.contains("ledger") == true) {
                return "ledgerClosed"
            }
        }
        return null
    }

    override fun unsubscribe(request: ChainRequest): Mono<ChainResponse> {
        if (request.params is ListParams && (request.params.list.isEmpty() || request.params.list.contains(""))
        ) {
            return Mono.empty()
        }
        return wsPool.getConnection()
            .callRpc(request)
    }

    override fun connectionInfoFlux(): Flux<WsConnection.ConnectionInfo> =
        wsPool.connectionInfoFlux()
}
