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
package io.emeraldpay.dshackle.upstream.calls

import io.emeraldpay.dshackle.Global
import io.emeraldpay.dshackle.upstream.Head
import io.emeraldpay.dshackle.upstream.Selector

/**
 * Get a matcher based on a criteria provided with a RPC request. I.e. when the client requests data for "latest", or "0x19f816" block.
 * The implementation is specific for Ethereum.
 */
class EthereumCallSelector : CallSelector {

    private val objectMapper = Global.objectMapper

    /**
     * @param method JSON RPC name
     * @param params JSON-encoded list of parameters for the method
     */
    override fun getMatcher(method: String, params: String, head: Head, passthrough: Boolean): Selector.Matcher? {
        if (method in DefaultEthereumMethods.withFilterIdMethods) {
            return sameUpstreamMatcher(params)
        }
        return null
    }

    private fun sameUpstreamMatcher(params: String): Selector.Matcher? {
        val list = objectMapper.readerFor(Any::class.java).readValues<Any>(params).readAll()
        if (list.isEmpty()) {
            return null
        }
        val filterId = list[0].toString()
        if (filterId.length < 4) {
            return Selector.SameNodeMatcher(0.toByte())
        }
        val hashHex = filterId.substring(filterId.length - 2)
        val nodeId = hashHex.toInt(16)
        return Selector.SameNodeMatcher(nodeId.toByte())
    }
}
