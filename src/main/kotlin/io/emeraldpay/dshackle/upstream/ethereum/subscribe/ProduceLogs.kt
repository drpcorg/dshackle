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

import com.google.common.cache.CacheBuilder
import io.emeraldpay.dshackle.Chain
import io.emeraldpay.dshackle.Global
import io.emeraldpay.dshackle.data.BlockId
import io.emeraldpay.dshackle.data.TxId
import io.emeraldpay.dshackle.reader.Reader
import io.emeraldpay.dshackle.upstream.Multistream
import io.emeraldpay.dshackle.upstream.ethereum.EthereumDirectReader.Result
import io.emeraldpay.dshackle.upstream.ethereum.EthereumLikeMultistream
import io.emeraldpay.dshackle.upstream.ethereum.subscribe.json.LogMessage
import io.emeraldpay.etherjar.hex.HexData
import io.emeraldpay.etherjar.rpc.json.TransactionReceiptJson
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.switchIfEmpty
import java.util.concurrent.TimeUnit

class ProduceLogs(
    private val receipts: Reader<TxId, Result<ByteArray>>,
    private val chain: Chain
) {

    companion object {
        private val log = LoggerFactory.getLogger(ProduceLogs::class.java)
    }

    constructor(upstream: EthereumLikeMultistream) :
        this(upstream.getReader().receipts(), (upstream as Multistream).chain)

    private val objectMapper = Global.objectMapper

    // need to keep history of recent messages in case they get removed. cannot rely on
    // any other cache or upstream because if when it gets removed it's unavailable in any other source
    private val oldMessages = CacheBuilder.newBuilder()
        // in general a block with its events can be replaced in ~90 seconds, but in case of a big network disturbance
        // it can be much longer. here we keep events up to an hour
        .expireAfterWrite(60, TimeUnit.MINUTES)
        .maximumSize(90000)
        .build<LogReference, List<LogMessage>>()

    fun produce(block: Flux<ConnectBlockUpdates.Update>): Flux<LogMessage> {
        return block.flatMap { update ->
            if (update.type == ConnectBlockUpdates.UpdateType.DROP) {
                produceRemoved(update)
            } else {
                produceAdded(update)
            }
        }
    }

    fun produceRemoved(update: ConnectBlockUpdates.Update): Flux<LogMessage> {
        val old = oldMessages.getIfPresent(LogReference(update.blockHash, update.transactionId))
        if (old == null) {
            log.warn(
                "No old message to produce removal messages for tx ${update.transactionId} " +
                    "at block ${update.blockHash} for chain ${chain.chainName}"
            )
            return Flux.empty()
        }
        return Flux.fromIterable(old)
            .map { it.copy(removed = true) }
    }

    fun produceAdded(update: ConnectBlockUpdates.Update): Flux<LogMessage> {
        return receipts.read(update.transactionId)
            .switchIfEmpty {
                log.warn("Cannot find receipt for tx ${update.transactionId} for chain ${chain.chainName}")
                Mono.empty()
            }
            .map { it.data }
            .flatMapMany { jsonBytes ->
                // receipt could be a null, like when the original block was replaced, etc.
                // so just skip it as Flux.empty
                val receipt = objectMapper.readValue(jsonBytes, TransactionReceiptJson::class.java)
                    ?: return@flatMapMany Flux.empty<LogMessage>()
                try {
                    val messages = receipt.logs
                        .map { txlog ->
                            LogMessage(
                                txlog.address,
                                txlog.blockHash,
                                txlog.blockNumber,
                                txlog.data ?: HexData.empty(),
                                txlog.logIndex,
                                txlog.topics,
                                txlog.transactionHash,
                                txlog.transactionIndex,
                                false,
                                update.upstreamId

                            )
                        }
                    oldMessages.put(LogReference(update.blockHash, update.transactionId), messages)
                    Flux.fromIterable(messages)
                } catch (t: Throwable) {
                    log.warn(
                        "Invalid Receipt ${update.transactionId} for chain ${chain.chainName}. " +
                            "${t.javaClass}: ${t.message}"
                    )
                    Flux.empty<LogMessage>()
                }
            }
    }

    private data class LogReference(
        val block: BlockId,
        val tx: TxId
    )
}
