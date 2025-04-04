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
package io.emeraldpay.dshackle.upstream.rpcclient

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import io.emeraldpay.dshackle.Global
import io.emeraldpay.dshackle.upstream.ChainCallError
import io.emeraldpay.dshackle.upstream.ChainResponse
import io.emeraldpay.dshackle.upstream.ethereum.rpc.RpcResponseError
import org.slf4j.LoggerFactory
import java.io.IOException

abstract class ResponseParser<T> {

    companion object {
        private val log = LoggerFactory.getLogger(ResponseParser::class.java)
    }

    private val jsonFactory = JsonFactory()

    abstract fun build(state: Preparsed): T

    fun parse(json: ByteArray): T {
        return build(parseInternal(json))
    }

    private fun parseInternal(json: ByteArray): Preparsed {
        var state = Preparsed()
        try {
            val parser: JsonParser = jsonFactory.createParser(json)
            parser.nextToken()
            if (parser.currentToken != JsonToken.START_OBJECT) {
                return Preparsed(
                    error = ChainCallError(
                        RpcResponseError.CODE_UPSTREAM_INVALID_RESPONSE,
                        "Invalid JSON: not an Object",
                    ),
                )
            }
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                val field = parser.currentName ?: break
                state = process(parser, json, field, state)
            }
        } catch (e: JsonParseException) {
            log.warn("Failed to parse JSON from upstream: ${e.message}")
        }
        if (state.error != null && state.id == null) {
            state = state.copy(id = ChainResponse.NumberId(0))
        }
        if (state.isReady) {
            return state
        }
        return Preparsed(
            error = ChainCallError(
                RpcResponseError.CODE_UPSTREAM_INVALID_RESPONSE,
                "Invalid JSON structure: never finalized",
            ),
        )
    }

    open fun process(parser: JsonParser, json: ByteArray, field: String, state: Preparsed): Preparsed {
        if (field == "jsonrpc") {
            if (!parser.nextToken().isScalarValue) {
                return state.copy(
                    error = ChainCallError(
                        RpcResponseError.CODE_UPSTREAM_INVALID_RESPONSE,
                        "Invalid JSON (jsonrpc value)",
                    ),
                )
            }
            // just skip the field
            return state
        } else if (field == "id") {
            return state.copy(id = readId(parser))
        } else if (field == "result") {
            val result = readResult(json, parser)
            return if (result == null) {
                // if result is null we should check if an error is also present, and if it's set then return only the error
                state.copy(nullResult = true)
            } else {
                state.copy(result = result)
            }
        } else if (field == "error") {
            val err = readError(parser)
            if (err != null) {
                return state.copy(error = err)
            }
        }
        return state
    }

    private fun readId(parser: JsonParser): ChainResponse.Id {
        if (parser.currentToken() == JsonToken.FIELD_NAME) {
            parser.nextToken()
        }
        return if (parser.currentToken() == JsonToken.VALUE_NUMBER_INT) {
            ChainResponse.NumberId(parser.intValue)
        } else if (parser.currentToken() == JsonToken.VALUE_STRING) {
            ChainResponse.StringId(parser.text)
        } else {
            log.warn("Invalid id type: ${parser.currentToken()}")
            return ChainResponse.NumberId(0)
        }
    }

    @Throws(IOException::class)
    private fun readNumber(parser: JsonParser): Int {
        if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
            parser.nextToken()
        }
        if (!parser.currentToken().isNumeric) {
            throw IllegalStateException("Not a number: ${parser.currentToken.name}")
        }
        return parser.intValue
    }

    fun readResult(json: ByteArray, parser: JsonParser): ByteArray? {
        val value = parser.nextToken()
        val start = parser.tokenLocation
        if (value.isScalarValue) {
            val text = parser.text
            return if (value == JsonToken.VALUE_STRING) {
                ("\"" + text + "\"").toByteArray()
            } else if (value == JsonToken.VALUE_NULL) {
                null
            } else {
                text.toByteArray()
            }
        } else if (value == JsonToken.START_OBJECT || value == JsonToken.START_ARRAY) {
            parser.skipChildren()
            val end = parser.currentLocation.byteOffset.toInt()
            val copy = ByteArray((end - start.byteOffset).toInt())
            System.arraycopy(json, start.byteOffset.toInt(), copy, 0, copy.size)
            return copy
        } else {
            throw IllegalStateException("Invalid JSON structure, cannot read result from ${value.name}")
        }
    }

    fun readError(parser: JsonParser): ChainCallError? {
        var code = 0
        var message = ""
        var details: Any? = null

        var count = 0
        var inited = false

        while (true) {
            if (count == 0 && inited) {
                break
            }
            val token = parser.nextToken()
            when (token) {
                null -> {
                    return null
                }
                JsonToken.START_OBJECT -> {
                    inited = true
                    count++
                    continue
                }
                JsonToken.END_OBJECT -> {
                    count--
                    continue
                }
                JsonToken.VALUE_NULL -> {
                    return null
                }
                JsonToken.VALUE_STRING -> {
                    if (!inited) {
                        message = parser.valueAsString
                        break
                    }
                }
                else -> {}
            }
            val field = parser.currentName()
            if (field == "code" && token == JsonToken.VALUE_NUMBER_INT) {
                code = parser.intValue
            } else if ((field == "message" || field == "error") && token == JsonToken.VALUE_STRING) {
                message = parser.valueAsString
            } else if (field == "data") {
                when (val value = parser.nextToken()) {
                    JsonToken.VALUE_NULL -> details = null
                    JsonToken.VALUE_STRING -> details = parser.valueAsString
                    JsonToken.START_OBJECT -> details = Global.objectMapper.readValue(parser, java.util.Map::class.java)
                    else -> log.warn("Unsupported error data type $value")
                }
            }
        }
        return ChainCallError(code, message, details)
    }

    data class Preparsed(
        val id: ChainResponse.Id? = null,
        val result: ByteArray? = null,
        val nullResult: Boolean = false,
        val error: ChainCallError? = null,
        val subMethod: String? = null,
        val subId: String? = null,
    ) {

        private val isResultSet = result != null || nullResult

        // Ripple Response doesn't have `id` field
        val isRpcReady: Boolean = // id != null &&
            (error != null || isResultSet)

        val isSubReady: Boolean = subId != null &&
            isResultSet

        val isReady: Boolean = isRpcReady || isSubReady
    }
}
