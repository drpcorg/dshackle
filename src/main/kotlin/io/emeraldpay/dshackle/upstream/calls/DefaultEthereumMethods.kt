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
package io.emeraldpay.dshackle.upstream.calls

import io.emeraldpay.dshackle.Chain
import io.emeraldpay.dshackle.Global
import io.emeraldpay.dshackle.quorum.AlwaysQuorum
import io.emeraldpay.dshackle.quorum.BroadcastQuorum
import io.emeraldpay.dshackle.quorum.CallQuorum
import io.emeraldpay.dshackle.quorum.NonceQuorum
import io.emeraldpay.dshackle.quorum.NotLaggingQuorum
import io.emeraldpay.etherjar.rpc.RpcException

/**
 * Default configuration for Ethereum based RPC. Defines optimal Quorum strategies for different methods, and provides
 * hardcoded results for base methods, such as `net_version`, `web3_clientVersion` and similar
 */
class DefaultEthereumMethods(
    private val chain: Chain
) : CallMethods {

    private val version = "\"EmeraldDshackle/${Global.version}\""

    companion object {
        val withFilterIdMethods = listOf(
            "eth_getFilterChanges",
            "eth_getFilterLogs",
            "eth_uninstallFilter"
        )

        val newFilterMethods = listOf(
            "eth_newFilter",
            "eth_newBlockFilter",
            "eth_newPendingTransactionFilter",
        )

        val traceMethods = listOf(
            "trace_call",
            "trace_callMany",
            "trace_rawTransaction",
            "trace_replayBlockTransactions",
            "trace_replayTransaction",
            "trace_block",
            "trace_filter",
            "trace_get",
            "trace_transaction",
        )
    }

    private val anyResponseMethods = listOf(
        "eth_gasPrice",
        "eth_call",
        "eth_estimateGas"
    )

    private val firstValueMethods = listOf(
        "eth_getBlockTransactionCountByHash",
        "eth_getUncleCountByBlockHash",
        "eth_getBlockByHash",
        "eth_getTransactionByHash",
        "eth_getTransactionByBlockHashAndIndex",
        "eth_getStorageAt",
        "eth_getCode",
        "eth_getUncleByBlockHashAndIndex",
        "eth_getLogs"
    )

    private val specialMethods = listOf(
        "eth_getTransactionCount",
        "eth_blockNumber",
        "eth_getBalance",
        "eth_sendRawTransaction"
    )

    private val headVerifiedMethods = listOf(
        "eth_getBlockTransactionCountByNumber",
        "eth_getUncleCountByBlockNumber",
        "eth_getBlockByNumber",
        "eth_getTransactionByBlockNumberAndIndex",
        "eth_getTransactionReceipt",
        "eth_getUncleByBlockNumberAndIndex",
        "eth_feeHistory"
    )

    private val filterMethods = withFilterIdMethods + newFilterMethods

    private val hardcodedMethods = listOf(
        "net_version",
        "net_peerCount",
        "net_listening",
        "web3_clientVersion",
        "eth_protocolVersion",
        "eth_syncing",
        "eth_coinbase",
        "eth_mining",
        "eth_hashrate",
        "eth_accounts",
        "eth_chainId"
    )

    private val allowedMethods: List<String>

    init {
        allowedMethods = anyResponseMethods +
            firstValueMethods +
            specialMethods +
            headVerifiedMethods -
            chainUnsupportedMethods(chain) +
            getChainSpecificMethods(chain)
    }

    override fun createQuorumFor(method: String): CallQuorum {
        return when {
            newFilterMethods.contains(method) -> NotLaggingQuorum(4)
            withFilterIdMethods.contains(method) -> AlwaysQuorum()
            hardcodedMethods.contains(method) -> AlwaysQuorum()
            firstValueMethods.contains(method) -> AlwaysQuorum()
            anyResponseMethods.contains(method) -> NotLaggingQuorum(4)
            headVerifiedMethods.contains(method) -> NotLaggingQuorum(1)
            specialMethods.contains(method) -> {
                when (method) {
                    "eth_getTransactionCount" -> NonceQuorum()
                    "eth_getBalance" -> NotLaggingQuorum(0)
                    "eth_sendRawTransaction" -> BroadcastQuorum()
                    "eth_blockNumber" -> NotLaggingQuorum(0)
                    else -> AlwaysQuorum()
                }
            }

            getChainSpecificMethods(chain).contains(method) -> {
                when (method) {
                    "bor_getAuthor" -> NotLaggingQuorum(4)
                    "bor_getCurrentValidators" -> NotLaggingQuorum(0)
                    "bor_getCurrentProposer" -> NotLaggingQuorum(0)
                    "bor_getRootHash" -> NotLaggingQuorum(4)
                    "eth_getRootHash" -> NotLaggingQuorum(4)
                    else -> AlwaysQuorum()
                }
            }
            else -> AlwaysQuorum()
        }
    }

    private fun getChainSpecificMethods(chain: Chain): List<String> {
        return when (chain) {
            Chain.OPTIMISM -> listOf(
                "rollup_gasPrices"
            )
            Chain.POLYGON -> listOf(
                "bor_getAuthor",
                "bor_getCurrentValidators",
                "bor_getCurrentProposer",
                "bor_getRootHash",
                "bor_getSignersAtHash",
                "eth_getRootHash"
            )
            else -> emptyList()
        }
    }

    private fun chainUnsupportedMethods(chain: Chain): Set<String> {
        if (chain == Chain.OPTIMISM) {
            return setOf("eth_getAccounts")
        }
        return emptySet()
    }

    override fun isCallable(method: String): Boolean {
        return allowedMethods.contains(method)
    }

    override fun isHardcoded(method: String): Boolean {
        return hardcodedMethods.contains(method)
    }

    override fun executeHardcoded(method: String): ByteArray {
        // note that the value is in json representation, i.e. if it's a string it should be with quotes,
        // that's why "\"0x0\"", "\"1\"", etc. But just "true" for a boolean, or "[]" for array.
        val json = when (method) {
            "net_version" -> {
                when {
                    Chain.ETHEREUM == chain -> {
                        "\"1\""
                    }

                    Chain.ETHEREUM_CLASSIC == chain -> {
                        "\"1\""
                    }

                    Chain.POLYGON == chain -> {
                        "\"137\""
                    }

                    Chain.TESTNET_MORDEN == chain -> {
                        "\"2\""
                    }

                    Chain.TESTNET_ROPSTEN == chain -> {
                        "\"3\""
                    }

                    Chain.TESTNET_RINKEBY == chain -> {
                        "\"4\""
                    }

                    Chain.TESTNET_KOVAN == chain -> {
                        "\"42\""
                    }

                    Chain.TESTNET_GOERLI == chain -> {
                        "\"5\""
                    }

                    Chain.TESTNET_SEPOLIA == chain -> {
                        "\"11155111\""
                    }

                    else -> throw RpcException(-32602, "Invalid chain")
                }
            }

            "eth_chainId" -> {
                when {
                    Chain.ETHEREUM == chain -> {
                        "\"0x1\""
                    }

                    Chain.POLYGON == chain -> {
                        "\"0x89\""
                    }

                    Chain.TESTNET_ROPSTEN == chain -> {
                        "\"0x3\""
                    }

                    Chain.TESTNET_RINKEBY == chain -> {
                        "\"0x4\""
                    }

                    Chain.ETHEREUM_CLASSIC == chain -> {
                        "\"0x3d\""
                    }

                    Chain.TESTNET_MORDEN == chain -> {
                        "\"0x3c\""
                    }

                    Chain.TESTNET_KOVAN == chain -> {
                        "\"0x2a\""
                    }

                    Chain.TESTNET_GOERLI == chain -> {
                        "\"0x5\""
                    }

                    Chain.TESTNET_SEPOLIA == chain -> {
                        "\"0xaa36a7\""
                    }

                    else -> throw RpcException(-32602, "Invalid chain")
                }
            }

            "net_peerCount" -> {
                "\"0x2a\""
            }

            "net_listening" -> {
                "true"
            }

            "web3_clientVersion" -> {
                version
            }

            "eth_protocolVersion" -> {
                "\"0x3f\""
            }

            "eth_syncing" -> {
                "false"
            }

            "eth_coinbase" -> {
                "\"0x0000000000000000000000000000000000000000\""
            }

            "eth_mining" -> {
                "false"
            }

            "eth_hashrate" -> {
                "\"0x0\""
            }

            "eth_accounts" -> {
                "[]"
            }

            else -> throw RpcException(-32601, "Method not found")
        }
        return json.toByteArray()
    }

    override fun getGroupMethods(groupName: String): Set<String> =
        when (groupName) {
            "filter" -> filterMethods
            "trace" -> traceMethods
            else -> emptyList()
        }.toSet()

    override fun getSupportedMethods(): Set<String> {
        return allowedMethods.plus(hardcodedMethods).toSortedSet()
    }
}
