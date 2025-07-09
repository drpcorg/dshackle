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

import io.emeraldpay.dshackle.Global.Companion.nullValue
import io.emeraldpay.dshackle.data.BlockId
import io.emeraldpay.dshackle.data.TxId
import io.emeraldpay.dshackle.reader.ChainReader
import io.emeraldpay.dshackle.upstream.ChainRequest
import io.emeraldpay.dshackle.upstream.ChainResponse
import io.emeraldpay.dshackle.upstream.calls.CallMethods
import io.emeraldpay.dshackle.upstream.ethereum.rpc.RpcException
import io.emeraldpay.dshackle.upstream.ethereum.rpc.RpcResponseError
import io.emeraldpay.dshackle.upstream.rpcclient.ListParams
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.switchIfEmpty

/**
 * Reader for JSON RPC requests. Verifies if the method is allowed, transforms if necessary, and calls EthereumReader for data.
 * It provides data only if it's available through the router (cached, head, etc).
 * If data is not available locally then it returns `empty`; at this case the caller should call the remote node for actual data.
 *
 * @see EthereumCachingReader
 */
class EthereumLocalReader(
    private val reader: EthereumCachingReader,
    private val methods: CallMethods,
) : ChainReader {

    override fun read(key: ChainRequest): Mono<ChainResponse> {
        if (methods.isHardcoded(key.method)) {
            return Mono.just(methods.executeHardcoded(key.method))
                .map { ChainResponse(it, null) }
        }
        if (!methods.isCallable(key.method)) {
            return Mono.error(RpcException(RpcResponseError.CODE_METHOD_NOT_EXIST, "Unsupported method"))
        }
        if (key.nonce != null) {
            // we do not want to serve any requests (except hardcoded) that have nonces from cache
            return Mono.empty()
        }
        return commonRequests(key)?.switchIfEmpty {
            // we need to explicitly return null to prevent executeOnRemote
            // for example
            Mono.just(ChainResponse(nullValue, null, emptyList()))
        } ?: Mono.empty()
    }

    /**
     * Prepare RpcCall with data types specific for that particular requests. In general it may return a call that just
     * parses JSON into Map. But the purpose of further processing and caching for some of the requests we want
     * to have actual data types.
     */
    fun commonRequests(key: ChainRequest): Mono<ChainResponse>? {
        val method = key.method
        val params = key.params
        if (params is ListParams) {
            return when {
                method == "eth_getTransactionByHash" -> {
                    if (params.list.size != 1) {
                        throw RpcException(RpcResponseError.CODE_INVALID_METHOD_PARAMS, "Must provide 1 parameter")
                    }
                    val hash: TxId
                    try {
                        hash = TxId.from(params.list[0].toString())
                    } catch (e: IllegalArgumentException) {
                        throw RpcException(RpcResponseError.CODE_INVALID_METHOD_PARAMS, "[0] must be transaction id")
                    }
                    reader.txByHashAsCont(key.upstreamFilter)
                        .read(hash)
                        .map { ChainResponse(it.data.json, null, it.resolvedUpstreamData) }
                }

                method == "eth_getBlockByHash" -> {
                    if (params.list.size != 2) {
                        throw RpcException(RpcResponseError.CODE_INVALID_METHOD_PARAMS, "Must provide 2 parameters")
                    }
                    val hash: BlockId
                    try {
                        hash = BlockId.from(params.list[0].toString())
                    } catch (e: IllegalArgumentException) {
                        throw RpcException(RpcResponseError.CODE_INVALID_METHOD_PARAMS, "[0] must be block hash")
                    }
                    val withTx = params.list[1].toString().toBoolean()
                    if (withTx) {
                        null
                    } else {
                        reader.blocksByIdAsCont(key.upstreamFilter).read(hash).map {
                            ChainResponse(it.data.json, null, it.resolvedUpstreamData)
                        }
                    }
                }

                method == "eth_getTransactionReceipt" -> {
                    if (params.list.size != 1) {
                        throw RpcException(RpcResponseError.CODE_INVALID_METHOD_PARAMS, "Must provide 1 parameter")
                    }
                    val hash: TxId
                    try {
                        hash = TxId.from(params.list[0].toString())
                    } catch (e: IllegalArgumentException) {
                        throw RpcException(RpcResponseError.CODE_INVALID_METHOD_PARAMS, "[0] must be transaction id")
                    }
                    reader.receipts(key.upstreamFilter)
                        .read(hash)
                        .map { ChainResponse(it.data, null, it.resolvedUpstreamData) }
                }

                else -> null
            }
        }
        return null
    }
}
