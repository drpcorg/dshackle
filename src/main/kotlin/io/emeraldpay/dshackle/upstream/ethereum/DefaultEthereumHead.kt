/**
 * Copyright (c) 2020 EmeraldPay, Inc
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

import io.emeraldpay.dshackle.Defaults
import io.emeraldpay.dshackle.data.BlockContainer
import io.emeraldpay.dshackle.reader.JsonRpcReader
import io.emeraldpay.dshackle.upstream.AbstractHead
import io.emeraldpay.dshackle.upstream.BlockValidator
import io.emeraldpay.dshackle.upstream.Head
import io.emeraldpay.dshackle.upstream.forkchoice.ForkChoice
import io.emeraldpay.dshackle.upstream.rpcclient.JsonRpcRequest
import io.emeraldpay.etherjar.hex.HexQuantity
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler

open class DefaultEthereumHead(
    protected val upstreamId: String,
    forkChoice: ForkChoice,
    blockValidator: BlockValidator,
    private val headScheduler: Scheduler,
) : Head, AbstractHead(forkChoice, headScheduler, blockValidator, 60_000, upstreamId) {

    fun getLatestBlock(api: JsonRpcReader): Mono<BlockContainer> {
        return api.read(JsonRpcRequest("eth_blockNumber", emptyList()))
            .subscribeOn(headScheduler)
            .timeout(Defaults.timeout, Mono.error(Exception("Block number not received")))
            .flatMap {
                if (it.error != null) {
                    Mono.error(it.error.asException(null))
                } else {
                    val value = it.getResultAsProcessedString()
                    Mono.just(HexQuantity.from(value))
                }
            }
            .flatMap {
                // fetching by Block Height here, critical to use the same upstream as in previous call,
                // b/c different upstreams may have different blocks on the same height
                api.read(JsonRpcRequest("eth_getBlockByNumber", listOf(it.toHex(), false)))
                    .subscribeOn(headScheduler)
                    .timeout(Defaults.timeout, Mono.error(Exception("Block data not received")))
            }
            .map {
                BlockContainer.fromEthereumJson(it.getResult(), upstreamId)
            }
            .onErrorResume { err ->
                log.error("Failed to fetch latest block: ${err.message} $upstreamId", err)
                Mono.empty()
            }
    }
}
