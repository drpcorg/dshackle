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
package io.emeraldpay.dshackle.upstream.rpcclient

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonNode
import io.emeraldpay.api.proto.BlockchainOuterClass
import io.emeraldpay.dshackle.Global

data class JsonRpcRequest(
    val method: String,
    val params: ByteArray,
    val id: Int,
    val nonce: Long?,
    val selector: BlockchainOuterClass.Selector?
) {

    @JvmOverloads constructor(
        method: String,
        params: ByteArray,
        nonce: Long? = null,
        selectors: BlockchainOuterClass.Selector? = null
    ) : this(method, params, 1, nonce, selectors)

    @JvmOverloads constructor(
        method: String,
        params: List<Any>,
        nonce: Long? = null,
        selectors: BlockchainOuterClass.Selector? = null
    ) : this(method, Global.objectMapper.writeValueAsBytes(params), 1, nonce, selectors)

    fun toJson(): ByteArray {
        val textBytes = """{"jsonrpc":"2.0","id":$id,"method":"$method","params":""".toByteArray()
        return textBytes.plus(params).plus(125)
    }

    override fun toString(): String {
        return String(this.toJson())
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as JsonRpcRequest

        if (method != other.method) return false
        if (!params.contentEquals(other.params)) return false
        if (id != other.id) return false
        if (nonce != other.nonce) return false
        if (selector != other.selector) return false

        return true
    }

    override fun hashCode(): Int {
        var result = method.hashCode()
        result = 31 * result + params.contentHashCode()
        result = 31 * result + id
        result = 31 * result + (nonce?.hashCode() ?: 0)
        result = 31 * result + (selector?.hashCode() ?: 0)
        return result
    }

    class Deserializer : JsonDeserializer<JsonRpcRequest>() {

        override fun deserialize(p: JsonParser, ctxt: DeserializationContext): JsonRpcRequest {
            val node: JsonNode = p.readValueAsTree()
            val id = node.get("id").intValue()
            val method = node.get("method").textValue()
            val params = node.get("params").map {
                if (it.isNumber) {
                    it.asInt()
                } else if (it.isTextual) {
                    it.textValue()
                } else if (it.isBoolean) {
                    it.booleanValue()
                } else if (it.isNull) {
                    null
                } else {
                    throw IllegalStateException("Unsupported param type: ${it.asToken()}")
                }
            }.run { Global.objectMapper.writeValueAsBytes(this) }
            return JsonRpcRequest(method, params, id, null, null)
        }
    }
}
