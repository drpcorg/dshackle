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

import io.emeraldpay.api.proto.BlockchainOuterClass
import io.emeraldpay.api.proto.Common
import io.emeraldpay.dshackle.Chain
import io.emeraldpay.dshackle.upstream.Multistream
import io.emeraldpay.dshackle.upstream.MultistreamHolder
import io.emeraldpay.dshackle.upstream.UpstreamAvailability
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

@Service
class SubscribeStatus(
    private val multistreamHolder: MultistreamHolder
) {

    fun subscribeStatus(requestMono: Mono<BlockchainOuterClass.StatusRequest>): Flux<BlockchainOuterClass.ChainStatus> {
        return requestMono.flatMapMany { req ->
            if (req.chainsCount == 0) {
                Flux.error(RuntimeException("empty chains list"))
            } else {
                // check status for all requested chains
                val all = req.chainsList.map {
                    val chain = Chain.byId(it.number)
                    val up = multistreamHolder.getUpstream(chain)
                    up.observeStatus().map { availability ->
                        chainStatus(chain, availability, up)
                    }
                }
                Flux.merge(all)
            }
        }
    }

    fun chainUnavailable(chain: Chain): BlockchainOuterClass.ChainStatus {
        return BlockchainOuterClass.ChainStatus.newBuilder()
            .setAvailability(Common.AvailabilityEnum.AVAIL_UNAVAILABLE)
            .setChain(Common.ChainRef.forNumber(chain.id))
            .setQuorum(0)
            .build()
    }

    fun chainStatus(chain: Chain, available: UpstreamAvailability, ups: Multistream): BlockchainOuterClass.ChainStatus {
        val quorum = if (available != UpstreamAvailability.UNAVAILABLE) {
            ups.getAll().count {
                it.getStatus() > UpstreamAvailability.UNAVAILABLE
            }
        } else {
            0
        }
        return BlockchainOuterClass.ChainStatus.newBuilder()
            .setAvailability(Common.AvailabilityEnum.forNumber(available.grpcId))
            .setChain(Common.ChainRef.forNumber(chain.id))
            .setQuorum(quorum)
            .build()
    }
}
