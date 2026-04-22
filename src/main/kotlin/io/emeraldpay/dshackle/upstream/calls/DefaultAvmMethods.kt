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

import io.emeraldpay.dshackle.quorum.AlwaysQuorum
import io.emeraldpay.dshackle.quorum.BroadcastQuorum
import io.emeraldpay.dshackle.quorum.CallQuorum
import io.emeraldpay.dshackle.upstream.ethereum.rpc.RpcException

/**
 * Default configuration for AVM (Algorand Virtual Machine) REST API, matching
 * the algod OpenAPI spec. Method identifiers use the `VERB#/path` convention
 * consumed by dshackle's REST HTTP reader.
 */
class DefaultAvmMethods : CallMethods {

    // Root-level common endpoints (not under /v2/*)
    private val commonMethods = setOf(
        getMethod("/genesis"),
        getMethod("/health"),
        getMethod("/ready"),
        getMethod("/metrics"),
        getMethod("/versions"),
        getMethod("/swagger.json"),
    )

    // Node / ledger / blocks read endpoints under /v2/*
    private val nodeMethods = setOf(
        getMethod("/v2/status"),
        getMethod("/v2/status/wait-for-block-after/*"),
        getMethod("/v2/ledger/supply"),
        getMethod("/v2/ledger/sync"),
        getMethod("/v2/blocks/*"),
        getMethod("/v2/blocks/*/hash"),
        getMethod("/v2/blocks/*/txids"),
        getMethod("/v2/blocks/*/logs"),
        getMethod("/v2/blocks/*/lightheader/proof"),
        getMethod("/v2/blocks/*/transactions/*/proof"),
        getMethod("/v2/stateproofs/*"),
        getMethod("/v2/deltas/*"),
        getMethod("/v2/deltas/*/txn/group"),
        getMethod("/v2/deltas/txn/group/*"),
    )

    private val accountMethods = setOf(
        getMethod("/v2/accounts/*"),
        getMethod("/v2/accounts/*/assets"),
        getMethod("/v2/accounts/*/assets/*"),
        getMethod("/v2/accounts/*/applications/*"),
        getMethod("/v2/accounts/*/transactions/pending"),
        getMethod("/v2/applications/*"),
        getMethod("/v2/applications/*/box"),
        getMethod("/v2/applications/*/boxes"),
        getMethod("/v2/assets/*"),
    )

    private val transactionReadMethods = setOf(
        getMethod("/v2/transactions/params"),
        getMethod("/v2/transactions/pending"),
        getMethod("/v2/transactions/pending/*"),
    )

    private val sendMethods = setOf(
        postMethod("/v2/transactions"),
        postMethod("/v2/transactions/async"),
    )

    private val computeMethods = setOf(
        postMethod("/v2/transactions/simulate"),
        postMethod("/v2/teal/compile"),
        postMethod("/v2/teal/disassemble"),
        postMethod("/v2/teal/dryrun"),
    )

    private val allowedMethods: Set<String> =
        commonMethods + nodeMethods + accountMethods + transactionReadMethods + sendMethods + computeMethods

    override fun createQuorumFor(method: String): CallQuorum {
        return if (sendMethods.contains(method)) {
            BroadcastQuorum()
        } else {
            AlwaysQuorum()
        }
    }

    override fun isCallable(method: String): Boolean {
        return allowedMethods.contains(method)
    }

    override fun isHardcoded(method: String): Boolean {
        return false
    }

    override fun executeHardcoded(method: String): ByteArray {
        throw RpcException(-32601, "Method not found")
    }

    override fun getGroupMethods(groupName: String): Set<String> =
        when (groupName) {
            "default" -> getSupportedMethods()
            else -> emptySet()
        }

    override fun getSupportedMethods(): Set<String> {
        return allowedMethods.toSortedSet()
    }

    private fun getMethod(path: String) = "GET#$path"

    private fun postMethod(path: String) = "POST#$path"
}
