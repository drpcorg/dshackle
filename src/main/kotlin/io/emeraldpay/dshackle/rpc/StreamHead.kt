/**
 * Copyright (c) 2020 EmeraldPay, Inc
 * Copyright (c) 2019 ETCDEV GmbH
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
package io.emeraldpay.dshackle.rpc

import com.google.protobuf.ByteString
import io.emeraldpay.api.proto.BlockchainOuterClass
import io.emeraldpay.api.proto.Common
import io.emeraldpay.dshackle.Chain
import io.emeraldpay.dshackle.data.BlockContainer
import io.emeraldpay.dshackle.upstream.Multistream
import io.emeraldpay.dshackle.upstream.MultistreamHolder
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

@Service
class StreamHead(
    @Autowired private val multistreamHolder: MultistreamHolder,
) {

    private val log = LoggerFactory.getLogger(StreamHead::class.java)

    fun add(requestMono: Mono<Common.Chain>): Flux<BlockchainOuterClass.ChainHead> {
        return requestMono.map { request ->
            Chain.byId(request.type.number)
        }.flatMapMany { chain ->
            val ms = multistreamHolder.getUpstream(chain)
            ms.getHead()
                .getFlux()
                .map { asProto(ms, chain, it!!) }
                .onErrorContinue { t, _ ->
                    log.warn("Head subscription error", t)
                }
        }
    }

    fun asProto(ms: Multistream, chain: Chain, block: BlockContainer): BlockchainOuterClass.ChainHead {
        return BlockchainOuterClass.ChainHead.newBuilder()
            .setChainValue(chain.id)
            .setHeight(block.height)
            .setSlot(block.slot)
            .setCurrentLowerBlock(ms.getLowerBlock().blockNumber)
            .setCurrentLowerSlot(ms.getLowerBlock().slot ?: 0)
            .setTimestamp(block.timestamp.toEpochMilli())
            .setWeight(ByteString.copyFrom(block.difficulty.toByteArray()))
            .setBlockId(block.hash.toHex())
            .setParentBlockId(block.parentHash?.toHex() ?: "")
            .build()
    }
}
