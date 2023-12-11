package io.emeraldpay.dshackle.upstream.rpcclient.stream

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonToken
import io.emeraldpay.dshackle.upstream.rpcclient.JsonRpcError
import io.emeraldpay.dshackle.upstream.rpcclient.ResponseRpcParser
import io.emeraldpay.etherjar.rpc.RpcResponseError
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.netty.ByteBufFlux
import java.util.Arrays
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class JsonRpcStreamParser {
    companion object {
        private val jsonFactory = JsonFactory()
        private val responseRpcParser = ResponseRpcParser()

        private const val ARRAY_OPEN_BRACKET: Byte = 91
        private const val ARRAY_CLOSE_BRACKET: Byte = 93
        private const val OBJECT_OPEN_BRACKET: Byte = 123
        private const val OBJECT_CLOSE_BRACKET: Byte = 125
        private const val QUOTE: Byte = 34
    }

    fun streamParse(statusCode: Int, response: Flux<ByteArray>): Mono<out Response> {
        return response.switchOnFirst({ first, responseStream ->
            if (first.get() == null) {
                aggregateResponse(responseStream, statusCode)
            } else {
                val count = AtomicInteger(1)
                val whatCount = AtomicReference<Count>()
                val endStream = AtomicBoolean(false)

                val firstBytes = first.get()!!

                val firstPart: SingleResponse? = parseFirstPart(firstBytes, endStream, whatCount, count)

                if (firstPart == null) {
                    aggregateResponse(responseStream, statusCode)
                } else {
                    processSingleResponse(firstPart, responseStream, endStream, whatCount, count)
                }
            }
        }, false,)
            .single()
            .onErrorResume {
                Mono.just(
                    SingleResponse(
                        null,
                        JsonRpcError(RpcResponseError.CODE_UPSTREAM_INVALID_RESPONSE, it.message ?: "Internal error"),
                    ),
                )
            }
    }

    private fun processSingleResponse(
        response: SingleResponse,
        responseStream: Flux<ByteArray>,
        endStream: AtomicBoolean,
        whatCount: AtomicReference<Count>,
        count: AtomicInteger,
    ): Mono<out Response> {
        if (response.noResponse()) {
            throw IllegalStateException("Invalid JSON structure")
        } else {
            if (response.hasError()) {
                return Mono.just(response)
            } else {
                return if (endStream.get()) {
                    Mono.just(SingleResponse(response.result, null))
                } else {
                    Mono.just(
                        StreamResponse(
                            streamParts(
                                response.result!!,
                                responseStream,
                                endStream,
                                whatCount,
                                count,
                            ),
                        ),
                    )
                }
            }
        }
    }

    private fun aggregateResponse(response: Flux<ByteArray>, statusCode: Int): Mono<AggregateResponse> {
        return ByteBufFlux.fromInbound(response).aggregate().asByteArray()
            .map { AggregateResponse(it, statusCode) }
    }

    private fun streamParts(
        firstBytes: ByteArray,
        responseStream: Flux<ByteArray>,
        endStream: AtomicBoolean,
        whatCount: AtomicReference<Count>,
        count: AtomicInteger,
    ): Flux<Chunk> {
        return Flux.concat(
            Mono.just(Chunk(firstBytes, false)),
            responseStream.skip(1)
                .filter { !endStream.get() }
                .map { bytes ->
                    val whatCountValue = whatCount.get()
                    for (i in bytes.indices) {
                        if (whatCountValue is Count.CountObjectBrackets) {
                            countBrackets(bytes[i], count, OBJECT_OPEN_BRACKET, OBJECT_CLOSE_BRACKET)
                        } else if (whatCountValue is Count.CountArrayBrackets) {
                            countBrackets(bytes[i], count, ARRAY_OPEN_BRACKET, ARRAY_CLOSE_BRACKET)
                        } else if (whatCountValue is Count.CountQuotes) {
                            if (bytes[i] == QUOTE) {
                                count.decrementAndGet()
                            }
                        }
                        if (count.get() == 0) {
                            endStream.set(true)
                            return@map Chunk(Arrays.copyOfRange(bytes, 0, i + 1), true)
                        }
                    }
                    Chunk(bytes, false)
                },
            Mono.just(endStream)
                .flatMap {
                    if (!it.get()) {
                        Mono.just(Chunk(ByteArray(0), true))
                    } else {
                        Mono.empty()
                    }
                },
        )
    }

    private fun parseFirstPart(
        firstBytes: ByteArray,
        endStream: AtomicBoolean,
        whatCount: AtomicReference<Count>,
        count: AtomicInteger,
    ): SingleResponse? {
        jsonFactory.createParser(firstBytes).use { parser ->
            while (true) {
                parser.nextToken()
                if (firstBytes.size == parser.currentLocation.byteOffset.toInt()) {
                    break
                }
                if (parser.currentName != null) {
                    if (parser.currentName == "result") {
                        val token = parser.nextToken()
                        val tokenStart = parser.tokenLocation.byteOffset.toInt()
                        return if (token.isScalarValue) {
                            whatCount.set(Count.CountQuotes)
                            SingleResponse(processScalarValue(token, tokenStart, firstBytes, endStream), null)
                        } else {
                            when (token) {
                                JsonToken.START_OBJECT -> {
                                    whatCount.set(Count.CountObjectBrackets)
                                    SingleResponse(
                                        processAndCountBrackets(tokenStart, firstBytes, count, endStream, OBJECT_OPEN_BRACKET, OBJECT_CLOSE_BRACKET),
                                        null,
                                    )
                                }
                                JsonToken.START_ARRAY -> {
                                    whatCount.set(Count.CountArrayBrackets)
                                    SingleResponse(
                                        processAndCountBrackets(tokenStart, firstBytes, count, endStream, ARRAY_OPEN_BRACKET, ARRAY_CLOSE_BRACKET),
                                        null,
                                    )
                                }
                                else -> {
                                    throw IllegalStateException("'result' not an object nor array'")
                                }
                            }
                        }
                    } else if (parser.currentName == "error") {
                        return SingleResponse(null, responseRpcParser.readError(parser))
                    }
                }
            }
            return null
        }
    }

    private fun processAndCountBrackets(
        tokenStart: Int,
        bytes: ByteArray,
        brackets: AtomicInteger,
        endStream: AtomicBoolean,
        openBracket: Byte,
        closeBracket: Byte,
    ): ByteArray {
        for (i in tokenStart + 1 until bytes.size) {
            countBrackets(bytes[i], brackets, openBracket, closeBracket)
            if (brackets.get() == 0) {
                endStream.set(true)
                return Arrays.copyOfRange(bytes, tokenStart, i + 1)
            }
        }
        return Arrays.copyOfRange(bytes, tokenStart, bytes.size)
    }

    private fun countBrackets(
        byte: Byte,
        brackets: AtomicInteger,
        openBracket: Byte,
        closeBracket: Byte,
    ) {
        if (byte == openBracket) {
            brackets.incrementAndGet()
        } else if (byte == closeBracket) {
            brackets.decrementAndGet()
        }
    }

    private fun processScalarValue(
        token: JsonToken,
        tokenStart: Int,
        bytes: ByteArray,
        endStream: AtomicBoolean,
    ): ByteArray? {
        if (token == JsonToken.VALUE_NULL) {
            endStream.set(true)
            return "null".toByteArray()
        } else if (token == JsonToken.VALUE_STRING) {
            for (i in tokenStart + 1 until bytes.size) {
                if (bytes[i] == QUOTE) {
                    endStream.set(true)
                    return Arrays.copyOfRange(bytes, tokenStart, i + 1)
                }
            }
            return Arrays.copyOfRange(bytes, tokenStart, bytes.size)
        }
        return null
    }

    private sealed class Count {
        data object CountArrayBrackets : Count()
        data object CountObjectBrackets : Count()
        data object CountQuotes : Count()
    }
}
