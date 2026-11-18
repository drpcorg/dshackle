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
import io.emeraldpay.dshackle.data.BlockContainer
import io.emeraldpay.dshackle.data.BlockId
import io.emeraldpay.dshackle.data.TxId
import io.emeraldpay.dshackle.reader.ChainReader
import io.emeraldpay.dshackle.upstream.ChainRequest
import io.emeraldpay.dshackle.upstream.ChainResponse
import io.emeraldpay.dshackle.upstream.calls.CallMethods
import io.emeraldpay.dshackle.upstream.ethereum.rpc.RpcException
import io.emeraldpay.dshackle.upstream.ethereum.rpc.RpcResponseError
import io.emeraldpay.dshackle.upstream.rpcclient.ListParams
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Metrics
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.switchIfEmpty
import java.util.concurrent.ConcurrentHashMap

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
        if (key.method == "eth_getBlockByNumber" && reader.recentBlocksEnabled()) {
            reader.noteBlockRequest()
            val (block, reason) = blockByNumberFromCache(key)
            blockCounter(if (block == null) "miss" else "hit", reason).increment()
            if (block != null) {
                return Mono.just(ChainResponse(block.json, null, emptyList()))
            }
            // a miss: goes the usual way
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
    private val counters = ConcurrentHashMap<String, Counter>()

    private fun blockCounter(result: String, reason: String): Counter =
        counters.computeIfAbsent("b|$result|$reason") {
            Counter.builder("local.cache.block_by_number")
                .description("eth_getBlockByNumber requests answered from (hit) or not found in (miss) the recent blocks, by reason")
                .tag("chain", reader.chainCode())
                .tag("result", result)
                .tag("reason", reason)
                .register(Metrics.globalRegistry)
        }

    private fun firstRequestCounter(result: String): Counter =
        counters.computeIfAbsent("f|$result") {
            Counter.builder("local.cache.block_first_request")
                .description("the first eth_getBlockByNumber(false) request for each recent block: in memory already (hit) or not (miss)")
                .tag("chain", reader.chainCode())
                .tag("result", result)
                .register(Metrics.globalRegistry)
        }

    /**
     * Only `[<hex number> | "latest", false]`: a full block (with tx objects) isn't kept in memory, and the
     * finalized/safe/pending tags depend on the upstream.
     */
    private fun blockByNumberFromCache(key: ChainRequest): Pair<BlockContainer?, String> {
        val params = key.params as? ListParams ?: return null to "params"
        if (params.list.size != 2) {
            return null to "params"
        }
        if (params.list[1]?.toString()?.lowercase() != "false") {
            return null to "full_tx"
        }
        val tag = params.list[0] as? String ?: return null to "params"
        val height: Long? = when {
            tag == "latest" -> null
            tag.startsWith("0x") && tag.length in 3..18 -> tag.substring(2).toLongOrNull(16) ?: return null to "params"
            else -> return null to "tag"
        }
        val block = reader.canonicalBlockFromMemory(height)
        // the first request for a block: was it already in memory (warm-up, head) or did it have to wait for a node
        if (reader.firstRequest(height) == true) {
            firstRequestCounter(if (block != null) "hit" else "miss").increment()
        }
        return if (block != null) block to "" else null to reader.blockMissReason(height)
    }

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
