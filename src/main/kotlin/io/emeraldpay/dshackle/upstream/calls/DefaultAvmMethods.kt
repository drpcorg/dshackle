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
import io.emeraldpay.dshackle.quorum.AlwaysQuorum
import io.emeraldpay.dshackle.quorum.BroadcastQuorum
import io.emeraldpay.dshackle.quorum.CallQuorum
import io.emeraldpay.dshackle.quorum.MaximumValueQuorum
import io.emeraldpay.dshackle.quorum.NotNullQuorum
import io.emeraldpay.dshackle.upstream.ethereum.rpc.RpcException

/**
 * Default configuration for AVM (Algorand Virtual Machine) based RPC.
 * Defines optimal Quorum strategies for different methods and provides
 * hardcoded results for chain-identity methods.
 */
class DefaultAvmMethods(
    private val chain: Chain,
) : CallMethods {

    private val stateMethods = setOf(
        "algod_status",
        "algod_statusAfterBlock",
        "algod_getBlock",
        "algod_getBlockHash",
        "algod_getBlockHeader",
        "algod_ledgerSupply",
        "algod_health",
        "algod_ready",
        "algod_versions",
        "algod_genesis",
        "algod_metrics",
        "algod_getSyncRound",
    )

    private val accountMethods = setOf(
        "algod_getAccount",
        "algod_getAccountAssetInfo",
        "algod_getAccountApplicationInfo",
        "algod_getApplication",
        "algod_getApplicationBoxes",
        "algod_getApplicationBoxByName",
        "algod_getAsset",
    )

    private val transactionMethods = setOf(
        "algod_getPendingTransactions",
        "algod_getPendingTransactionsByAddress",
        "algod_getPendingTransaction",
        "algod_getTransactionProof",
        "algod_getTransactionParams",
        "algod_suggestedParams",
        "algod_dryrun",
        "algod_simulateTransaction",
        "algod_compileTEAL",
        "algod_disassembleTEAL",
    )

    private val sendMethods = setOf(
        "algod_sendRawTransaction",
        "algod_sendTransaction",
    )

    private val nonLagging = setOf(
        "algod_getSupply",
    )

    private val hardcodedMethods = setOf(
        "algod_chainId",
        "algod_genesisId",
    )

    private val allowedMethods: Set<String> =
        stateMethods + accountMethods + transactionMethods + sendMethods + nonLagging

    override fun createQuorumFor(method: String): CallQuorum {
        return when {
            sendMethods.contains(method) -> BroadcastQuorum()
            method == "algod_getPendingTransaction" -> NotNullQuorum()
            method == "algod_getSyncRound" -> MaximumValueQuorum()
            method == "algod_getBlock" -> NotNullQuorum()
            method == "algod_getBlockHash" -> NotNullQuorum()
            method == "algod_getBlockHeader" -> NotNullQuorum()
            method == "algod_getTransactionProof" -> NotNullQuorum()
            else -> AlwaysQuorum()
        }
    }

    override fun isCallable(method: String): Boolean {
        return allowedMethods.contains(method)
    }

    override fun isHardcoded(method: String): Boolean {
        return hardcodedMethods.contains(method)
    }

    override fun executeHardcoded(method: String): ByteArray {
        val json = when (method) {
            "algod_chainId" -> "\"${chain.chainId}\""
            "algod_genesisId" -> "\"${chain.netVersion}\""
            else -> throw RpcException(-32601, "Method not found")
        }
        return json.toByteArray()
    }

    override fun getGroupMethods(groupName: String): Set<String> =
        when (groupName) {
            "default" -> getSupportedMethods()
            else -> emptySet()
        }

    override fun getSupportedMethods(): Set<String> {
        return (allowedMethods + hardcodedMethods).toSortedSet()
    }
}
